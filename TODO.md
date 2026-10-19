# Phase 6: to-do
Done 10 of 11 | Failed or skipped: item 8 (phone round - phone never appeared; SKIPS.md row) | Updated 07:40 | Now: item 11 done; report written; STATE.md last; phone watcher still polling until ~08:45. HARD STOP 08:50.

 1. [x] 06:09 start: `adb devices` empty (phone unplugged, so no phone round); RAM 1.437 GB free at
    06:08 -> code first, `wsl --shutdown` freed ~0; RAM 3.67 GB at 06:13 -> emulator started ONCE
    06:13 (`emulator -avd Pixel_8_Pro -memory 3072 -no-boot-anim -no-snapshot-load`), booted and
    `adb devices` shows `emulator-5554 device` at 06:17. Temp dir C:\Users\Asus\AppData\Local\Temp\opencode\p6 (+ gb.sh, the Gradle-via-Git-Bash helper).
 2. [x] 06:18 licenses: checked THIRD_PARTY.md against the Phase-6 code - the shell uses only the
    Android SDK (`View`/`ViewGroup`, `WebView`, `java.io.File`, `Paint`), i.e. the existing
    "Android SDK / build tools / emulator" row. NO new component, no new row needed.
 3. [x] 06:16 B-047 video-on both sides + tests: phone starts video only on the PC's `video-on`
    (`Phase5Session.enableVideo`, gate-guarded); `pair`/`bench` never send it. `Phase5Protocol.parseControl`
    + `Phase5ProtocolTest` 16/16 (was 14), `phase5::VideoOnPacer` + 3 tests, cargo test --release 41/41.
 4. [x] 07:09 Shell core: `Phase6ShellModel.kt` (pure window manager: open/focus/raise/minimize/maximize/
    restore/close/drag/resize/clamp/z-order) + `Phase6ShellModelTest.kt` **18 tests, 0 failures**
    (fresh run 06:19:56; `:app:testDebugUnitTest` BUILD SUCCESSFUL, 82 tests total). Views
    `Phase6Shell.kt` (root + taskbar + clock + start menu + z-order + input), `Phase6Window.kt`
    (title bar, minimize/maximize/close, 8 resize edges, min size, key routing, wheel), `Phase6Views.kt`.
    Live on the emulator: `p6:shell` windows=4 focused=4, start menu opens/closes, 300-move title-bar
    drag moves the window (`drags=300`, shot 01+02).
 5. [x] 07:09 Apps + input + B-039: Files (real `files/desktop` dir; `p6:newfolder` created "New folder",
    listing `/desktop [New folder]`, shot 04), Browser (address typed on the PC keyboard, auto-https,
    loaded `https://example.com/` - shot 03), Settings (text line), Test (Phase-5 desktop hosted in a
    window, flash area visible). Input: real mouse MotionEvents (SOURCE_MOUSE) from PC window messages
    (`buttonsAllTime`, clicks/drags), keyboard to the focused widget (`keysAllTime=29 keyAtFocused=58
    keyFlashes=29`), Enter navigates. **B-039 probe: `p6:probe:harness` -> `route=edittext-ime`,
    `p6:keys` -> `pageKeys=(pending)`** = PC keys reach the browser through the address EditText's IME
    route, the WebView DOM keydown listener gets nothing from the link.
 6. [x] 07:09 CORE GATE (emulator): link up (START LINK -> NSD -> `view --addr 127.0.0.1:47600` over
    `adb forward`), 4 screenshots captured **app-side from the live ShellRoot view hierarchy** and pulled
    with `run-as`, each PNG starts `89 50 4E 47`: `01-startmenu-1792373789635.png` 64,396 B (start menu
    over 4 windows), `02-windows-1792373791063.png` 40,187 B (overlapping Files/Settings/Test/Browser),
    `03-browser-1792373722669.png` 65,396 B (URL typed from the PC keyboard, page loaded),
    `04-files-1792373759531.png` 65,552 B (Files with the created "New folder"). LIMIT, stated plainly:
    the PC window stayed black - the PC decoder drops every frame of the emulator's stream (BUGS B-056),
    so the pixels are the desktop surface as composed on the phone, not the PC window; the input in
    these shots came from the PC keyboard/mouse over the link (counters above).

 7. [x] 07:14 Measurements (emulator via `adb forward`; the PC decoder still drops every frame -
     B-057 - so the receiver VSTAT shows `presented=0`, and the fps numbers come from the phone
     encoder's `P5 VSTAT sent=` counter, which is stated at each row): **idle with no animating
     window = 1.0 fps** (0 windows, 10 frames/10 s; re-confirmed 07:18, and 21/21.1 s earlier -
     the keep-alive >=1/s holds); idle Files only = 2.3 fps (23/10 s); idle Files+Browser with
     `https://example.com` loaded = 1.0 fps (10/10 s) - so the earlier 53.9 fps "idle" figure was
     the Test window's Phase-5 animated test desktop, by design: with the Test window open the
     same probe reads 55.6 fps (556/10 s). Not a bug; corrected here. **Drag (title bar, Files,
     600 moves) = 30.6 fps** over 30.4 s (929 frames). Typing from the PC into the focused Test
     window: 20 keys -> 19 `P5 LAT` samples (one key coalesced into the previous frame), medians
     recv->dispatch **0.14 ms**, dispatch->drawn **8.4 ms**, drawn->encoded **26.8 ms** (p90
     2.1 / 15.4 / 41.7; the PC leg is not measurable - no decoded frame). Stuck keys after
     100 keys + 10 drags: **keysDown=0 buttonsDown=0** (keysAllTime 49->149, keyFlashes 49->149,
     buttonsAllTime 6->16).
 8. [ ] Phone round (the real gate): SKIPPED - the phone `2c0f6edc` never appeared in
     `adb devices` (polled 06:09 -> 08:4x, every 30 s at the end via a background watcher;
     unplugged at the start). SKIPS.md row added with the exact checklist to run. Every
     check has an emulator stand-in, labelled `emulator via adb forward` (items 5-7 +
     `docs/PHASE6_RESULT.md`); none of them closes this item.
 9. [x] 07:23 Build gates, all green: **gradle** `./gradlew :app:testDebugUnitTest :app:lintDebug
     :app:assembleDebug` -> `BUILD SUCCESSFUL in 50s`, `GRADLE_EXIT=0` (07:16; junit aggregates
     testDebugUnitTest: 100 tests, 0 failures, 3 skipped); **cargo** build --release exit 0;
     `cargo test --release` **41 passed, 0 failed** (first test run died at link with `exit code:
     1104` because the just-killed `udreceiver.exe` still held the file - clean re-run at 07:22);
     `cargo clippy --release --all-targets` exit 0.
10. [x] 07:34 Docs: `docs/PHASE6_RESULT.md` written (gate table incl. the PC-window BLACK limit, numbers
     with methods and the 53.9-fps correction, self-found fixes, B-056/B-057, unknowns; every row labelled
     emulator). `ARCHITECTURE.md` H4 updated to the built shell. `BUGS.md` gained **B-056** (emulator
     encoder `BAD_VALUE`, mitigated) and **B-057** (PC black window: permanent drop while awaiting a
     keyframe the emulator never sends). `SKIPS.md`: the Phase-5 carry-over row updated (keep-alive
     emulator-proven phone-side, receiver-side open; B-039 probe run -> IME route) + the item-8 phone
     round row added. Code refs fixed: `Phase5Encoder.kt` comment B-055->B-056, `Phase6Dispatch.kt`
     comment B-056->B-057.
11. [x] 07:40 Cleanup + report. Phone side: intermediate shots deleted; kept the 4 gate PNGs +
     `filesfinal-1792375311960.png` + `filesfix2-1792375185695.png` in `filesDir` (evidence) and the
     app's `desktop/` store (it holds the "New folder" created by the Files test). PC side: temp p6
     dir pruned to the scripts, the gate logs (`gate-gradle{,2,3}.log`, `gate-cargo{,-test}.log`),
     the gate PNG copies, view11.log (B-057 evidence) and the typing table; `udreceiver` killed,
     `adb forward` removed, emulator shut down (`emu kill`, qemu gone, `adb devices` empty - the
     laptop is back to the 06:09 state). Final gate re-run on the final tree (07:38, comment-only
     edits since 07:32): `BUILD SUCCESSFUL in 16s`, exit 0, junit 100/0/3. STATE.md updated last.
     Report: the end-of-task table in the session reply.
