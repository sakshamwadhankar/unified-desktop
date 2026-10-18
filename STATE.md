# STATE: where the work stands

Rules: replace stale lines, never append history, never record a status that was not
verified this session. The coding agent updates this as its last action; the manager
corrects it after review.

## Now

- **Phase:** 2 of 13. Phase 1 (hidden Android app) is **CLOSED, GO** (2026-10-16). Phase 2: the
  GUI gate is **CLOSED on the phone** (round 5c): **B-026 is SOLVED** - the no-window cause was
  the guest **SysV-shared-memory path** (winex11's XShm); the F4 block in
  `android/win86/fetch-box64.sh` intercepts it on both routes (wrapped-libc `my_shm*` and raw
  syscalls 29/30/31/67) and Xvfb runs `-extension MIT-SHM`. Clean install, broadcasts only
  (uid u0_a475): window PNG (`evidence/phase2/npp-window-clean-install.png`), "hello from
  phase 2" typed through the app (`npp-typed-clean-install.png`, length 18, unchanged 30 s
  later), 10-min 26-s survival (`npp-window-11.19-10min.png`), launch-to-first-frame 27.0 s,
  8 procs, notepad++ PSS 191,814 kB, idle ~6.7% of one core. **PHASE 2 GATE: PASS**, verified by
  the manager 2026-10-18 11:15 (PNGs viewed, 24 unit tests green, phone at 0 processes, no seccomp kill since 09:37). **Phase 2 is CLOSED.** Shipping lib = the F4 build `e1c21ed2...` (jniLibs +
  APK); the box64 recipe is NOT reproducible (B-030) - the tested bytes are the artifact
  (gitignored `vendor/` + the p2r5 dir).
- **Last updated:** 2026-10-18, agent, round 5c (session ~09:45-11:12; this update 11:12).
  Round 5c: **B-026 fixed** (F4: the raw-syscall block stopped the `exit=159` SIGSYS deaths; the
  wrapped-libc shm stubs produced the window; `COMCTL32.dll.381 aborting` was a pre-fix symptom,
  gone after F4). XTEST typing fixed end-to-end (QueryExtension +2 pad bytes; reply present@8 /
  opcode@9 with a mainline fallback; FakeInput 36 B) and the stuck-key flood fixed with a
  keycode release sweep (B-032). Every gate item re-proven from a clean install (numbers + PNGs
  above); B-029 reaper verified (`REAP tag=killall candidates=10 killed=9`, only app + `:black`
  left); early-death race counted 5/5 cold vs 5/5 warm (B-031); box64 rebuild check gave three
  shas from one recipe -> B-030 (ship the tested bytes); LMK kill 10:11:50 documented (B-033);
  build gates green; 0 processes left; all watchdogs stopped (the round-5 instance had gone
  stale at uid u0_a467 - the in-app guard was the live cap). **PHASE 2 GATE: PASS**.
- **Next action:** the owner decides when to start Phase 3 (PLAN.md: full-speed tools, i.e.
  Node.js + code-server/VS Code shipped as `lib*.so`). Manager-side: D13 (box64/wine pins +
  F4), D4 and ARCHITECTURE.md H6/H7 notes for Phase 2; a safe off-laptop copy of the tested
  box64 lib (B-030). Open Phase 2 follow-ups: B-031 (first-launch race), B-030, B-033. Deferred
  rows in SKIPS.md: 11.0's `libgcc_s.so.1` staging, the `android/win86` scratch-script pass.
- **Agent setup:** OpenCode on Windows with free models (DeepSeek/GLM flash) until about
  Phase 4/5. The agent keeps `TODO.md` ("Done X of Y"). Dev runs on the phone need
  `svc power stayon true` + the harness activity in the foreground (B-027), the app
  cap (40/600) and the laptop watchdog (40 procs / 600 MB floor; restart it after every install,
  it caches the uid — LESSONS.md §5; 2026-10-18 the instance had gone stale at u0_a467: check the
  log's `start user=` line, a stale watchdog reads 0 and cannot fire).

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
