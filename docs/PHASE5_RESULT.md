# Phase 5 result: video + input (phone desktop on the PC, mouse and keyboard back)

> Every number here was measured on the **real phone** (OnePlus CPH2619, adb serial
> `2c0f6edc`, Android 16) with the laptop (Windows 11, Intel UHD + NVIDIA RTX 4050) on
> 2026-10-19 between 01:42 and 02:23. No emulator was used.
>
> Who did what: the coding agent (OpenCode, DeepSeek flash) built the pipeline and got the
> first picture and typed text on the PC (01:42-01:50), then stopped at 01:51 when every free
> model hit its usage limit. The owner was asleep. Under the owner's overnight instruction
> ("do whatever you want; complete Phase 5 by morning") the manager finished the phase:
> fixes, measurements, gates and these docs.

## 1. Gate

| Gate line (PLAN.md) | Result | Evidence |
|---|---|---|
| Phone desktop full screen on the PC | PASS: 1920x1080 test desktop, borderless full screen on the laptop panel | `evidence/phase5/p5-wifi-desktop.png`, `p5-usb-desktop.png` (gitignored PNGs) |
| Hardware encode (phone) | PASS: `c2.qti.avc.encoder` (Qualcomm), H.264 baseline, CBR 12 Mbps, low-latency on | phone logcat `encoder name=c2.qti.avc.encoder` |
| Hardware decode (PC) | PASS: Microsoft H.264 MFT with a D3D11 device manager on Intel UHD Graphics, NV12 D3D11 surfaces, `CODECAPI_AVLowLatencyMode=1`, flip-model swap chain | receiver log `DECODE hardware=true path=D3D11 NV12 surface` |
| Mouse and keyboard work | PASS: cursor follows, click focuses the EditText, drag moves the box (1,377 box moves), typed text incl. Shift appears in a real Android EditText | `p5-wifi-after-stress.png`, `item6-typed.png` |
| fps while something moves / while dragging | PASS: ~57 fps both (cap 60) | VSTAT lines in `p5-wifi-fps-drag-stress.txt`, `p5-usb-tethering.txt` |
| Typing latency over Wi-Fi and USB | MEASURED (see 2). Above the PRD target of 80 ms: Phase 12 | `p5-wifi-latency.txt`, `p5-usb-tethering.txt` |
| No stuck keys after fast typing and dragging | PASS: 300 fast key presses (20 % with Shift) + 30 fast drags, then the phone reports `keysDown=0 buttonsDown=0` | `p5-wifi-fps-drag-stress.txt` |

## 2. Numbers

| Link | fps moving | fps dragging | typing latency p50 / p95 (end to end, n=50) | internal total median | phone CPU (our app) | phone PSS | verified on |
|---|---|---|---|---|---|---|---|
| Wi-Fi (same home LAN) | 57 | 57 | **122.7 / 172.9 ms** | 105.1 ms | 130-151 % (of 800 %) | ~186 MB | phone |
| USB tethering (RNDIS) | 57 | 57 | **106.5 / 148.8 ms** | 80.2 ms | not sampled | not sampled | phone |

Per-stage medians (phone `LAT` report joined with the PC's frame times, one clock via the
control-channel clock sync, offset error about +-RTT/2 = +-8 ms on the cross-device stages):

| Stage | Wi-Fi | USB tethering |
|---|---|---|
| PC send -> phone receive | 21.7 ms | 15.8 ms |
| phone receive -> view dispatch | 1.0 | 0.9 |
| dispatch -> flash drawn | 6.1 | 6.1 |
| drawn -> encoder output | **46.1** | **42.1** |
| encoder output -> PC receive | 21.7 | 6.2 |
| PC receive -> presented | 3.7 | 3.5 |

End to end (external) is longer than the internal total by ~18-26 ms: key injection to the
window message, the display's scan-out after Present, and the 1-3 ms screen-capture poll.

Battery temperature stayed at 36.5 C during the 2.5-minute Wi-Fi run (level 5 %, charging).

## 3. Method

- **End-to-end typing latency** (OPTIMIZATION 6, LESSONS 4): a throwaway harness on the PC
  presses `A` with `keybd_event` only while the receiver window has the foreground (checked
  before every press), then polls a 260x40 px patch inside the phone desktop's flash area on
  the PC screen (`CopyFromScreen`, physical pixels) until more than half of it is white. 50
  samples, 250-500 ms apart. The flash lasts 120 ms on the phone.
- **Per-stage**: on each key-down the phone records receive, dispatch and the moment the
  flash is first drawn (`onDraw`), then reports the first encoder output whose presentation
  time is not earlier than that draw (`lat ... seq`). The PC joins it with that frame's
  receive and present times. The match can be one frame early.
- **fps**: the receiver's per-second count of presented frames (VSTAT).
- **Stuck keys**: the phone's own held-key bookkeeping (`keysDown`, `buttonsDown`) queried
  through the debug harness after the stress.
- **USB**: tethering switched on and off with taps on the phone's Settings switch; the
  measurement ran inside a 2 min 21 s window (no admin, so the laptop's default route moved
  to the tether for that time, then went back to Wi-Fi; WARP untouched).

## 4. Fixes made while measuring (manager, 2026-10-19)

1. **Encoder fed faster than it drains (~200 ms of lag).** The GL compositor drew on every
   virtual-display frame and again on every input event, ~84 fps, and latched only one
   queued buffer per draw. The encoder's input queue stayed full, so every frame waited
   ~200 ms. Fix: pace submissions to 60 fps (a deferred draw takes the newest content),
   latch every queued buffer per draw, and drop the forced redraw on input (it only encoded a
   duplicate of the old picture). Typing latency p50 went 290 -> 143 ms.
2. **Qualcomm low-latency mode was never enabled.** `c2.qti.avc.encoder` lists
   `vendor.qti-ext-enc-low-latency.enable` in `supportedVendorParameters`; it is now set,
   and `KEY_FRAME_RATE` matches the 60 fps pacing. p50 143 -> 118-123 ms (encoder stage
   ~65 -> ~45 ms).
3. **The phone's latency probe never fired** (it compared milliseconds with microseconds);
   it also took its "drawn" time at dispatch, not at the real draw. Both fixed; the PC parks
   a report until its frame arrives (the report rides the higher-priority control channel and
   used to arrive first).
4. **Keep-alive frame never sent** (ALGORITHMS 1): the 1 s keep-alive called the draw without
   `force`, so a static desktop produced no frames. Now forced when nothing was drawn for 1 s.
   Code-only: the test desktop always moves, so this path was not exercised on the phone.
5. **Release-all only cleared bookkeeping**: it now sends a real `ACTION_UP` for every held
   key and cancels a held touch in the EditText.
6. Receiver: VSTAT printed microseconds labelled `ms`; help text named the program `udrxcb`.
7. B-044: the phone logs `PAIR_YES action received` / `PAIR_NO action received` (code-only).

## 5. What is not done

- **Latency target**: 107-123 ms p50 against the PRD's 80 ms. The big stages are the
  encoder (~45 ms) and, on Wi-Fi, the transport (~43 ms both ways). Ideas for Phase 12: a
  lower resolution or bitrate, slice output (`vendor.qti-ext-enc-slice.spacing`), a
  dedicated input-send thread on the PC, and present without waiting for vblank.
- **CPU**: our app uses 130-151 % CPU while the test desktop animates; Phase 12.
- **B-039 (keyboard into a WebView)**: not probed in this phase; the EditText route works.
- **Video starts for every paired session**, including `pair` and `bench`; the receiver
  should ask for video explicitly (BUGS).
- **The desktop needs our activity on the phone screen.** The `Presentation` is created
  from the resumed HarnessActivity (Phase5Host); with no resumed activity the session runs
  without video. The rule "the phone stays fully usable" needs a desktop that lives without
  an activity in front: Phase 10 (BUGS).
