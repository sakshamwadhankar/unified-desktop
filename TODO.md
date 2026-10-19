# Phase 7 round 3: to-do
Done 4 of 6 | Failed or skipped 3 | Updated 03:07 | Now: item 5 (gates)

 1. [x] 00:43 Start: `adb devices` -> 2c0f6edc; battery 12 %, charging, 39.3 C, mCallState=0;
    laptop FreePhysicalMemory 2.9 GB (no emulator needed). APK `install -r` Success (app uid
    u0_a476; B-010: same code/native libs as round 2, tree clean at 3b34d63). OEM VirusDialog
    ("Ignore"/"Fix now") covered the app: found via uiautomator, tapped Ignore at (312,2190)
    (mCallState=0 checked), dialog gone, HarnessActivity focused. Link started through the app
    (`phase4x start`): `START ok port=47600`, `SERVER listening`, FGS connectedDevice.
    Wi-Fi works today: `udreceiver view --addr auto` discovered `unified-desktop._udlink._tcp`
    at 192.168.1.16:47600 (no adb forward needed for the phone link; the forward stays as a
    fallback). First session had `P5 video DISABLED: no resumed Activity` while the dialog
    covered us (B-048 again); after the dialog was gone the session streams: VSTAT 1.0 fps
    idle, presented==decoded, dropped=0, decode 3.7-4.9 ms, d3d11=true.
    First PC capture of the video rectangle (rows 56..1143 of the 1920x1200 client,
    `p7c/setup1.png`): DPI-160 shell - normal-size taskbar "Start", clock 00:42:19.
    **Deviation:** the Win32 window of the receiver still reports a 1536x960 client to a
    DPI-unaware process; the capture tool sets `SetProcessDPIAware()` (per-monitor yes). [15 min]
 2. [x] 01:26 B-066 DPI half DONE on the phone: rebuilt+installed (Git Bash gradle), logs `shell
    density=1.0 dpi=160` / `BROWSER density ctx=1.0` / `WEBVIEW attached density=1.0 dpi=160`;
    live JS probe 01:05 returns `devicePixelRatio=1` (`[1,1920,881,"native-edit-context"]`);
    PC captures `p7c/b066-dpi160.png` (00:55) and `p7c/i2-now.png` (01:10) show the VS Code UI at
    1:1 (Explorer/statusbar small text) vs round 2's 3x `p7b/ph-vscode-max1.png`. Manager's 00:59
    note was about `menu1.png` (00:50, pre-fix). [43 min]
 2b. [ ] NOT done in item 2: typing "phase7phone" into the VS Code EDITOR, Ctrl+S, file on disk
    (run-as cat still "phone-gate"). Two attempts: 00:56 (chars did not land in the editor; DOM
    focus stayed on a monaco list) and 01:19 (click hit a bare desktop because the session had
    reset). Carried into item 4's gate, which needs "code7" in VS Code anyway. [evidence: p7c/i2-
    type.png, i2-now.png, p4.log]
 2c. [x] New facts found during item 2 (logged, not fixed - AGENTS 4): (a) **B-067**: the PC
    receiver view dies alone after 10-20 min (`VIEW FAILED: read: Overlapped I/O ... os error
    997`; `p7c/view-r3.err` ~00:53 and `view-r3b.err` ~01:14); (b) when it dies the phone tears
    the session down - all shell windows close and counters reset (`p6:shell` -> `windows=0`).
 3. [x] 02:28 ITEM 3 DONE (phone). The Android window renders Google Tasks' own UI inside our shell
    window: `p7c/s10.png` (Welcome to Tasks + Sign in, window "Android app"), logs
    `P7AND display id=53 900x576 surface=true` / `P7AND launch pathB1 ok=true` /
    `B1_PRESENTATION_SHOWN guest=com.google.android.apps.tasks.ui.TaskListsActivity display=53
    target=900x576 size=900x576`. Touch works: click on Sign in reached the guest
    (`02:05:02.229 P7AND touch x=508 y=465 -> guest 508.0,465.0 handled=true`). Two fixes were
    needed in the tree: (a) the presentation lives in the GUEST process, so the host's static
    `dispatchGuestTouch` always saw `liveContent=null` - added a same-uid abstract LocalSocket
    bridge (`B1_INPUT listening dev.unifieddesktop.guestinput`, one connection per event);
    (b) the earlier click logged `handled=false` (`p7c/p7.log` 01:57:00). Reopening after the
    black window needs an app restart: the shell X button did not close the window in this build
    (clicks counted, `windows=` stayed 1). Typing into Tasks is NOT testable tonight (Tasks
    requires a signed-in account). New rows: B-068 (second activity -> black window), B-069
    (mDNS auto-discovery race). [~35 min]
 3a. [ ] NOT done: Android window input beyond one click (no key target without an account).
 3b. [ ] NOT done: closing a shell window with its X button did not work in three attempts
    (`buttonsAllTime` 6->7, `windows=1`); worked around by restarting the app. Needs a BUGS row
    if it repeats (candidate B-070, not logged yet - verify next round).
 4. [x] 03:07 GATE (phone) PARTIAL, honest result: all four kinds were OPEN at once (`p7c/s17.png`,
    `s19.png` taskbar: Test, Notepad++ + xeyes, VS Code, Android app) but never in one
    side-by-side screenshot, because (a) the Android window went black on reopen (B-070), (b)
    the NPP window's X11 frame loop died at 02:14:30 before Xvfb existed (`ECONNREFUSED :6000`;
    the Xvfb root capture `p7c/x11cap2.png` proves Notepad++ + xeyes DO render inside the
    phone's X server), (c) the VS Code window stayed blank: code-server was only started at
    02:45, after the window was created (`cs-start`, old `code-server.out` log), and (d) title-bar
    drags stopped moving windows in this session (B-071). Per-kind evidence: Android = `s10.png`
    (02:11) + `B1_PRESENTATION_SHOWN`; Windows = `x11cap2.png` + round 2 `ph-x11-12.png`;
    Linux/xeyes = same two; VS Code = round 2 `i2-now.png` (1:1 on the phone). Typing: Notepad++
    `npp7` NOT run tonight (no live NPP window); VS Code `code7` NOT proven on the phone (no
    loaded workbench) - emulator proof stands from round 2. Numbers (phone): 55-61 fps over 47 s
    of continuous dragging, dropped=0, decode 4.2-5.0 ms, d3d11=true; PSS 197 MB; 12 processes;
    app CPU 126% (~1.6 of 8 cores), phone 351-388% busy of 800%; battery 38.2 C at t0 and t1
    (45 s apart), level 22%. [~40 min]
 5. [x] 02:55 Build gates (code-only): `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
    -> BUILD SUCCESSFUL in 37s (101 actionable tasks, 16 executed); `cargo build --release` ok;
    `cargo test --release` -> 44 passed, 0 failed; `cargo clippy --all-targets` -> no code
    warnings (only the pre-existing "profiles for the non root package" workspace note). [5 min]
 6. [x] 03:23 Docs + cleanup done: `docs/PHASE7_RESULT.md` "Round 3" (gate row honest: all four
    kinds open together, no single side-by-side shot), ARCHITECTURE Q7 answered (one display per
    window works for rendering; per-activity presentation still open), BUGS B-066 half/B-067
    (61 min 40 s, no 997) + new rows B-068..B-072, SKIPS 3 rows, STATE.md updated. Cleanup:
    view stopped (it had died at 03:11:35), link stopped, Wine/Xvfb/code-server/guest stopped,
    `am force-stop`, **0 of our processes**, **no DaemonService**, stayon=0, brightness=2047,
    adb forwards removed, gradle daemon stopped. [~12 min]
