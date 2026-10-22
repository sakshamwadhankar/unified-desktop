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

## 2. Quality profiles (implemented and tuned in Phase 12)

| Profile | When | Desktop | Video |
|---|---|---|---|
| **Light** | software encoder, weak phone, thermal status >= 3, or Wi-Fi that is not 5 GHz | 1280x720, up to 30 fps | H.264, 6 Mbps |
| **Standard** | mid phone, USB or good 5 GHz Wi-Fi, thermal status 2 | 1920x1080, up to 30 fps | H.264, 14 Mbps |
| **Smooth** | strong phone on USB, thermal status <= 1 | 1920x1080, up to 60 fps | H.264, 24 Mbps |

`Phase12Profiles.choose()` is a pure function with JVM tests (`Phase12ProfilesTest`): a
software encoder, a non-strong phone or thermal status 3+ picks Light; thermal status 2 picks
Standard; a non-USB, non-5 GHz link picks Light; USB on a strong phone picks Smooth. The
session still starts from the existing 1080p/60 defaults and the governor's real
`LowerResolution` step rebuilds it at 960x540 when the thermal policy asks; applying a profile
at session start is follow-up work (the function is ready and tested).

Measured basis (phone rows are labelled; the rest are `emulator`, `proxy` or `PC-only`):

- 1080p hardware stream on the 2-core proxy: **35.65 fps average / 45.9 max**, `dropped=0`,
  decode **21.29 ms**, CPU **34.9 % of two cores**, working set **131 MB** (`p12/a4-hw-after.log`).
- Real 1920x1080 -> 960x540 governor rebuild on the emulator: desktop and the Settings
  window stay, input stays in the 1920x1080 link space, PC `dropped=0`
  (`p12/view-lowres.log`, `p12/lowres-after.png`).
- Idle 1080p receiver: **0.38 % of one core, 127 MB, 0 dropped** over 60 s
  (`p12/a1-after.log`).
- Software H.264 fallback after the B-104 fix: **36.68 fps average / 40.9 max**, 1070/1070
  frames presented, `dropped=0`, decode **16.75 ms**, CPU **33.3 % of two cores**, working set
  **~96 MB** (`p12/a4-sw-after3.log`). The fix allocates the output sample the software MFT
  asks for; the hardware path is unchanged.
- **Phone `2c0f6edc`, final app/receiver:** connect 0.944 s visible; typing p50 90.8 ms on the
  USB adb path (baseline 196.3 ms) and 91.2 ms on the USB-tether dev path (baseline 129.7 ms);
  drag 42.0 fps average / 55.9 max, `dropped=0` at the governor's 960x540; idle baseline
  0.62 % of one core / 123.9 MB / 0 dropped. The governor dropped to 960x540 automatically at
  thermal status 3 in 0.2 s with the desktop and windows kept; the Settings text stayed stale
  (B-105). Notepad++ 15.95 s baseline -> 11.95 s with `p12warm:wine` (28.23 s cold without it).
  Wi-Fi was OFF by owner request, so no Wi-Fi latency number exists (SKIPS.md).

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
