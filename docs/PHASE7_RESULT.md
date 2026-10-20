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

## Round 3 (2026-10-20 00:43-03:15, phone 2c0f6edc)

Scope (manager prompt): DPI at 1:1 (B-066), the Android window through the Phase-1 B1 route
(item 3), then ONE gate screenshot and typing/number proofs. Every row is **phone** unless
it says otherwise.

| Item | What was done | Files | Test | Verified on | Numbers |
|---|---|---|---|---|---|
| 1 Start | adb device up, battery 20% charging 35.3->38.2 C, mCallState=0; OEM VirusDialog (B-048) cleared via uiautomator bounds + one tap; link over Wi-Fi (phone 192.168.1.16:47600) | - | - | phone | FreePhysicalMemory 2.9 GB, no emulator |
| 2b B-066 DPI | shell+WebView at density 1.0 / dpi 160; JS probe devicePixelRatio=1; VS Code readable at 1:1 vs round 2's 3x | Phase5Desktop.kt, Phase6Apps.kt | live JS probe + PC capture | phone | shell density=1.0 dpi=160, capture p7c/i2-now.png |
| 3 Android window | **Task's own UI renders in a shell window** via the Phase-1 B1 path: window creates the `phase1-owned` VirtualDisplay on its SurfaceView, guest launches normally, GuestPresentationHost moves the activity decor into a Presentation on that display. PC input crosses processes on a same-uid abstract LocalSocket (host window -> guest process; no INJECT_EVENTS, no Accessibility) | AndroidWindowContent.kt, GuestPresentationHost.java | live session + click | **phone** | `P7AND display id=53 900x576 surface=true`, `B1_PRESENTATION_SHOWN guest=...TaskListsActivity display=53 target=900x576`, click `handled=true` |
| 3b Android input | one click on "Sign in" reached the guest (handled=true) and started the guest's next activity, which the B1 route does not present -> window black | - | - | **phone** | BUGS B-068 |
| 3c Android typing | not testable: Tasks needs a signed-in account before it shows any text field | - | - | phone (blocked) | SKIPS row |
| 4 GATE | all four kinds were **open at the same time** (taskbar `s17.png` 02:27, `s19.png` 02:47: Test, Notepad++ + xeyes, VS Code, Android app) but **not in one side-by-side screenshot**: Android went black on reopen (B-070), the NPP window's frame loop had died before Xvfb existed (opened 02:14:30 -> `ECONNREFUSED :6000`; the Xvfb root capture `x11cap2.png` shows Notepad++ + xeyes DO render), the VS Code window stayed blank (code-server started 02:45, after the window), and title-bar drags stopped moving windows (B-071) | - | - | **phone** (partial) | per-kind: Android `s10.png` (02:11) + `B1_PRESENTATION_SHOWN`; Windows/Linux `x11cap2.png` (NPP `length: 27 lines: 2`, xeyes) + round 2 `ph-x11-12.png`; VS Code `i2-now.png` (1:1 on the phone) + round 2 emulator typing |
| 4b typings | Notepad++ `npp7` not run (no live NPP window); VS Code `code7` **not proven on the phone** (no loaded workbench, no editor target - round-2 emulator proof only); Android `tasks7` impossible (Tasks needs an account) | - | - | phone (blocked) | SKIPS row |
| 4a numbers | fps over 47 s of continuous window dragging; PSS; process count; phone CPU; battery temp over 45 s | - | VSTAT + dumpsys + top | **phone** | fps 55-61 sustained, **dropped=0**, decode 4.2-5.0 ms, d3d11=true; PSS **197 MB** (197,409 kB), **12** of our processes (app, :black, :p0-:p4, wineserver, Xvfb, notepad++, 2x libnode); app CPU **126%** (~1.6 cores of 8), phone 351-388% busy of 800%; battery **38.2 C** at t0 and t1 (45 s apart), level 22% -> 22% |
| 5 B-067 | view.rs treats raw 997/10060 as a transient read timeout; survival run with live video | pc/udreceiver/src/view.rs | 44/44 cargo tests | **phone** | view-r7 started 02:09:55 and streamed for **61 min 40 s** (ended 03:11:35, 202,252 frames presented, dropped=0, fps 55-61) - **no 997**. It then exited on a different failure, `read: cannot decrypt peer's message` (BUGS B-072), so B-067's own symptom did not recur in 61 min. |
| 6 Gates | Android and Rust gates | - | gradle + cargo | code-only | `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` -> BUILD SUCCESSFUL in 37s (101 tasks); `cargo build --release` ok; `cargo test --release` **44 passed, 0 failed**; `cargo clippy --all-targets` clean (only the pre-existing workspace-profile note) |

GATE (phone): **all four window kinds were open at once, but not in one side-by-side
screenshot**. The phase-7 gate itself (one screenshot of Android + Windows + Linux + VS Code
tiled with their content, typed into) is therefore **not met**.

Not done this round: VS Code content (window blank, code-server started too late), Notepad++
content in the shell window (X11 loop started before Xvfb), `code7`/`npp7` typing proofs
(no live text target), Tasks typing (no account), closing a shell window with its X button
(clicks counted, window stayed; worked around by an app restart).

Evidence (`C:\\Users\\Asus\\AppData\\Local\\Temp\\opencode\\p7c`): `s10.png` (Tasks UI in
the shell window), `s12.png`/`s17.png`/`s18.png` (cascaded windows, VS Code blank),
`x11cap2.png` (X11 root: Notepad++ + xeyes do render in Xvfb), `view-r7.log` (55-61 fps,
dropped=0), `view-r6.err`/`view-r7.log` (B-069), `ui3.xml` (B-048 dialog).

## Round 4 (2026-10-20 03:35-05:05): fixes and the gate

Fixed and proven on the phone 2c0f6edc:

- **B-073**: the guest input bridge now accepts only the kernel uid of our own process. In the
  BlackBox guest, `Process.myUid()`, `Os.getuid()` and the `st_uid` of `/proc/self` all
  report 10009, while the kernel's `/proc/self/status` says 10476; the guest parses that
  `Uid:` line, one bad accept no longer kills the loop. Log: `B1_INPUT listening ...
  realUid=10476 myUid=10009`, close answered `reply=true`.
- **B-074**: the X11 window retries every 500 ms for 60 s, shows "Starting...", and reconnects.
  Log: window opened before Xvfb -> `attempt=1 failed ... ECONNREFUSED`, then `connected
  attempt=11 after_ms=5218`; after the 04:35 Xvfb restart `connected attempt=37
  after_ms=18367`. No user action.
- **B-075**: the VS Code window starts code-server itself. Log: stale port 36031 healthz=0 ->
  `VSCODE start ok port=32883 health=2493 ms` -> workbench loaded.
- **B-076**: the Android window froze the Phase-5 encoder when its VirtualDisplay rendered into
  a SurfaceView surface (phone `sent` stuck at 47, PC `rx=0`). It now uses a TextureView
  SurfaceTexture; `sent` kept climbing with `dropped=0`.
- **B-070**: close now sends `C <pkg>` over the input socket, the guest finishes the
  activities and the display is released; reopening relaunches. Proven twice (04:01:33 close
  reply=true + 04:01:53 reopen display 64 + Tasks live in `p8/reopen1.png`; 04:04:51 close,
  04:04:56 reopen display 67).
- **B-071**: title-bar drag moved the X11 window (60,48) -> (280,168) with `P7WIN drag` logs;
  all three gate windows were moved by real PC drags afterwards.

The gate picture is `p8/gate_npp.png` (04:20:50): one PC screenshot with Notepad++ and xeyes
(X11), the VS Code workbench and Android Tasks, all live. Typing: `npp7` landed in
Notepad++ (`p8/npp7e.png`; the line still holds the B-077 flood). `code7` did **not**:
the VS Code workbench loaded, PC clicks and keys reached the shell (`P7WIN down ...
hit=CONTENT`; `P6SHELL key` C,O,D,E,7 `handled=true`), but the Explorer's "workspace"
row never expanded and Ctrl+P never opened Quick Open, so `phase7phone.txt` could not be
opened; the file stayed `phone-gate`. SKIPS 4e. The 20 s drag fps measurement was skipped
(SKIPS 4f); round 3 measured 55-61 fps.

New bug: **B-077** (lost X11 key-up on the 04:17:21 reconnect -> Xvfb auto-repeat typed
~4,900 `p` into Notepad++; open).