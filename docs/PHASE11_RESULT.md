# Phase 11 result: audio, clipboard, files, PC webcam/mic

2026-10-22 03:40 - 08:12. Phone `2c0f6edc` (Wi-Fi 192.168.1.16:47600), Windows 11 26H2
build 26300.9711, not elevated. The phone was locked 05:45-07:59; the owner unlocked it at
07:59 and the phone round below ran on the real phone. No phone result is inferred from code.

## What was done

| Item | What I did | Files | Test | Verified on | Numbers |
|---|---|---|---|---|---|
| B-098 | Receiver checks `running` inside the pipeline inner loop, sends release-all and `shutdown(Both)` on a cloned socket; main thread waits 2 s. | `pc/udreceiver/src/view.rs` | `phase9_exit.ps1` | phone | `EXIT_MS=1807`; lock restored 24 ms; phone `P5 release-all done` + `SESSION_END` |
| Channels (D-a) | CH_AUDIO=5, CH_CLIP=6, CH_FILE=7, CH_CAM=8; priority INPUT, CONTROL, AUDIO, CLIP, VIDEO, CAM, FILE, BENCH; both ends. | `protocol.rs`, `Phase4Protocol.kt` | 3 new Kotlin protocol tests + Rust tests | code-only | 169 Android / 0 fail / 3 skip; 73 PC / 0 fail |
| Clipboard (D-c) | CF_UNICODETEXT listener + set, FNV-1a loop guard; Android ClipboardManager; `phase11x clip-set:` / `clip-show`. Fixed `appCtx` never assigned (both directions were silently dead). | `clip.rs`, `view.rs`, `Phase11Clip.kt`, `HarnessReceiver.kt` | real clipboard both ways | phone | PC -> phone: phone holds len=22 `sha=c5f04e0e`; phone -> PC: PC clipboard = the phone marker; only length + SHA-256 prefix logged |
| File transfer (D-d) | START/DATA/END, one DATA per 64 KiB wire frame; `.part` + SHA-256 verify, never overwrite (" (2)"); PC drop + Ctrl+O Import + `UD_VIEW_IMPORT` test hook; phone Files "Send to PC" + `phase11x files-send:`. | `files.rs`, `view.rs`, `Phase11Files.kt`, `Phase6Apps.kt`, `Phase4LinkService.kt` | 5 Rust + 6 Kotlin tests; both directions on the phone | phone | 20 MB phone -> PC `sha_ok=true`, SHA-256 `4515...53cc` both ends; 1 MB PC -> phone `sha256=bdb1ca24...`, SHA-256 `bdb1ca24...` both ends |
| TLS short write (B-100) | A 64 KiB frame used one `write` and any partial write aborted the session ("short frame write"), killing the first 1 MB transfer. Now `write_all` loops; a 1 MB transfer passes. | `protocol.rs` | 1 MB PC -> phone transfer | phone | first try failed at START (0-byte `.part`); after fix 1,048,576 B saved, SHA match |
| Audio PC half (D-b) | Jitter buffer (3-packet target, late/duplicate drop, 12-packet cap), WASAPI shared playback, loopback capture, `audiotest`. | `audio.rs`, `main.rs` | 4 unit tests + self-test | PC-only | 200/200 packets played, 0 late, 0 dropped, 96,000 frames written; loopback RMS baseline 0.00058, during 0.00061, after 0.00053 |
| Webcam (D-e PC half) | MF Source Reader, numbers only, released after stop. | `cam.rs`, `main.rs` | `camtest` | PC-only | 1280x720 RGB32, 15 frames, 11.6 fps, brightness 0.000 -> 0.165, `camera released` |
| Mic (D-e PC half) | WASAPI capture RMS, numbers only, never saved. | `audio.rs` | `miclevel` | PC-only | 48 kHz 2 ch f32, mean RMS 0.0127, peak 0.0427 |
| WebView shim (D-e phone half) | `camera-shim.html` + `camera-shim.js`: overrides `getUserMedia`/`enumerateDevices`, paints pushed frames into a canvas `captureStream(15)` plus a 48 kHz WebAudio stream; `Phase11Media` CAM (26-byte header) and PCM (12-byte header) framing + the exact `evaluateJavascript` bridge call. Nothing recorded. Not yet loaded in the shell WebView. | `assets/phase11/*`, `Phase11Media.kt` | 5 Kotlin tests | code-only | CAM 1280x720 roundtrip; 10 ms PCM = 1920 B + 12 |
| Audio capture engine (D-b phone half) | `Phase11Audio`: AudioPlaybackCaptureConfiguration (MEDIA/GAME/UNKNOWN), 48 kHz stereo s16 AudioRecord, exact 10 ms packets into the tested AUDIO header. Consent/start wiring still to do. | `Phase11Audio.kt` | compiles | code-only | engine only |

## Gates (2026-10-22 08:11)

```
Android: 169 tests / 0 failures / 3 skipped (26 classes); :app:lintDebug + :app:assembleDebug
         BUILD SUCCESSFUL.
PC:      73 tests / 0 failures (cargo test --release); cargo clippy --all-targets
         -D warnings clean; git diff --check exit 0.
```

## What failed

- **Audio loopback proof.** First run: the endpoint was muted (`mute=true level=0.00`); the test
  unmuted it for the run. Second run after the owner woke: `mute=false level=0.66`, 96,000 frames
  written, yet the loopback RMS stayed at the noise floor (0.00058 baseline / 0.00061 during).
  The tone was therefore not measured at the endpoint. This is an environment/driver fact until
  diagnosed with a WAV played by Windows itself and a loopback capture on the same endpoint; no
  code bug is claimed, and no audio was saved. Phone -> PC audio is unproven.

## Not built / not wired yet (free-model quota)

1. AudioPlaybackCapture: the MediaProjection consent dialog, `RECORD_AUDIO` request, the
   CH_AUDIO sender wiring, and the PC pipeline hook-up to the jitter buffer/player.
2. The WebView shim: load `camera-shim.html` in the shell WebView and feed it CAM frames from
   `Phase11Cam`/`Phase11Media`, then prove it on the phone.
3. X11 clipboard bridge (Linux app clipboard <-> Android clipboard).

## Not done

- The locked `view --lock-max-seconds 180` + Import + Exit click was not repeated this round;
  B-098's exact Exit path was proven on the phone at 04:03 (`EXIT_MS=1807`).
- Phone audio end to end; WebView getUserMedia on the phone; X11 clipboard bridge.

## Bugs

- B-098 fixed and phone-proven.
- B-100 logged (64 KiB frame short-write) and fixed; phone-proven by the 1 MB transfer.
- No other code bug found. The audio loopback is recorded as an environment fact, not a BUGS row.

## Still unknown

- Why WASAPI loopback of this endpoint stays silent while the endpoint is unmuted.
- Whether the AudioPlaybackCapture engine works on this phone (no consent wiring yet).
- Whether the shim page renders PC frames correctly in the shell WebView.

```
PHASE 11 GATE: NOT FULLY PASSED - B-098 fixed (phone); clipboard both ways and files both ways
pass on the phone (20 MB SHA-256 match, 1 MB after the B-100 short-write fix); PC camera/mic
numbers pass and release; gates Android 169/0/3 + lint + assemble, PC 73/0 + clippy clean,
git diff --check 0. Open: phone -> PC audio (loopback silent even unmuted; environment),
AudioPlaybackCapture consent/start wiring, WebView getUserMedia shim loading, X11 clipboard.
No commit made.
```

## Round 2 (2026-10-22 09:20-12:10): external-page webcam, X11 bridge, lock import, B-101

All phone results are on `2c0f6edc`, Android 16, on the **dev path**: the PC connected through
`adb forward tcp:47600 -> tcp:47600` to 127.0.0.1. The phone's Wi-Fi address
`10.17.106.29:47600` was NOT reachable from the laptop this round (`ping`: host unreachable;
TCP connect: timeout), so no round-2 result proves the Wi-Fi path - every row below is labelled
**dev path**. No network settings were changed.

| Item | What I did | Files | Test | Verified on | Numbers |
|---|---|---|---|---|---|
| 3(a) shim on every page | `WebViewClient.shouldInterceptRequest` re-serves main-frame HTML with the shim as the first `<head>` script + relaxed CSP (androidx.webkit is not in the offline Gradle cache and dl.google.com is unreachable, so no new dependency and no THIRD_PARTY row). `UdBridge` exists for every page; the shim marks `window.__udCamShim` and starts the stream on `getUserMedia`. New harness action `p6:jsb64` runs JS in the current Browser window and reports through `UdBridge.report` (a receiver cannot await `evaluateJavascript` on the UI thread - deadlock). | `Phase6Apps.kt`, `Phase6Dispatch.kt`, `assets/phase11/camera-shim.js`, `HarnessReceiver.kt` | external page https://webcamtests.com, its own "Test my cam" button | phone (dev path) | `BROWSER shim injected https://webcamtests.com/ bytes=31238`; its table: `Webcam Name: PC camera (unified-desktop); Quality Rating: 8; Frame rate: 11 FPS; Stream Type: video; Image Mode: rgb; 0.23 MP; 640x360; nHD; Aspect 1.78`; "Testing was completed successfully". zoom.us/test not tried. |
| 3(b) fps | PC send rate is exactly 10.0 fps before and after (frames 300->400 in 10 s); the UVC device is the cap (MF negotiates 1280x720 RGB32, ~295 Mbit/s ~ USB2 ceiling). Phone was the fixable part: the shim allocated a fresh Image and read back `getImageData` on every frame; now one reused Image and brightness every 5th frame. PC converts+downscales RGB in one pass and paces from the sample time (`next = got + 66 ms`). | `camera-shim.js`, `cam.rs` | ud:camtest frame counter; PC CAMSTAT | phone (dev path) + PC-only | shim 6-9 fps -> **10-11 fps** (frames advance 10/s = the full PC feed); PC sends 10.0 fps; >10 fps not achievable (device). |
| 3(c) brightness | `OWNER: cover the laptop webcam...` at 10:38, 10:52, 11:08, 11:22, 11:40 and 12:05 in TODO.md, plus a sampler reading the shim's `brightness`/`changed`. | `TODO.md`, shim report | P11CAMSTATS samples every 3 s | phone (dev path) | baseline 0.51-0.85, `changed` 0-0.063; real observed dip **0.525 -> 0.285 at 11:36:46** (~4 s at 0.41-0.44), jump to 0.766-0.85 from 11:37:35. No deliberate 5 s cover happened: 11:22 lost the stream to B-102, 11:40 to the view window being closed at 11:39:32 (`pipeline stopping (release-all sent)` -> `window closed`). The 12:05 window also had no stream: the app process was gone at ~12:0x (the owner removed the task; `ForegroundServiceDidNotStartInTimeException` at 11:26) and the view window was closed again, so the sampler got 0 lines. No deliberate cover in six windows. |
| 4 X11 clipboard | Both directions implemented; Wine's SendEvent-delivered SelectionNotify has bit 7 set (`0x9F` = 31&#124;0x80), masked with `0x7f`; bounded property waits + 5 s liveness tick. | `Phase11X11Clip.kt`, `x11clip.c` | Notepad++ marker in Xvfb | phone | X11->Android: `notify target=238 prop=242`, `property bytes=133`, `x11->android len=133 sha=8d80920a`. Android->X11: claim/serve works (`android->x11 len=133`, `selection owner=2097664`) but Notepad++ Ctrl+V issued **no SelectionRequest** - unverified, SKIP row. |
| 5 lock import | `view --lock-max-seconds 180` on the dev path; new `lock-inject --import <path>` = foreground-PID check before every synthetic key, Ctrl+O, dialog `#32770` PID check, path typed, Enter; `--click-exit` for the Exit button. | `view.rs`, `lock.rs`, `HarnessReceiver.kt` | phone lock desktop, 1 MB file | phone | `LOCK engaged switch_ms=19, first_frame_after_engage_ms=413`; saved `import-1mb.bin` 1,048,576 B SHA-256 `1c59b867...5a1afe38` == PC; `--click-exit` -> `switch_ms=21 total_ms=110`. |
| B-102 keepalive | A link reconnect makes the receiver release camera+mic, but the page keeps its MediaStream and no track event fires, so nothing re-sends `cam-on` and the webcam stays dark until reload. The shim now re-asserts `cam-on` after ~3 s of frozen frames (the receiver ignores duplicates). | `camera-shim.js` | kill + restart the view mid-stream | phone (dev path) | after the restart the camera came back on its own: page frames 260->281, PC `CAM stream frames=` resumed; no reload, no user action. |
| B-101 audio | Measured the real path before the fix: packets arrive in **bursts of ~5 every 50 ms** (adb-forward TCP coalescing), the 30 ms target drained empty between bursts, and the 60 ms age window rejected the late arrivals (`age_ms=n/a`). Fix: target 6 packets (above one burst), re-buffer after a gap counting **one** underrun per gap, age window -50..500 ms, and log the device buffer (`AUDIO device buffer=N frames (N ms)`, `devbuf_ms=` in stats). | `audio.rs`, `view.rs` | PC-only `audiotest --seconds 10`; real-path run blocked | PC-only + code | pre-fix real path: `underruns=29185` over 17,389 packets (~20/s), depth 1, `age_ms=n/a`. PC-only after fix: `packets=1000 played=1000 late=0 dropped=0 underruns=1 depth_end=0` (the 1 is the priming pump before the first packet), `window_ms=11203 frames_written=480000 max_padding=960`; loopback baseline 0.00057 / during 0.00060 / after 0.00056 / max 0.00063. Real-path re-measure BLOCKED (phone password-locked; MediaProjection consent needs the unlock). |
| Gates | Android unit tests + lint + assemble; PC tests + clippy; whitespace. | - | - | code-only | Android **169 tests / 0 fail / 3 skip**, lint + assemble `BUILD SUCCESSFUL` (48 s); PC **75/0** `cargo test --release`; `cargo clippy --release --all-targets` clean; `git diff --check` exit 0. |

### What failed or was skipped (round 2)

- **Android -> X11 paste** stayed unverified: Wine made no SelectionRequest (`no served request`),
  and a background Android clipboard read is refused while WhatsApp is foreground. Timeboxed,
  SKIP row.
- **Real-path audio after the B-101 fix**: the phone went to a password lock at ~11:50 and the
  MediaProjection consent does not survive a link-session end (`P11AUDIO session open, waiting
  for consent`). The 10 s tone/underrun and the click end-to-end delay could not be re-measured;
  only the pre-fix numbers and the PC-only buffer result exist. SKIP row.
- **A deliberate 5 s hand cover** never happened inside any OWNER window (six windows). Numbers
  and a real observed luminance drop are recorded; that is not the requested proof.
- **zoom.us/test** was not tried.
- **fps above 10** is not achievable: the PC capture device is the cap, not the code.

### Bugs (round 2)

- **B-102** logged and fixed: webcam stays off after a link reconnect (description and
  evidence above and in BUGS.md).
- **B-101** fix written and unit/PC-tested; its real-path proof is still open (blocked), so the
  row stays open until a locked-free re-measure.

```
PHASE 11 GATE: NOT FULLY PASSED - item 3(a)(b) pass on the phone on the dev path (external
https://webcamtests.com sees 640x360 / 11 fps / "Testing was completed successfully"; shim
6-9 -> 10-11 fps, PC sends 10.0 fps = device cap). Item 3(c) numbers recorded but no
deliberate 5 s cover. Item 4 X11->Android pass, Android->X11 unverified. Item 5 lock import
pass (switch_ms=19, import SHA match, Exit switch_ms=21 total_ms=110). B-102 fixed and
verified. B-101 fix + PC-only 10 s tone (underruns=1 at priming, 0 after); real-path
re-measure blocked by the phone password lock. Gates: Android 169/0/3 + lint + assemble,
PC 75/0 + clippy clean, git diff --check 0. No commit made.
AUDIO: phone -> PC works end to end (tone proven earlier); B-101 jitter/re-buffer fixed;
       underruns 29185 -> 1 (PC-only 10 s); real-path re-measure and click delay BLOCKED
       (phone locked, MediaProjection consent).
CLIPBOARD: X11 -> Android pass; Android -> X11 unverified (Wine issues no SelectionRequest).
FILES: phone <-> PC pass (1 MB lock import SHA match this round; 20 MB + 1 MB earlier).
WEBCAM+MIC: PC camera visible in an external page at 640x360 / 11 fps, mic RMS present,
       release on stop; B-102 keepalive verified.
```

## Round 3 (2026-10-22 13:20-14:15): B-101 phone proof, webcam cover, Zoom

All phone results are on `2c0f6edc` (Android 16) on the **dev path** (`adb forward tcp:47600 -> tcp:47600`); the phone's Wi-Fi address was still unreachable. The USB link dropped at ~14:13-14:15 (receiver `window closed`, `adb devices` empty, last phone camera frame 14:12:47), which ended the round before the Zoom slot.

| Item | What I did | Files | Test | Verified on | Numbers |
|---|---|---|---|---|---|
| B-101 fix + real path | Root cause: the **1 s WASAPI device buffer** swallowed the whole 6-packet jitter queue on every pump, so `underruns` measured queue emptiness, not audible gaps. Fix: 100 ms render buffer, the pump tops the device up to 60 ms, the jitter target stays 6; audio packet timestamps use `System.nanoTime/1_000_000` like the clock-sync frame (deep sleep had made `age_ms` `n/a`); onset line `AUDIO onset ... play_age_ms`. The tone page is buttons only (3 s, 10 s, 20 ms click; no autoplay). | `audio.rs`, `view.rs`, `Phase11Audio.kt`, `tone.html` | phone: 10 s 1 kHz tone at low volume + 3 x 20 ms click | phone (dev path) | tone: **packets 1000 sent / 1000 received, late 0, dropped 0, underruns 2 (both at session start), depth 8-10, age_ms 13-15, devbuf_ms 60**; clicks: capture->PC play **109.4 / 128.2 / 131.3 ms, median 128.2 ms**, 0 underruns during the clicks; from the page's `play()` call 410 / 476 / 481 ms because the WebView Audio element takes ~300 ms to start. Evidence `p11c/tone10.log`, `p11c/clicks.log`, `p11c/logcat-clicks.txt`. |
| Webcam cover | OWNER asked for a 5 s hand cover at 14:12; stream live (640x360, ~11 fps, receiver window open); sampler every ~0.5 s from 14:11:30 to 14:12:35. | `TODO.md`, `p11c/cam-cover.log` | P11CAMSTATS brightness/changed samples | phone (dev path) | frames 3619 -> 4100 during the window; baseline brightness 0.62-0.66; largest sustained 5 s drop **0.628 -> 0.600 (-0.028)** at 14:12:01-05; then flat 0.618 from 14:12:22-36 (`changed` 0.012) and 0.642 from 14:12:37. **No sample near 0 and no large frame change: no deliberate cover happened in the window.** Not the requested proof. |
| Zoom page | Slot 14:15-14:25; no page opened. | - | - | blocked | The phone was gone from USB at 14:15: `adb devices` empty, receiver `window closed`, VSTAT fps 0.0. No phone input after the link died. SKIP row added with the exact demand. |
| Android -> Notepad++ paste | No new work (manager 14:01); wrote **B-103** from the round-2 findings (no SelectionRequest from Wine after Ctrl+V; claim/serve side proven). | `BUGS.md`, `SKIPS.md` | - | code-only | B-103 OPEN, next round. |
| Gates | Android unit tests + lint + assemble; PC tests + clippy; whitespace. | - | - | code-only | Android **169 tests / 0 fail / 3 skip**, lint + assemble `BUILD SUCCESSFUL` (1m12s); PC **76/0** `cargo test --release`; `cargo clippy --all-targets -- -D warnings` clean (only the pre-existing workspace-profile warning); `git diff --check` exit 0. No commit made. |

### What failed or was skipped (round 3)

- **The deliberate 5 s webcam cover did not happen** in the 14:11:30-14:12:35 window. The stream
  and the numbers are good; the proof is not. SKIP row.
- **zoom.us/test was not run**: the phone dropped off USB at ~14:15 before the slot. SKIP row.
- **14:03 input incident**: before the last MediaProjection consent a notification shade was in
  front and two blind taps landed there. After `am start` brought our activity to the front, every
  tap targeted our own dialog (verified with `uiautomator dump` each step). No harm measured.
- **Phone cleanup incomplete**: `stay_on_while_plugged_in` is still `2` and the app was not
  force-stopped, because adb lost the device. Brightness was never changed (699). OWNER line added.

### Bugs (round 3)

- **B-101** moved to *Fixed* with the phone evidence above (the manager may re-open on review).
- **B-103** opened: Android -> X11 paste (Wine issues no SelectionRequest); no new work this round
  by manager instruction.

```
PHASE 11 GATE (phone): NOT FULLY PASSED - B-101 audio proven on the phone; webcam cover proof not obtained (no cover in the window); zoom.us/test not run (phone USB dropped at 14:15); Android->X11 paste remains open as B-103. Gates: Android 169/0/3 + lint + assemble, PC 76/0 + clippy clean.
AUDIO (phone, dev path): packets 1000/1000/late 0/dropped 0, underruns 2 (priming), delay median 128 ms
WEBCAM COVER: no cover detected in the 14:11:30-14:12:35 window (baseline 0.62-0.66, largest 5 s drop 0.028); stream live at 11 fps
ZOOM PAGE: not tested - phone dropped off USB at ~14:15 (adb no devices); SKIP row added
NOTEPAD++ PASTE: not done this round (manager 14:01) - B-103 OPEN with round-2 findings
```
