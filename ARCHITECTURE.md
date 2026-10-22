# ARCHITECTURE: how it works inside

> **Status: PROVISIONAL.** Parts marked **[bet]** are unproven. PLAN.md phases 1-4 prove
> them on the real phone before product code depends on them.

## 1. Picture

```
 ANY PC (old is fine)                        PHONE (does all the work)
+--------------------------+   USB / Wi-Fi  +------------------------------------------+
| RECEIVER (Windows 10/11) |<---- video ----| OUR APP                                  |
|  P1 lock + Exit button   |<---- audio ----|  H1 session service (keeps it running)   |
|  P2 hardware decode      |---- input ---->|  H2 link: USB (AOA) + Wi-Fi              |
|  P3 mouse/keyboard send  |                |  H3 compositor + hardware encoder        |
|  P4 link + pairing       |                |  H4 desktop shell (taskbar, start, ...)  |
|  P5 audio out            |                |  H5 Android apps   - app virtualization  |
+--------------------------+                |  H6 Windows apps   - Wine + Box64        |
                                            |  H7 Linux apps     - Box64 (x86-64)      |
  The phone's own screen stays free:        |  H8 full-speed tools - bundled natively  |
  WhatsApp, calls, anything.                |  H9 App Center                           |
                                            +------------------------------------------+
```

## 2. The phone app (one Android app: Kotlin + native code)

| Id | Part | What it does | Status |
|---|---|---|---|
| H1 | **Session service** | Foreground service (type `connectedDevice`) plus a partial wakelock while a PC is connected. Guides the user to the OEM "allow background activity" setting. Watches the engines and restarts them if Android kills them. Phase 10 adds a service-owned `Phase10Runtime`, explicit session ownership, activity-free Presentation rebinds, `onTaskRemoved`/trim cleanup, and a phone-first governor. | Phase 10 implementation; long-run phone gates open |
| H2 | **Link** | USB through Android Open Accessory (AOA), which needs no developer mode; USB tethering as fallback. Wi-Fi on the same network, with discovery. One framed protocol with separate channels: control, input, video, audio, clipboard, files. Pairing with a 6-digit code. | **Phase 4 measured (phone):** Wi-Fi and USB tethering work end-to-end (fresh 6-digit pairing, trusted-PC silent reconnect; 8.7-11.5 MB/s, RTT p50 ~15-16 ms on Wi-Fi vs ~4 ms on the tether - `docs/PHASE4_RESULT.md` 9-11); AOA leg NOT PROVEN tonight (open, UsbDk route in section 7 + SKIPS.md "AOA round"). Discovery from scratch fixed: unbound multicast left via WARP - the PC now probes each local IPv4 as the socket source. |
| H3 | **Compositor + encoder** | Draws the desktop frame with OpenGL ES from every window's surface, straight into the hardware encoder's input surface (no CPU copies). H.264 by default, HEVC optional. **Built in Phase 5:** a private VirtualDisplay (1920x1080) renders into a SurfaceTexture; a GL thread draws it into the `MediaCodec` input surface, paced to 60 fps (a deferred draw takes the newest content), plus a forced keep-alive frame after 1 s of no change and on a keyframe request. Encoder `c2.qti.avc.encoder`, baseline, CBR 12 Mbps, no B-frames, keyframes on demand, `KEY_LOW_LATENCY` + Qualcomm `vendor.qti-ext-enc-low-latency.enable`. Feeding faster than the encoder drains cost ~200 ms (B-052). | Phase 5 |
| H4 | **Desktop shell** | Our own UI: taskbar, start menu, window frames (move, resize, close), notifications, Files, Settings. **Built in Phase 6** (`Phase6Shell.kt`/`Phase6Window.kt`/`Phase6Apps.kt`; emulator round verified, phone round open in SKIPS.md): `ShellRoot` is our own view hierarchy inside the private VirtualDisplay - taskbar + clock + Start menu (Files, Browser, Settings, Test), windows with 8-edge resize, min size, z-order, minimize/maximize/restore/close, real pointer input from the PC mapped through the receiver's letterbox, keyboard routed to the focused widget. Apps: Files (app-private `files/desktop`: open/up/new folder/rename/delete), Browser (WebView window; PC keys reach the address field through the IME route - B-039), Settings, Test (the Phase-5 desktop in a window). The window model is pure Kotlin with 18 unit tests. Emulator numbers (phone-side counters): idle keep-alive 1.0 fps, title-bar drag 30.6 fps, key stages recv->dispatch 0.14 ms / dispatch->drawn 8.4 ms / drawn->encoded 26.8 ms (medians). The PC window itself stays black on the *emulator's* stream (BUGS B-057; the phone's encoder honoured keyframe requests in Phase 5). | Phase 6 |
| H5 | **Android apps (hidden)** | An app-virtualization engine runs apps installed on the phone inside our process. Each Android window gets its **own virtual display**, owned by us; that display's surface feeds the compositor. Input goes to our own windows. Launching the guest activity onto that display is refused by Android (path A: NO-GO). Round 3 proved public path B1 (Presentation reparent after resume) and B2 (SurfaceControlViewHost) with Google Tasks on the real phone while the real screen showed something else. Typed-key routing, browser launch lookup, and stable guest-process measurements remain open. | **[bet]** Phase 1 |
| H6 | **Windows apps** | Wine on Box64 (FEXCore optional). An X11 server inside our app; each Windows/X11 top-level window becomes its own surface for the compositor (rootless). DXVK/VKD3D on a Vulkan driver for 3D, later. | **[bet]** Phase 2 |
| H7 | **Linux apps (x86-64)** | Box64 runs x86-64 Linux programs and their x86-64 libraries, through the same X11 server. | **[bet]** Phase 2 |
| H8 | **Full-speed tools** | Native ARM64 programs **shipped inside the APK** as `lib*.so` (allowed to run): Node.js + code-server (VS Code), Python, Git, a shell. Their UI shows in built-in browser windows (Android WebView). Phase 3 (emulator): the first tool chain is proven end-to-end - node runs from `nativeLibraryDir`, code-server serves VS Code to `Phase3CodeActivity` (127.0.0.1 + `auth: none`, loopback-only; B-037/B-038 fixes at start, B-106 changed the auth in Phase 12), the full gate flow (open folder, edit+save to disk, terminal command) runs from a WebView with injected taps/IME, APK 88.2 MiB, workbench-family ~330 MB PSS, 0.15%/core idle. Phone round open (SKIPS.md); Python/Git/tool packaging still [bet]. | **[bet]** Phase 3 |
| H9 | **App Center** | Catalog, search, install/uninstall/update, best-version picker, compatibility ratings, "Open any .exe". | Phase 8 |
| H10 | **PC camera + mic in web pages** | The shell Browser injects `camera-shim.js` into **every** page at document start by re-serving main-frame HTML from `WebViewClient.shouldInterceptRequest` (shim as the first `<head>` script plus a relaxed CSP - no androidx.webkit, no new dependency). The shim overrides `getUserMedia`/`enumerateDevices`, paints CH_CAM JPEG frames into one reused canvas `captureStream(15)` and CH_AUDIO s16 packets into a 48 kHz WebAudio destination, starts the stream on `getUserMedia`, and reports numbers through `UdBridge` to logcat. The PC side captures 1280x720 RGB32 (device-limited 10.0 fps), downscales to 640x360 JPEG in one pass, and sends the mic as 10 ms PCM. After a link reconnect the receiver has dropped the camera while the page still holds the stream, so the shim re-asserts `cam-on` when frames freeze (B-102). | Phase 11 (phone, dev path): external https://webcamtests.com sees 640x360 / 11 fps / "Testing was completed successfully" |

### H9 detail (Phase 8, implemented)

- **Catalog** `android/app/src/main/assets/app_catalog.json`: each app has `id`, `name`,
  `aliases`, an `open` target (`files`, `browser`, `settings`, `vscode`, `x11`, `android`) and
  one or more versions with `version`, `kind` (`builtin|android|web|linux|windows`), `rating`
  (`Works` / `Works, slow` / `Not yet`), `fetch` (URL, package or Play page), optional
  `sha256` and `source` (`winget`, `deb`, `shipped`, `installed`, ...). Fourteen apps ship:
  the eleven section-7 names plus GIMP, Firefox and xmessage.
- **Picker** `Catalog` (pure Kotlin, unit-tested): best rating first, then the D7 kind order
  builtin > android > web > linux > windows, then the highest version (numeric-aware). A
  `Not yet` version is never offered for install while any other version works; if every
  version is `Not yet`, `pick` still returns it so the window can show the reason, and
  `pickUsable` returns null. The rating says how the app runs on the phone, never whether an
  installer exists.
- **Window** `AppCenterView`: search box (name + aliases), rows with the picked kind and
  rating, detail with installer state and fetch, Install/Open/Uninstall and a path field for
  "Open any .exe". It is a normal `ShellRoot.App.APPCENTER` window, so the start menu and
  taskbar need no special cases.
- **Installers**: builtin = nothing to install; android = `EngineBridge.importInstalled` into
  the guest engine when the package is already on the phone; there is no headless Play
  installer. When the package is missing, both Open and Install route to the official Play
  page in Browser (`AndroidOpen.browserFallback`), never a guest window or a fabricated
  install (B-092); web
  = nothing to install, Open loads the URL in the Browser; windows = the pinned
  `microsoft/winget-pkgs` manifest URL is fetched, the x64 **WiX MSI** entry preferred, its
  catalog/PV SHA-256 checked, the MSI downloaded and verified, then `runInstaller` boots Wine
  from empty hives first (B-021/B-081) and runs `msiexec /i ... /qn` through the loader;
  Notepad++ 8.9.8.1 and 7-Zip 26.04 install exit 0. Wine maps `ProgramFiles64Folder` to
  `drive_c/windows/syswow64` for these packages, so `installedExe` accepts
  `Program Files`, `Program Files (x86)` and `windows/syswow64` before opening; linux `.deb`
  = pinned Debian URL + SHA-256, downloaded, verified and `data.tar` unpacked into the rootfs
  (xmessage, x11-utils 7.7+5). Installed records live in `files/appcenter/installed.json`
  as `id -> {version, productCode}` (plain string values still read as version-only);
  `Update` is shown when the catalog version is newer. Windows uninstall (B-086) reads the
  MSI `ProductCode` after install (`reg.exe query HKLM\...\Uninstall /s`), keeps the live
  wineserver (`ensureLivePrefix`; `boot()` drops the hives) and runs
  `msiexec /x {code} /qn`; a missing code is refused truthfully. "Open any .exe" is
  `Phase2Wine.openExe`: boot only when Xvfb/wineserver are down (B-084), then run the file as
  data through the loader.
- **Installed rows + launch**. `InstalledMenu.rows(installed, apps)` is the pure start-menu
  list (fixed rows first, then installed apps whose catalog entry exists); the start menu is
  rebuilt on install/import/uninstall. `OpenRoute.of(app, version)` resolves the one open
  target, and both the App Center and the start menu call `ShellRoot.openCatalogApp(id)`.
- **X11 titles (B-087/B-088)**. There is one X11 shell window; it is reused and renamed through
  `X11Title`/`ShellModel.rename`: `X11 desktop` when no app is named, otherwise
  `X11 desktop — <app>`. The frame title bar reads `win.title` (not the fixed app name).
  Before another catalog launch, `Phase2Wine.closeCatalogApps` closes the previous catalog X
  client (winemine/notepad++/7zFM/xeyes/xcalc/xmessage, matched per argv token), so an old
  window cannot linger under the new title; `openExe` and Windows uninstall use the same rule.
- **Window chrome (B-091)**. Every `ShellWindowView` paints an opaque body below its title bar;
  content views with transparent gaps therefore cannot let the maximized window behind show
  through a focused/raised window. `ShellModel.focus` still moves the window to the top of the
  z-order and `syncViews` mirrors that order with `bringChildToFront`.
- **Android guest windows (B-090/B-092)**. A shell Android window hosts one package.
  Uninstalling or re-importing a package closes the old window
  (`ShellRoot.closeGuestWindow`), and opening a different package closes the previous Android
  window, so the next Open builds a fresh `VirtualDisplay`/TextureView instead of reusing a
  dead surface. `ShellRoot.openCatalogApp` checks PackageManager first: a missing package
  opens the official Play page in Browser and reports the truth, so no black Android window is
  created (B-092).
- **Input path**: the App Center is driven by real PC mouse/keyboard events like every shell
  window; the harness adds `p6:appcenter:*` for scripted runs (search/select/install/run/
  uninstall/exe/summary). The view is the normal full-screen desktop view and always forwards
  the physical PC mouse and keyboard; `--x/--y/--w/--h` place it on another monitor, and with
  no such flags it opens full screen on the primary monitor.
- **Tests**: `CatalogPickerTest.section7Table` reads the shipped JSON with `CatalogLoader` and
  checks the 11 section-7 names (11/11); `InstallersTest` covers both real manifests, x64
  pick, SHA/version mismatch, update check and the tiny `.deb` ar/tar reader (7 tests);
  `Phase8CatalogProcessesTest` covers the catalog-process close rule, and
  `Phase8OpenTest` covers focus above a maximized window in addition to titles/routes,
  including a missing Android package routing to Browser/Play (not Android).


## 3. The PC receiver (Windows 10/11)

| Id | Part | What it does |
|---|---|---|
| P1 | **Lock + Exit** | Full screen on a **separate Windows desktop** (`CreateDesktopW("udreceiver-lock-<pid>")` on `WinSta0`), switched to with `SwitchDesktop`; the default desktop and its taskbar are never hidden or modified. A `WH_KEYBOARD_LL` hook, armed before the switch, swallows Win, Alt+Tab, Alt+Shift+Tab, Alt+Esc, Ctrl+Esc, Ctrl+Shift+Esc and Alt+F4 on both down and up (per-key atomic counters); the mouse is never swallowed. One topmost `WS_EX_NOACTIVATE` **Exit** overlay calls `Lock::request_exit()`; a watchdog force-exits at `--lock-max-seconds`. A single-session mutex/event (`Local\udreceiver-lock-single`) is acquired **before** crash recovery and held through lock creation, so a second `view` is refused before it connects or touches the journal. The line journal (`%LOCALAPPDATA%\unified-desktop-receiver\lock-state.txt`) records `clean`, `desktop`, `pid` and each policy; the parser is strict, `Journal::start_at` refuses a dirty/unreadable file, and recovery leaves unreadable journals untouched. `Lock::exit` verifies `input_desktop == Default` (with retries) before restoring/deleting the journal; if it cannot, the journal, watchdog and guard stay armed. `udreceiver lock-guard --parent <pid>`, started on the default desktop, waits for the PID, re-checks the mutex, and restores only that parent's journal/desktop (Windows leaves a dead process's private desktop as the input desktop — verified). Policies (Ctrl+Alt+Del options) apply only when elevated **and** `--policies` is passed; default off. Modelled on Safe Exam Browser. |
| P2 | **Decode + present** | Hardware video decode (H.264 is supported by almost every PC GPU since about 2010), low-latency present. Software decode at a lower resolution as fallback. **Built in Phase 5** (`udreceiver view`): the Microsoft H.264 MFT with an `IMFDXGIDeviceManager` on D3D11 (DXVA), `CODECAPI_AVLowLatencyMode`, NV12 D3D11 surfaces converted by `ID3D11VideoProcessor` into a flip-model swap chain (FLIP_DISCARD, max frame latency 1); `--soft` runs the same MFT in system memory. On a decode error or gap it asks for a keyframe and drops until one arrives. **B-085:** the decoder pads NV12 to 1920x1088 (1080 + 8 macroblock rows); the receiver crops to the visible 1920x1080 with `VideoProcessorSetStreamSourceRect` (hardware) or a visible-rect copy (soft), so no green band reaches the client. **Phase 12:** `SetProcessDpiAwarenessContext(PER_MONITOR_AWARE_V2)` runs before any window, so on the 125 % laptop the client is 1920x1200 physical pixels and input maps 1:1 (B-096 fixed; B-104 is open - the current software path drops every frame). |
| P3 | **Input** | Captures mouse and keyboard and sends them at once on the input channel. **Built in Phase 5:** window messages (mouse, wheel, keys with scan codes) scaled through the letterbox to desktop coordinates; release-all on focus loss and on exit. The phone dispatches them to its own views (no INJECT_EVENTS, no Accessibility); key/button events are never coalesced; the phone keeps held-key bookkeeping and releases everything on release-all or session end. `--x/--y/--w/--h` choose the display rect; no such flags open full screen on the primary monitor. |
| P4 | **Link + pairing** | USB (the AOA host side, with a driver installed once) and Wi-Fi; 6-digit pairing; trusted-PC list. Phase-4 measured (phone): Wi-Fi + USB tethering + pairing + silent reconnect proven with numbers (`docs/PHASE4_RESULT.md` 9-11); AOA host side NOT PROVEN tonight. Phase 10 keeps the phone desktop runtime alive across a link loss, refuses a different trusted PC while a desktop is live, and lets the receiver reconnect to the pinned address for 10 seconds. The receiver keeps rustls blocking reads, uses a separate cloned-socket watchdog, sends control/clock keep-alives inside the receive loop, and uses full-width local clock stamps. Windows detail: a USB tether can take the laptop's default route (RNDIS metric 25 beats Wi-Fi 30) - keep it on Wi-Fi with a one-time admin metric pin (9999, active store) or the route removal. |
| P5 | **Audio** | Plays the desktop's sound. |

Language: a native Windows app; decided in Phase 4 (Rust recommended, see TECH_STACK.md).

## 4. Data flow

```
app windows (Android / X11 / WebView)
  -> each window's surface
  -> compositor (GLES, only redraws what changed)
  -> hardware encoder (H.264, low-latency)
  -> link (USB or Wi-Fi)
  -> receiver: hardware decode -> full screen

PC mouse / keyboard
  -> receiver -> link (input channel, highest priority)
  -> shell: which window is under the pointer? which window has focus?
  -> that window (Android: its virtual display; X11: the X server; WebView: the view)

PC camera / microphone
  -> receiver (MediaFoundation + WASAPI) -> CH_CAM / CH_AUDIO
  -> shell WebView shim: canvas captureStream / WebAudio destination -> page getUserMedia

phone desktop sound (our uid only, AudioPlaybackCapture)
  -> CH_AUDIO 10 ms packets -> receiver jitter buffer (6-packet target)
  -> 100 ms WASAPI render buffer, the pump tops it up to 60 ms -> shared playback
```

Phase 10 session lifetime is owned by `Phase4LinkService`, not by a resumed Activity:
`Phase10Runtime` retains the desktop and its guest/window state, `Phase5Desktop` can detach and
rebind its surface, and a reconnect attaches a new link to the existing runtime. A partial
wakelock is held only while a live session exists. The governor polls Android memory/trim/
thermal signals only while a desktop runtime is live and applies the ordered phone-first policy
(hidden windows, 30 FPS, 15 FPS, lower resolution, then a PC warning). **Phase 12 made the
lower-resolution action real:** the governor prepares a second encoder at 960x540, resizes and
rebinds the VirtualDisplay, stops the old encoder, keeps the desktop and every window, and
scales incoming input from the 1920x1080 link space (`Phase5Session.governorLowerResolution`,
emulator proof in `docs/PHASE12_RESULT.md`). `Phase12Profiles.choose()` is the unit-tested
automatic profile choice (Light/Standard/Smooth); wiring it into session start is follow-up
work.

## 5. Constraints (non-negotiable)

- **C1 Play-compliant code loading.** targetSdk 36. Only `lib*.so` files shipped in the APK
  are executed. Downloaded programs run only as data through Box64/FEXCore; downloaded
  scripts only through an interpreter we ship.
- **C2 No developer mode, no root, no ADB** for the user.
- **C3 No VM.**
- **C4 No system-only permissions.** A Play app cannot launch activities (not even its own)
  onto a virtual display it created (LESSONS.md), so hidden Android apps must run inside our
  process and have their windows shown by us (H5).
- **C5 No Accessibility-service input.**
- **C6 Licenses recorded first** (THIRD_PARTY.md).
- **C7 The phone stays usable.** The desktop has a CPU/RAM budget and yields to what the user
  does on the phone (OPTIMIZATION.md).
- **C8 The PC lock always exits cleanly** with the Exit button, and recovers even after a
  crash.

## 6. Open questions

| # | Question | Answered in |
|---|---|---|
| Q1 | Which app-virtualization engine (license, real Android 16 behaviour)? Do its windows render on our virtual display and take injected input? | Phase 1 |
| Q2 | Which Wine + Box64 base (Winlator forks or upstream)? Is it targetSdk 36 with only `lib*.so` executables? | Phase 2 |
| Q3 | Does Wine's multi-process model fit inside Android's ~32 child-process limit? | Phase 2 |
| Q4 | Do Node.js + code-server run from `nativeLibraryDir`, and how big is the APK? | **Phase 3 (emulator) YES, with numbers in `docs/PHASE3_RESULT.md` item 9:** dlopen/require of the shipped native modules works from `nativeLibraryDir` (B-034 keeps the policy; note dlopen of app-storage copies is *not* OS-blocked on this image - exec is), code-server reaches `GET /healthz 200`, cold start 7.9 s / warm 2.8 s; APK 88.2 MiB (libnode 79.4+78.9 MiB per ABI, box64 21.4 MiB arm64-only), no data shipped - the 117 MB deb is fetched and sha-checked at runtime; phone verification still owed (2c0f6edc). |
| Q5 | Can a Windows PC switch the phone into USB accessory (AOA) mode without a driver hassle? If not, USB tethering. | **Phase 4: NOT PROVEN tonight.** The probe only covered the debugging-ON state and WinUSB-bound interfaces; 51/52/53 are endpoint-0 device requests, so any device handle suffices, and UsbDk (Apache-2.0, signed MSI, admin once) is an untested signed-detach route (`docs/PHASE4_RESULT.md` section 7 + SKIPS.md "AOA round"). Fallback proven: USB tethering (section 9) and Wi-Fi (section 6). |
| Q6 | Protocol over Wi-Fi: QUIC or WebRTC? | **Phase 4: neither** - a custom framed protocol over TCP + TLS 1.3 (rustls, one port 47600, mDNS `_udlink._tcp.local` discovery, numeric-comparison pairing), measured 8.7-11.5 MB/s / p50 ~15-16 ms on Wi-Fi and 4 ms on the tether (`docs/PHASE4_RESULT.md` 9-11). D16 keeps maintained crypto; QUIC/WebRTC can be revisited when video needs them. |
| Q7 | Does one virtual display per Android window work, or does the engine need freeform windows? | **Phase 7 round 3 (phone): one display per shell window works for RENDERING.** The window creates its own VirtualDisplay (``phase1-owned``) on its SurfaceView; the engine imports and launches the guest, and the Phase-1 B1 route moves the guest activity's decor into a Presentation on that display - Tasks' own UI appears in the shell window (``s10.png``, ``B1_PRESENTATION_SHOWN ... display=53 target=900x576``). Two gaps remain: (a) PC input must cross processes, solved tonight with a same-uid abstract LocalSocket between the shell window and the guest process (no INJECT_EVENTS, no Accessibility - D3/D5); (b) a guest that starts a SECOND activity is not presented (B-068), and a reopened window stays black (B-070), so per-activity presentation (or freeform windows) is still an open design point. |
| Q8 | GPU path for Windows apps on the Adreno 619 (Turnip, DXVK)? | **Phase 12: investigated, not implemented (not trivial).** The shipped Box64 v0.4.4 already wraps `libvulkan.so.1`/`libvulkan.so` and forwards entry points to the native Android loader (`src/wrapped/wrappedvulkan.c`, `src/library_list.h`), so the translator side exists. The missing pieces are on the Android/Wine side: the stock Adreno 619 driver's Vulkan level must be checked on the phone (DXVK 2.x needs Vulkan 1.3; older DXVK 1.10 needs 1.1); an app-local Turnip `libvulkan_freedreno.so` plus a loader/ICD shim would have to ship as `lib*.so` (not present today), and Wine's Vulkan WSI presents to an X11 surface while the Android driver only offers Android/headless surfaces. Winlator solves this with its own loader and display stack; we have neither. DXVK/VKD3D DLLs are not in the tree. Recorded as future work; no code change. |
