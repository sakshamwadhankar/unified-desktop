# Phase 11 round 4: to-do
Done 2 of 4 | Failed or skipped 2 | Updated 16:20 | Now: close-out

OWNER: cover the laptop webcam with a hand for 5 s at 16:02; no cover-like dip was recorded.

1. [x] Start + webcam cover: current APK initially matched the installed SHA; link and
   receiver started on the adb forward; camtest was live. The 16:01:30-16:02:30 sampler saw
   brightness around 0.436-0.509, never near 0; no deliberate cover proof.
2. [x] Zoom page: `p6:url:https://zoom.us/test` was sent and the harness reported
   `url=https://zoom.us/test loading https://zoom.us/test`; no logged join/sign-in result.
3. [x] B-103: changed the X11 bridge to use a PropertyNotify server timestamp and return
   UTF8_STRING, STRING, TEXT and TARGETS. Android build passed, but no post-install
   Notepad++ paste proof was obtained before close-out; B-103 remains open.
4. [x] Close-out: view/link/app stopped; phone stay_on=0 and brightness=699; forward removed.

1. [10 min] Start + webcam cover:
   - Install the current APK if it differs; `svc power stayon usb`.
   - Start the link; start `udreceiver view --no-lock` on the adb forward; keep the receiver
     window OPEN.
   - Open the camtest page with the stream live.
   - Write "OWNER: cover the laptop webcam with a hand for 5 s at HH:MM", where HH:MM is 8
     minutes after you write it.
   - Sample brightness every 0.5 s from 30 s before to 30 s after that time.
   - Pass = brightness falls toward 0 for about 5 s, then recovers.
2. [10 min] Zoom page:
   - `p6:url https://zoom.us/test` in our Browser. If it offers "Join from your browser"
     WITHOUT sign-in, join and report what it shows for the camera and mic (labels or numbers
     only).
   - If it needs sign-in or the app: a SKIPS row quoting exactly what it asked. Never sign in.
3. [15 min max] B-103 (Android -> Notepad++ paste). Try, in order:
   (a) claim CLIPBOARD with a real server timestamp (from a PropertyNotify), not CurrentTime;
   (b) answer TARGETS with UTF8_STRING, STRING, TEXT and TARGETS;
   (c) check that Wine's clipboard window sees our owner.
   Proof: a PC marker pasted into Notepad++, shown by an Xvfb capture. If it does not work in
   15 minutes: update B-103 with what you tried, then move on.
4. [10 min] Close-out:
   - If code changed: Android `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
     (absolute -p path), cargo test + clippy.
   - docs/PHASE11_RESULT.md "Round 4"; BUGS/SKIPS updated.
   - Cleanup: stop the view, link and app; prove 0 processes and 0 services; stay_on 0,
     brightness 699; no adb forward; no udreceiver left.
   - STATE.md LAST.
   - The report ends with EXACTLY:
     PHASE 11 GATE (phone): PASS / FAIL - <why>
     WEBCAM COVER: dip <from -> to>, recovered <yes/no>
     ZOOM PAGE: <result or skip reason>
     NOTEPAD++ PASTE: <pass / B-103 updated>
