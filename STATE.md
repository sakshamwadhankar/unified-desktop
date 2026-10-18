# STATE: where the work stands

Rules: replace stale lines, never append history, never record a status that was not
verified this session. The coding agent updates this as its last action; the manager
corrects it after review.

## Now

- **Phase:** 3 of 13. Phase 2 is **CLOSED, PASS** (2026-10-18, verified on the phone by the
  manager; details in `docs/PHASE2_RESULT.md`; shipping box64 lib = `e1c21ed2...`, recipe not
  reproducible, B-030). Phase 3 (VS Code/code-server in a WebView): **gate flows PASS on the
  emulator** (`emulator-5554`, Android 17 API 37, 16 KB pages) - node+code-server run from
  `nativeLibraryDir` (healthz 200; cold 7.9 s / warm 2.8 s), the full gate flow runs from the
  WebView with injected taps+IME (folder via the `?folder=` deep link; hello.txt edit+Ctrl+S
  save verified on disk by `run-as`; terminal command files `pwd.txt/term.txt/machine.txt`
  verified on disk), plus item-9 numbers (APK 88.2 MiB universal, libnode 79.4/78.9 MiB per
  ABI, app PSS 132.5 MiB + node 21.8 + worker 53.0, family 14 procs steady ~11, idle
  0.15%/core, `files/phase3` 580.1 MiB) and the Open VSX stretch (indent-rainbow 8.3.1
  installed through the marketplace UI, on-disk `extensions.json`; MIT verified in the
  unpacked tree). Full record: `docs/PHASE3_RESULT.md` (items 1-12, every PNG header-checked
  in `evidence/phase3/`). Build gates green 16:0x (`:app:testDebugUnitTest :app:lintDebug
  :app:assembleDebug`, build28, 48 unit tests green). **The phone round is owed** (2c0f6edc
  never connected; SKIPS.md "Phase 3 phone gate" row has the exact chain).
- **Last updated:** 2026-10-18 ~16:10, agent, phase-3 emulator round (session ~11:00-16:10).
  Mid-session the host killed the emulator in an OOM storm (PowerShell OOM, ~15:2x); the
  emulator is now owned by the agent and its hang/crash recovery is codified (BUGS B-041,
  incident log in docs/PHASE3_RESULT.md, LESSONS §5: relaunch only above 3 GB free at
  `-memory 3072 -no-boot-anim -no-snapshot-load`, delete stale `hardware-qemu.ini.lock`).
  New bugs: **B-039** (keyboard events injected by the app never reach the WebView page -
  all dispatch routes return true, zero page keydowns; system-path `input keycombination`
  works after the WebView focus fix: Ctrl+S saved a file; Phase-5 PC-keyboard decision
  owed), B-040 (PowerShell->adb quoting trap; spaces via `%s`, metas via `\> \;`), B-041
  (host RAM/emulator hangs). Harness additions: `cs-gofolder` deep-link action,
  focusable WebView, `boxes` DOM probe v4 (menus/quick-input/editor lines/terminal/
  activity bar/extension rows).
- **Next action:** the manager reviews Phase 3 (docs/PHASE3_RESULT.md + PNGs) and then
  decides: (a) run the phone gate when 2c0f6edc is available (SKIPS.md row), (b) B-039's
  Phase-5 keyboard-input decision, (c) extension-shipping policy (THIRD_PARTY row -
  indent-rainbow added as runtime-installed, not shipped). Phase 4 (PC link/stream) per
  PLAN.md; its input plan must start from B-039's findings.
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
| Bets: Phases 1-4 | ARCHITECTURE.md section 6, questions Q1-Q6 |
