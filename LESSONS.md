# LESSONS: facts learned the hard way

From the owner's previous project (AndroidDex-V3, closed). **No code is carried over, only
these facts.** Re-verify anything critical: Android changes.

## 1. The test phone

- OnePlus CPH2619 (Nord CE4 Lite), Snapdragon 695 (2x Cortex-A78 + 6x A55), Adreno 619,
  7.5 GB RAM, Android 16 (SDK 36), OEM skin based on ColorOS, 4 KB pages, adb serial
  `2c0f6edc`. USB 2.0, no video output over USB-C.
- Lower-end than most target phones: its numbers are a floor.

## 2. Android rules that shape the design

- **Exec rule (W^X):** with targetSdk >= 29, files in app storage cannot be executed. Only
  `lib*.so` files shipped in the APK (`nativeLibraryDir`) can. Generating machine code into
  anonymous memory and running it (a JIT) works at targetSdk 36: proven.
- **Virtual displays:** a Play app can NOT launch any activity, not even its own, onto a
  virtual display it created (proven on the phone, Android 16, 2026-10-14). App-made
  displays are untrusted; a launch there needs `allowEmbedded` plus the
  signature|privileged `ACTIVITY_EMBEDDING`, or an activity of the same uid already on the
  display (AOSP `ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay`). An app may
  still show plain windows (e.g. a `Presentation`) on its own private display.
- **Google's own VM (AVF / Linux Terminal)** is not available to third-party apps.
- **Phantom process killer:** about 32 child processes system-wide (Android 12+). It can only
  be relaxed in developer options, which we may never require.
- **Screen-off freezing (ColorOS "Hans"):** about 30 s after the screen turns off, the app
  is frozen, even with a foreground service and a `PARTIAL_WAKE_LOCK`. Signs:
  `/proc/<pid>/wchan` = `do_freezer_trap`; the app cgroup's `cgroup.events` = `frozen 1`.
  `dumpsys deviceidle whitelist` does NOT help. Only the OEM setting works:
  Settings > Apps > (app) > Battery usage > **Allow background activity**. No public API sets
  it, so onboarding must guide the user there. With it on, a heavy workload ran 30 minutes
  screen-off without freezing.
- **Memory killing (ColorOS "Osense"):** kills memory-hungry processes
  (`am_kill ... Cached(lowmem)`), even a foreground service. A busy process is killed far
  sooner than an idle one. Watch logcat for `am_kill` and `OsenseKillAction`.
- **A live PID proves nothing.** Check CPU time advancing (`/proc/<pid>/stat` utime+stime).
- **Screen wakes you do not control:** push-driven apps (e.g. `snapchat:screenwaker`) light
  the screen for ~10 s at random times. Judge a test by what it tests; read the screen
  history with `adb logcat -b events` (`screen_toggled`).
- `adb install -r` stops the app: relaunch it and confirm the UI with `uiautomator dump`
  before tapping.
- `grep -w <pid>` on `ps` output also matches other columns: compare the first field.
- **`LocalSocket`:** connect **before** setting `soTimeout` (no fd before connect), and never
  close its output stream (that closes the whole socket). Both bugs produced the same error
  message and hid each other.

## 3. Video and input

- Qualcomm's `c2.qti.avc.encoder` ignores `KEY_REPEAT_PREVIOUS_FRAME_AFTER`: a static screen
  produces no frames, so the app must send keep-alive frames, or a joining receiver never
  gets a keyframe.
- A decoder does not recover from a lost reference frame on its own: the receiver must ask
  for a keyframe and rebuild the decoder.
- **Input rate limiting must never drop key or button presses/releases.** Only pointer moves
  and scroll may be coalesced. Dropped releases caused stuck keys and drags.
- **Never block inside an async task** to send video. Use a non-blocking send; if full, drop
  the frame and request a keyframe. A blocking send stalled audio and input.
- The previous design measured **244 ms** median typing latency over USB (target 80 ms) and
  never isolated the cause. Measure each stage separately from day one: input send, app
  reaction, encode, transport, decode.
- Window screenshots on Windows that include GPU content: `PrintWindow` with flag 2.

## 4. Measurement methods that worked

- **Input latency:** the app flashes the screen white on each key; the test presses a key on
  the PC and polls the receiver window's brightness (PrintWindow) until it flips. Make sure
  the receiver window keeps focus during the test (validate it first).
- **A/V offset:** a flash and a beep at the same moment; record the receiver window's
  brightness and the PC's speaker loopback on one clock (`time.perf_counter`); offset =
  flash time - beep time. Measure on a **moving** screen: an idle screen sends few frames and
  makes the number worthless.
- **Survival:** sample every 1-2 minutes: PID identity, CPU time advancing, freezer state,
  VmRSS/VmSwap, `am_kill`. For a screen-off test, abort the moment the screen wakes.

## 5. The owner's laptop

- Windows 11, 15.6 GB RAM; Git Bash, PowerShell, WSL (Ubuntu).
- Git Bash + adb: `export MSYS_NO_PATHCONV=1` for device paths.
- PowerShell: `$PID` is reserved (never assign it); wildcards are not expanded for Python
  (glob inside Python).
- USB tethering: switch it on in Settings only. `svc usb setFunctions rndis` drops adb.
  Same-Wi-Fi is more stable for adb work.
- Before touching the phone screen: `adb shell dumpsys telephony.registry` must show
  `mCallState=0` (no active call).
- WSL2 cannot reach adb forwards bound on Windows `127.0.0.1`.
- **The coding agent (OmniRush) runs inside its own WSL 2 distro `OmniRushBox`.** It cannot
  see USB devices, and Windows interop is off (no `adb.exe` from inside). To reach the
  phone: with the phone on USB, on Windows run `adb -s 2c0f6edc tcpip 5555`, read the
  phone's Wi-Fi IP, then inside the box `adb connect <ip>:5555`. Same Wi-Fi needed; redo
  after a phone reboot. Developer-only; the user never does this.
- `ssh-keygen -N ''` run through `cmd /c` sets a literal `''` passphrase: make keys in WSL.
- Android emulator: AVD `Pixel_8_Pro` has 6 GB RAM (`hw.ramSize=6144`); at 2 GB heavy work
  was killed. If its NFC/UWB crash loop starts, reboot it before measuring. Its UDP port
  redirect drops replies, so UDP/QUIC to the emulator does not work: use TCP, or the phone.
- ffmpeg: `C:\Users\Asus\AppData\Local\CapCut\Apps\9.1.0.3879\ffmpeg.exe`.
- The `gh` CLI is not installed.

## 6. How past sessions went wrong

- A "GO" was reported on a process that had already died, from a monitor the agent had
  stopped watching.
- Hours were lost re-discovering solved problems. Read STATE.md and this file first.
- Performance settings were changed without before/after numbers and had to be re-measured
  or reverted.
- A prompt's premise was wrong (a feature was claimed missing; it was already built). The
  agent checked instead of trusting it, and that saved a full session. Always verify.
- A strict rule (any screen wake voids a test) made an agent loop all night. Rules must say
  what is being tested and when to stop.
