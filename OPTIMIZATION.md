# OPTIMIZATION: fast on a cheap phone, light on an old PC

**Rule zero: measure first.** Every optimization needs a before and an after number from a
real run on the phone. Changing performance settings without numbers has had to be undone
before (LESSONS.md).

## 1. The optimized design we use

| Idea | What it means | Why it is fast |
|---|---|---|
| **Two speeds** | The most-used tools (VS Code engine, terminal, Python, Git, browser) ship inside our app as native ARM code. Everything else (any `.exe`, x86 Linux apps) goes through the translator. | Native code runs at 100% phone speed. Only the long tail pays for translation. |
| **No VM** | Apps start directly, as normal processes and windows. | No guest OS to boot, no memory reserved for a guest, no extra layer on every memory access. |
| **Zero-copy video** | Window surfaces -> GPU compositor -> encoder input surface -> hardware encoder. | The CPU never touches pixels. |
| **Encode only on change** | No change, no frame (plus a keep-alive). | An idle desktop costs almost nothing. |
| **Input first** | Input has its own highest-priority channel and triggers an immediate frame. | Lowest typing latency. |
| **USB first** | Cable in: use USB. | Lower and steadier delay than Wi-Fi, and it charges the phone. |
| **Right-sized windows** | One virtual display per Android window, sized to the window; hidden windows do not render. | Fewer pixels to draw and encode. |
| **Best version per app** | App Center prefers full-speed, then Android, then web, then translated versions. | Users get the fastest working version automatically. |

## 2. Quality profiles (provisional; tuned in Phase 12)

| Profile | When | Desktop | Video |
|---|---|---|---|
| **Light** | low-end phone, old PC, or weak Wi-Fi | 1280x720, up to 30 fps | H.264, 4-8 Mbps |
| **Standard** | mid phone, USB or good 5 GHz Wi-Fi | 1920x1080, up to 30 fps | H.264, 10-20 Mbps |
| **Smooth** | strong phone, USB | 1920x1080, up to 60 fps | H.264/HEVC, 20-40 Mbps |

The phone picks a profile automatically from its hardware, the link and its temperature;
the user can override it in Settings.

## 3. Phone side

- **Translator:** dynamic recompilation on, wrapped native libraries wherever possible,
  per-app settings profiles, FEXCore where Box64 is weak.
- **3D in Windows apps** (later): DirectX -> Vulkan through DXVK/VKD3D, on Turnip for Adreno.
- **Memory:** a budget for the desktop, so the user's own phone apps keep working. Stop
  rendering hidden windows, pause background desktop apps when memory runs low.
- **Heat and battery:** read the thermal headroom; step fps and resolution down before the
  phone throttles. On USB the phone charges.
- **Startup:** reconnecting resumes the running session (no cold start), aiming at <= 5 s.

## 4. PC side (old PCs)

- **Hardware H.264 decode** (Windows' built-in decoder, GPU-accelerated on almost every PC
  since about 2010). Software decode at 720p as a fallback.
- **Present immediately:** no extra frame buffering; a low-latency swap chain.
- A small native program: little RAM, no browser engine, no runtime to install.
- The receiver stays responsive even if the phone stalls (the Exit button always works).

## 5. Link

- **USB:** AOA bulk transfer, or tethering as fallback. High bitrate ceiling.
- **Wi-Fi:** 5 GHz recommended; adaptive bitrate; keyframe on loss; input never queued
  behind video.
- Separate channels so a big video frame never delays a key press.

## 6. How we measure (every phase that touches speed)

| What | How |
|---|---|
| Typing latency | The app flashes the screen on each key; the test presses a key on the PC and times until the receiver window brightens. Also measure each stage separately: input send, app reaction, encode, transport, decode. |
| Frame rate | Frames presented per second on the PC while dragging a window. |
| CPU / RAM / heat | Phone `/proc` and thermal readings during a fixed 5-minute workload. |
| App start | Click to first window drawn. |
| Phone stays usable | WhatsApp and a call during a session: no freezes, no kills (`am_kill`). |
| Survival | 2 hours on charger: CPU time advancing the whole time. |
