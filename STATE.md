# STATE: where the work stands

Rules: replace stale lines, never append history, never record a status that was not
verified this session. The coding agent updates this as its last action; the manager
corrects it after review.

## Now

- **Phase:** 5 of 13 **CLOSED, PASS** (2026-10-19 02:28, real phone 2c0f6edc). Phases 1-4
  CLOSED, PASS (records: `docs/PHASE1_RESULT.md` ... `PHASE4_RESULT.md`; Phase 4 = Wi-Fi +
  USB tethering, AOA moved to its own round, D17). Next: Phase 6 (Desktop shell), not started.
- **Phase 5 (Video + input), phone:** the phone's 1920x1080 desktop surface shows full screen
  on the PC; hardware encode `c2.qti.avc.encoder` and hardware decode (MF H.264 MFT on D3D11,
  Intel UHD); mouse, drag and keyboard (incl. Shift) work on real Android views. ~57 fps
  moving and dragging (paced at 60). Typing latency p50/p95: Wi-Fi 122.7/172.9 ms, USB
  tethering 106.5/148.8 ms (n=50 each; target 80 ms -> Phase 12, B-051). Stuck keys: 0 after
  300 fast keys + 30 drags. Our CPU 130-151 %, PSS ~186 MB. Record: `docs/PHASE5_RESULT.md`.
  The agent built the pipeline (to 01:51); every free model then hit its usage limit and the
  manager finished the phase overnight on the owner's instruction (fixes B-052..B-055).
- **Last updated:** 2026-10-19 02:28, manager (Phase 5 close). Phone settings restored
  (stayon 0, brightness 2047), link stopped by the app's button, tethering off, laptop routes
  on Wi-Fi only, WARP untouched.
- **Next action:** owner review of Phase 5; Phase 6 prompt only when the owner asks. Open
  owner items: the AOA round (SKIPS, one UAC click + USB debugging off) and the 5-step
  no-developer-mode check (SKIPS "No-adb proof"). Phase 6 carry-overs are in SKIPS.
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
