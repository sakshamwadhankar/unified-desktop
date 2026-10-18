# Phase 4 result: connection (PC receiver <-> phone link)

> All numbers in this file were measured on the **emulator via `adb forward`, not a real
> link**. The packet path is adb's TCP relay on one machine; nothing here is a Wi-Fi or
> USB throughput figure. Real-link numbers are phone items 7-10 (blocked, see the end).

Date of the runs below: 2026-10-18, 21:29-21:35 (item 6) and 21:45-21:56 (watchdog proofs).
Machine: owner's laptop (Win11), emulator `Pixel_8_Pro` (android-37.2
google_apis_playstore_ps16k x86_64, 3072 MB), Android app `dev.unifieddesktop.app`, PC tool
`pc/udreceiver` (release).

## 1. Review round 2: pairing-gate fixes (verified before the item-6 run)

Review found three bugs in the first version of the pairing gate. Fixed in
`Phase4Protocol.kt`, `Phase4LinkService.kt`, `Phase4ProtocolTest.kt`:

1. `paired` is now mutable per session and flips to true the moment the phone stores the
   PAIR_YES pin - the PC's first post-pairing frame is no longer dropped.
2. `pendingPairing` is an `AtomicReference<Pairing?>`: claimed with
   `compareAndSet(null, p)`, released with `compareAndSet(p, null)`. Two PCs can no longer
   fight over one slot; a dangling prompt is cleared when the session dies.
3. The channel gate is a pure function `Phase4Protocol.mayProcessChannel(channel, Gate)`
   (`Gate` = UNPAIRED | PAIRING | PAIRED), unit-tested for all cases: UNPAIRED and PAIRING
   pass only `control`; PAIRED passes control/input/video/bench.

Proof: `:app:testDebugUnitTest` green, `Phase4ProtocolTest` 12/12 (3 gate tests + 2 watchdog
tests), `Phase4CryptoTest` 6/6, whole app suite green; `:app:lintDebug :app:assembleDebug`
OK; `pc/` `cargo test --release` 12/12.

**Manager review 21:50 correction.** An earlier version of this section claimed "the phone
log shows PAIR_YES and then a clean SESSION_END". That was only true for the successful
run's session; the sentence implied it of the whole log, which is wrong. In
`evidence/phase4/p4log-pair-bench.txt` the three stalled-pipe sessions (TLS_UP
1792338522475 / PAIR_YES 1792338537509, TLS_UP 1792338788252, TLS_UP 1792339092604) do
**not** end; their threads were stuck in a socket write and only went away with the
process force-stop. The successful session does end (`PAIR_YES 1792339434843` ->
`SESSION_END 1792339440923`). Root cause, fix and proofs: section 2b.

## 2. Pairing + same-session bench (item 6 main run)

Both stores cleared first (phone `phase4x forget-all` -> `removed=1 pins=0`; PC
`udreceiver trust forget 12FB` -> "no entry matched"). Link via
`adb forward tcp:47600 tcp:47600`.

Steps: `udreceiver bench --addr 127.0.0.1:47600 --seconds 5` (starts unpaired) -> phone
raised the pairing notification -> screenshot -> **real UI tap** on the notification's
`Yes` button -> the same TLS session then ran the full bench.

- PC printed `PC code: 733362`; the phone notification showed `PC code: 733362` - same
  code (see the PNG; the code in the screenshot sits right above the Yes/No buttons).
- The tap was a real `input tap 665,723` on the expanded notification action (after
  tapping the notification's expand chevron); no harness shortcut was used for this run.
- PC output (file `evidence/phase4/bench-same-session.txt`):

  ```
  PC identity: FAAED033F62353AC (dir unified-desktop-receiver)
  PC code:  733362  <-- MUST match the code on the phone
  Waiting for Yes on the phone (up to 300s) ...
  Phone confirmed. Pin saved to C:\Users\Asus\AppData\Roaming\unified-desktop-receiver\trusted-phones.ndjson
  link: paired with phone 12FB82597D0CBCCC (127.0.0.1:47600)
  === BENCH (PC -> 127.0.0.1:47600, pinned TLS session) ===
  RTT (1000 samples, 64 B per sample): p50 0.96 ms | p95 1.68 ms | p99 2.73 ms | min 0.64 | max 4.92
  Throughput: 32.10 MB/s over 5 s (161 MB echoed)
  ```

  **RTT (1000 x 64 B samples): p50 0.96 ms, p95 1.68 ms, p99 2.73 ms (min 0.64, max 4.92);
  throughput 32.10 MB/s over 5 s (161 MB echoed), 256 KiB in-flight window.**
- Phone log (`evidence/phase4/p4log-pair-bench.txt`), successful-run session: `PAIRING
  prompt code=733362 client-fpr=FAAED033... pins=0` -> `PAIR_YES stored ... pins=1` ->
  (bench runs on the same session) -> `SESSION_END`; no `DROP` lines in that session, i.e.
  the bench frames passed the gate in the same session. (The three sessions above it in the
  same file never end - that is the hang fixed in section 2b.)

Evidence files:

| file | what it proves |
|---|---|
| `evidence/phase4/pairing-prompt.png` | notification with `Pair this PC?`, `PC code: 733362`, Yes/No visible (valid PNG, magic 89 50 4E 47, 174 219 B) |
| `evidence/phase4/bench-same-session.txt` | PC transcript: code, `Phone confirmed`, same-session bench numbers |
| `evidence/phase4/p4log-pair-bench.txt` | full phone log up to the bench session end (keeps the three stalled sessions as the "before" record) |
| `evidence/phase4/reconnect-after-restart.txt` | silent reconnect after service stop/start |
| `evidence/phase4/repair-after-forget.txt` | pairing after both stores were cleared |

## 2b. Dead/stalled session teardown (manager review 21:50 fix)

The manager found the honest defect: sessions can hang forever after the PC goes away.
Reproduced and located: a thread dump taken while the pipe was stalled (`phase4x dump`
harness action - `kill -3` dumps land in `/data/anr`, unreadable by shell on this image, and
`debuggerd -j` needs root) shows exactly where the thread sits:

```
THREAD p4-session state=RUNNABLE alive=true
  at java.net.SocketOutputStream.socketWrite0(Native Method)
  at java.net.SocketOutputStream.socketWrite(SocketOutputStream.java:116)
  ...
  at dev.unifieddesktop.app.Phase4LinkService.sessionThread(Phase4LinkService.kt:413)
```

I.e. blocked in a raw socket `write()` of a bench echo. A peer that stops reading (or a
half-closed adb tunnel) leaves it stuck forever; a read timeout can never fire there.

Fix (Phase4LinkService.kt, Phase4Protocol.kt, Phase4Dispatch.kt; unit-tested where pure):

- `LiveSession` registry: every accepted socket is tracked with `lastInboundMs` and `armed`.
- Watchdog thread `p4-watchdog` (checks every 5 s): a paired (`armed`) session with nothing
  inbound for `Phase4Protocol.DEAD_TIMEOUT_MS` (15 s, `watchdogExpired()`) is force-closed -
  `SO_LINGER(0)` + `shutdownInput/Output` + `close` - so a blocked `write()` wakes with an
  error and the session thread reaches its `finally`.
- `stopAll()` force-closes every live session and interrupts the watchdog.
- Every session logs `SESSION_END` in `finally`; `phase4x status` prints `live=N`.
- New pure predicate + 2 unit tests: `Phase4ProtocolTest` 12/12.

Proofs (emulator, 2026-10-18 21:45-21:56):

| test | what ran | result | evidence |
|---|---|---|---|
| A | bench running, PC process killed mid-bench | `SESSION_END` after **1 s** (limit 20 s) | `evidence/phase4/watchdog-kill-pc.txt` |
| B | `bench --window-kib 8192` (stalled pipe), stacks dumped mid-stall | stacks show `socketWrite0`; watchdog force-close at 15 s -> `SESSION io SocketException: Broken pipe` -> `SESSION_END` 10 s after the dump | `evidence/phase4/watchdog-hung-stack.txt`, `watchdog-deadlock-repro.txt` |
| C | bench running, `phase4x stop` mid-bench | `SESSION force-close (service stop)` -> `SESSION_END`; `STATUS running=false live=0` | `evidence/phase4/watchdog-stop-closes.txt` |

The PC side fails cleanly in the same situations, e.g. `BENCH FAILED: tls read: peer closed
connection without sending TLS close_notify`. This is what item 10 (failover) needs: a
pulled cable or a dead PC is noticed and the session is torn down.

## 3. Silent reconnect and forget cycles

- **Reconnect after service restart** (`phase4x stop` -> `phase4x start` -> `udreceiver
  pair`): `OK: reconnected silently to pinned phone 12FB82597D0CBCCC (127.0.0.1:47600)` -
  no code, no prompt, pinned certs verified.
- **Forget on the phone** (`phase4x forget-all` + PC `trust forget 12FB`) then `udreceiver
  pair`: PC showed `PC code: 733362` and waited - i.e. the PC was asked again - and after
  the phone's Yes: `Phone confirmed. ... OK: paired with phone 12FB82597D0CBCCC`.

## 4. Finding: 8 MiB in-flight window deadlocks the adb-forward path (fixed)

The first item-6 attempt used the original 8 MiB in-flight window. The bench froze on the
emulator dev path. Evidence (guest `/proc/net/tcp`, port 47600 = B9F0): three established
connections, each with ~0.8-1.0 MB queued **in both directions**, byte-identical at t=6 s
and t=20 s (only the retransmit timer changed), e.g.

```
0100007F:B6D9 0100007F:B9F0 01 000D2772:000FD869   (tx 862 066 B / rx 1 038 441 B)
0100007F:B04D 0100007F:B9F0 01 000C2B78:000FD894   (tx 797 048 B / rx 1 037 716 B)
```

The app session thread was blocked writing an echo while the relay was blocked writing the
next input; neither side ever drained (no `SESSION_END`, even for sessions whose PC process
had died). adb forward gives the guest TCP sockets ~1 MB buffers; an 8 MiB window fills
both directions before either drains.

Fix (`pc/udreceiver`): default window 256 KiB (`BENCH_WINDOW_BYTES`; `--window-kib <n>`
overrides it for real links) and a 60 s socket read timeout so a stalled peer fails with
`BENCH FAILED: stalled: no inbound data within the socket read timeout` instead of hanging
forever. The numbers in section 2 were taken after this fix. The phone side cannot hang on
this any more either: the watchdog in section 2b force-closes a session whose peer stops
draining.

## 5. WARP / routing pre-check (for phone items 7-10)

The laptop runs Cloudflare WARP (adapter `CloudflareWARP`, up). Route check for the dev
LAN and mDNS: `192.168.1.0/24` is on-link via Wi-Fi (`0.0.0.0/0` -> `192.168.1.1`), and
WARP's tunnel routes cover **only public ranges** - the private LAN (`192.168.1.0/24`) and
multicast `224.0.0.251` (mDNS) are **not** in its route list. So WARP should not be in the path for phone
LAN/mDNS traffic; if discovery fails on the phone, re-check this first. WARP itself was not
touched.

## 6. Status of the remaining Phase-4 items

- Items 7-10 (real phone: USB/AOA, Wi-Fi LAN, mDNS discovery, real-link numbers): **not
  run** - phone `2c0f6edc` was not in `adb devices` during the whole task. Exact owner
  steps and the reason are in SKIPS.md.
- Item 11 ("no developer mode / no adb required" code-path proof): see SKIPS.md section
  "No-adb proof" (grep transcript + owner check steps).
- Item 12-13 (close-out: gates, docs, cleanup): see TODO.md and the task report.
