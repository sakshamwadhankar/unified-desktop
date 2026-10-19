# Phase 7 result (2026-10-19, hard stop 16:30)

Scope this round (manager notes 15:32 + 15:49): finish item 2 (B-061), then (a) the X11
window first (Xvfb screen in one shell window, xeyes AND Notepad++ in it, XTEST
pointer+keys), (b) the VS Code window (code-server in a shell window, B-039 route),
(c) the Tasks window only if (a)+(b) were done before 16:00; ONE PC-window screenshot at
~16:05 with the window kinds together, type a word into every text-capable window; gates
16:05-16:30. Everything below is **phone** (2c0f6edc, CPH2619, Android 16, 480 dpi)
unless a row says otherwise.

| Item | What was done | Files | Test | Verified on | Numbers |
|---|---|---|---|---|---|
| 1 Start checks / provisioning | adb device, battery, RAM (< 3 GB -> no emulator), stayon, tmp dir; the phone re-provisioned itself (`fetchall`, `cs-fetch`) after the install | TODO.md item 1 | - | phone | see TODO item 1 |
| 2 B-061 (FIX NOW) | Engine daemon off at app start; started only by an engine app launch/import, stopped with the last explicit close, idle stop at app create; neutral notification text; process-name guard so guest `:pN`/`:black` never call stopService | `EngineDaemon.kt`, `EngineApplication.kt`, `GuestPresentationHost.java`, `EngineBridge.kt`, vendor `DaemonService.java` + `fetch-source.sh` | `dumpsys activity services` before/after | **phone** | idle app -> 0 DaemonService records; headless Tasks launch -> DaemonService up, notification `title=Android app engine`, `text=A hidden app is running`; old "BlackBox Core" channel name persists (channels are create-once) |
| 6 X11 window (a) | New `X11WindowContent` shell window ("Notepad++ + xeyes (X11)"): Xvfb screen (1280x720) pulled by `X11.getImage()` on a worker (~8 fps), PC mouse -> XTEST motion/button, PC keys -> unicode -> keysym -> XTEST; `open:x11` in `p6:`; XTEST `motion`/`button`/`fakeKey` added to `Phase2X11` | `Phase7Windows.kt`, `Phase2X11.kt`, `Phase6Shell.kt`, `Phase6Window.kt`, `Phase6Dispatch.kt` | build + live PC session | **phone** (partial) | Notepad++ (Windows/Wine) and xeyes (Linux) both render in the window on the PC (`pc-final.png`, `x11now.png`); XTEST **keys did not reach Notepad++**: `length: 0 lines: 1` after typing "x11ok"/"hello-x11" from the PC (fresh capture `files/phase2/x11now`, 19,532 B) - BUGS B-062 |
| 7 VS Code window (b) | code-server started and its URL typed into a shell Browser window over the PC link | - | healthz | **phone** (not done) | `CS_START pid=24379 port=44093 healthz=200`; the page never rendered (black content, status "ready", ~3 min spent) - no VS Code window. BUGS B-065 |
| 5 Tasks window (c) | not built; the guest renders only on the engine's private display (Phase-7 harness) | - | - | **phone** (not done) | `p7g-0.png` / `p7g-1.png` show "Welcome to Tasks" on 1280x720 via the harness |
| 8 GATE | ONE PC-window screenshot (video rectangle only) with the window kinds together; typing proof per window | - | - | **phone** | `pc-final.png` (16:15): X11 window (Notepad++ + xeyes) + Browser window (address bar "phase7x11ok" typed by real PC keys). Typing: **Browser yes** (`phase7`, then `phase7x11ok` visible), **X11 no** (B-062). fps/PSS/CPU numbers: NOT RUN (out of the 16:05 stop) |
| 10 Gates | Android gate and Rust gate run in the close-out window | - | gradle + cargo | code-only | `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` -> `BUILD SUCCESSFUL in 1m 11s` / `101 actionable tasks: 26 executed, 75 up-to-date` / `GRADLE_EXIT=0`; `cargo test --release` -> `test result: ok. 42 passed; 0 failed; 0 ignored` (`CARGO_TEST_EXIT=0`); `cargo clippy --all-targets` -> `Finished dev profile` (`CARGO_CLIPPY_EXIT=0`) |

Transfer note: the phone's Wi-Fi has client isolation (ping to 10.17.124.62 fails; mDNS
discovery finds nothing), so the PC link ran over USB:
`adb forward tcp:47600 tcp:47600` + `udreceiver view --addr 127.0.0.1:47600 --seconds 1800`
(`presented==decoded`, `dropped=0`, decode 3.8-5.9 ms, `d3d11=true`, ~9 fps live).

Evidence files (laptop, `C:\Users\Asus\AppData\Local\Temp\opencode\p7`): `pc-01.png`
(X11 window, Notepad++), `pc-03.png` (two windows), `pc-gate.png` / `pc-final.png` (gate
screenshot), `pc-07.png` / `pc-08.png` (typed text in the Browser), `x11now.png` (fresh
Xvfb capture: xeyes visible, NPP `length: 0`), `view4.log` (PC receiver), `gate_android.log`,
`gate_cargo.log` / `gate_cargo2.log`.

Not done / skipped this round: items 3, 4 (deferred per manager 15:32, one SKIPS row),
items 5, 7 and the measurement half of item 8 (SKIPS rows), the Android-window input path
(guest runs in `:p0`, no view-tree access - SKIPS row).
