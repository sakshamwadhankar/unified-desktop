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
