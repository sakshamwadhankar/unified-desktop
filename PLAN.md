# PLAN: the phases

**One phase = one prompt.** The manager writes one prompt per phase. Each phase ends with a
**gate**: numbers measured on the real phone. The next phase starts when the gate passes, or
when the manager records in DECISIONS.md why the plan changes. Leftovers go into SKIPS.md and
the next prompt.

Order rule: **riskiest first.** Phases 1-4 are **bets**. If a bet fails, the manager and the
owner decide the new path before going on.

| # | Phase | Gate (on the phone, with numbers) |
|---|---|---|
| 1 | Hidden Android app **[bet]** | A Play-installed app renders on our own virtual display and reacts to injected input, while the phone's screen shows something else |
| 2 | Windows + Linux apps **[bet]** | Notepad++ (Windows) and an x86-64 Linux GUI app run in our targetSdk 36 app; start time, CPU, RAM and process count measured |
| 3 | Full-speed tools **[bet]** | VS Code (code-server on bundled Node.js) opens in a WebView, edits and saves a file; a terminal works; APK size measured |
| 4 | Connection **[bet]** | PC receiver skeleton; USB (AOA, or tethering) and Wi-Fi links both work with 6-digit pairing and no developer mode; throughput and latency measured for both |
| 5 | Video + input | The phone's desktop surface shows full screen on the PC through hardware encode/decode; mouse and keyboard work; fps and end-to-end latency measured |
| 6 | Desktop shell | Taskbar, start menu, movable/resizable windows, Files, built-in browser, all usable from the PC |
| 7 | All apps in one desktop | Android, Windows, Linux and VS Code windows side by side, each controllable |
| 8 | App Center | Install 10 test apps by name; the best-version picker chooses as expected; "Open any .exe" works |
| 9 | PC lock + Exit | Full-screen lock blocks Win, Alt+Tab, Alt+Esc, Ctrl+Esc and the taskbar on Windows 10 and 11; Exit restores the PC instantly; crash recovery works |
| 10 | Phone stays free, keep-alive, continuity | A 2-hour session keeps executing; WhatsApp and a call work meanwhile; reconnect resumes where it was in <= 5 s |
| 11 | Audio, clipboard, files, PC webcam/mic | Sound on the PC; copy/paste both ways; files between PC and desktop; PC webcam and mic usable by desktop apps |
| 12 | Speed and lightness | PRD section 4 targets met on the test phone and an old PC, with a before/after table for every change |
| 13 | Google Play readiness | Policy declarations done; internal-testing build accepted by Google Play (the owner uploads) |

---

## Phase details

### Phase 1: Hidden Android app [bet]
- **Build:** the Android project (Kotlin, targetSdk 36). Choose an app-virtualization engine:
  compare 2-3 BlackBox-family forks on license, recent activity and Android 16 support;
  record the choice in THIRD_PARTY.md and DECISIONS.md. Create a virtual display our app
  owns. Run an app that is already installed from Google Play inside the engine and launch
  its activity on our virtual display. Try a simple app and a browser.
- **Prove:** frames saved as PNG show the app; the phone's real screen shows something else;
  an injected tap and key change the app (before/after PNG); no Accessibility service.
- **Also:** a short written check of Google Play policy for this approach (precedent: app
  cloners on Play).
- **If it fails:** report exactly where (class loading, hidden API, binder, permissions). A
  clear NO-GO with its cause is a valid result.

### Phase 2: Windows + Linux apps [bet]
- **Build:** pick a base (Winlator Bionic, Ludashi, Bannerlator, or upstream Box64 + Wine).
  Many Winlator builds target SDK 28 so they can execute from storage; ours must be
  targetSdk 36 with Box64 shipped as `lib*.so`. An X11 server inside our app.
- **Prove:** Notepad++ (Windows) and one small x86-64 Linux GUI app render (PNG); measure start
  time, CPU%, RAM, and the number of processes against Android's ~32 limit.

### Phase 3: Full-speed tools [bet]
- **Build:** Node.js (built for Android) and code-server shipped as `lib*.so`; a shell; Git and
  Python if time allows. VS Code's screen in a WebView. Extensions from Open VSX.
- **Prove:** VS Code opens a folder, edits and saves a file, its terminal runs a command;
  measure start time, RAM and APK size. Check every license (Git is GPL: DECISIONS entry).

### Phase 4: Connection [bet]
- **Build:** the PC receiver skeleton (Windows 10/11; language chosen with a DECISIONS entry,
  Rust recommended). USB through Android Open Accessory, with USB tethering as fallback.
  Wi-Fi on the same network with automatic discovery. One framed protocol with separate
  channels. 6-digit pairing.
- **Prove:** both links work with no developer mode at any step; throughput and round-trip
  latency for each; the answer to "which is faster", with numbers.

### Phase 5: Video + input
- **Build:** compositor drawing into the hardware encoder's input surface; low-latency H.264;
  hardware decode and immediate present on the PC; input back on its own channel; keyframe on
  demand; keep-alive frames.
- **Prove:** fps while something moves; end-to-end typing latency over USB and Wi-Fi
  (method in OPTIMIZATION.md); no stuck keys after fast typing and dragging.

### Phase 6: Desktop shell
- **Build:** taskbar, start menu, window manager (move, resize, close, focus, z-order), Files,
  built-in browser (WebView), Settings.
- **Prove:** all of it usable from the PC's mouse and keyboard; measured frame rate while
  dragging.

### Phase 7: All apps in one desktop
- **Build:** plug the engines into the shell: Android windows (one virtual display each),
  X11 windows (rootless), WebView windows (VS Code).
- **Prove:** four kinds of windows side by side; focus and typing go to the right one.

### Phase 8: App Center
- **Build:** catalog from built-in tools, Google Play (Android), winget manifests (Windows,
  official URL + SHA-256), and x86-64 Linux packages; the best-version picker; compatibility
  ratings; install, uninstall, update; "Open any .exe".
- **Prove:** 10 test apps installed by name (including VS Code and Zoom); the picker's choice
  matches the expected answer written down beforehand.

### Phase 9: PC lock + Exit
- **Build:** the lock state machine (ALGORITHMS.md section 13), Exit button, crash recovery.
- **Prove:** on Windows 10 and Windows 11: the blocked keys stay blocked; Exit restores the
  PC; killing the receiver and starting it again restores the PC too.

### Phase 10: Phone stays free, keep-alive, continuity
- **Build:** session service, OEM background-setting onboarding, watchdog, resource governor,
  reconnect without restarting apps.
- **Prove:** 2 hours on charger with CPU time advancing throughout; WhatsApp use and a phone
  call during the session; pull the cable, reconnect, everything is still there, <= 5 s.

### Phase 11: Audio, clipboard, files, PC webcam/mic
- **Prove:** desktop sound plays on the PC; copy/paste both ways; drag a file from the PC into
  the desktop and back; a desktop app uses the PC's webcam and mic (Zoom test).

### Phase 12: Speed and lightness
- **Prove:** PRD section 4 targets on the test phone and on an old PC, with a before/after
  table for every change; quality profiles (OPTIMIZATION.md) tuned; 3D path for Windows apps
  if the GPU allows.

### Phase 13: Google Play readiness
- **Build:** permissions declarations and their justifications, data-safety form, store
  listing draft (no name until the owner picks one), signed release build.
- **Prove:** an internal-testing build accepted by Google Play. The owner uploads it with
  their developer account.
