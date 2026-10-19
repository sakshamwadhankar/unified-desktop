# STATE: where the work stands

Rules: replace stale lines, never append history, never record a status that was not
verified this session. The coding agent updates this as its last action; the manager
corrects it after review.

## Now

- **Phase:** 6 of 13 **CLOSED, PASS on the phone** (manager review 2026-10-19 08:45), with
  input bugs to fix FIRST in the next prompt: B-059 (a `:` typed on the PC never arrived),
  B-060 (address bar prepends https:// and appends to the old URL), B-058 (a synthetic drag
  moved the window in x only), B-057 (PC black on the emulator stream). Residual: PC-typing
  latency samples in the shell (SKIPS). Drag 28.3 fps on the phone (PRD target 30: Phase 12).
  Phases 1-5 CLOSED, PASS (records: `docs/PHASE1_RESULT.md` ... `PHASE5_RESULT.md`).
- **Phase 6 (Desktop shell), phone (the real gate, label: phone):** install + OEM scan
  "Ignore"; START LINK; `view --addr auto` over Wi-Fi decoded on the PC (`presented==decoded`,
  `dropped=0`, decode 4.4-5.2 ms - **B-057 is emulator-only**); start menu opened by a PC-mouse
  click; overlapping windows; PC-keyboard-typed address bar - every capture from the **PC
  window's video rectangle only** (PNG magic checked): `phone-01`, `phone-02b`,
  `phone-03/03b/03c`. Numbers: title-bar drag **28.3 fps** (892 frames/31.5 s), idle **1.0 fps**
  (10 frames/10.02 s, no windows - closes the keep-alive-at-the-receiver carry-over), stuck
  keys **0** after 100 keys + 10 drags. Residual skip: typing latency samples (no
  `P5 LAT`/`LATSTAGE`). **B-058** logged (title-bar drag lost the y delta). Link stopped via
  the app 08:31:40, `svc power stayon false` restored; a missed call at ~08:29 was after all
  measurements and untouched. Record: `docs/PHASE6_RESULT.md` sections 1b + phone gates in 1.
- **Phase 6, emulator (earlier round, same tree):** shell + apps + input; 18 model tests;
  junit 100/0/3, lint + assemble green (final tree 07:38); cargo 41/41, clippy clean; idle
  1.0 fps, drag 30.6 fps; typing medians 0.14/8.4/26.8 ms; stuck keys 0. Emulator-only limits:
  **B-056** (mitigated in code), **B-057** (PC window black on the emulator stream; not
  reproduced on the phone).
- **Last updated:** 2026-10-19 08:35, agent (Phase 6 item 8 phone round). Teardown: receiver
  killed, link stopped via the app's STOP LINK, `svc power stayon false`; phone untouched
  otherwise (evidence PNGs + `viewphone.log` kept in the temp p6 dir).
- **Next action:** manager review of Phase 6 (B-056/B-057/B-058 decisions; the typing-sample
  residual). Earlier open owner items: the AOA round and the 5-step no-developer-mode check
  (SKIPS).
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
| Phone round (Phase 6 item 8) | **RUN 2026-10-19 08:02-08:31, PASS** except the typing-latency samples (SKIPS.md residual: no `P5 LAT`/`LATSTAGE` during PC typing - needs the probe path explained). Evidence: `docs/PHASE6_RESULT.md` section 1b + the phone PNGs, all captured from the PC window's video rectangle. |
| B-056 / B-057 / B-058 (Phase 6) | Open in BUGS.md awaiting the manager's decision: B-056 emulator encoder configure rejection (mitigated in code); B-057 PC black window on the **emulator** stream only (FIX NOW per the manager; the phone round did NOT reproduce it); B-058 title-bar drag lost the y delta on the phone (verify with one real-mouse drag). |
