# STATE: where the work stands

Rules: replace stale lines, never append history, never record a status that was not
verified this session. The coding agent updates this as its last action; the manager
corrects it after review.

## Now

- **Phase:** 6 of 13 - **emulator round COMPLETE, PHONE ROUND OPEN** (item 8: the phone
  `2c0f6edc` never appeared in `adb devices` - unplugged at 06:09, polled every 30 s until
  the hard stop -> SKIPS.md). Phases 1-5 CLOSED, PASS (records: `docs/PHASE1_RESULT.md` ...
  `PHASE5_RESULT.md`).
- **Phase 6 (Desktop shell), emulator:** taskbar + start menu (Files, Browser, Settings,
  Test), movable/resizable windows driven by the PC's real mouse and keyboard through the
  Phase-5 link, keyboard routed to the focused widget; four app-side gate screenshots of the
  live shell hierarchy. Window model pure Kotlin, 18 tests; `:app:testDebugUnitTest` 100 tests
  / 0 failures / 3 skipped, lint + assemble green (last run on the final tree 07:38); cargo
  41/41, clippy clean. Numbers (emulator via adb forward, phone-side counters): idle
  keep-alive **1.0 fps** (static shell), title-bar drag **30.6 fps** over 30.4 s (929 frames),
  typing medians recv->dispatch **0.14 ms** / dispatch->drawn **8.4 ms** / drawn->encoded
  **26.8 ms**; stuck keys **0** after 100 keys + 10 drags. Limits: the PC window stays black
  on the *emulator's* stream (**B-057** - every frame dropped awaiting a keyframe the emulator
  encoder never sends; the phone's encoder honoured it in Phase 5), so the gate shots are
  app-side and the PC-side typing stages could not print; **B-056** (emulator encoder
  configure rejection) mitigated in code. B-039 probe: PC keys reach the browser's address
  field via the **IME route**; the page-DOM route is still open. Record: `docs/PHASE6_RESULT.md`.
- **Last updated:** 2026-10-19 07:41, agent (Phase 6 items 9-11). Cleanup done at 07:39:
  emulator shut down (`emu kill`, qemu gone), receiver killed, `adb forward` removed,
  `adb devices` empty, phone never touched. A background watcher kept polling for `2c0f6edc`
  until ~08:45 at the time of writing.
- **Next action:** the phone round: plug `2c0f6edc` in and run the item-8 checklist
  (docs/PHASE6_RESULT.md, SKIPS.md): install, dismiss the OEM scan dialog with Ignore,
  Start link, `udreceiver view --addr auto` over Wi-Fi, receiver-window screenshots (start
  menu, overlapping windows), 30 s drag fps + idle fps from the receiver's VSTAT, 20 typing
  samples into the browser address bar, stuck keys after 100 keys + 10 drags. Then manager
  review of Phase 6 (B-056/B-057 decisions). Earlier open owner items: the AOA round and the
  5-step no-developer-mode check (SKIPS).
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
| Phone round (Phase 6 item 8) | SKIPS.md row (phone unplugged all run; polled until the hard stop). Every check has an emulator stand-in, labelled `emulator via adb forward` in `docs/PHASE6_RESULT.md`; none closes the item. Needs the owner to plug `2c0f6edc` in. |
| B-056 / B-057 (Phase 6) | Open in BUGS.md awaiting the manager's decision: emulator encoder configure rejection (mitigated in code) and the PC black window on the emulator stream (permanent drop while awaiting a keyframe). |
