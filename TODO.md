# Phase 11 round 3: to-do
Done 3 of 6 | Failed or skipped 3 (item 3 no cover, item 4 phone USB lost, item 5 -> B-103 per manager 14:01) | Updated 14:23 | Now: done (report in chat; STATE.md current)
OWNER: cover was requested for 14:12; the 14:11:30-14:12:35 sampler saw no cover (numbers below). If you covered later, tell the manager; the recorded window is closed.
OWNER: the phone's USB link dropped at ~14:13-14:15 (adb: no devices; view: window closed; VSTAT fps 0). Zoom could not be opened. Reconnect USB if you want the phone left clean (stay_on=2 -> 0) and the Zoom check done.

1. [x] Start: build, install, stayon usb, link, view --no-lock on the dev path; receiver open.
   - Android build OK, cargo 76/0; consent tapped (RECORD_AUDIO already granted; MediaProjection
     "Entire screen" -> Cast) twice (the second after the reinstall).
   - Audio real: `AUDIO recv/played` 100/s, depth 8-11, age_ms 12-22, devbuf_ms=60, underruns 0
     during the measured events.
   - Incident 14:03: before the last consent a notification shade was foreground; two blind taps
     landed there. No phone input since without confirming our UI in front (`am start` used).
2. [x] B-101 on the phone: 10 s 1 kHz tone: packets 1000 sent/1000 received, late=0, dropped=0,
   underruns 2 total (both at start), depth 8-10, age_ms 13-15. Clicks: onset play_age
   109.4 / 128.2 / 131.3 ms, median 128.2 ms (capture->PC play); page `play()`->PC play
   410 / 476 / 481 ms (WebView Audio startup ~300 ms); 0 underruns during the clicks.
3. [x] Webcam cover window 14:11:30-14:12:35: FAILED as proof. Stream live the whole time
   (640x360, 11 fps, frames 3619->4100, PC window open); brightness baseline 0.62-0.66,
   largest 5 s drop 0.628->0.600 (-0.028) at 14:12:01-05, then 0.618 flat 14:12:22-36,
   recovery 0.642 at 14:12:37. No dip to black and no large frame change -> no deliberate
   5 s cover happened in the window. Evidence `p11c/cam-cover.log`.
4. [x] Zoom page: BLOCKED at 14:15 - the phone dropped off USB (`adb devices` empty, view
   `window closed`, VSTAT fps 0.0 at 14:13-14:15). No page could be opened; no phone result.
   SKIP row: needs the phone connected and `https://zoom.us/test` in our Browser.
5. [x] Android -> Notepad++ paste: NO NEW WORK (manager 14:01); B-103 row written from round-2
   findings (OPEN, next round).
6. [ ] Close-out from 14:25: gates, docs, cleanup, STATE.md, report; end lines exact.

Details (append-only):
