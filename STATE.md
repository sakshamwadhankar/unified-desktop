# STATE: where the work stands

Rules: replace stale lines, never append history, never record a status that was not
verified this session. The coding agent updates this as its last action; the manager
corrects it after review.

## Now

- **Phase:** 2 of 13. Phase 1 (hidden Android app) is **CLOSED, GO** (2026-10-16). Phase 2
  is close to done for the runtime: Linux x86-64 apps render (xeyes); Wine 11.19 boots a
  prefix in ~8 s, `create_winstation`/`create_desktop` succeed and the B-022 explorer chain
  is gone (functional no-op fontconfig stub in the rootfs). The one remaining GUI blocker is
  **B-026**: no wine window ever maps under Xvfb (`NtUserShowWindow` -> c0000005 inside
  wine's syscall dispatcher while win-proc callbacks run; xeyes on the same server renders).
- **Last updated:** 2026-10-18, agent, round 3b (hard stop 01:38). Phone-verified: B-022
  FIXED (fresh boot 8.1 s; `PREFIX_BOOT_OK`; `NtUserInitializeClientPfnArrays
  retval=00000000`; 0 NULL-calls; 6 procs at rest, no explorer chain). NPP launches and
  stays alive (9.9 s to visible in `ps`) but shows no window (B-026) - window PNG and typed
  text stayed SKIPPED (SKIPS.md T6). 10-minute survival passed (same 8 PIDs, CPU
  advancing). Gates pass on a forced rerun (13 tests, 0 failures; APK 23,477,499 B).
  `fetch-box64.sh` default pin is now main `27860e6a` (was v0.4.4; overrides documented,
  all anchors dry-run-validated); `fontconfig-stub/build.sh` reproduces the stub
  bit-identical. Details: docs/PHASE2_RESULT.md round 3b.
- **Next action:** decide B-026 (another box64/wine pin or upstream: dispatcher fault under
  nested win-proc calls) - then the window PNG + typed text gate. For a clean install the
  fontconfig stub must also be wired into the in-app rootfs fetch (Phase2Data): this round
  deployed it phone-side only. Hardening (B-019 clean install, scratch scripts, docs) stays
  in SKIPS.md.
- **Agent setup:** OpenCode on Windows with free models (DeepSeek/GLM flash) until about
  Phase 4/5. The agent keeps `TODO.md` ("Done X of Y") for the owner, and every phone run
  needs the watchdog plus the app cap (B-022).

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
