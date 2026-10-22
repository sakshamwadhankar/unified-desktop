# STATE: where the work stands

Rules: replace stale lines, never append history, never record a status that was not
verified this session. The coding agent updates this as its last action; the manager
corrects it after review.

## Now

- **Phase 11 round 2 (2026-10-22 12:20, dev path): the manager's 10:27 list is answered;
  two partials.** Item 3(a) DONE: the camera shim is injected into **every** shell Browser page
  at document start (`WebViewClient.shouldInterceptRequest` re-serves main-frame HTML; no
  androidx.webkit, no new dependency). External https://webcamtests.com with its own "Test my
  cam": 640x360, Quality Rating 8, **11 FPS**, "Testing was completed successfully" (numbers
  from the session record - the phone logcat rotated before a durable copy; the app process was
  gone at 12:0x). 3(b) DONE: the PC sends exactly **10.0 fps** - the UVC device is the cap
  (1280x720 RGB32, USB2); the phone fix (one reused Image, brightness every 5th frame) took
  ud:camtest **6-9 -> 10-11 fps**. 3(c): brightness numbers only (baseline 0.51-0.85; real dip
  0.525 -> 0.285 at 11:36:46, jump to 0.869 at 11:55:28); six OWNER windows, **no deliberate
  5 s cover** -> SKIP. Item 4: X11->Android verified (`x11->android len=133 sha=8d80920a`);
  Android->X11 unverified (Wine issued no SelectionRequest) -> SKIP. Item 5 phone-verified:
  `LOCK engaged switch_ms=19, first_frame_after_engage_ms=413`, import SHA
  `1c59b867...5a1afe38` == PC, Exit `switch_ms=21 total_ms=110`. **B-102** found + fixed +
  verified: after a link reconnect the shim re-asserts `cam-on` when frames freeze (kill/restart
  the view -> camera returns alone). **B-101**: cause measured (packets arrive in bursts of ~5
  per 50 ms; a 30 ms target drained empty and the 60 ms age window hid the age). Fix: target 6,
  one underrun per gap, age window -50..500 ms, device buffer logged. PC-only 10 s tone:
  `packets=1000 played=1000 late=0 dropped=0 underruns=1 depth_end=0` (1 = priming), loopback
  0.00057/0.00060/0.00056. Real-path re-measure + click delay **BLOCKED** (phone
  password-locked; consent needs the unlock) -> SKIP row. Gates: Android **169/0/3** + lint +
  assemble `BUILD SUCCESSFUL` (48 s); PC **75/0**; clippy clean; `git diff --check` 0.
  Cleanup 12:18: app force-stopped (0 processes), forward removed, `stay_on=0`, brightness 699,
  view stopped, no Xvfb/Wine/NPP, no commit.
- **Phase 10: COMPLETE on the phone (manager verdict 2026-10-22 08:25).** The last gate, the
  owner's real call, passed at 08:20 (1 min 37 s; same app PID, CPU advancing, frames kept
  flowing, dropped=0; `p10/call-test-manager.txt`). Round 2 on the real Wi-Fi link (no adb forward),
  phone `2c0f6edc`, evidence in `C:\Users\Asus\AppData\Local\Temp\opencode\p10`:
  2-hour charger soak PASS (25 samples / 121 min, the same app PID throughout, CPU advancing
  24/24, LMK 0, battery 47 -> 57 %, max 38.2 C); WhatsApp 60 s in front PASS; three real Wi-Fi
  drops PASS 3/3 (Wi-Fi connected -> picture 3.7 / 2.8 / 2.8 s; the same windows, guest PIDs
  and Notepad++ text); a different PC refused PASS; ColorOS background setting + confirmation
  persisted PASS; B-049 and B-072 proven (one view 105 min continuous, 0 decrypt errors).
  Also fixed and phone-verified this round: ONE MOUSE (the `--no-input` mode and the phone
  virtual pointer removed, owner request), B-095 (one normal PC arrow, the phone pointer hidden
  while a PC is connected), B-094 (Windows-app uninstall after a cold Wine restart: Notepad++
  and 7-Zip exit 0). **Phase 9 Exit after soak PASS:** the actual Exit click returned to
  `Default` in **657 ms** and the named-event path in **473 ms** after the B-098 fix; B-098
  is fixed (Phase 11). **Open:** the governor's
  lower-resolution step is only requested, not applied (FIX LATER, Phase 12); B-097
  (window rect after reconnect) not reproduced in the re-run. **Manager verdict: the governor's resolution drop is a Phase 12 item, not a Phase 10 gate**; multi-monitor is SKIP (one display, 1536x960). Gates
  (manager re-run 02:15): Android 155 tests / 0 failures / 3 skipped, lint + assemble OK;
  PC 73/0 after the B-098 fix, clippy clean; `git diff --check` 0. Manager cleanup: our app
  force-stopped (0 processes, 0 services); phone stay_on 0, brightness 699, Wi-Fi on. A
  concurrent Phase 11 `view --no-lock` test started at 08:00:17; the phone app was live
  again at 08:02 and was left untouched.
- **Phase 9 (PC lock + Exit): PASS on Windows 11 2026-10-21 05:10 (review rework + manager gate).** The lock
  is a private `udreceiver-lock-<pid>` desktop on `WinSta0` (default desktop and taskbar
  never hidden or modified), `WH_KEYBOARD_LL` blocks 8/8 combinations on down and up
  (counters = 2, focus stayed on the video window), and a topmost no-activate **Exit**
  overlay calls one exit path. Watchdog, `--lock-max-seconds`, `--freeze-video-after`, named
  `lock-exit`, strict PID journal, and a PID-aware `lock-guard`. **Review fixes:** the
  single-session mutex/event is taken **before** recovery and held through lock creation, so
  a second `view` is refused before connect (42 ms) and `lock-recover` refuses a live lock
  (exit 1, journal unchanged). Recovery/start fail closed: a dirty journal plus a denied
  restore made `view` exit in 52 ms with the journal byte-identical and no lock;
  `lock-selftest` refused; `Journal::start_at` never overwrites a dirty/unparseable journal.
  `Lock::exit` verifies `Default` before cleanup; on failure the journal/watchdog/guard stay
  armed. A clean journal on a leftover lock desktop returns to Default (75 ms); an
  unparseable one does too (59 ms) without changing its bytes. **Phone:** normal Exit 291 ms
  to process exit (switch 21, lock path 206); key/frozen click commands 984/641 ms (lock
  paths 211/100); phone gone 1134 ms (lock 76); guard 12 ms (426 ms wall); 8/8 keys;
  selftest PASS 8/8. If the guard is killed too, the next `view` recovers (T17: guard and
  lock killed, next `view --no-lock` recovered the dirty journal, returned Default and
  removed it; 6 s phone view). Locked: 1.2 % of one core, WS 133.9 MB, 1.0 fps,
  decode 4.3-5.4 ms (pre-review sample). **Lock is now the default** (`--no-lock` opts out,
  guard still held). Gates: Android **149 tests / 0 failures /
  3 skipped**, lint+assemble **BUILD SUCCESSFUL**; PC **55/0 release**, clippy clean,
  `git diff --check` exit 0. **B-093 fixed (phone)**; **Windows 10 SKIP** (no hardware;
  SKIPS.md row); policies **not-admin** (HKCU round-trip unit test only); multi-monitor
  code-only. Record `docs/PHASE9_RESULT.md`; evidence `p9t4`...`p9t11`, `p9t17` (manager),
  `p9b093` (agent review logs removed). **Manager commit: Phase 8 + Phase 9, pushed to
  origin/main.**
- **Phase 7: PASS on the phone (2026-10-20 09:00).** B-073 (input uid), B-077 (X11 key
  release on reconnect/close) and B-078 (VS Code Explorer click + Ctrl/Ctrl+P + typing) are
  fixed and proven on 2c0f6edc: `B1_INPUT realUid=10476 myUid=10010`; forced X11 reconnect ->
  exactly `npp8npp8npp8`; `phase7phone.txt` saved with `phone-gate` + `code8`. Record:
  `docs/PHASE7_RESULT.md` Round 5.
- **Round 2 (2026-10-19 16:30 hard stop):**
  Items 1-2 done (+ item 9 not needed): **B-061 FIXED and proven on the phone** (idle app -> 0 DaemonService
  records; headless Tasks launch -> DaemonService + neutral notification `Android app engine /
  A hidden app is running`; process-name guard so guest `:pN`/`:black` never call `stopService`;
  old "BlackBox Core" channel name persists, channels are create-once). Item 6 (X11 window)
  **partial**: a shell window ("Notepad++ + xeyes (X11)") shows the Xvfb screen on the PC with
  Notepad++ (Windows/Wine) and xeyes (Linux) both visible (`pc-final.png`); the pointer path
  reaches the X server, but PC keys never reached Notepad++ (`length: 0 lines: 1` after typing
  `x11ok`/`hello-x11`; B-062) and with no window manager xeyes cannot be placed (B-063; `runbin`
  ignores arguments, B-064). Item 7 (VS Code) **not done**: code-server healthy (`pid=24379
  port=44093 healthz=200`), URL typed into the shell Browser window over the PC link, page never
  rendered (B-065). Item 5 (Tasks window) **not built** - guests run in `:pN` where the engine's
  lifecycle callbacks never fire, so there is no in-shell route yet. Items 3-4 deferred by the
  manager (SKIPS row "Phase 7 deferred input bugs"; B-057's PC half is written and tested -
  cargo 42/42 incl. `stream_change_is_handled_in_place`). The link ran over USB
  (`adb forward tcp:47600`; the phone's Wi-Fi is client-isolated: ping fails, mDNS silent).
  Gates: Android `BUILD SUCCESSFUL in 1m 11s` / `GRADLE_EXIT=0`; `cargo test --release`
  42 passed/0 failed; `cargo clippy --all-targets` exit 0. Record: `docs/PHASE7_RESULT.md`.
  Phases 1-6 CLOSED, PASS (records `docs/PHASE1_RESULT.md` ... `PHASE6_RESULT.md`).
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
- **Last updated:** 2026-10-22 12:20, agent (Phase 11 round 2: items 3(a)(b) done, 4 partial,
  5 done; B-102 fixed + verified; B-101 fix written and PC-tested; two SKIP rows for the locked
  phone and the X11 paste; phone cleaned).
- **Next action:** manager review of B-102, then the B-101 real-path re-measure when the phone is
  unlocked (10 s tone + click delay) and the Phase 11 gate decision. Record:
  `docs/PHASE11_RESULT.md` round 2.
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
| Phase 9 (2026-10-21) | **PASS (review rework + manager gate).** Private desktop lock + Exit, 8/8 blocked keys, strict PID journal + PID-aware guard, session mutex before recovery, fail-closed start/recovery, verified Default exit. Phone: second view refused 42 ms; lock-recover refused while locked; Exit 291 ms; frozen/key click 641/984 ms (lock paths 100/211); phone gone 1134 ms (lock 76); guard 12 ms/426 wall; 8/8; selftest PASS; clean/unreadable leftover desktop 75/59 ms; T17 next view after guard+lock kill recovered the dirty journal and removed it. Gates: 149 Android/0/3 skipped, 55 PC/0 release, clippy 0, diff check 0. Skips: Windows 10 (no hardware), policies (not-admin), multi-monitor (code-only). Record: `docs/PHASE9_RESULT.md`. |
| Phase 8 (2026-10-20) | **Round 6 complete; gate PASS on the phone with fresh 10/10.** Catalog 14, picker 11/11; B-083/B-085..B-092 fixed and phone-verified; Zoom's missing package routes to the official Browser Play page (B-092). Fresh content 10/10 (VS Code, Tasks, Zoom Play, Excalidraw, Photopea, xeyes, xcalc, Notepad++, 7-Zip, Minesweeper). Gates at the time: 148 Android tests / 0 failures / 3 skipped, lint+assemble green; 45 PC tests / 0; clippy exit 0. B-093 was fixed in Phase 9 (phone: `X11 desktop — winemine`). Evidence: `docs/PHASE8_RESULT.md` Round 6, `p8r6_*`. |
| Phase 7 round 4 (2026-10-20) | **PARTIAL - gate not met.** B-070/B-071/B-073/B-074/B-075/B-076 fixed + proven; gate picture `p8/gate_npp.png`; `npp7` typed; `code7` not typed (B-078, Explorer/Quick Open). Record: `docs/PHASE7_RESULT.md` "Round 4". |
| B-056 / B-057 / B-058 (Phase 6) | Open in BUGS.md awaiting the manager's decision: B-056 emulator encoder configure rejection (mitigated in code); B-057 PC black window on the **emulator** stream only (FIX NOW per the manager; the phone round did NOT reproduce it; its PC half is now written + tested - cargo 42/42); B-058 title-bar drag lost the y delta on the phone (a real SendInput drag this round moved nothing at all - still open). |
| B-062..B-065 (Phase 7) | Mostly closed by round 4: PC keys reach Notepad++ (`npp7` landed, B-062), xeyes is placed by `place` (B-063), the harness handles multi-part commands (B-064), the VS Code window loads code-server (B-065). Open from Part A: B-077 (X11 flood after a lost key-up) and B-078 (VS Code Explorer/Ctrl+P from the PC). |
