# LESSONS: facts learned the hard way

From the owner's previous project (AndroidDex-V3, closed). **No code is carried over, only
these facts.** Re-verify anything critical: Android changes.

## 1. The test phone

- OnePlus CPH2619 (Nord CE4 Lite), Snapdragon 695 (2x Cortex-A78 + 6x A55), Adreno 619,
  7.5 GB RAM, Android 16 (SDK 36), OEM skin based on ColorOS, 4 KB pages, adb serial
  `2c0f6edc`. USB 2.0, no video output over USB-C.
- Lower-end than most target phones: its numbers are a floor.
- **After every `adb install`, the OEM "phone manager" (`com.oplus.phonemanager`) shows a
  security-scan dialog for our debug app** ("This app may lead to serious risks...", buttons
  Ignore / Fix now), often 3-10 s after the install. It covers our app until dismissed.
  Tap **Ignore**; "Fix now" may remove the app. Poll for it after installs (2026-10-19).
- The test phone has a PIN. If its screen turns off, it locks and adb cannot use the UI.
  Overnight runs keep `svc power stayon usb` on (restore it after) and never send
  KEYCODE_POWER or KEYCODE_SLEEP.

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
  **2026-10-18 (Phase 2):** this same freezer hit our app mid-`wineboot` while the screen
  was Dozing — the app cgroup (wine children included) froze, and after the screen woke the
  wine IPC was deadlocked (`do_freezer_trap` wchan, children unkillable until thaw) and the
  boot never completed. Rule for dev runs: `svc power stayon true` PLUS the harness activity
  kept in the foreground (BUGS B-027).
- **Killing all our processes on the phone (2026-10-18):** `adb shell "run-as <pkg> sh -c
  'kill -9 -1'"` with the whole kill line single-quoted as ONE device-shell argument kills
  the uid cleanly. After killing a *foreground* app the system respawns a `:black` WebView
  process within seconds — follow with `am force-stop <pkg>` to settle at zero processes.
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
- **Never feed the hardware encoder faster than it drains (Phase 5, measured).** A GL
  compositor that drew on every virtual-display frame plus a forced redraw per input event
  (~84 fps) kept the encoder's input queue full: ~200 ms from draw to encoder output, typing
  latency 290 ms. Pacing to 60 fps, latching every queued SurfaceTexture buffer per draw
  (`updateTexImage` takes the OLDEST queued buffer) and no redraw on input gave 143 ms.
- `c2.qti.avc.encoder` lists `vendor.qti-ext-enc-low-latency.enable` in
  `supportedVendorParameters` (API 31+); setting it to 1 took the encoder stage from ~65 to
  ~45 ms (typing latency 143 -> ~120 ms).
- A control message that reports on a video frame arrives BEFORE that frame (control outranks
  video on the link). Match it to the frame when the frame arrives.

## 4. Measurement methods that worked

- **Input latency:** the app flashes the screen white on each key; the test presses a key on
  the PC and polls the receiver window's brightness (PrintWindow) until it flips. Make sure
  the receiver window keeps focus during the test (validate it first).
  Phase 5 version that worked: press the key with `keybd_event` only while the receiver owns
  the foreground window (check before every press: a stray key would go to another app), and
  poll a small patch of the flash area with `CopyFromScreen` from a per-monitor-DPI-aware
  thread (physical pixels) every 1-3 ms. PrintWindow can serve a stale flip-model frame.
  Per stage: the phone reports receive / dispatch / drawn / encoded for the frame that first
  shows the flash; the PC joins it with that frame's receive and present times through the
  control-channel clock sync.
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
  see USB devices, and Windows interop is off (no `adb.exe` from inside). Working method
  (verified 2026-10-15): on Windows, `adb kill-server` then `adb -a start-server` (listens
  on all interfaces, phone stays on USB); inside the box
  `export ADB_SERVER_SOCKET=tcp:<Windows WSL IP>:5037` (was `172.27.128.1`; it is the
  box's default gateway, or `vEthernet (WSL)` in `ipconfig`). The box's adb client must
  be protocol 41 (Windows: platform-tools 37.0.0); never run `adb kill-server` or
  `start-server` from the box, that would stop the Windows server. Redo `-a start-server`
  after a PC reboot. Fallback: adb over Wi-Fi (`adb tcpip 5555`, then `adb connect`).
  Developer-only; the user never does this.
- **The Windows adb server falls back to localhost-only** whenever it is restarted without
  `-a` (any plain `adb` command after the server died, Android Studio). Then the box times out
  on `172.27.128.1:5037` (round 5 lost its whole phone run to this). Before handing out a
  phone round, check `netstat -ano | grep LISTENING | grep :5037` shows `0.0.0.0:5037`.
- The box has no `/dev/kvm`: the emulator cannot run inside it. An emulator fallback must
  run on Windows, reached through the same adb server.
- `ssh-keygen -N ''` run through `cmd /c` sets a literal `''` passphrase: make keys in WSL.
- Android emulator: AVD `Pixel_8_Pro` has 6 GB RAM (`hw.ramSize=6144`); at 2 GB heavy work
  was killed. If its NFC/UWB crash loop starts, reboot it before measuring. Its UDP port
  redirect drops replies, so UDP/QUIC to the emulator does not work: use TCP, or the phone.
- ffmpeg: `C:\Users\Asus\AppData\Local\CapCut\Apps\9.1.0.3879\ffmpeg.exe`.
- The `gh` CLI is not installed.
- **Starting a WSL background watchdog from PowerShell (Phase 2, 2026-10-18):** the script
  must use `/mnt/c/...` paths (`/c/...` did not exist in the Ubuntu distro that day), and it
  must be started through a **script file** (`wsl.exe -e bash /mnt/c/.../launcher.sh` where
  the launcher does `nohup setsid bash watchdog.sh <log> &`). Passing the whole command via
  `wsl.exe -e bash -c "..."` or `Start-Process -ArgumentList ...` silently mangles the
  quoting (empty samples in the log, no error). The watchdog also caches the app uid at
  start (`run-as <pkg> id -un`) — restart it after every reinstall, or it counts zero
  processes forever.
- **PowerShell 5.1 strips embedded double quotes when passing arguments to adb** (Phase 3,
  2026-10-18 15:2x): `adb shell input text '"echo x > term.txt"'` reached the device shell
  UNQUOTED, so the device shell parsed `> term.txt` as its own redirect (cwd `/` ->
  `can't create term.txt: Read-only file system` on the host console) and the text never
  reached the terminal IME. Delivery recipe that avoids the whole class: **no quotes, no
  spaces** - spaces as `%s` (adb `input text` converts them), shell metacharacters
  backslash-escaped (`\>`, `\;`), then `input keyevent 66` for Enter. Same trap class as
  the screenshot `>` corruption (LESSONS 7): PowerShell and adb both re-interpret what you
  meant to pass through. (BUGS B-040.)
- **Emulator under low host RAM hangs, it does not fail** (Phase 3, 2026-10-18): with
  0.5-1.2 GB free (owner's browser holds ~6.5 GB), every relaunch froze before adb
  registration - qemu WS pinned at ~192 MB, TotalProcessorTime frozen (sample twice, 8 s
  apart, before declaring anything), 21-24 threads; later attempts paged the whole process
  out (WS 28-48 MB). A killed emulator leaves `Pixel_8_Pro.avd/hardware-qemu.ini.lock` (a
  DIRECTORY) and `multiinstance.lock` behind - delete both before relaunch or the next
  start may hang on the lock. Policy: start the emulator only above 3 GB free with
  `-memory 3072 -no-boot-anim -no-snapshot-load`, one launch, no relaunch loops; every
  hang goes to the incident log in docs/PHASE3_RESULT.md.

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

## 7. Phase 2 round 5 (2026-10-18)

- **Pulling binary evidence off the phone:** `adb exec-out run-as <pkg> cat <file>` through
  `cmd /c` (PowerShell `>` corrupts binary output). Device file names are case-sensitive and
  the harness writes some captures WITHOUT an extension (`NoF1npp`, 5336 B); a 57-byte "file"
  is an error message, not a capture. Compare captures by SHA-256: every no-window frame so
  far equals the black reference `527F19E0...` (5336 B); a real render differs (xeyes 9442 B).
- **`sh -c` quoting through PowerShell -> adb -> device-shell collapses:** `run-as pkgs
  sh -c "…'…'…"` ran only the first word as the script (a bare `find` listed the data dir).
  Pipe a here-string instead: `@'…'@ | adb shell run-as <pkg> sh`.
- **Reinstall changes the app uid:** restart EVERY watchdog instance (it caches
  `run-as id -un` at start) and re-check that none is left after cleanup.
- **Cold boot of a fresh install** burns ~5:00-5:17 (wineboot + prefix heal); a warm re-boot
  is < 15 s. Do not kill early.
- **The ntdll patch applies at BOOT, not at fetch:** verify the on-device `ntdll.so` sha
  AFTER a boot.
- **Box64's `src/wrapped/generated/` is regenerated at build time** (`rebuild_wrappers.py`):
  two builds from the same source can differ there (~577 KB), so "same source" != "same
  binary". Reproduce the baseline bytes before trusting any patch A/B.
- **Binary recipe checks that worked:** `readelf -sW` (dynsym identity), `cmp -l` (byte
  diffs), `objcopy -O binary --only-section=...` to localize diffs per section; map a
  stripped lib via an unstripped twin of the same build.
- **B-020's glibc-shaped `sa_mask`** (ANDROID branch of `src/include/signals.h`) makes plain
  `sa_mask`/`sigset_t` assignments uncompilable without the `sub1f` size-aware copies - proof
  that a build without sub1f cannot be the ancestor of the shipped lib (the hypothesis died
  on compile errors, before any phone time).
- **Wine 11.0 needs `libgcc_s.so.1`** for `x86_64-unix/ntdll.so`: absent from the 11.0 tree
  and the runtime-lib set; 11.19 does not need it. The app prepends `<wine>/lib64` to
  LD_LIBRARY_PATH - a staging spot. 11.0 predates SUD, and its runs show the 11.19
  c0000005/SIGSYS storm signature NOT at all - but NPP still never shows a window (exits by
  itself, code 159, in 9-23 s), so the no-window bug is not the SUD mismatch alone.
- **Untraced vs `+seh` runs are different experiments** (timing changes): only compare like
  with like.

### Round 5c (B-026 solved: the SysV-shared-memory path)

- **The real B-026 root cause was the shm path, not SUD:** winex11 wants SysV shared memory
  (XShm) and box64 must intercept it on BOTH routes - the raw guest syscalls (29/30/31/67)
  AND the wrapped-libc stubs (`my_shm*`). Raw-only builds stop the `exit=159` deaths but stay
  black at ~100% CPU (window blocker); libc-only builds still die. Both are needed (F4).
- **`exit=159` = 128+31 = SIGSYS** - a seccomp kill of a guest syscall box64 forwarded
  unhandled. `COMCTL32.dll.381 aborting` was logged BEFORE the SIGSYS death (the abort path
  releases the kill); it disappeared once F4 landed - so that line was a symptom, not the cause.
- **Xvfb must run `-extension MIT-SHM`**: proven by a raw `QueryExtension` (MIT-SHM absent ->
  all-zero reply; the flag verifiably disables it). XTEST stays enabled.
- **XTest wire format on this Xvfb (raw protocol, phone-verified):** the `QueryExtension`
  request needs its 2 pad bytes (a 14-byte request deadlocks -> SocketTimeoutException); the
  reply carries `present` at byte 8 and the major opcode at byte 9 (mainline is 1/8 - keep the
  fallback; this server pads 3 extra bytes at the end); first-event is byte 10. A 16-byte
  `FakeInput` draws BadLength and types nothing; 36 bytes (all 4 event slots) works. Synthetic
  keycodes must be >= 8.
- **Stuck-key flood (B-032):** a synthetic KeyRelease can be lost when the same key is pressed
  twice within the 660 ms auto-repeat delay; the server then repeats the key forever (observed:
  4,168 `h`, ~47% CPU). Always sweep `KeyRelease` across all keycodes before and after typing.
- **Typing into the wine window needs no focus work** with focus=PointerRoot: with the pointer
  over the window, synthetic events route to it.
- **Cold-start race (B-031):** NPP launched as the first wine client of an app generation dies
  in ~4-5 s (loader stack overflow); `wineboot` first, then NPP - 5/5 stable both ways.
- **The laptop raw-protocol bench is a DIAGNOSTIC only.** Gate evidence must come from the
  app's own broadcast path; label bench results as diagnostics in the report.
- **The dev watchdog caches the app uid at start:** the round-5 instance (start user=u0_a467,
  06:31) was still alive in round 5c (uids u0_a472-475) and read `procs=0` the whole time.
  After every reinstall, restart it and check the log's `start user=` line - the in-app guard
  may be the only live cap otherwise.
- **LMK on this phone:** MemFree sits at ~50-70 MB even with MemAvailable ~2.5 GB, so a kill
  can land when MemFree dips (`lowmemorykiller ... free 18160` at 10:11:50), taking app + wine
  children + Xvfb down together. A documented clean kill is acceptable for the survival item;
  the product answer is the Phase 4 foreground service (B-027).
