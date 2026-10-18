# STATE: where the work stands

Rules: replace stale lines, never append history, never record a status that was not
verified this session. The coding agent updates this as its last action; the manager
corrects it after review.

## Now

- **Phase:** 2 of 13. Phase 1 (hidden Android app) is **CLOSED, GO** (2026-10-16). Phase 2's
  runtime is now proven from a **clean install** (round 4, item 9): broadcasts only, the app
  alone fetched and staged everything (runtime + udsh + fontconfig stub, bundle, Wine 11.19,
  Notepad++), booted the prefix (`PREFIX_BOOT_OK`), started Xvfb 10/10 (all first attempt,
  ~2.83 s) and rendered xeyes (PNG bit-identical to round 3b). The one remaining GUI blocker
  is **B-026**: no wine window ever maps. Round-4 root cause: wine 11.19 cannot enable
  syscall-user-dispatch here ("could not find library containing signal restorer trampoline"
  -> `syscall_dispatch_enabled=FALSE`) and box64's synthesized-SIGSYS SUD emulation then
  makes every guest-syscall-derived NT call return c0000005 — NPP self-dies 1.5 s in at
  `comdlg32.dll` load and the capture is bit-identical to the black reference (5336 B,
  527F19E0…); xeyes renders on the same server.
- **Last updated:** 2026-10-18, agent, round 4 (session 00:30-06:30; this update 03:28).
  Tried and REVERTED this round: H1 `allowNativeHeapPointerTagging=false` (no effect), H2
  box64 rt_sigreturn-synthesis patch (a host-side rt_sigreturn cannot consume the guest
  sigframe; deterministic crash — patch block in `fetch-box64.sh` marked REVERTED, lib at
  sha `F69ACC14…`). H3 became diagnostics (root cause above); the two real fix directions are
  in docs/PHASE2_RESULT.md "Round 4". Guards retuned: in-app cap 160 procs/400 MB, laptop
  watchdog 200 (they had been killing fair NPP tests at 44-49 procs). Clean-install proof
  done: B-019 evidence (no staging steps left), B-018 re-verified 10/10. Window PNG / typed
  text / survival stayed SKIPPED (SKIPS.md T6/T7). New bugs: B-027 (app-freezer deadlock —
  platform hazard; runs need stayon + foreground), B-028 (activity-intent replay quirk),
  B-029 (orphan wine children survive wineserver death + killall). Gates pass (6 classes /
  16 tests, 0 failures; APK 23,477,543 B). **PHASE 2 GATE: FAIL** (the window is the gap).
- **Next action:** manager decides B-026: (a) make wine's SUD setup succeed (restorer visible
  to `dl_iterate_phdr`) or (b) a box64-side guest-stack-correct SUD/rt_sigreturn
  implementation; a different candidate pin remains an option (B-024 `bin/wine` re-test still
  open). Then the window PNG + typed-text + survival gate. Smaller opens: B-029 reaper
  (`killWine()` via the existing `killAllOurProcesses`), B-028 launch-intent fix,
  scratch-script pass + D13/D4/ARCHITECTURE refresh (SKIPS.md).
- **Agent setup:** OpenCode on Windows with free models (DeepSeek/GLM flash) until about
  Phase 4/5. The agent keeps `TODO.md` ("Done X of Y"). Dev runs on the phone need
  `svc power stayon true` + the harness activity in the foreground (B-027), the retuned app
  cap (160/400) and the laptop watchdog (200 / 400 MB floor; restart it after every install,
  it caches the uid — LESSONS.md §5).

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
