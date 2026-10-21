# ALGORITHMS: the key methods

Each method: what it does, and how. Numbers are starting points; phases measure and tune them.

## 1. Frame pipeline: draw only what changed
- Every window surface reports when it changes ("damage").
- The compositor redraws a frame **only when something changed**, and only the changed
  windows, straight into the encoder's input surface (GPU only, no CPU copy).
- No change means no new frame, except a **keep-alive frame** every ~1 s, so a receiver that
  just joined, or lost a frame, gets a picture. The Qualcomm encoder will not repeat frames
  by itself (LESSONS.md).
- The frame rate adapts: up to 60 fps while things move, down to near zero when idle. Input
  triggers an immediate frame; never wait for the next tick.

## 2. Encoder settings (low latency)
- H.264, hardware, low-latency mode, **no B-frames**, short GOP.
- Rate control CBR-like with a small buffer, so one frame never takes long to send.
- **Keyframe on demand:** the receiver reports a lost or corrupt frame and the phone sends a
  keyframe at once. Periodic keyframes only as a safety net.

## 3. Adaptive bitrate
- Every ~250 ms, measure the link: round-trip time, bytes delivered, losses.
- Raise the bitrate slowly while the link is healthy; cut it fast when delay or loss grows.
- USB starts with a high ceiling (it is fast and steady); Wi-Fi starts lower.
- Resolution steps down (1080p -> 900p -> 720p) only if bitrate alone is not enough.

## 4. Input path
- Sent **immediately**, on its own channel, with the **highest priority** on the link.
- Mouse moves may be **coalesced** under load (only the latest position matters).
- **Key and button presses/releases are never dropped or reordered.** Dropping a release
  caused stuck keys and stuck drags before (LESSONS.md).
- Each event carries a timestamp, so latency can be measured end to end.

## 5. Windows: focus, hit-testing, routing
- The shell keeps a **z-order** (which window is on top) and a **focused** window.
- A mouse event: PC coordinates -> desktop coordinates -> the top-most window under the
  pointer -> that window's own coordinates -> delivered to that window: an Android app's
  virtual display, the X11 server, or a WebView.
- Keyboard events go to the focused window. Clicking a window focuses it and raises it.

## 6. Compositing
- Each window is a GPU texture (from its surface). The compositor draws, back to front: the
  wallpaper, the windows, their frames and shadows, then the taskbar and the cursor.
- **One virtual display per Android window**, sized to the window, so each app renders only
  the pixels it needs (ARCHITECTURE Q7).
- Minimized or fully covered windows stop rendering.

## 7. Binary translation (Box64)
- **Dynamic recompilation:** blocks of x86 code are translated to ARM the first time they
  run, then cached and reused, so hot code runs near native speed.
- **Wrapped libraries:** calls into common libraries (graphics, sound, the C library) go to
  the phone's own native versions instead of being translated. This is a big part of its
  speed.
- Code that programs generate while running (for example JavaScript engines) is
  re-translated when it changes.
- **Per-app profiles:** translator settings tuned per app (the Winlator approach), stored in
  App Center's compatibility data. FEXCore as an alternative where Box64 fails.

## 8. App Center: picking the best version
For an app name, collect the available versions and rank them:
1. built-in full-speed tool
2. Android app from Google Play
3. web version
4. Linux x86-64 through Box64
5. Windows `.exe` through Wine + Box64

Pick the highest-ranked version whose rating is **Works** on a phone like the user's. Ratings
come from our own compatibility list, built by testing apps on the test phone.
Windows installers come from winget manifests (official URL + SHA-256, checked before
running). Android apps come from Google Play.

## 9. Pairing and security
- First connection: a key exchange; both sides show the same **6-digit code** made from the
  shared key; the user confirms on the phone.
- The PC's identity is stored; next time it reconnects silently. The phone can forget a PC
  at any time, which cuts its live connection too.
- All traffic is encrypted.

## 10. Choosing the link, and failover
- Cable plugged in: use USB (AOA; tethering as fallback).
- No cable: Wi-Fi on the same network, found automatically.
- Link lost: show "Reconnecting...", keep the session alive on the phone, reconnect on
  whichever link appears, request a keyframe, continue. The phone side never restarts the
  apps because of a link loss.

## 11. Keep-alive and recovery
- While a PC is connected: foreground service + partial wakelock + the OEM background
  setting (LESSONS.md).
- A watchdog checks the engines' CPU time advancing, not just that they exist. If Android
  killed an engine: restart it and reopen its windows where possible, and tell the user.
- Phase 10 keeps the desktop runtime in the service after the link ends. The next connection
  must use the same trusted-PC identity; a different PC is refused while a live desktop exists.
  The receiver retries the pinned address for 10 seconds and requests a keyframe after attach.
- The receiver's main rustls reader remains blocking. A cloned socket is used only to close a
  dead connection after 15 seconds without inbound data; the normal stream never receives the
  old 2 ms read timeout. Clock-sync control messages are emitted from the receive loop as well
  as the outer loop so a video stream cannot starve the phone's inbound watchdog.

## 12. Phone-first resource governor
- The user's own phone use comes first (R5). The desktop runs at normal priority, below
  whatever the user is doing on the phone.
- Low memory: stop rendering hidden windows, then pause background desktop apps, then warn
  the user on the PC.
- Heat: read the phone's thermal headroom; when it gets hot, lower fps, then resolution.
- The Phase 10 policy is a pure ordered state machine tested on the JVM. Android applies one
  action per pressure sample: stop hidden windows, 30 FPS, 15 FPS, lower-resolution request,
  then a PC warning. The lower-resolution request is intentionally explicit but remains an
  open implementation/gate item until it changes encoder output and is measured.

## 13. The PC lock (receiver)
States: **Normal -> Locking -> Locked -> Exiting -> Normal.** Only those edges are legal;
an invalid transition is an error, and `exit()` is idempotent (`Normal`/`Exiting` do nothing).
- **Session guard:** `view` takes the single-instance mutex (`Local\udreceiver-lock-single`)
  and the named exit event **before** recovery, then passes that guard into `Lock::prepare`,
  which holds it through desktop and journal creation. `lock-selftest` does the same;
  `lock-recover` and the crash guard take the same mutex for their work. A process that
  cannot take it refuses before reading or writing the journal, switching the desktop, or
  connecting to the phone. `--no-lock` holds the guard for the whole view, so a second view
  is still refused.
- **Locking** (`Lock::prepare`, on the thread that will own the windows, before any window
  or hook exists on it): start the crash guard on the default desktop, create
  `udreceiver-lock-<pid>` on `WinSta0`, start the watchdog and status threads,
  `SetThreadDesktop`, create the journal. `Journal::start_at` refuses a dirty or unparseable
  existing journal instead of overwriting the only undo record.
- **Engage** (`Lock::engage`): create one black `WS_POPUP` window per monitor, create the
  video window with `--x/--y/--w/--h` (primary by default) and the Exit overlay at the
  primary monitor's top-right (120x40, DPI-scaled, `WS_EX_TOPMOST|TOOLWINDOW|NOACTIVATE`),
  install `WH_KEYBOARD_LL` **on the lock desktop**, then `SwitchDesktop`.
- **Blocked keys** (swallow both `WM_KEYDOWN` and `WM_KEYUP`, return 1, count per key):
  LWIN, RWIN, Alt+Tab, Alt+Shift+Tab, Alt+Esc, Ctrl+Esc, Ctrl+Shift+Esc, Alt+F4. The hook
  does not look at the injected flag, so a test key and a physical key follow the same
  rule. Mouse messages always pass.
- **Exit sources:** the Exit overlay (`request_exit()`), `WM_CLOSE`, Esc while locked, the
  pipeline ending (phone gone, `--seconds`), the watchdog (`--lock-max-seconds`, also
  force-switches if the main thread stops draining), and internal error. All of them set
  the same flag and end in `Lock::exit()`; no `PostQuitMessage` path skips it.
- **Exit order:** unhook -> `SwitchDesktop(Default)` -> **verify that the input desktop is
  `Default`**, retrying `ensure_default_input` before any cleanup -> restore policies ->
  journal clean/delete -> close handles -> stop the lock threads; the caller then stops the
  pipeline. If `Default` cannot be verified, the journal, Exit window, watchdog and guard
  stay in place; the mutex is kept until the process exits so a second view cannot race the
  armed watchdog. A failed policy restore also leaves the journal dirty. Both are fail-closed.
- **Journal:** line-based `clean=0`, `desktop=<name>`, `pid=<owner pid>`, then one line per
  value in the form `policy=<root>|<path>|<name>|<type>|<value>` with its original value.
  It is flushed **before** a change is applied and the file is deleted before the journal is
  marked clean. The parser is strict: a missing `clean=`/`desktop=`, duplicate, malformed or
  unknown line is an error; a missing `pid=` is accepted as an old journal. An unreadable
  journal is left byte-for-byte untouched.
- **Recovery:** under the session guard, restore a dirty journal and return any leftover
  `udreceiver-lock-*` input desktop to `Default`, even when the journal is clean, missing or
  unreadable (in the unreadable case the journal itself is not touched). A recovery error
  aborts `view`/`lock-selftest` before the phone connection or lock creation.
- **Crash guard:** Windows does not return input to `Default` when a process on a private
  desktop is killed (proven 2026-10-21). `udreceiver lock-guard --parent <pid>` waits on the
  lock PID, takes the mutex, and restores only a journal whose `pid` and
  `udreceiver-lock-<pid>` desktop both belong to that parent. If only the parent's desktop is
  left, it switches that to `Default` and leaves a foreign journal alone; it never switches
  another private desktop. If the guard is also killed, the next `view` recovers first.
- **Policies** (only elevated **and** `--policies`): Task Manager, lock workstation, logoff,
  fast user switching and change-password values, journaled and restored. Not applied in
  the non-admin dev tests; the HKCU round-trip is unit-tested.
- **Multi-monitor:** black windows cover every monitor, video stays on the chosen monitor's
  rect, Exit stays on the primary monitor. Only a single-monitor laptop was available, so
  this layout is code-only.
