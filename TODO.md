# Phase 7 round 2: to-do
Done 6 of 10 | Failed or skipped 1 | Updated 21:12 | Now: close-out (items 8-10)

 1. [x] 17:58 Start: `adb devices` -> none; laptop FreePhysicalMemory 1.84 GB (< 3 GB) so code
    first. 17:59 the manager reported 3.98 GB and said start the emulator once: started
    `emulator.exe -avd Pixel_8_Pro -memory 3072 -no-boot-anim -no-snapshot-load` in the
    background; booted by 18:06 (`sys.boot_completed=1`, x86_64, SDK 37). APK installed
    (`adb -s emulator-5554 install -r`, Success). Link started through the app's own service
    (`phase4x start`): `START ok port=47600`, `SERVER listening port=47600`, video `VSTAT sent=`
    advancing; `adb forward tcp:47600`; `udreceiver view --addr 127.0.0.1:47600` running.
 2. [x] 18:24 B-057 on the emulator: the PC window now shows the emulator's shell - NOT black.
    `VSTAT fps=1.0 presented=140 decoded=140 dropped=0 seq=140 d3d11=true` (idle desktop).
    **Correction (manager 18:14):** `e2-b057.png` shows ONLY the green wallpaper and the cursor -
    no taskbar, no clock. My first line claimed otherwise; that was wrong. Cause: this laptop
    runs at 125%, and the PowerShell driver was DPI-unaware, so it saw the receiver window as
    1536x960 while its real client is 1920x1200 and `CopyFromScreen` grabbed only the top-left
    1536x960 physical pixels - cutting the right edge and the bottom 120 px (the taskbar).
    With `SetProcessDPIAware()` the driver sees `client=1920x1200`, the 1920x1088 video at
    y=56, and `e2-b057-proof.png` (1920x1080, the video rectangle only) shows the open start
    menu (Programs/Files/Browser/Settings/Test/Notepad++ + xeyes (X11)/VS Code), the Files
    window and the taskbar with Start, Files and the clock 18:24:43. That is the B-057 proof.
 3. [x] 18:28 Input bugs, code + unit tests + emulator device halves:
    B-060 `setSelectAllOnFocus(true)` + `BrowserUrl.normalize` only on Go (test `Phase6BrowserUrlTest`);
    B-059 pure `Phase5Protocol.keyCodeToChar` used by the X11 window, tested through VK -> keycode
    -> char (`shiftedPunctuationVkToChar`, all shifted punctuation + digits); B-064(a) Phase2Dispatch
    splits only the action name; B-064(b) letterbox math extracted to `phase5::scale_client_to_desktop`
    + Rust test `letterbox_maps_video_pixels_to_the_desktop`; B-063 X11 `ConfigureWindow` placement
    and B-062 per-hop key logging written (device proof needs the phone's X11 apps).
    **Real device results (emulator via adb forward):** one real SendInput drag of the Files title bar
    from desktop (300,80) to (500,230) moved the window (60,48,900,620) -> (260,198,900,620), exactly
    +200/+150, no y loss (`drags=16`); one real SendInput click at desktop (75,1048) opened the start
    menu (`startMenu=1`, `e2-click-start.png` shows the menu and the cursor on Start). Root cause of
    the round-1 click miss: the PC test driver was DPI-unaware on this 125% laptop - it saw a 1536x960
    window and clicked screenshots at 1/1.25 scale; `scaled()` itself was right. The receiver is
    unchanged (its `view` window is 1920x1200 physical and shows the 1920x1088 video 1:1).
    Unit tests: `:app:testDebugUnitTest` BUILD SUCCESSFUL.

 4. [x] 19:40 VS Code window (B-065 + B-039) on the emulator:
    - code-server 4.141.0 x86_64 deb: `CSDL ok bytes=117273956`, `CS_UNPACK ms=19433`, `CS_VERIFY
      files=9134 bytes=326506636 symlinks=31 broken=0`, entry present, version 4.141.0;
      `CS_START OK port=41379 healthz=200`.
    - `p6:vscode` logs in with a real POST to `/login`, then loads
      `?folder=/data/user/0/dev.unifieddesktop.app/files/phase3/workspace`; the window shows the
      workbench (Explorer with note.txt + phase7, Welcome tab, status bar) - `e4-reopen2.png`.
      The window is still the BrowserView chrome (address bar) plus the WebView.
    - **B-039 route that works: the IME route.** VS Code 1.141's editor is an EditContext, not a
      textarea: synthetic keydowns run page shortcuts (Ctrl+P opened Quick Open, New File dialogs
      took the typed name) but never insert into Monaco. `KeyWebView` now sends printable
      characters through Chromium's own `super.onCreateInputConnection(...)` with `commitText`
      and keeps Ctrl/Alt/special keys as real key events. Logs: `B039 route=ime ch='p' ... ic=dp2`.
    - One real click on the Explorer row opened `phase7`; a second real click **into the editor**
      gave its EditContext focus. Before that second click `super.onCreateInputConnection` returns
      null (`B039 ime refused ... ic=null`); after it, all six chars are accepted.
    - Typed `phase7` from the PC keyboard: editor showed `phase7` at Ln 1, Col 7
      (`e4-phase7-typed2.png`), Ctrl+S, and
      `run-as cat files/phase3/workspace/phase7` printed `phase7` (6 bytes, 19:39)
      (`e4-phase7-saved.png`).
    - Honest note: the earlier `note.txt` "hello" was written with `run-as` on disk, **not**
      typed from the PC. The first PC-typed "phase7" went into the Chat box (`e4-typed.png`);
      the New File / Create File dialogs were then driven from the PC keyboard
      (`e4-newfile-typed.png`, `e4-file-created.png`) and created the empty `phase7` on disk.
 5. [-] 20:06 Android app window (guest app) - **BLOCKED, the Phase-1 NO-GO is still true**.
    Emulator attempt on today's build: `am start ... --es guest com.google.android.contacts
    --ei caps 6` created display id=7, imported the app, requested the launch on display 7
    (`Phase1` log 20:03:01); the guest process runs under our uid (u0_a233,
    `com.google.android.contacts`) but every capture says `ERROR: no frame yet`, and `dumpsys`
    shows the guest task and its windows at `mDisplayId=0` (the real screen), not display 7.
    That is the documented blocker: an app-made display is untrusted, so the activity cannot
    launch there; Phase 1 B1 decor reparenting failed (`DecorView ... not attached to window
    manager`) and the engine lifecycle callbacks never fire in the guest `:pN` process, so no
    in-shell route exists. Needs engine work (hook in :pN + host view/input forwarding) - a
    design change, out of this item's 60 min. Stopped at 20:06 to leave time for the gates and
    docs; SKIPS row added. **Phase-1 evidence:** STATE "The blocker", B-026-era T2/T3.
 6. [x] 20:00 X11 key path + placement: B-062 per-hop logging (`P7X11 key code=... sym=...
    handled=...`), B-063 `placeWindows()` (QueryTree/InternAtom/GetProperty8/ConfigureWindow by
    WM_CLASS) called on X11 connect; the char->keysym mapping is now the pure
    `X11Keys.keysym(keyCode, meta)` and `Phase7X11KeysTest` covers a/A, Shift+1 -> '!', ':' '?'
    '=', Enter 0xFF0D, BackSpace 0xFF08, Delete 0xFFFF, Tab, Escape, arrows and unknown keys.
    `:app:testDebugUnitTest` BUILD SUCCESSFUL (113 tests). The server-keymap half (keysym ->
    X keycode + Shift, XTEST) and Notepad++/xeyes typing need the phone (item 7; SKIPS row).
 7. [x] 21:15 PHONE ROUND on 2c0f6edc (round 2). Method: `adb install -r` (Success), `am start`,
    `phase4x start` + `adb forward tcp:47600` + `pc\target\release\udreceiver.exe view`, then
    `phase2x xvfb|wineboot|npp|xeyes` (always xvfb -> wineboot -> npp; NPP alone dies, B-026
    signature) and `p6:open:x11`.
    (a) **B-062 FIXED and proven on the phone.** Two root causes found and fixed: X11 socket I/O
      ran on the main thread (`NetworkOnMainThreadException`, B-062) - moved to one
      `p7-x11-input` worker (`submit`); the frame loop and the input worker shared one X11 socket
      and desynced (`GetImage failed type=34 code=0`, then `SocketTimeoutException`) - all
      requests now serialized under one `xio` lock. After the fix: real PC typing of `phase7npp`
      reached Notepad++ (`ph-x11-12.png`, status `length: 10`, 20:33) and the log shows one
      `P7X11 key code=... queued=true` per character, no `frame failed`. xeyes placed by B-063
      `placeWindows()` (`P7X11 place npp=6 xeyes=1`; both visible in `ph-x11-6.png`).
    (b) Android app window built (item 5's plan): `AndroidWindowContent` - the guest's private
      VirtualDisplay (960x540, OWN_CONTENT_ONLY) renders into a SurfaceView in a shell window;
      new `p6:open:android[ <pkg>]` + start-menu entry; guest default `com.google.android.apps.tasks`.
      Phone log: `P7AND display id=41 ... gms ok=true import ok=true launch ok=true`. **But the
      guest's own window lands at `mDisplayId=0`** (`dumpsys window windows`: TaskListsActivity
      `mDisplayId=0 taskId=24996`, `Surface: shown=true`), so the window stays black - the
      ColorOS/engine refuses the untrusted display for the guest activity, the Phase-1 NO-GO.
      Host->guest input has no route (keys/clicks log `P7AND ... dropped: no host->guest input
      path`). Both halves: SKIPS rows.
    (c) GATE (phone): all three window kinds together in `ph-gate-5.png` (20:50, X11 with
      Notepad++ + xeyes / Android app window / VS Code window loading)
      and `ph-gate-8.png` (21:05). NPP typed `phase7npp` (`ph-x11-12.png`); a later stray
      injection also typed `PHONE7GATES` into it (`ph-vscode-type4.png` - honest note: that text
      landed in NPP, not VS Code). VS Code: `phase3x cs-start` -> `CS_START OK port=42047
      healthz=200`, `p6:vscode` logged in and the workbench loaded
      (`ph-vscode-max1.png`: Explorer with `phase7phone.txt`, Welcome tab, Chat);
      the PC keys reached the workbench **Chat box** (`phone7`) but **not the Monaco editor**
      on the phone - the WebView renders ~3x too large (B-066), the file opens
      (`ph-vscode-file2.png`, `phase7phone.txt` shown at Ln 2, Col 1) but clicks do not move the
      editor caret and IME text is not inserted. Emulator round 4 already proved editor typing
      (`e4-phase7-typed2.png`, file saved). Typing verdict: Notepad++ yes, VS Code no (chat box
      only), Android no.
    (d) Numbers (21:09, phone): PC viewer `VSTAT fps=10.0 presented=15600 decoded=15600
      dropped=0 rx=424 kbit/s decode=4.32 ms d3d11=true`; phone encoder `VSTAT sent=15608
      frames=17416 dropped=1808 windows=3 focused=3 keysAllTime=257 buttonsAllTime=113
      drags=48`; app `TOTAL PSS 216701 KB / RSS 359766 KB`; battery 20 %, temp 37.9 C;
      momentary `top`: app 110 % CPU (encoder + compositor + 3 guests), system_server 106 %,
      surfaceflinger 20 %, Xvfb 3.3 %. [70 min]
 8. [ ] Build gates: :app:testDebugUnitTest :app:lintDebug :app:assembleDebug (absolute -p
    path), cargo build --release, cargo test --release, cargo clippy --all-targets. [15 min]
 9. [ ] Docs: docs/PHASE7_RESULT.md round 2, ARCHITECTURE (H4/H5/H6/H8, Q7), BUGS, SKIPS,
    LESSONS. [20 min]
10. [ ] Cleanup and report: stop view/links/guests/Xvfb/Wine/code-server, am force-stop on
    every device, svc power stayon false, stop the emulator, gradle --stop, STATE.md LAST.
    [20 min]
