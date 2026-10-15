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
| H1 | **Session service** | Foreground service (type `connectedDevice`) plus a partial wakelock while a PC is connected. Guides the user to the OEM "allow background activity" setting. Watches the engines and restarts them if Android kills them. | design known (LESSONS.md) |
| H2 | **Link** | USB through Android Open Accessory (AOA), which needs no developer mode; USB tethering as fallback. Wi-Fi on the same network, with discovery. One framed protocol with separate channels: control, input, video, audio, clipboard, files. Pairing with a 6-digit code. | **[bet]** Phase 4 |
| H3 | **Compositor + encoder** | Draws the desktop frame with OpenGL ES from every window's surface, straight into the hardware encoder's input surface (no CPU copies). H.264 by default, HEVC optional. | Phase 5 |
| H4 | **Desktop shell** | Our own UI: taskbar, start menu, window frames (move, resize, close), notifications, Files, Settings. | Phase 6 |
| H5 | **Android apps (hidden)** | An app-virtualization engine runs apps installed on the phone inside our process. Each Android window gets its **own virtual display**, owned by us; that display's surface feeds the compositor. Input goes to our own windows. Launching the guest activity onto that display is refused by Android (Phase 1, path A: NO-GO). Round 2 tried public Presentation and SurfaceControlViewHost reparenting (path B) on the emulator; neither produced a guest frame. Phone path-B proof is deferred because the phone was unavailable. | **[bet]** Phase 1 |
| H6 | **Windows apps** | Wine on Box64 (FEXCore optional). An X11 server inside our app; each Windows/X11 top-level window becomes its own surface for the compositor (rootless). DXVK/VKD3D on a Vulkan driver for 3D, later. | **[bet]** Phase 2 |
| H7 | **Linux apps (x86-64)** | Box64 runs x86-64 Linux programs and their x86-64 libraries, through the same X11 server. | **[bet]** Phase 2 |
| H8 | **Full-speed tools** | Native ARM64 programs **shipped inside the APK** as `lib*.so` (allowed to run): Node.js + code-server (VS Code), Python, Git, a shell. Their UI shows in built-in browser windows (Android WebView). | **[bet]** Phase 3 |
| H9 | **App Center** | Catalog, search, install/uninstall/update, best-version picker, compatibility ratings, "Open any .exe". | Phase 8 |

## 3. The PC receiver (Windows 10/11)

| Id | Part | What it does |
|---|---|---|
| P1 | **Lock + Exit** | Full screen on its own Windows desktop; a low-level keyboard hook swallows Win, Alt+Tab, Alt+Esc and Ctrl+Esc; the taskbar is hidden. One **Exit** button restores everything. Crash-safe: anything changed is restored on the next start too. Modelled on Safe Exam Browser. |
| P2 | **Decode + present** | Hardware video decode (H.264 is supported by almost every PC GPU since about 2010), low-latency present. Software decode at a lower resolution as fallback. |
| P3 | **Input** | Captures mouse and keyboard and sends them at once on the input channel. |
| P4 | **Link + pairing** | USB (the AOA host side, with a driver installed once) and Wi-Fi; 6-digit pairing; trusted-PC list. |
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
```

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
| Q4 | Do Node.js + code-server run from `nativeLibraryDir`, and how big is the APK? | Phase 3 |
| Q5 | Can a Windows PC switch the phone into USB accessory (AOA) mode without a driver hassle? If not, USB tethering. | Phase 4 |
| Q6 | Protocol over Wi-Fi: QUIC or WebRTC? | Phase 4 |
| Q7 | Does one virtual display per Android window work, or does the engine need freeform windows? | Phase 7 |
| Q8 | GPU path for Windows apps on the Adreno 619 (Turnip, DXVK)? | Phase 12 |
