# Phase 12: to-do
OWNER: laptop window released 21:40; no more synthetic input from us (the 21:00-22:15 notice is no longer needed).
Done 16 of 17 | Failed or skipped 5 | Updated 21:40 | Now: close-out complete; final report and STATE.md (nothing committed)

PHONE CONNECTED 19:39: 2c0f6edc attached, 42 %, unlocked, no call, Wi-Fi OFF (do not enable). USB tethering allowed for a measurement then off; `svc power stayon usb` only while testing; pause below 20 %. Manager 19:40: finish doc edit, close emulator, start B1.
B1 baseline: install/run `p12/baseline-app-debug.apk` + `p12/baseline-udreceiver.exe`; measure every section-3 row (connect, typing USB + adb dev path, drag fps, Notepad++ start, VS Code open, old-PC proxy already done, governor, B-103). No Wi-Fi numbers today -> SKIPS row. OWNER notice 5+ min before keyboard/mouse runs.
OWNER 19:44: synthetic mouse/keyboard only inside our foreground receiver window, 19:50-20:20 (B1 baseline). No real desktop control outside it.
OWNER 20:01: please do not use the laptop 20:07-20:27 (synthetic keyboard/mouse inside our foreground receiver window only; B1 latency/drag).
B1 progress 20:01: connect 0.919 s, idle 0.62 % / 123.9 MB / 0 dropped, Notepad++ cold start 15.95 s. VS Code blocked: phone `files/phase3` has no code-server (`CS_START FAIL entry missing`); fetching the pinned aarch64 deb on the PC to push it.
OWNER 20:31: please do not use the laptop 20:37-20:57 (synthetic keyboard/mouse inside our foreground receiver window only; B1 AFTER / B2).
B1 BEFORE (baseline APK + baseline receiver, phone 2c0f6edc): connect 0.919 s visible (window 0.225 s); idle 1080p 0.62 % of one core, 123.9 MB WS, 0 dropped / 30 s; Notepad++ cold start 15.95 s; drag 33.5 fps avg / 55.9 max, 0 dropped; typing adb path p50 196.3 ms, p90 295.4 ms (n=29); typing USB-tether path p50 129.7 ms, p90 149.0 ms (n=25). Wi-Fi OFF by owner, no Wi-Fi number -> SKIPS row. VS Code BEFORE: code-server was absent in the baseline data; pinned aarch64 deb was installed after the baseline number was due, and B-106 keeps the app on the login page, so no honest BEFORE app-path number (BUGS B-106).
B1 AFTER (final arm64 APK 55,906,904 B + final receiver 2,219,008 B, phone): connect 0.944 s visible (0.260 s window); typing measured from receiver `LATSTAGE total` (baseline receiver had no LATSTAGE): default profile 0 p50 90.8 / p90 115.3 ms (n=25), fresh-receiver --no-vsync p0 p50 129.7 / p90 220.1 ms (n=25); profile 1 p50 95.9 ms, fresh --no-vsync 91.2 / 110.0 ms (n=25); profile 2 106.8 / 118.4 ms; profile 3 152.9 / 104.9 ms. Two 50-key runs lost most probes (n=6, n=10; keysAllTime advanced), likely probe coalescing under load: not used. No clean B2 win; profile 1 is best-behaved but within noise; one-change-at-a-time restart test still needed.
A5 note: installed final arm64 APK over baseline with data preserved (files/phase3 454 MB). Code-server now installed: 9,134 files, 326,507,659 B, entry present; login page reachable but B-106 blocks workbench.
Next: AFTER drag fps + Notepad++ start (default vs p12warm), B4 governor drop, B-103; then docs/gates/report. Phone battery 41 %, Wi-Fi still OFF, tethering OFF and PC default route back on Wi-Fi.
CLOSE-OUT 20:47 (superseded by the 21:36 round): AFTER drag 42.0 fps avg / 55.9 max, dropped 0 at 960x540 (thermal governor); NPP 28.23 s cold / 11.95 s warm (p12warm:wine; Wine prewarm 5.54 s); B4 governor real on phone (automatic thermal=3, 1920x1080 -> 960x540 in 0.2 s, desktop/windows kept, dropped 0); B-103 still OPEN on the final build (marker set, Ctrl+V reached NPP, no SelectionRequest, screenshot unchanged); B-106 still OPEN on phone (login page, `Failed login attempt`). Phone restored: stay_on=0, brightness=699, tethering off, no rndis, forwards removed, app/receiver stopped; battery 40 %. Android gate re-run BUILD SUCCESSFUL (tests up-to-date, earlier 184/0/3); git diff --check clean; gradle daemon to stop. Gate FAIL: typing 90.8 ms > 80, NPP 11.95 > 10, VS Code blocked B-106. Final report in the turn; STATE.md last.
B2/B3/B-106 UPDATE 21:36: B2 keep profile 0 (p50 90.8 / p90 115.3, n=25); fresh no-vsync p1 91.2, p3 104.9, p2 118.4; 50-key nv-p0 184.2 (n=10), nv-p1 238.9 (n=6) worse; stage `drawn>encoded` 46-67 ms dominates, next lever. B3 cold: baseline 18.46 s same thermal vs final 15.88 s settled; the old 28.23 s / repeat 26.44 s is app cold-start, not Wine; warm 11.95 s with prewarm (5.54 s), later cold-app run 20.61 s; <=10 still MISS. B-106 FIXED: auth none loopback only, workspace mkdir, VS Code cold 13.48 s to `.monaco-workbench` (CDP), target <=15 PASS. B-103 still OPEN; B-105 OPEN. B6 code-only investigation in PHASE12_RESULT section 7. B7 table updated.
CLEANUP 21:40: final no-hold APK 55,906,460 B installed; app force-stopped (0 processes, 0 services); receiver stopped; no adb forwards; stay_on=0; brightness=699; Wi-Fi OFF; tethering OFF; no rndis; battery 37 %, thermal 3; Gradle daemon stopped; `git diff --check` 0. Nothing committed.
A1 baseline: receiver 2,569,216 B, 128 MB WS, 0.47 % of one core idle 1080p, 0 dropped; window up 303 ms, first frame <= 1.31 s.
A2 DONE: profile at pc/Cargo.toml; 2,569,216 -> 2,217,472 B after A2, 2,219,008 B final (+1,536 B for B2 --no-vsync); abort/recover test passes.
A3 DONE: client 1536x960 -> 1920x1200; text lap_var 1285 -> 3465 (125 % panel); Start click at (40,1135) maps 1:1.
A5 DONE: arm64 APK 55,906,904 B vs universal baseline 93,158,519 B (-40.0 %); x86_64 49,708,307 B; universal 93,294,347 B kept.
A6 DONE (emulator 17:19): real 1920x1080 -> 960x540 rebuild, desktop + Settings window kept.
A4 DONE, B-104 FIXED: HW 2-core proxy 35.65 fps avg / 45.9 max, dropped 0, decode 21.29 ms, CPU 34.9 %, WS ~131 MB. SW (--soft) after fix 36.68 fps avg / 40.9 max, 1070/1070 presented, dropped 0, decode 16.75 ms, CPU 33.3 %, WS ~96 MB. Tests 78+1, clippy 0.
A7 SKIPPED: test page Join opens zoommtg://; meeting web client loaded without sign-in and asked about audio/video, no camera/mic UI in the box. SKIPS row written.
A8 DONE (emulator practice): p12lat:1 -> encoder/redraw/TCP_NODELAY on; emulator rejects the QTI slice key, new fallback keeps the base low-latency set, stream dropped=0.
B2 PREP DONE: p12lat profiles 0/1/2/3 (2 = 1-row slices + immediate redraw, 3 = second redraw at 8 ms) + receiver --no-vsync; unit tests; emulator profile 2 + no-vsync stream 37 frames/dropped 0/desktop visible.
B3 PREP DONE: p12warm:0|wine|code|1 starts Wine/code-server on Presentation; code-server warm 1719 ms on emulator; Wine prewarm impossible there (no Wine tree, ok=false ms=50). VS Code window opened but stayed on code-server login -> B-106.
B4/B5/B6/B7 BLOCKED with Part B: phone absent; B-103 still open, B-105 open.
Gates: Android 184 tests / 0 failures / 3 skipped, lint + assemble BUILD SUCCESSFUL; cargo test 78+1, clippy exit 0.
Docs: PHASE12_RESULT updated (B-104 fixed, final sizes, B2/B3 practice); BUGS B-104 FIXED, B-106 added; SKIPS stale A4 row removed, B3 emulator rows added.

PART A - NO PHONE (emulator + PC + code), about 3.5 hours
A1 [20 min] Baselines: receiver size/CPU/WS/start-to-first-frame; APK size/entries -> PHASE12_RESULT BEFORE.
A2 [20 min] B-050: release profile to workspace root; BEFORE/AFTER size; panic=abort lock recovery test.
A3 [40 min] B-096 DPI: per-monitor v2; mouse map, cursor, lock windows, Exit overlay; BEFORE/AFTER client px + sharpness.
A4 [20 min] Old-PC proxy: 2 cores, HW vs SW decode, CPU/dropped/decode ms at 1080p.
A5 [30 min] APK size: remove dead/debug content; BEFORE/AFTER; unit tests pass.
A6 [45 min] Quality profiles: pure choice function + tests; REAL LowerResolution (encoder+VirtualDisplay rebuild, desktop kept); emulator practice.
A7 [15 min] Zoom web client on emulator in our Browser with webcam shim; no sign-in, else SKIPS row.
A8 [30 min] B-051 prep: encoder/transport changes behind a switch; emulator practice.

PART B - PHONE (from "PHONE CONNECTED"), about 5.5 hours
B1 [45 min] Phone BEFORE for every section-3 row.
B2 [90 min] B-051 changes one at a time, BEFORE/AFTER; target 80 USB / 120 Wi-Fi.
B3 [45 min] Notepad++ <= 10 s, VS Code <= 15 s; BEFORE/AFTER.
B4 [30 min] Governor + profiles on the phone; real resolution drop; numbers.
B5 [30 min] B-103 paste into Notepad++; install round-4 build; trace; else update B-103.
B6 [20 min] 3D path: Vulkan/DXVK on Adreno 619 under Box64 (investigate only).
B7 [20 min] Final table in PHASE12_RESULT.md.

CLOSE-OUT
C1 Gates: Android test/lint/assemble; cargo build/test/clippy; git diff --check.
C2 Docs, cleanup, STATE.md last, final report.
