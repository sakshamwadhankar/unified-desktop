# PHASE 12 RESULT: speed and lightness

Status: **PART A FINISHED (B-104 software decode fixed at 19:05); PART B PHONE ROUND RUN
20:00-21:40 on `2c0f6edc`; ROUND 2 (23 Oct, final build) brought typing to Browser 65.7 /
Notepad++ 66.6 / VS Code 75.5 ms (all <= 80) and Notepad++ start to 12.15 s with the Wine
warm-up shipped on by default; PHASE 12 GATE still FAILS on the Test-shell typing (87.0 ms)
and the Notepad++ start (12.15 s; target 10 s). B-106 is FIXED: VS Code opens the workbench
in 13.48 s cold (target <= 15 s).** Every number says
where it came from and is labelled `phone`, `emulator`, `PC-only`, `proxy` or `code-only`;
nothing emulator-measured is called phone-verified. The phone's Wi-Fi was OFF by owner
request, so there is no Wi-Fi latency number (SKIPS.md row).

## 1. What changed

| Item | Change | Files |
|---|---|---|
| A2 / B-050 | The release profile now lives at the workspace root, so `strip`, thin LTO and `panic=abort` actually apply. A new abort/recover test proves the single-session mutex and the dirty lock journal survive a `panic=abort` death. | `pc/Cargo.toml`, `pc/udreceiver/Cargo.toml`, `pc/udreceiver/src/lock.rs`, `pc/udreceiver/src/main.rs`, `pc/udreceiver/tests/lock_crash.rs` |
| A3 / B-096 | The receiver sets per-monitor DPI awareness v2 before any window. The client area is physical pixels, the blit is 1:1 at 1080p on the 125 % panel, and mouse mapping was re-checked with a synthetic click on the Start button. | `pc/udreceiver/src/main.rs`, `pc/udreceiver/src/view.rs` (no code change needed: it already uses the client rect) |
| A5 | Per-ABI APK splits. The universal APK stays for emulator + adb testing; a phone installs the arm64 APK without the 82 MB x86_64 Node binary. | `android/app/build.gradle.kts` |
| A6 | Quality profiles as a pure, unit-tested function (hardware, USB/5 GHz, thermal, strong phone). The governor's `LowerResolution` is now real: a second encoder is prepared, the VirtualDisplay is resized and rebound, the old encoder is stopped, and the Presentation plus every window stay alive. Input coordinates stay in the 1920x1080 link space and are scaled on the phone. | `Phase12Profiles.kt`, `Phase12ProfilesTest.kt`, `Phase5Session.kt`, `Phase5Encoder.kt`, `Phase5Desktop.kt`, `Phase4Dispatch.kt` |
| A8 | B-051 switches, all off by default: Qualcomm slice spacing on the encoder, a redraw right after a key is dispatched, and `TCP_NODELAY` on the phone's TLS socket. Already on since Phase 5: no B-frames, `KEY_LOW_LATENCY`, the QTI low-latency vendor key, 60 fps pacing, a 2-frame video queue and keyframe-on-pressure. The encoder first drops only the Phase-12 keys if configure rejects them, then the whole optional set (B-056 behaviour preserved). | `Phase12Tuning.kt`, `Phase5Encoder.kt`, `Phase5Session.kt`, `Phase4LinkService.kt`, `Phase4Dispatch.kt` |
| A4 / B-104 fix | The software decode path now allocates the output sample the Microsoft H.264 MFT asks for. Only a decoder with `MFT_OUTPUT_STREAM_PROVIDES_SAMPLES=false` gets a caller-owned `IMFSample` + `MFCreateMemoryBuffer(cbSize)`; the hardware path is unchanged. | `pc/udreceiver/src/view.rs` |
| B2 prep (latency variants) | `p12lat` is now four named profiles: 0 off, 1 the A8 set, 2 adds one-row QTI slices and an immediate input redraw, 3 adds a second redraw one 8 ms frame later. The PC receiver has `--no-vsync` (`SyncInterval=0`, `DXGI_PRESENT_ALLOW_TEARING`, swap-chain flag). Defaults stay off/Phase-5. | `Phase12Tuning.kt`, `Phase12TuningTest.kt`, `Phase4Dispatch.kt`, `Phase5Session.kt`, `Phase5Encoder.kt`, `pc/udreceiver/src/view.rs`, `pc/udreceiver/src/main.rs` |
| B3 prep (preload) | `p12warm:0|wine|code|1` starts Wine and/or code-server warm-up when the desktop Presentation is first shown, off by default (Wine boot drops saved hives, B-021). `CodeServerWarm` serialises the startup so the VS Code window does not start a second server. | `Phase12Preload.kt`, `CodeServerWarm.kt`, `Phase2Wine.kt`, `Phase5Desktop.kt`, `Phase6Apps.kt`, `Phase4Dispatch.kt`, `Phase12PreloadTest.kt` |
| B-106 fix (VS Code) | code-server now runs `auth: none` on `127.0.0.1` only (DECISIONS D18), no password env and no `/login` POST; the app loads `/?folder=` directly, and `defaultCodeFolder()` creates the workspace directory. This removes the login page that blocked the workbench. | `Phase3Server.kt`, `Phase3CodeActivity.kt`, `Phase6Apps.kt`, `Phase3ServerTest.kt`, `ARCHITECTURE.md`, `DECISIONS.md` |

## 2. Change table (B7): BEFORE / AFTER for every change

| Change | BEFORE | AFTER | Label | Evidence |
|---|---|---|---|---|
| A2 release profile + strip/LTO | 2,569,216 B | **2,217,472 B after A2; 2,219,008 B final** (+1,536 B for B2 `--no-vsync`, -13.6 % vs baseline) | PC-only | `p12/baseline-udreceiver.exe`, `pc/target/release/udreceiver.exe` |
| A2 panic=abort crash recovery | no test | **abort leaves a recoverable journal and no stale mutex; `lock-recover` restores Default** | code-only | `pc/udreceiver/tests/lock_crash.rs`, `cargo test --release` |
| A3 DPI awareness v2, client size | 1536x960 | **1920x1200** | PC-only | `p12/view-emu-before-a1.log`, `p12/view-dpi-after.log` |
| A3 sharpness, Settings text crop (Laplacian variance) | 1284.9 | **3465.0** (2.70x) | PC-only | `p12/dpi-before.png`, `p12/dpi-after.png` |
| A3 sharpness, whole video frame (Laplacian variance) | 285.4 | **757.2** (2.65x) | PC-only | same captures |
| A3 sharpness, text crop mean gradient | 11.358 | **13.514** (+19 %) | PC-only | same captures |
| A3 mouse mapping at 125 % | - | **physical (40,1135) -> Start opens (`startMenu=1`)** | emulator | `p12/a3-mouse2.log` |
| A5 APK splits, universal artifact | 93,158,519 B | 93,294,347 B (+0.15 %, the new Phase-12 code; universal still ships every ABI) | code-only | `app-universal-debug.apk` |
| A5 APK splits, arm64 phone artifact | (not produced) | **55,906,904 B** (-40.0 % vs universal baseline) | code-only | `app-arm64-v8a-debug.apk` |
| A5 APK splits, x86_64 emulator artifact | (not produced) | 49,708,307 B | code-only | `app-x86_64-debug.apk` |
| A6 governor LowerResolution | request only, never applied | **real 1920x1080 -> 960x540 rebuild**, desktop + Settings window kept, PC dropped=0 (re-proven on the final tree at 18:12) | emulator | `p12/lowres-before.png`, `p12/lowres2-after.png`, `p12/view-lowres.log`, `p12/lowres2-view.log` |
| A6 profile choice | none | pure function + JVM tests (Light/Standard/Smooth) | code-only | `Phase12Profiles.kt`, `Phase12ProfilesTest.kt` |
| A8 QTI slice spacing / input redraw / TCP_NODELAY | absent | `p12lat:1`; emulator rejects the QTI key, base low-latency set remains, stream dropped=0 | emulator practice | `p12/a8-practice.log`, `p12/P12TUNING` log line |
| B2 profile 2 + `--no-vsync` | absent | **profile 2 parsed (`slices=1 redrawMs=0`), receiver `swap chain vsync=off tearing=allowed`, 37 frames presented / dropped 0, 1920x1080 desktop visible** | emulator practice | `p12/b2-emu-view.log`, `p12/b2-novsync.png`, logcat `P12TUNING profile=2` |
| A1 receiver start | - | **window up 303 ms, first frame <= 1.31 s** | emulator / PC | `p12/a1-start.log` |
| A1 receiver idle 1080p (60 s) | 0.47 % of one core / 128.2 MB / 0 dropped | **0.38 % / 127 MB / 0 dropped** | emulator / PC | `p12/view-emu-before-a1.log`, `p12/a1-after.log` |
| A4 old-PC proxy, 2 cores, hardware decode | - | **35.65 fps avg / 45.9 max, dropped 0, decode 21.29 ms, CPU 34.9 % of 2 cores, WS ~131 MB** | proxy | `p12/a4-hw-after.log` |
| A4 old-PC proxy, 2 cores, software decode | every frame dropped (B-104) | **36.68 fps avg / 40.9 max, 1070/1070 frames presented, dropped 0, decode 16.75 ms, CPU 33.3 % of 2 cores, WS ~96 MB** | proxy | `p12/b104-sw-after.log`, `p12/a4-sw-after3.log` |
| B1 phone connect (baseline APK + baseline receiver -> final arm64 APK + final receiver) | **0.919 s visible** (window 0.225 s, first frame 0.919 s) | **0.944 s visible** (window 0.260 s, first frame 0.944 s) | phone | `p12/b1-usb-visible2.png`, `p12/b1-after-visible.png`, `p12/b1-after-view.log` |
| B1 phone idle 1080p, 30 s | **0.62 % of one core, 123.9 MB WS, 0 dropped** | not re-run after the final install; A1 emulator/PC 0.38 % / 127 MB / 0 dropped | phone (before) | `p12/b1-idle.log` |
| B1 phone typing, USB adb path | **p50 196.3 ms, p90 295.4 ms, min 12.6, max 397.0, n=29** (PC flash poll) | **p50 90.8 ms, p90 115.3 ms, min 59.0, max 124.2, n=25** (`LATSTAGE total`, default profile 0) | phone | `p12/b1-typing-usb-adb.csv`, `p12/b1-latstage-after-p0.txt` |
| B1 phone typing, USB tethering path | **p50 129.7 ms, p90 149.0 ms, min 96.3, max 166.4, n=25** (PC flash poll) | **p50 91.2 ms, p90 110.0 ms, n=25** (profile 1 + receiver `--no-vsync`, `LATSTAGE total`) | phone | `p12/b1-typing-usb-tether.csv`, `p12/b1-latstage-after-nv-p1.txt` |
| B1 phone drag fps | **33.5 fps avg / 55.9 max, dropped 0** (1080p, 12 sampled VSTAT seconds) | **42.0 fps avg / 55.9 max, dropped 0** (960x540 governor active, 17 VSTAT seconds, window-title drag) | phone | `p12/b1-test-view3.log`, `p12/b1-after-drag.log` |
| B3 Notepad++ start | **15.95 s** cold baseline; same-thermal rerun **18.46 s** | **15.88 s** cold final after the app/encoder settles (faster than the 18.46 s same-thermal baseline); **26.28-26.44 s** if opened while the app is still cold-starting; **11.95 s** with `p12warm:wine` (Wine prewarm 5.54 s), 20.61 s on a later cold-app prewarm run | phone | `p12/b1-npp2/`, `p12/b3-baseline-cold-again/`, `p12/b3-final-settled/`, `p12/b3-warm-reopen/`, `p12/b1-after-npp-warm/` |
| B-106 / VS Code workbench | blocked on `/login` (`Failed login attempt`) | **13.48 s cold from `p6:vscode` to `.monaco-workbench`**, target <= 15 s; code-server cold start included; workspace `README.md` visible | phone | `p12/b106-cold-view.log`, `p12/b106-cold-cdp.png`, `p12/b106-ws-open.png` |
| B4 governor resolution drop | request only, never applied (emulator proof) | **real on phone: automatic thermal=3, 1920x1080 -> 960x540 in 0.2 s, desktop + Notepad++/Settings kept, PC dropped=0, input still 1920x1080** | phone | `p12/b4-lowres-phone.png`, logcat `GOVERNOR action=LowerResolution`, `P5 governor lower-resolution applied` |
| B-103 paste into Wine/Notepad++ | open (round 3) | **still open on final round-4 build**: Android marker set (`P11CLIP test set len=23`), Ctrl+V reached NPP (`keyAtFocused` advanced), no clipboard request/served text, screenshot unchanged | phone | `p12/b103-after-paste.png`, logcat `P11X11CLIP tick ... pending=false` |

### APK entries before (largest)

| Entry | Bytes |
|---|---|
| `lib/arm64-v8a/libnode.so` | 83,296,208 |
| `lib/x86_64/libnode.so` | 82,722,872 |
| `lib/arm64-v8a/libbox64.so` | 22,390,072 |
| `classes.dex` | 6,968,940 |
| `lib/x86_64/libcrypto3.so` | 5,538,256 |

The 82 MB x86_64 Node binary is emulator-only and is what the ABI split removes from a phone
install.

## 3. PRD section 4 targets (B7)

| Measure | Target | Result | Label | Evidence |
|---|---|---|---|---|
| Connect to usable desktop | <= 5 s | **0.919 s baseline -> 0.944 s final** (first visible frame, USB adb path) | phone | `p12/b1-usb-visible2.png`, `p12/b1-after-visible.png` |
| Typing latency USB | <= 80 ms | **196.3 -> 90.8 ms p50** (p90 295.4 -> 115.3); **MISS, 10.8 ms over target**. Profile 0 is best; fresh-receiver no-vsync: p1 91.2, p3 104.9, p2 118.4; 50-key attempts were worse (p0 184.2 n=10, p1 238.9 n=6, probe loss). Stage medians: `drawn>encoded` 46-67 ms dominates, `pc>phone` 8-25 ms, `dispatch>drawn` 6-16 ms; the next lever is the canvas->encoder stage, not transport. | phone | `p12/b1-typing-usb-adb.csv`, `p12/b1-latstage-after-p0.txt`, `p12/b1-latstage-after-nv-p2.txt`, `p12/b1-latstage-after-nv-p3.txt`, `p12/b1-latstage-after-nv50-p0.txt`, `p12/b1-latstage-after-nv50-p1.txt` |
| Typing latency 5 GHz Wi-Fi | <= 120 ms | **not measured: owner Wi-Fi OFF**. USB-tether dev path: **129.7 -> 91.2 ms p50** | phone (dev path); Wi-Fi SKIPPED | `p12/b1-typing-usb-tether.csv`, `p12/b1-latstage-after-nv-p1.txt`, SKIPS.md |
| Frame rate moving windows | >= 30 fps | **33.5 fps baseline at 1080p -> 42.0 fps after at 960x540**, dropped 0 both. The after number is at the governor's thermal resolution, not 1080p; not a like-for-like pixel test. | phone | `p12/b1-test-view3.log`, `p12/b1-after-drag.log` |
| Notepad++ start | <= 10 s | **15.95 s baseline -> 15.88 s cold final (settled) / 11.95 s with Wine prewarm; MISS 1.95 s**. The old 28.23 s was measured while the app was still cold-starting; same-thermal settled final is faster than the 18.46 s baseline. | phone | `p12/b1-npp2/`, `p12/b3-baseline-cold-again/`, `p12/b3-final-settled/`, `p12/b1-after-npp-warm/` |
| VS Code small project | <= 15 s | **PASS 13.48 s** cold to `.monaco-workbench` (B-106 fixed). code-server 4.141.0 installed and healthy (9,134 files, 326,507,659 B), loopback `auth: none`, workspace `README.md` open. | phone | `p12/b106-cold-view.log`, `p12/b106-cold-cdp.png`, BUGS B-106 FIXED |
| Old PC 1080p full screen | 1080p full screen | **passes on the proxy** (2 cores, 131 MB, 35.65 fps, hardware; software path 36.68 fps / dropped 0) | proxy | `p12/a4-hw-after.log`, `p12/a4-sw-after3.log` |

## 4. Methods

- Receiver size: `Get-Item` length of the release exe built from the same tree.
- CPU/WS: `Get-Process` CPU-time deltas over the run, working set sampled every 5 s.
- DPI: `dpi-probe` prints `DPI awareness=per-monitor screen=1920x1200`; the view log prints the
  client size. Sharpness: per-monitor-aware Python capture of the client area, Laplacian
  variance and mean gradient on a fixed text crop (60..900 x 100..350).
- Governor: phone run started with `p6:open:settings`; the governor stepped automatically on
  `thermal=3` (`SetFps(30)`, `SetFps(15)`, then `LowerResolution`), and `p12lowres` then said
  "already low". The phone log shows `1920x1080 -> 960x540`, "applied 960x540 (desktop and
  windows kept)" and the PC stayed at dropped=0. Emulator proof is the earlier forced call.
- Old-PC proxy: `view` pinned to cores 0+1 (`ProcessorAffinity=3`), `SetProcessWorkingSetSize`
  512 MB, a scripted 25 s title-bar drag inside our foreground window; hardware path is the
  normal D3D11 MFT, software path is `--soft`. Software decode was re-run after the B-104 fix
  on the same 1070-frame run and is labelled `proxy`.
- A8 practice: `p12lat:1`, link restart, live view, `dropped=0`. The emulator rejects the QTI
  slice key and the new fallback keeps the base low-latency set (the session still streams).
- B2 practice: new x86_64 APK, `p12lat:2`, link restart, `view --no-vsync --seconds 60`; the
  receiver log prints the swap-chain variant and VSTAT, the device log prints `P12TUNING`.
  Encoder profiles are read at video start, so a profile change needs a link restart.
- B3 practice: `p12warm:1` before `link-start`; the prewarm starts when the desktop Presentation
  is shown. Emulator code-server warm: `P12PREWARM code ok=true started=true port=41951 ms=1719`.
  Wine prewarm on the x86_64 emulator: `P12PREWARM wine ok=false ms=50` because the Wine tree is
  not installed there (not a code failure). `p6:vscode` opened the window and loaded code-server,
  but the emulator stayed on the login page (BUGS B-106, fixed later on the phone with auth none).
- Phone typing BEFORE: the baseline APK and `baseline-udreceiver.exe` were installed/run; the app
  flashed the Test window on each key and the PC polled a flash-area pixel with GDI `GetPixel`
  every ~1 ms while checking the receiver owned the foreground before every press. This is the
  Phase-5 method from LESSONS section 4. The baseline receiver does not print `LATSTAGE`.
- Phone typing AFTER: the final app prints `P5 LAT` and the final receiver prints
  `LATSTAGE total` (PC key -> PC presented). Keys were posted only while the receiver window was
  foreground; `LATSTAGE` lines appended during the run were parsed (`p12/b1_latstage.py`). The
  method is not identical to the BEFORE flash poll, so the 196.3 -> 90.8 ms change includes the
  measurement change; it is not a pure B2 effect.
- Tethering path: USB tethering was switched on through the phone's Tether Settings (the
  `svc usb setFunctions rndis,adb` call alone did not start it), PC got `10.62.130.162/24` on
  "Ethernet 2", phone `10.62.130.6/24`; `view --addr 10.62.130.6:47600` connected directly.
  Tethering was then switched off, the RNDIS adapter disappeared and the Wi-Fi default route
  was the only `0.0.0.0/0` route again. A 300 s on-phone watchdog would have restored `adb` if
  the function switch had dropped it; it was not needed.
- B2 profiles: each profile was applied by `p12lat:N` **before a fresh receiver start**, because
  the encoder keys are read at video start. Each clean run used 25 keys at 250 ms spacing.
  Two 50-key runs lost most probes (`n=6`, `n=10`) while `keyAtFocused` still advanced; they
  are reported as worse (p0 184.2 ms, p1 238.9 ms) but not used for the gate decision.
- Phone drag/npp/paste: drag used PostMessage on the receiver's Test title bar (receiver
  foreground, verified); Notepad++ timing used the same broadcast + light-area screenshot
  detector as the baseline (`b1_npp_start.py`); B-103 set an Android marker with
  `phase11x clip-set:...`, clicked the NPP editor, sent Ctrl+V, and checked the screenshot plus
  `P11X11CLIP` logs.
- VS Code readiness (B-106): after `p6:vscode`, `adb forward tcp:9229
  localabstract:webview_devtools_remote_<app pid>`, poll `/json` for the page whose URL is
  `.../?folder=/data/.../workspace`, open its WebSocket with no Origin, and evaluate
  `!!document.querySelector('.monaco-workbench')` + `document.title`; capture the CDP page.
  `jsb64` is not usable here: `browser(s)` opens/finds the separate blank Browser window.

## 5. What the emulator cannot tell us

- Every emulator speed number is practice only (x86 host, no Adreno, no phone thermals).
- The emulator encoder rejects the optional keys (B-056), so the A8/B2 encoder switches were not
  exercised on real hardware.
- Software decode was broken before the B-104 fix; the fixed path has a proxy number (36.68 fps,
  dropped 0) but no real old-PC run yet.
- Wine/Notepad++ cannot run on the x86_64 emulator: the Wine tree is not installed there
  (`P12PREWARM wine ok=false ms=50`, `ntdll.so missing`), so B3's Notepad++ warm-up is code-only
  until the phone returns.
- VS Code on the emulator opened the window in ~1.5 s but stayed on code-server's login page
  (BUGS B-106, later fixed on the phone); the prewarm itself succeeded in 1719 ms.
- Zoom: the web client loaded without sign-in and showed the pre-join audio/video question,
  but no camera/mic UI was reached inside the A7 time box; SKIPS.md has the row.

## 6. Part B (phone round, 20:00-21:32)

Phone `2c0f6edc` (OnePlus CPH2619, Android 16) stayed attached throughout; Wi-Fi was OFF by
owner request and was never enabled. Battery 43 % at the start and 39 % at the end (pause
limit 20 %). `stay_on_while_plugged_in` was 2 during the run and was restored to 0; brightness
stayed 699; tethering was switched off after its measurement; `mCallState=0` at every check.
The phone stayed at thermal status 3 through the late runs; the governor was at 960x540 /
15 fps unless a row says otherwise.

### B1 BEFORE (baseline APK + baseline receiver)

- Connect: `t_window=0.225 s`, `t_visible=0.919 s` (USB adb path; capture
  `p12/b1-usb-visible2.png`).
- Idle 1080p, 30 s: **0.62 % of one core, 123.9 MB WS, dropped 0**.
- Typing USB adb path: **p50 196.3 ms, p90 295.4 ms, min 12.6, max 397.0, n=29**.
- Typing USB tethering: **p50 129.7 ms, p90 149.0 ms, min 96.3, max 166.4, n=25**.
- Drag: **33.5 fps average during motion, max 55.9, dropped 0** (12 sampled VSTAT seconds).
- Notepad++ cold start: **15.95 s** (broadcast -> large light window; `p12/b1-npp2/`).
- VS Code: the baseline data had no code-server tree (`CS_START FAIL entry missing`). The
  pinned aarch64 deb was installed only after this baseline number was due; B-106 then kept
  the app on the login page. There is therefore no honest BEFORE app-path VS Code number.

### B1 AFTER (final arm64 APK + final receiver)

- Connect: `t_window=0.260 s`, `t_visible=0.944 s` (`p12/b1-after-visible.png`).
- Typing (`LATSTAGE total`): **profile 0 is best at p50 90.8 / p90 115.3 ms** (n=25);
  fresh `--no-vsync` receiver p0 was worse (129.7 / 220.1, n=25). Fresh-receiver no-vsync:
  profile 1 91.2 / 110.0, profile 3 104.9 / 129.9, profile 2 118.4 / 142.6 (all n=25).
  Two 50-key attempts were worse and lost probes: no-vsync p0 184.2 ms (n=10), no-vsync p1
  238.9 ms (n=6), so they are not used for the gate. Keep profile 0. Stage medians for p0:
  total 90.8, `drawn>encoded` 46-67 (dominant), `pc>phone` 8-25, `dispatch>drawn` 6-16,
  `encoded>pc_rx` 7-26, `pc_rx>presented` ~3.5 ms; the next lever is the canvas->encoder
  stage, not the network.
- Drag: **42.0 fps average / 55.9 max, dropped 0** at the governor's 960x540 (thermal=3),
  so this is faster but not a 1080p like-for-like comparison.
- Notepad++: **15.88 s** cold final when the app/encoder has settled; **18.46 s** baseline
  under the same thermal=3 conditions (so there is no cold regression). The old **28.23 s**
  was measured while the app was still cold-starting; a repeat at 21:25 gave 26.44 s, and
  26.28 s with an attempted governor hold, so the residual is app cold-start contention,
  not a Wine code change. **11.95 s** with `p12warm:wine` (`P12PREWARM wine ok=true ms=5535`);
  a later cold-app prewarm run took 20.61 s. `p12warm` is opt-in and off by default. The <=10 s
  target is still MISSED.
- VS Code / B-106: **FIXED and PASS**. code-server runs `auth: none` on loopback only; the
  workspace directory is created; `p6:vscode` -> `.monaco-workbench` = **13.48 s cold**
  (code-server start included), title `Welcome - workspace - code-server`, `README.md` open.
  CDP is the readiness method; `jsb64` was evaluating a separate blank Browser page, which is
  why it reported `0||`.
- B4 governor: the phone automatically stepped `thermal=3 -> SetFps(30) -> SetFps(15) ->
  LowerResolution`; `P5 governor lower-resolution begin 1920x1080 -> 960x540`, applied in
  0.2 s, desktop and Notepad++/Settings windows kept, PC dropped 0. The Settings window still
  says "1920x1080" (B-105, open).
- B-103: still **OPEN** on the final round-4 build. Android marker `P12PASTE...` was set; the
  click and Ctrl+V reached NPP (`keyAtFocused` advanced), but the NPP capture is unchanged and
  the phone log shows no SelectionRequest/served request - only `P11X11CLIP tick ... pending=false`.

### What is still missing or not honest to claim

- **Wi-Fi typing latency** (owner Wi-Fi OFF) - SKIPS.md row; needs the owner's router/SSID.
- **1080p drag after** - the final app's governor drops to 960x540 on this warm phone; a cooled
  phone is needed for a like-for-like 1080p number.
- **B2 causal effect** - profile runs are noisy and the BEFORE/AFTER latency methods differ;
  no profile shows a repeatable win over profile 0. This is a measurement result, not a claim
  that the B2 code is useless.
- **Idle after** was not re-run after the final install; the A1 emulator/PC number is separate.

## 7. B6: 3D path for Windows apps (investigation only, code-only)

- **What exists:** the shipped Box64 v0.4.4 has a Vulkan wrapper. `src/wrapped/wrappedvulkan.c`
  loads the native `libvulkan.so.1` and forwards Vulkan entry points;
  `src/library_list.h` maps both `libvulkan.so.1` and `libvulkan.so`. So an x86_64 Wine/DXVK
  call can in principle reach the phone's native Android Vulkan loader.
- **What is missing in our tree:** no DXVK, VKD3D, Vulkan loader shim or Turnip driver is
  shipped (`grep -ri dxvk\|vkd3d\|winevulkan\|vulkan android/` finds nothing). The Wine pin is
  the Kron4ek 11.19 prebuilt, whose Vulkan DLLs were not inspected on the phone (absent).
- **Phone hardware:** Snapdragon 695 / Adreno 619 (LESSONS.md). DXVK 2.x requires Vulkan 1.3.
  The stock Qualcomm driver's Vulkan level on this Android build is unknown without the phone;
  older DXVK 1.10.x can run on Vulkan 1.1 but with less game compatibility. Turnip (Mesa
  freedreno) supplies 1.3+ for a6xx, but an app-local non-root install needs an arm64
  `libvulkan_freedreno.so` plus a loader/ICD shim packaged as `lib*.so`, and none of that is
  trivial.
- **The real blocker is presentation, not translation:** our Wine windows render through Xvfb
  (X11). Wine's Vulkan WSI would create an X11 surface; the Android Qualcomm/Turnip driver
  offers Android (`VK_KHR_android_surface`) or headless surfaces, not X11. Winlator solves this
  with its own display/driver stack; we have no equivalent. A DXVK path therefore needs a
  presentation bridge into our compositor's SurfaceTexture (or a headless swapchain + copy),
  on top of the driver packaging.
- **Verdict:** not implemented (the prompt allows implementation only if trivial). Recorded as
  future work in ARCHITECTURE Q8 and SKIPS.md. First measurement when the phone returns:
  `dumpsys SurfaceFlinger` / a Vulkan probe for the driver name and `apiVersion`, then decide
  whether the stock driver can run DXVK 1.10 before attempting Turnip packaging.

## 8. Round 2 (night run 2026-10-23): the typing number was a receiver bug

The Phase-12 gate failed typing at p50 90.8 ms. This round found that the 90.8 ms was not
the cost of the path: **the receiver only flushed queued input when a video frame arrived**.
On a still desktop the phone sends one keep-alive frame per second, so a key could wait
almost a second for the next frame, and the flash-based probe sometimes saw no frame at all
(8 of 25 keys reported). Everything below is `phone` on `2c0f6edc`, USB adb path, thermal 3,
960x544 stream, unless a row says otherwise.

The fix has four parts, one in the receiver and three in the phone:

1. **Transport readiness without socket timeouts.** A reader thread does fully blocking
   socket reads into a small channel; the TLS state machine (`rustls`) takes its bytes from
   the channel, and the pipeline waits on the channel with a 5 ms tick. The socket itself
   never gets `SO_RCVTIMEO`: a timed-out blocking recv leaves a Windows connection in an
   indeterminate state, and this project already lost streams that way (B-067 997 errors,
   B-072 decrypt failure after 61 min). Pairing and bench keep blocking reads.
2. **Urgent frames** (`Phase5Encoder.UrgentFrames`, `inputUrgent()`): a frame composed
   within 150 ms of a key/button/wheel event skips the governor's fps pacing (the governor
   holds 15 fps on this thermal-3 phone).
3. **Drop-before-input** (`dropPreInput`): a frame whose SurfaceTexture timestamp predates
   the input is dropped instead of being presented as if it showed the key.
4. **X11 frame loop: no identical pictures** (`Phase2X11.getImageIfChanged`,
   `Phase7X11Frames`). The X11 window used to push the Xvfb root into the shell every 120 ms
   even when nothing had changed; every push became a video frame, so a key flash from a
   Wine app queued behind one of them. Measured on the same build: Notepad++ typing
   `encoded>pc_rx` 35 ms and p50 105-109 ms, while a window with no X11 frame behind it
   reached the PC in 5 ms. The loop now compares the raw GetImage bytes (3.7 MP, exact
   `contentEquals`, no ARGB conversion when equal) and posts only changed pictures; after a
   key it also polls every 8 ms for 300 ms so the typed echo does not wait for the 120 ms
   idle tick. Pure decisions are unit-tested (`Phase7X11FramesTest`).

| Case | BEFORE (Phase-12 build) | AFTER (final build, app+receiver freshly started) | Evidence |
|---|---|---|---|
| Browser, still desktop (the gate case) | **p50 220.1 / p90 442.2 ms, n=8/25** | **p50 65.7 / p90 74.5 ms, n=25** | `p13/b2-browser.csv`, `b2-browser-urgent.csv`, `b2-browser-static-fix.csv` |
| Browser, X11 window behind it | p50 65.3 / p90 89.2, n=25 | **p50 67.5 / p90 84.4 ms, n=25** (pre-fix on the same transport: 108.1 / 118.9) | `b2-browser-anim.csv`, `b2-browser-anim-final.csv`, `b2-browser-anim-fix.csv` |
| VS Code editor | p50 81.6 / p90 110.8, n=25 | **p50 75.5 / p90 103.5, n=25** (urgent build; not re-measured on the final build - editor focus could not be re-established, B-110) | `b2-vscode.csv`, `b2-vscode-urgent.csv` |
| Test shell (permanent 60 fps harness) | p50 85.6 / p90 129.7, n=25 | **p50 87.0 / p90 97.0, n=25** (still over target; same path as the earlier 83.8 run, run-to-run noise) | `b2-test-shell.csv`, `b2-test-shell-drop.csv`, `b2-test-fix.csv` |
| Notepad++ editor | (the 00:57-01:08 runs are invalid: NPP was not running, the X11 frame was stale and only the probe chip flashed) | **p50 66.2 / p90 73.7, n=25** (final build before the helper refactor) and **66.6 / 72.8, n=25** (final APK, text confirmed in the editor); pre-fix on the same transport: 104.8 / 115.0 n=12 and 108.8 / 120.3 n=17 | `b2-npp-real*.csv`, `b2-npp-fix.csv`, `b2-npp-final.csv` |

Stage medians on the final build (all n=25, final APK) - `pc>phone` / `recv>dispatch` /
`dispatch>drawn` / `drawn>encoded` / `encoded>pc_rx` / `pc_rx>presented`:

| Case | pc>phone | recv>disp | disp>drawn | drawn>enc | enc>pc_rx | rx>pres |
|---|---|---|---|---|---|---|
| Notepad++ | 12.2 | 1.5 | 6.7 | 38.0 | **5.0** | 2.1 |
| Browser, still | 12.7 | 2.5 | 6.5 | 36.0 | **4.9** | 2.0 |
| Browser, X11 behind | 12.2 | 1.2 | 7.2 | 36.7 | **5.6** | 1.9 |
| Test shell | 6.2 | 0.9 | 6.5 | 39.1 | **29.3** | 1.9 |

The X11 fix removed the 31-35 ms `encoded>pc_rx` queue for every case that has an X11
window (now 5 ms): a picture that did not change no longer becomes a video frame, so the
key frame reaches the codec and the network alone. The static Browser's earlier
76.5 / 109.7 (bimodal `encoded>pc_rx` 4 vs 42 ms) did not reproduce with a freshly started
app and receiver - all 25 rows at 4.9 ms, and `drawn>encoded` fell from 44.4 to 36.0 ms;
that half was app-process/encoder state, not the X11 change. The remaining typing miss is
the Test shell: a permanent 60 fps harness animation keeps frames in flight, and the key
frame waits 29.3 ms in `encoded>pc_rx` behind them. The next lever there would be dropping
stale animation frames ahead of an input frame on the receiver, which is deliberately not
attempted here (it would risk smoothness, and the manager's one-attempt box was used).
The phone's canvas-to-encoder stage (~37 ms, all cases) is the other honest lever.

The first channel version queued 16 x 32 KB and was measured at 100.2 ms for the same
Browser while a Wine window animated behind it: the reader thread ran hundreds of KB ahead
of the presenter, so the frame a key caused sat behind older frames. The queue is now
4 x 16 KB (64 KB, the size the kernel socket buffer bounded the old direct-read loop by).

**Transport soak (30 minutes, final build):** one real key through the receiver every 5 s
(**350 keys, 0 failures**; `p13/soak-keys.log` 01:40:52-02:10:54). The receiver log has
**0 read/decrypt/stall errors** and **`presented=2263 decoded=2263 dropped=0`** at the end;
the same process (PID 44684) served the whole run and was still streaming after the soak
(`p13/soak-view.log`). That is the B-067/B-072 failure mode exercised for 30 minutes with the
socket never given a read timeout.

**Notepad++ start, round 2 (final build, Wine prewarm on by default - D21):** the desktop
Presentation starts the warm-up on a background thread (`P12PREWARM wine ok=true ms=6687`,
second run 5249 ms), and every app open reuses the live prefix. Click-to-visible with the
warm-up finished before the click: **12.15 s** (`nppstart-prewarm-default.png`; an earlier
run measured 11.2 s, so 11.2-12.2 s). The same first open without the warm-up is **28.23 s**
(cold prefix, Phase 12). The target <= 10 s is still missed by ~2 s: the residual is
Notepad++'s own start and paint under Box64/Wine, not the boot - the warm-up moves the boot
off the click. A click that arrives during the warm-up waits for it under one monitor and
reuses the result: clicked 2 s into a 5.2 s boot -> **13.83 s**, one boot only, Notepad++
running (`nppstart-cold-fastclick.png`, `ps` notepad++.exe). Cost measured once: app PSS
**111.9 MB** on the empty warmed desktop, Xvfb **62.4 MB**, wineserver **18.1 MB** (so the
warm desktop is ~192 MB total); with Notepad++ open: app 142.6, Xvfb 62.4, wineserver 18.1,
Notepad++ 195.6 MB. Proposal only (not done): pre-launch Notepad++ itself to approach the
10 s target; that costs more RAM and is a separate decision.

## PHASE 12 GATE

PHASE 12 GATE: **FAILED** - 5 targets pass, 2 miss, 1 is skipped (Wi-Fi off by owner).
CONNECT: PASS 0.944 s visible (target <= 5 s) | phone
TYPING USB: **FAIL on the Test-shell case only** - p50 Browser 65.7 / Notepad++ 66.6 / VS Code 75.5 (all <= 80, n=25 each; VS Code from the urgent build) and Test shell **87.0 / p90 97.0** (n=25, 7.0 ms over). Target <= 80 ms, profile 0 | phone
TYPING 5 GHz WI-FI: SKIP - owner Wi-Fi OFF; no number claimed (SKIPS.md) | phone/dev path only
MOVING-WINDOW FPS: PASS 42.0 fps avg / 55.9 max / dropped 0 at the governor's 960x540 (target >= 30); not a 1080p like-for-like | phone
NOTEPAD++ START: **FAIL 12.15 s with the shipped Wine prewarm** (second run; 11.2 s in the first) / **28.23 s cold without it** (target <= 10 s; 2.1 s over). The warm-up removes the boot from the click, not Notepad++'s own start | phone
VS CODE SMALL PROJECT: PASS **13.48 s** cold to `.monaco-workbench` (target <= 15 s); B-106 FIXED | phone
OLD-PC 1080p FULL SCREEN: PASS on the 2-core proxy (35.65 fps hardware / 36.68 fps software, dropped 0, CPU 33-35 %, WS 96-131 MB) | proxy
GOVERNOR: PASS real 1920x1080 -> 960x540 at thermal=3 in 0.2 s; desktop/windows kept; PC dropped 0 | phone
BUGS: B-106 FIXED | B-103 OPEN (Wine paste: marker set, Ctrl+V reaches NPP, no SelectionRequest) | B-105 OPEN (stale Settings text)
GATES: Android :app:testDebugUnitTest / :app:lintDebug / :app:assembleDebug BUILD SUCCESSFUL (194 tests, 0 failures, 3 skipped; lint 0 errors, 98 warnings); APK 55917404 B; PC cargo test 78/0 + lock_crash 1/0, clippy -D warnings exit 0 (first run under parallel Gradle load flaked one lock test, clean repeat passed; B-107); git diff --check 0
NO COMMIT: all changes uncommitted for the manager.


