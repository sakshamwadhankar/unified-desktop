# BUGS

Every bug found by anyone (the coding agent, the manager, or the owner) is logged here.

**Rules**
- **The coding agent never fixes a logged bug on its own.** It logs it and reports it.
- **The manager decides** for each bug:
  - **FIX NOW**: goes into the next prompt, with how to fix it.
  - **FIX LATER**: names the phase.
  - **WON'T FIX**: gives the reason.
- When a fix is verified, the manager moves the row to *Fixed* with the commit.
- Severity: **blocker** (stops a phase gate), **major** (wrong behaviour a user would hit),
  **minor** (cosmetic or rare).

## Open

| ID | Found (date, phase, by) | What is wrong | How to reproduce / evidence | Severity | Manager's decision |
|---|---|---|---|---|---|
| B-003 | 2026-10-15, P1 r3, agent | BlackBox `getLaunchIntentForPackage` returns null for Chrome, though Android resolves `com.google.android.apps.chrome.Main`. | Round 5 host audit found all five Chrome split names and alias target `org.chromium.chrome.browser.ChromeTabbedActivity`; BlackBox returned no virtual ActivityInfo for either alias or target and launch ended with `VIRTUAL_LAUNCH_COMPONENT_MISSING`. Firefox was installed from Google Play and launched, but remained on onboarding rather than a web page. Evidence: `evidence/phase1/round5_phone_results.txt`. | major | **FIX LATER** (Phase 7, App Center compatibility): Firefox covers the Phase 1 browser gate. |
| B-004 | 2026-10-15, P1 r3, agent | The guest process exited within 60 s of the measurement run; the cause was not recorded. | `evidence/phase1/round3_t4_phone_excerpt.txt`; round 4 ten-minute idle survival did not reproduce the exit: `evidence/phase1/round4_survival_phone_excerpt.txt`. Round 6 also encountered a phone call followed by adb disappearance before the required moving/survival sample completed; no guest kill attribution is claimed. | major | **FIX NOW** (round 4): find the cause before measuring. |
| B-007 | 2026-10-16, P1 r7, agent | With the owner-tag, layout-param normalization, and detached-window callback changes, Firefox accepts the URL and the old relayout crashes do not recur, but the post-Go guest frame is a dark blank content area; Example Domain body text is not visible. | Phone run and hashes: `evidence/phase1/round7_t2_phone_results.txt`; `round7_t2_moving-frame-4.png`. The browser process remains present, but no page-body frame was captured. | major | **FIX NOW** (round 8). Manager checked: the host has `android.permission.INTERNET: granted=true`, so it is not a missing permission. Find out first whether Gecko's content/GPU child processes (isolated services) start under the engine at all, then whether the GeckoView SurfaceView is composited. `about:robots` (no network) separates loading from drawing. |

## Fixed

| ID | What it was | Fixed in (commit) | Verified on (phone / emulator / code-only) |
|---|---|---|---|
| B-001 | Guest decor kept the phone size (2400x1080) on our 1280x720 display, so it was clipped. Fixed by an engine callback before the guest onCreate (patch in `android/engine/fetch-source.sh`) and a public `Resources.updateConfiguration` fallback. | round 5 commit (this review) | phone: manager re-run, display 66, `B1_BEFORE_MOVE size=1280x720`, whole Tasks screen, frame sha256 `31b931a2...` (same as the agent's) |
| B-002 | Guests saw no usable Google Play services ("Update Google Play Services", then `Play Store signature is invalid`, error 9). Three engine fixes in `android/engine/fetch-source.sh`: the GMS APK was misread as an APK set; a hard-coded fake Play Store PackageInfo had no signatures (now the normal virtual path); virtual `getPackageUid` now returns the virtual uid. | round 6 commit (this review) | phone: guest and host SHA-256 match for com.android.vending and com.google.android.gms (`f0fd6c5b...`); Tasks reaches its own Sign in screen (`round6_tasks_after_uid.png`, sha256 `9a8a6368...`). Residual `does not belong to` lines in logcat remain; not blocking. |
| B-005 | Typed text could not reach a Compose field in our Presentation (key events returned false). Now sent through the focused view's `InputConnection` (`commitText`, then a deferred IME action). | round 7 commit (this review) | phone: `round7_t2_final-frame-2.png` (sha256 `158190a8...`) shows `https://example.com` typed in Firefox's address bar before Go |
| B-006 | Firefox crashed on relayout of the moved decor (`WindowManager$LayoutParams` -> `MarginLayoutParams`), plus missing ViewTree owners and a detached-window crash. Fixed in `GuestPresentationHost.java`: owner tags copied, LayoutParams normalized, the guest Window callback guards the detached WindowManager. | round 7 commit (this review) | phone: one callback-protected run, none of the three crashes recurred (agent log); re-confirm in round 8 |
