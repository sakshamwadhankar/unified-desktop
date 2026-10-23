# STATE: where the work stands

Rules: replace stale lines, never append history, never record a status that was not
verified this session. The coding agent updates this as its last action; the manager
corrects it after review.

## Now

- **RECOVERY AUDIT (2026-10-24 02:45, manager, pi):** every chat that broke was read and
  reconciled — **75 sessions** (6 Claude incl. 2 tester runs, 18 pi, 51 OpenCode): **21 broken**,
  18 noise, 33 clean, 2 running. Full ledger, crash taxonomy, contradictions and salvage list in
  new **`docs/RECOVERY_2026-10-24.md`**. Facts proved against files/git/build outputs, not
  summaries: HEAD still `aad9bbf`; tree **38** paths at 02:30 (24 modified + 11 untracked +
  `undefined/`); Android gate read from the **02:29** build outputs on this exact tree —
  **214 tests / 0 failures / 3 skipped**, lint **0 errors / 142 warnings**, 3 APKs built; PC gate
  **re-run by me at 02:40 — 101 passed / 0 failed** + `lock_crash` 1/0, clippy `-D warnings` exit
  0 (the "100" in older notes is stale: the tree gained the stale-frame-skip test). ELF check on
  today's APKs: every `lib*.so` `LOAD 0x4000` **except `arm64-v8a/libbox64.so` = `0x1000`**.
  **No phone and no emulator** (`adb devices` empty at 02:28 and 02:38). **OpenCode is dead until
  ~05:29** (free-limit 429, `retryAfterMs` 12,483 s at 02:01:58); the coder that is running is
  **pi `01a16a89`** (started 02:22, Phase 12 final prompt, code-only: `p12nppwarm` candidate,
  `skip_stale_frame` in `phase5.rs`/`view.rs`, both sides built). Nothing committed. Docs
  corrected today: `context.md` §4 (Phase 12 not closed; Phase 13 ~90 % unproven),
  `SKIPS.md` D12/D14 rows (code written, not skipped), `BUGS.md` manager note on B-115…B-121,
  `managercontext.md` §17 (superseded pointer + true tree count), four prompts marked superseded.
  Next: review the coder's candidate when it reports (no phone ⇒ no number, so it cannot close a
  gate), then Phase 13 Part 1 live proof, then the three Phase 12 numbers.

- **Chat recovery (2026-10-24 02:15, agent):** the previous pi manager session crashed in the pi
  compaction footer (`addUsageToTotals`, `usage.total`) right after writing
  `docs/PROMPT_CODER_PHASE12_FINAL_2026-10-24.md`; its last edit was truncated and never landed.
  Completed now: the prompt's two stale section references (acceptance row 3 -> §3, row 4 -> §4)
  are fixed, `managercontext.md` §17.3 no longer claims the 01:58 fix, and all prompt paths were
  checked. **No code changed and no build/gate was run this session.** `adb devices` is empty:
  no phone, no emulator. Next: hand the Phase 12 prompt to a fresh coder when the phone attaches.
- **Continuation (2026-10-23 22:30, agent):** gates on the uncommitted Phase 13 tree PASS: Android
  **214 tests / 0 failures / 3 skipped**, lint **0 errors**, assemble OK; PC **100 tests / 0** +
  lock_crash 1/0, clippy `-D warnings` clean. Fixed in the tree: the compile error from the
  crashed lane (`say` -> `P7Log.say`), tester **T-004** (predictive-back + legacy back on Settings),
  **T-005** (Files delete uses a ShellRoot modal, no `AlertDialog`/window token), **T-006** (Files
  rename/new-folder prompts, name rule `FileNames`, single-click selects a folder), **T-007**
  (2 s focus reclaim + synthetic Alt removed; focus once at window creation), **T-003** Dobby is
  now 16 KB aligned in both ABIs (`-Wl,-z,max-page-size=16384`); a box64 16 KB candidate was built
  in WSL and ELF-checked (`win86/vendor/libbox64-16kb.so`, LOAD 0x4000, same 22,390,072 B) but
  **is not shipped until Wine boots on the phone**. BUGS rows **B-117..B-121** added. Next: emulator
  live check (T-004/5/6/7 + 16 KB dialog gone on x86_64), then phone (box64 swap + Wine), then
  B-115/B-116 live proof and docs.
- **MANAGER AUDIT (2026-10-24 01:30):** verified against the live tree. The 22:25 Android
  evidence is on these exact sources: **214/0/3**, lint **0 errors**; PC re-run now **100/0** +
  `lock_crash` 1/0, clippy `-D warnings` **0**, `git diff --check` **0**. APK ELF check: every
  x86_64 `lib*.so` LOAD `0x4000`; arm64 still only `libbox64.so` `0x1000` (16 KB candidate built,
  **not shipped**). The Part 1 coder (`ses_e963…`) died at **00:10:33** on the free-model 429
  limit while chasing an out-of-scope receiver exit; it produced **no** Files/rename/Back/focus/
  16 KB evidence (`p13fix/` has receiver logs only). No phone and no emulator attached since.
  Verdict: **Phase 12 NOT complete** (gate FAILED: Test-shell 87.0 ms, Notepad++ 12.15 s, Wi-Fi
  typing unmeasured); **Phase 13 NOT complete** (code ~90 %, uncommitted; live proof + box64 swap
  + final phone round + docs close-out missing); **Phase 14 not started**. Nothing committed since
  `aad9bbf`. **Owner 01:48:** phone on the same Wi-Fi, USB being attached; Phase 12 first. Next:
  hand `docs/PROMPT_CODER_PHASE12_FINAL_2026-10-24.md`; Phase 13 Part 1 evidence
  (`docs/PROMPT_CODER_CONTINUE_2026-10-24.md`) still outstanding. Commit only after the evidence
  is verified. `adb devices` was still empty at 01:58.
- **Handover docs (2026-10-23 19:37, agent):** `context.md` (project snapshot) and
  `managercontext.md` (manager playbook/handover) are written and verified against the live
  tree, recovering the previous pi session that crashed after writing them. **No code changed,
  no build run this session.** The tree still carries the uncommitted B-115/B-116 +
  B-109/B-110/B-112/B-113 + D12/D14 work (17 files, +837/-98, **not compiled/tested**); last
  commit `aad9bbf`. Next: gates on that tree, live proof of B-115/B-116, then the tester
  blockers T-003 (16 KB), T-005, T-006, T-007, T-004. Details: `context.md` §5-6.
- **Phase 12 round 1 (2026-10-22 16:59-21:42): NOT PASSED (manager verdict 21:58).** Phone results:
  connect 0.94 s PASS; VS Code opens 13.48 s PASS (B-106); window drag 42 fps PASS; old-PC 2-core
  proxy ~36 fps, 0 dropped PASS (B-104 software decode fixed); governor's real 1080p -> 540p drop
  PASS; receiver 2.57 -> 2.22 MB (B-050); per-ABI APK 93 -> 55 MB; sharp picture at 125 % (B-096).
  **Misses:** typing USB p50 90.8 ms (target 80); Notepad++ 11.95 s warm / 15.88 s cold (target
  10); Wi-Fi typing not measured (the phone's Wi-Fi was off). **Security, FIX NOW: B-108** - the
  B-106 fix runs code-server with `auth: none` on loopback, reachable by any app on the phone
  (the manager's own instruction; D18 withdrawn). Next round: B-108 first, then the encoder stage
  (drawn>encoded 46-67 ms) for typing, Notepad++ start, the Wi-Fi measurement, B-103.
  Gates (manager re-run): Android 184/0/3 + lint + assemble; PC 78/0 + lock_crash 1/0, clippy clean.
- **Phase 12: GATE FAILED, close-out complete (2026-10-22 21:40, agent).** Working tree
  uncommitted; nothing was committed. Two honest misses: typing p50 **90.8 ms** (target 80)
  and Notepad++ **15.88 s cold / 11.95 s warm** (target 10). VS Code is FIXED at **13.48 s**
  (target 15). **A1:** receiver idle 0.38 % of one core / 127 MB / 0 dropped, window up 303 ms,
  first frame <= 1.31 s. **A2/B-050:** release profile at `pc/Cargo.toml`, receiver
  **2,569,216 -> 2,219,008 B (-13.6 %)**; abort/recover test proves the mutex and dirty journal
  survive `panic=abort`. **A3/B-096:** per-monitor DPI v2, client 1536x960 -> **1920x1200**,
  text-crop sharpness 1284.9 -> **3465.0**; physical Start click maps 1:1. **A5:** per-ABI
  splits; arm64 phone app **55,906,460 B** vs 93,158,519 B universal baseline (-40.0 %);
  universal 93,294,347 B and x86_64 49,708,307 B kept. **A4/B-104:** software decode fixed,
  hardware proxy **35.65 fps / dropped 0 / CPU 34.9 % of 2 cores / WS 131 MB**, software
  **36.68 fps / 1070 presented / dropped 0 / 33.3 % / ~96 MB**. **A6:** LowerResolution is real.
  **A8/B-051:** QTI slices, post-key redraw and phone `TCP_NODELAY` are behind `p12lat:1`,
  default OFF. **A7 Zoom SKIPPED** (no camera/mic UI in the box); **B6 investigated only**.
  **B1 phone `2c0f6edc` (final arm64 APK + final receiver):** connect **0.919 -> 0.944 s**;
  typing USB adb **196.3 -> 90.8 ms p50** (p90 295.4 -> 115.3), USB-tether dev path
  **129.7 -> 91.2 ms**; drag **33.5 fps at 1080p -> 42.0 fps at 960x540**, dropped 0; idle
  baseline 0.62 % / 123.9 MB / 0 dropped. Wi-Fi OFF by owner: no Wi-Fi number (SKIPS.md).
  **B2:** keep **profile 0** (best p50 90.8 / p90 115.3, n=25); fresh no-vsync p1 91.2,
  p3 104.9, p2 118.4; 50-key attempts worse (p0 184.2 n=10, p1 238.9 n=6). Stage
  `drawn>encoded` 46-67 ms dominates; next lever is canvas->encoder, not transport.
  **B3:** same-thermal baseline **18.46 s**, final settled **15.88 s** (no cold regression;
  the old 28.23 s / repeat 26.44 s is app cold-start contention, not Wine); warm **11.95 s**
  with `p12warm:wine` (prewarm 5.54 s), later cold-app prewarm 20.61 s; still >10 s.
  **B-106 FIXED:** code-server `auth: none` on loopback only, direct `/?folder=`, workspace
  `mkdirs()`; CDP `.monaco-workbench` **13.48 s cold**, `README.md` open; DECISIONS D18.
  **B4:** governor automatically went 1920x1080 -> 960x540 in 0.2 s at thermal=3, desktop and
  windows kept, PC dropped 0. **B5/B-103 OPEN** on the final build (Android marker set, Ctrl+V
  reached NPP, no SelectionRequest, screenshot unchanged). **B-105 OPEN** (stale Settings
  text). **B-107 NEW** (lock test flaked once under parallel load; clean repeat passed).
  Android gate **184/0/3**, lint **0 errors / 98 warnings**, assemble **BUILD SUCCESSFUL**;
  PC **78/0 + lock_crash 1/0**, clippy `-D warnings` exit 0; `git diff --check` 0. Evidence
  `C:\Users\Asus\AppData\Local\Temp\opencode\p12`; record `docs/PHASE12_RESULT.md`.
  Cleanup verified: final no-hold APK installed, app force-stopped (0 processes, 0 services),
  receiver stopped, no adb forwards, stay_on=0, brightness=699, Wi-Fi OFF, tethering OFF,
  no rndis, battery 37 %, thermal 3, Gradle daemon stopped.
- **Last updated:** 2026-10-24 02:45, manager (recovery audit).
- **Next action:** the pi coder `01a16a89` reports or stops on its "no phone" rule; review its
  `p12nppwarm` + `skip_stale_frame` candidates (keep/revert, no gate claim without a phone);
  then `docs/PROMPT_CODER_CONTINUE_2026-10-24.md` Part 1 live proof, then the three Phase 12
  numbers. Commit only after the proofs. Record: `docs/RECOVERY_2026-10-24.md`.
- **Phase 11 (2026-10-22 16:40): PASS with known limits.** Every PLAN gate part has
  phone proof (adb dev path; the day's Wi-Fi blocked phone<->laptop): phone sound plays on the PC
  (1000/1000 packets, 128 ms delay, B-101 fixed); copy/paste both ways (WebView/VS Code;
  Notepad++ -> phone); files both ways with SHA-256 plus the lock-desktop import; a desktop web
  app uses the PC webcam + mic (webcamtests.com 640x360 @ 11 fps, mic RMS, released after).
  **Known limits, FIX LATER (Phase 12 polish):** B-103 paste INTO Notepad++/Wine apps; the
  zoom.us web client not verified (the page stayed on loading); the deliberate webcam cover
  check was never caught (live feed already shown by the external page). Gates (manager re-run
  16:40): Android 169/0/3 + lint + assemble; PC 76/0, clippy clean; `git diff --check` 0.
- **Phase 11 round 4 (2026-10-22 16:20, dev path): partial; cleanup verified.** Current APK
  initially matched the installed SHA. The link and `udreceiver view --no-lock` ran on the adb
  forward; camtest was live and the owner sampler ran 16:01:30-16:02:30. Brightness stayed about
  0.436-0.509 with no near-zero cover interval, so the webcam-cover proof is skipped. The Zoom
  command reached the Browser harness and recorded `url=https://zoom.us/test loading
  https://zoom.us/test`, but no page result was captured. B-103 code was changed to use a real
  PropertyNotify server timestamp for CLIPBOARD ownership and to answer TARGETS with
  UTF8_STRING, STRING, TEXT and TARGETS; the rebuilt APK was not installed, so paste remains
  unproven and B-103 remains open. Android gate passed: `:app:testDebugUnitTest :app:lintDebug
  :app:assembleDebug` BUILD SUCCESSFUL in 8m11s. Clippy passed. PC cargo test was **75 passed,
  1 failed** because `lock::tests::recovery_refuses_while_a_live_session_owns_the_mutex` ran
  while the live receiver held the single-session mutex. Receiver, link, app and adb forward
  were then stopped; phone `stay_on_while_plugged_in=0`, brightness `699`, no app PID and no
  service record were verified. Evidence is in `C:\Users\Asus\AppData\Local\Temp\opencode\p11d`.
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
