# Phase 4 result: connection (PC receiver <-> phone link)

> Numbers in this file come from two machines, and every section labels its own source:
> section 2 (item 6) is the **emulator via `adb forward`** (adb's TCP relay on one machine,
> not a real link), while sections 6, 9 and 10 are the **real phone** over Wi-Fi and USB
> tethering (section 7 is the phone's USB AOA probe). Real-link numbers are in section 11.

Date of the runs below: 2026-10-18, 21:29-21:35 (item 6), 21:45-21:56 (watchdog proofs) and
21:57-22:15 (manager-21:55 user-path/accessory/discover fixes; emulator rehearsal of the
real user path); phone round 2026-10-18 23:36 - 2026-10-19 00:37 (items 7-10).
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

## 2c. User path and other manager-21:55 fixes (before the phone round)

The manager's 21:55 review listed five fixes; all are implemented:

1. **The link service now has a user path** (before this, only the debug harness could
   start it). `HarnessActivity` (the launcher activity) asks for `POST_NOTIFICATIONS` at
   runtime on Android 13+ and shows a minimal `Start link` / `Stop link` pair with a
   status line (`link: running=... live=... acc=... last=...`); the buttons do a
   foreground start from the visible activity. Emulator rehearsal of the real user path
   (label emulator): `pm revoke POST_NOTIFICATIONS` -> open the app -> real system dialog
   "Allow Unified Desktop Phase 1 to send you notifications?" -> `input tap` Allow ->
   `input tap` `Start link` -> status `running=true live=0 acc=false last=NSD registered
   unified-desktop`, the "Desktop link running" FGS notification present, `p4.log` shows
   `START ok port=47600`; `Stop link` -> `running=false`. Screenshots
   `evidence/phase4/userpath1-notif-dialog.png`, `userpath2-after-start.png`. On the
   phone round the same flow runs after `pm revoke` (or clear data) - adb is used only
   for simulated taps, screenshots and reading logs.
2. **AOA attach path**: `Phase4AccessoryActivity` starts the link service itself on
   `USB_ACCESSORY_ATTACHED`; the new `Phase4AccessoryReceiver` handles the permission
   dialog result; `Phase4Aoa.handOff` opens the accessory and the service adopts the FD
   (`ParcelFileDescriptor.adoptFd`, status `acc=true`, log `ACC adopted fd=...`). The TLS
   transport over that FD is **not** wired yet - that is item 8's remaining work; the
   attach path no longer needs adb.
3. **`udreceiver discover [--seconds <s>] [--to <ip[:port]>]`** (item 7): a hand-written,
   std-only mDNS browse of `_udlink._tcp.local` (unicast-response query to
   `224.0.0.251:5353`, bind-5353-or-ephemeral, DNS name decompression, follow-up SRV/A
   queries). No new dependency, so no THIRD_PARTY row. `pair`/`bench` accept `--addr
   auto` (or no `--addr`) and use the first discovered phone; `--to` sends the same query
   unicast (diagnostic/fallback). Tests: record parser (synthetic packet with compression
   pointers), service filter, truncation, query bits, and a full loopback **socket
   roundtrip** (bind -> query -> receive -> parse -> resolve), 20/20 green. `discover` on
   the laptop without a phone prints `DISCOVER none` and exits 1 (verified). Note: on
   this laptop Windows' DNS Client service owns UDP 5353, so `discover` takes the
   ephemeral-port + QU path - exactly what the socket-roundtrip test covers.
4. **`udreceiver aoa list|probe|start [--vid <hex>]`** (item 8): SetupAPI enumeration of
   USB device interfaces (with each function's device description via
   `SPDRP_DEVICEDESC`) + `CreateFileW`/`WinUsb_Initialize` + `WinUsb_ControlTransfer`
   for GET_PROTOCOL (51), SEND_STRING (52; manufacturer `unified-desktop`, model
   `desktop` - matching `usb_accessory_filter.xml`) and START (53), via the `windows`
   0.62.2 crate (THIRD_PARTY.md row added before first use). **The phone's ADB interface
   is excluded by rule** (manager's Phase-4 prompt: it exists only with developer mode
   on, which would make the gate meaningless) - `is_adb_interface` matches "ADB" in the
   function description and `aoa list` marks it `[ADB - excluded by rule]`; the adb
   server is never killed or started. `aoa list` was run on the laptop: it enumerates
   real USB devices with descriptions (USB Input Device, USB Composite Device, Intel
   Wireless Bluetooth) and reports that none is WinUSB-openable (expected without the
   phone); the phone-side proof is item 8. The probe's NO-GO text now states the two
   shippable driver options (a Microsoft-OS-descriptor vendor interface that asks
   Windows for WinUSB, or a signed one-time WinUSB INF shipped by the installer).
5. **Phone-round scripts** `p4\item7.ps1` ... `item10.ps1` (+ `p4common.ps1`) drive the
   user path only (open app -> `Start link` button), use simulated taps for the pairing
   `Yes`, and write every text file UTF-8 without BOM via `[IO.File]::WriteAllText`.

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

Second networking fact found while rehearsing `discover` (2026-10-18 22:20): on this
laptop Windows' own DNS Client service already listens on UDP **5353**, so `discover`
cannot bind that port and falls back to an ephemeral port with the unicast-response bit
in its mDNS query (that path is covered by the loopback socket-roundtrip test). If a
phone's mDNS responder ignores the QU bit, the fallback for item 7 is
`udreceiver discover --to <phone-ip>` (or `--addr <phone-ip>:47600` with the IP read from
the phone's `wlan0` over adb); the evidence will say which path produced the address.

## 6. Phone round (2026-10-19): item 7 - Wi-Fi link, discovery, pairing

Phone 2c0f6edc (OnePlus CPH2619, Android 16, Wi-Fi 192.168.1.16); laptop Wi-Fi
192.168.1.13 (default route via 192.168.1.1). Evidence: `evidence/phase4/item7-*.txt`.

- **Permission + user path.** The real POST_NOTIFICATIONS dialog was used and tapped
  ("Allow Unified Desktop Phase 1 to send you notifications?"), grant state `USER_SET`
  (`phone-perm-dialog.txt`, `item7-0-perm-dialog.png`, `item7-0b-after-allow.png`). It was
  reached by uninstall + reinstall, not by `pm clear`: on this ColorOS build `pm clear`
  fails silently (PackageManager stack trace) - see SKIPS.md. The link service was started
  only through the user path (open the app -> Start link): `running=true live=0 acc=false`.
- **Discovery with no IP typed in** now works: `udreceiver discover --seconds 4` ->
  `DISCOVER unified-desktop._udlink._tcp.local 192.168.1.16:47600
  host=android_swqs3yz9.local`, `mdns: answer via 192.168.1.13`, exit 0
  (`item7-discovery.txt`).
  - **Root cause of the earlier failure:** this laptop has several adapters that carry
    `224.0.0.0/4` routes (WARP 172.16.0.2, VMware VMnet1/8, WSL 172.27.128.1, Wi-Fi
    Direct "Local Area Connection* 2"). An *unbound* multicast send leaves through the
    wrong adapter - probed: a socket `connect(224.0.0.251)` on this laptop resolves to
    WARP's 172.16.0.2, so the query never reached the Wi-Fi LAN.
  - **Fix (code):** the PC now probes every local IPv4 from the host-name resolution as
    the socket source address (multicast + unicast-response bit, 700 ms per candidate,
    first answer wins) and logs `mdns: answer via <ip>`; unbound multicast remains as a
    last resort. `--to <ip>` is unchanged. Test: `lan_candidates_are_unicast_and_deduped`.
- **Settings:** NO firewall rule was added; the Wi-Fi network profile was NOT changed
  (still Public); WARP was not touched; the laptop ran without admin
  (`IsInRole(Administrator)=False`). Nothing to remove at item 13.
- **Pairing (fresh, both sides forgotten).** PC identity `FAAED033F62353AC`; PC showed
  `PC code: 038762`; the phone's notification showed the same code; the phone stored
  `PAIR_YES stored fpr=FAAED033F62353AC pins=1`; the PC trust store holds
  `B20B88AF...` (`item7-pair-retry.txt`).
  - **How the Yes was pressed (manager question):** our scripted tap did **not** press
    it - the single tap attempt found no `text="Yes"` node and sent **no** `input tap`
    (`item7-pair-retry.txt`: `tap: NOT FOUND: Yes`). Logcat (`item7-pair-yes-forensics.txt`)
    shows the notification heads-up at 00:05:27.12, the shade taking input focus at
    00:05:34.189 and `PAIR_YES` at 00:05:34.439 - 250 ms later - while the phone was in
    active human use (another app held input focus until that second; name redacted).
    Conclusion: **a person pressed Yes on the phone**; we cannot prove the finger, but no
    agent-side actor existed (no leftover script job, no script ever used the dev dispatch
    accept path; grep in the forensics file) and no command we ran can press it. The pair
    itself was valid: both sides showed 038762 and exactly one pin was stored.
- **Bench over Wi-Fi (fixed binary, same session):** RTT p50 14.88 / p95 19.59 / p99
  30.10 ms (min 5.26 / max 39.79, 1000 x 64 B), **8.68 MB/s** over 5 s (44 MB echoed),
  `item7-pair-retry.txt`. Earlier pre-fix run: 12.44 MB/s, p50 15.12 ms (`item7-wifi.txt`).
- **Privacy (manager 00:15):** four screenshots that showed the phone's shade or home
  screen were deleted (`item7-2`, `item7-5`, `item7-6`, `phone-prep-screen`); only shots
  of our app/dialogs remain.
- **Battery (manager 00:15):** `dumpsys battery` before each item; at 00:18 level=1,
  status=charging (USB, 40.5 C). No drop-off occurred during the round.

## 7. Item 8: USB via AOA -> NOT PROVEN tonight (open, with the driver route)

Verdict: **NOT PROVEN tonight** - not a NO-GO. The probe ran only in our dev USB state
(debugging ON) and only against WinUSB-bound interfaces; two premises remain untested, so
this phone + this laptop have not disproven the AOA leg:

1. **Probing was done only with USB debugging ON** (`sys.usb.config=adb`,
   `sys.usb.state=adb`), which is not the user's state. AOA requests 51/52/53 are
   **device-level vendor control requests on endpoint 0**: they need any handle on the
   device, not a WinUSB-bound function interface. With debugging off the phone presents
   MTP (WPD driver) and nothing was probed in that state (switching mid-round would have
   cut our adb access - deliberately not done).
2. **WinUSB + INF is not the only signed route.** UsbDk (daynix/UsbDk, **Apache-2.0**,
   signed MSI, owner's admin once - and admin once is allowed by the workflow) detaches
   the device from whatever driver currently owns it (MTP/WPD or none) and gives user mode
   direct USB access, including control transfers. "Not fixable without admin" is
   therefore wrong; the MSI was deliberately **NOT installed tonight** (needs the owner's
   UAC click).

What was actually measured (evidence `evidence/phase4/item8-aoa-probe.txt`, 00:13):

- With debugging on: the phone exposes **one** functional interface, the ADB one -
  `aoa list` shows it as `"WinUsb Device" \?\usb#vid_22d9&pid_2769#2c0f6edc`; our probe
  cannot open it while `adb.exe` holds it (WinUSB is a single-claimant API) and the rule
  forbids using it for this gate anyway.
- `aoa probe --vid 18d1` and probe-all: "no WinUSB-openable interface answered
  GET_PROTOCOL (51)".
- Nothing was installed and nothing needs removing.

Next AOA round (owner present, one UAC click): the six exact steps are in SKIPS.md row
"AOA round" - UsbDk MSI, then `aoa start --usbdk` (UsbDkHelper.dll: GetDevicesList,
StartRedirect, then a control transfer on endpoint 0), owner turns USB debugging OFF and
plugs in, expect re-enumeration as `18D1:2D00` and our app opening the accessory, then
pair + bench; uninstall UsbDk if not kept. Code hardened meanwhile: the ADB interface is
excluded by name **and** by class triple FF/42/01 (`is_adb_triple`, unit-tested); the adb
server was never killed or started.

## 8. Status of the remaining Phase-4 items

- Items 9 (USB tethering) and 10 (failover): **DONE 2026-10-19 00:37** - sections 9 and 10
  below.
- Item 11 ("no developer mode / no adb required" code-path proof): see SKIPS.md section
  "No-adb proof" (grep transcript + owner check steps).
- Items 12-13 (close-out: gates, docs, cleanup, results table): section 11 below, TODO.md
  and the task report.

## 9. Phone round (2026-10-19): item 9 - USB tethering link

Owner path: Settings > Hotspot and tethering > USB tethering ON (one toggle, no adb); the
link service was already started through the app (user path). adb survived the whole round
(`sys.usb.state=rndis,adb`).

- The tether is OnePlus's `10.165.255.0/24` (**not** 192.168.42.x): PC `Ethernet 2`
  (RNDIS, 426 Mbps) got 10.165.255.217, phone 10.165.255.65. On-link reachability needs no
  extra route.
- **Routing rule (manager 00:27/00:35), measured:** Windows gave the RNDIS adapter
  automatic metric **25** vs Wi-Fi's **30**, so the laptop's default route had **moved to
  the phone** the moment the tether came up. Every route/metric write is Access-denied
  without elevation (Set-NetIPInterface, Remove-NetRoute, New-NetRoute all probed). One
  owner-approved UAC fixed it: an elevated one-liner removed the tether's default route
  and pinned its metric to **9999 in the active store only** (temporary, cleared at
  reboot; nothing installed). After that, every guard readout showed exactly one default
  route (ifIndex 11 via 192.168.1.1) and `ping 1.1.1.1` True; WARP was Running and
  untouched (public traffic still rides WARP - a route lookup for 1.1.1.1 gives
  172.16.0.2, ifIndex 52).
- Bench: RTT p50 4.06 / p95 5.20 / p99 6.64 ms (min 2.90 / max 13.43, 1000 x 64 B),
  **8.92 MB/s** over 5 s (45 MB echoed); connect was a **silent reconnect to the pinned
  phone** (same pin, no prompt). Phone log: accepted /10.165.255.217 -> TLS_UP pins=1 ->
  bench -> SESSION_END.
- Tethering OFF once, after item 10: the adapter disappeared, no leftover `10.165.255*`
  routes, the active-store metric went with the device (nothing to restore),
  `sys.usb.state=adb` again. Battery level 1, charging (status 2) before each step;
  `mCallState=0` before every input. Label: **phone**. Evidence:
  `evidence/phase4/item9-tether.txt`.

## 10. Phone round (2026-10-19): item 10 - failover smoke (tether -> cut -> Wi-Fi -> tether)

Method: the USB "pull" was PC-side - an elevated helper (owner-approved UAC) ran
`Disable-NetAdapter` on the RNDIS adapter mid-bench and re-enabled it when the run wrote a
signal file. The phone's USB mode was never changed and adb stayed up. (Two scripted
attempts of the `item10.ps1` runner aborted first: attempt 1 was a pull-timing collision;
attempt 2 stopped with "no RNDIS adapter" although the adapter was up - a background test
proved a background child sees the adapters fine (`adapters=8 rndis=1 rndis_up=1`), so that
stays an open runner question. The final run drove the same steps directly; the data below
is from that run.)

- A) tether up: as section 9 (p50 4.06 ms, 8.92 MB/s).
- B) **cut** at 00:36:00.398: the PC bench died **0.7 s** later (`BENCH FAILED: tls read:
  An established connection was aborted by the software in your host machine. (os error
  10053)`). Phone: accepted 1792350354812 -> `SESSION dead-timeout (no inbound in time)`
  1792350378804 -> `SESSION_END` 1792350378805 - lifetime 24.0 s, i.e. ~23.5 s after the
  last inbound frame, closed by the pump's 15 s read tick (no `SESSION force-close` line
  from the watchdog) -> **B-046**.
- C) Wi-Fi: `discover` (4 s) found `192.168.1.16:47600` (`mdns: answer via 192.168.1.13`);
  bench **11.46 MB/s**, p50 16.27 / p95 27.72 / p99 39.22 ms.
- D) back to USB: the adapter was re-enabled after 77 s, metric re-pinned to 9999, no
  tether default route; bench **12.08 MB/s**, p50 3.95 / p95 4.97 / p99 6.16 ms.
- End state: tethering off, default route Wi-Fi only, no leftover routes, WARP Running.
  Label: **phone**. Evidence: `evidence/phase4/item10-failover.txt`.

## 11. Phase-4 link results (every row labelled by where it ran)

| link | MB/s | RTT p50 / p95 / p99 (ms) | setup steps | verified on |
|---|---|---|---|---|
| emulator, adb forward (item 6) | 32.10 | 0.96 / 1.68 / 2.73 | both stores cleared; `adb forward tcp:47600`; pairing Yes tapped in the notification | emulator |
| phone Wi-Fi (item 7, fixed binary) | 8.68 | 14.88 / 19.59 / 30.10 | open app -> `Start link`; `discover`; fresh pairing (a person pressed Yes, B-044) | phone |
| phone Wi-Fi after a USB cut (item 10 C) | 11.46 | 16.27 / 27.72 / 39.22 | `discover` -> `bench` (silent reconnect to the pinned phone) | phone |
| phone USB tether (item 9) | 8.92 | 4.06 / 5.20 / 6.64 | Settings toggle ON; one-time owner UAC pins the tether metric 9999 (keeps the default route on Wi-Fi) | phone |
| phone USB tether after re-enable (item 10 D) | 12.08 | 3.95 / 4.97 / 6.16 | PC-side pull/re-enable (elevated helper re-pins the metric) | phone |
| phone USB AOA | - | - | NOT PROVEN tonight; next round in section 7 + SKIPS.md "AOA round" | not run |

**Faster link on this pair: USB tethering** - ~4x lower RTT (p50 3.95-4.06 vs
14.88-16.27 ms) and faster throughput in both pairwise runs (8.92 vs 8.68; 12.08 vs
11.46 MB/s). Wi-Fi (~8.7-11.5 MB/s, p50 ~15-16 ms) stays the no-cable path; the tether
needs the one-time metric guard (above). No firewall rule and no network-profile change
were made anywhere in the round; WARP was never touched.
