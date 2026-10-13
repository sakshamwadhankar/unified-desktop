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

## 12. Phone-first resource governor
- The user's own phone use comes first (R5). The desktop runs at normal priority, below
  whatever the user is doing on the phone.
- Low memory: stop rendering hidden windows, then pause background desktop apps, then warn
  the user on the PC.
- Heat: read the phone's thermal headroom; when it gets hot, lower fps, then resolution.

## 13. The PC lock (receiver)
States: **Normal -> Locking -> Locked -> Exiting -> Normal.**
- Locking: switch to our own Windows desktop, full screen, install the keyboard hook, hide
  the taskbar, apply the session policies.
- Exiting (Exit button): remove everything in reverse order and switch back.
- Crash safety: write what was changed before changing it; on the next start, if a
  previous session did not exit cleanly, restore it first.
