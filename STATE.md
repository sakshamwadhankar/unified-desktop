# STATE: where the work stands

Rules: replace stale lines, never append history, never record a status that was not
verified this session. The coding agent updates this as its last action; the manager
corrects it after review.

## Now

- **Phase:** 4 of 13 **CLOSED, PASS** (manager verified 2026-10-19 00:55; D17: Wi-Fi + USB
  tethering on the real phone, AOA moved to its own round). Next: Phase 5 (Video + input).
  Phases 1-3 CLOSED, PASS (records:
  `docs/PHASE1_RESULT.md`, `PHASE2_RESULT.md`, `PHASE3_RESULT.md`). **Phase 4 (Connection),
  every row labelled by where it ran:**
  - item 6 emulator dev path PASS (adb forward): RTT p50 0.96 / p95 1.68 / p99 2.73 ms,
    32.10 MB/s over 5 s.
  - item 7 phone Wi-Fi PASS: real POST_NOTIFICATIONS dialog + user-path start; discovery from
    scratch works (`mdns: answer via 192.168.1.13`; root cause: unbound multicast left via
    WARP - fixed by probing each local IPv4 as the socket source); fresh pairing 038762 (a
    person pressed Yes, B-044); silent reconnect; bench 8.68 MB/s, p50 14.88 ms.
  - item 8 phone USB AOA **NOT PROVEN tonight** (not a NO-GO): the probe covered only the
    debugging-ON state and WinUSB-bound interfaces; 51/52/53 are endpoint-0 device requests
    (any handle suffices) and UsbDk is an untested signed detach route; next round =
    SKIPS.md "AOA round" (one owner UAC); B-043.
  - item 9 phone USB tethering PASS: 8.92 MB/s, p50 4.06 ms, silent reconnect; the routing
    rule needed ONE owner UAC (RNDIS metric 25 < Wi-Fi 30 stole the default route; pinned
    9999 in the active store only, nothing installed); tethering OFF at the end, no residue.
  - item 10 phone failover PASS: PC-side pull (elevated helper, one UAC) - the PC noticed the
    dead link in 0.7 s; the phone closed it ~23.5 s after the last frame (watchdog missed the
    15 s rule; B-046); Wi-Fi hop 11.46 MB/s; back over the re-enabled tether 12.08 MB/s.
  - item 11 no-adb / no-developer-mode proof: code-only, SKIPS.md "No-adb proof".
  - item 12 build gates green (2026-10-18 22:04): `:app:testDebugUnitTest :app:lintDebug
    :app:assembleDebug` OK (lint 0 errors); `cargo test --release` green then, 25/25 after
    the B-045 fix + AOA-triple tests, clippy clean.
  - item 13 close-out: docs updated (`PHASE4_RESULT.md` 7/9/10/11, ARCHITECTURE H2/P4/Q5/Q6,
    BUGS B-043/B-046, SKIPS "AOA round", THIRD_PARTY UsbDk row); cleanup verified (tethering
    off, no routes/driver residue, gradle daemon stopped, `svc power stayon false`, WARP +
    firewall + Wi-Fi profile untouched, 47600 listeners 0, link service stopped via the
    app's own Stop link button).
  Numbers table + method: `docs/PHASE4_RESULT.md` §11. Fastest link on this pair: USB
  tethering (~4x lower RTT than Wi-Fi; 8.9-12.1 vs 8.7-11.5 MB/s).
- **Last updated:** 2026-10-19 00:47, agent, Phase-4 close-out round (items 7-10 + 13 on
  2c0f6edc). New this round: the PC pair-wait read tick (B-045, fixed), AOA ADB-triple
  hardening, the mdns multi-adapter discovery fix; B-043 rewritten to NOT PROVEN + Q5
  evidence, B-046 added (dead-link watchdog), B-044 (Yes-press forensics). All of it is committed by the manager
  (Phase 4 close-out commit).
- **Next action:** Phase 5 (Video + input). Manager decisions on the Phase-4 bugs are in
  BUGS.md (B-043 AOA round later; B-044 fix in Phase 5; B-027/B-033/B-035/B-046 Phase 10).
  Owner check still open: the 5-step no-developer-mode check (SKIPS.md "No-adb proof").
- **Agent setup:** OpenCode on Windows with free models (DeepSeek/GLM flash) until about
  Phase 4/5. The agent keeps `TODO.md` ("Done X of Y"). Dev runs on the phone need
  `svc power stayon true` + the harness activity in the foreground (B-027), the app
  cap (40/600) and the laptop watchdog (40 procs / 600 MB floor; restart it after every install,
  it caches the uid - LESSONS.md §5; 2026-10-18 the instance had gone stale at u0_a467: check the
  log's `start user=` line, a stale watchdog reads 0 and cannot fire). Emulator policy and
  the PowerShell->adb input recipes are in LESSONS.md §5 (emulator owned by the agent since
  ~15:0x 2026-10-18).

## Done (verified on the phone, 2026-10-14)

- **T1 engine** — `docs/PHASE1_ENGINE.md`: pick **Blacks-BlackBox** (Apache-2.0).
- **T2 build (G6)** — the whole `android/` project now **builds**: Java, Kotlin, AIDL, the
  native Dobby+BlackBox engine, dex, APK. Fixes made this session: library `targetSdk`
  removed, AGP-9 `sourceSets` DSL, `local.properties` path, submodule names
  (`:black-reflection`/`:compiler`), dropped the bad kotlin-stdlib pin, Bcore manifest
  wired in, Dobby re-pinned to self-consistent `e9fe7fb` + NDK 26, two Dobby/Bcore source
  patches (case-collision, `Logger::Shared` inline) applied reproducibly in `fetch-source.sh`.
- **T3 virtualization** — BlackBox runs a real Play app (Google Tasks) inside our app
  (ProxyActivity, uid 10189, process `:p0`); it renders. **But on the real screen, not our
  display.** Evidence in `evidence/phase1/`.
- **T5 numbers** — 2 processes (host + `:black`), TOTAL PSS ≈ 43.7 MB. No frame ever lands
  on our display.
- **T6** — `docs/PHASE1_PLAY_POLICY.md` (REQUIRE_SECURE_ENV, avoid QUERY_ALL_PACKAGES).

## Round 2 results (emulator; phone unavailable)

- **T0:** `evidence/phase1/round2_placement.txt` explains the activity-record versus
  actual-window display contradiction: the proxy task record is under the requested private
  display, while the guest WindowState is on built-in display 0.
- **T1:** the final smoke run received 97 ImageReader frames in 10.684 s (**9.08 fps**),
  first frame **833 ms**; samples stayed in app-private storage.
- **T2 B1:** public Presentation decor reparenting failed with
  `IllegalArgumentException: ...DecorView... not attached to window manager`.
- **T2 B2:** delayed public SurfaceControlViewHost reparenting reached
  `B2_SURFACE_PACKAGE_SHOWN`, but the ImageReader received no guest frame. No B3 public
  alternative was found without the same ViewRoot reparenting problem or forbidden APIs.
- **T3/T4:** not run because T2 did not produce guest content. Deferred rows are in
  `SKIPS.md`.
- **G6:** `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug` pass.

## The blocker (why NO-GO)

A Play-installed app cannot launch ANY activity (not even its own) onto a virtual display
it creates. Android's check (`ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay`,
AOSP main, read by the manager 2026-10-15): an app-made display is untrusted, so the
activity needs `allowEmbedded="true"` AND the caller needs `ACTIVITY_EMBEDDING`
(signature|privileged) or an activity of its uid already on that display (impossible for
the first one). Trusted displays need a system permission; PUBLIC displays need a
MediaProjection grant. Phone evidence: the SecurityException in
`evidence/phase1/selftest_launchDisplayId_denial.txt`. Details and the manager's
corrections in `docs/PHASE1_RESULT.md`.

## Open

| Item | Notes |
|---|---|
| Bets: Phases 1-4 | ARCHITECTURE.md section 6: Q1-Q4 answered (records in `docs/PHASE1_RESULT.md`...`PHASE4_RESULT.md`); Q5 NOT PROVEN - AOA round deferred (SKIPS.md row); Q6 answered (custom framed protocol over TCP + TLS 1.3, not QUIC/WebRTC). |
