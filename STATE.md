# STATE: where the work stands

Rules: replace stale lines, never append history, never record a status that was not
verified this session. The coding agent updates this as its last action; the manager
corrects it after review.

## Now

- **Phase:** 1 of 13 (hidden Android app). **CONCLUDED on the real phone: NO-GO for the
  Phase 1 architecture.** See `docs/PHASE1_RESULT.md`. Virtualization works; the Android 16
  display-hosting rule blocks the bet.
- **Last updated:** 2026-10-14. Run on OnePlus CPH2619 (adb `2c0f6edc`), Android 16/SDK 36.
- **Next action:** the OWNER/manager decides the pivot (DECISIONS.md): MediaProjection-based
  design, a custom in-process rendering attempt, or stop. Do NOT start Phase 2 until decided.

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

## The blocker (why NO-GO)

A Play-installed app cannot host an activity on a VirtualDisplay it creates on Android 16:
`canHostTasks=false`, `launchDisplayId` is refused with SecurityException **even for our own
same-UID activity**, and no app-available display flag helps (PUBLIC needs MediaProjection/
CAPTURE_VIDEO_OUTPUT). Trusted-display / INTERNAL_SYSTEM_WINDOW are signature-only. Full
evidence and gate table in `docs/PHASE1_RESULT.md`.

## Open

| Item | Notes |
|---|---|
| Bets: Phases 1-4 | ARCHITECTURE.md section 6, questions Q1-Q6 |
