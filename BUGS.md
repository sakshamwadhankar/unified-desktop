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
| B-004 | 2026-10-15, P1 r3, agent | A guest process dies unexpectedly. | Round 3: exit within 60 s. Rounds 4 and 8: 10-minute idle watches passed. Manager re-run 2026-10-16 08:54: Firefox main (slot p0) killed ~9 s after launch (exit-info SIGNALED, exit_self); same morning P3/P4 slots died repeatedly. | major | **FIX NOW** (round 9): likely the same slot problem as B-007; re-check after that fix with exit-info for every slot. |
| B-007 | 2026-10-16, P1 r7, agent | After Go, Firefox's toolbar draws on our display but the web page area is blank. | **Cause (manager re-run, display 94, `evidence/phase1/round8_phone_results.txt` last section):** not a SurfaceView limit; the Firefox web layer sits under our Presentation window on our display (SurfaceFlinger parent chain). Gecko's GPU process dies ~1 s after start: the engine reuses proxy slot P3 after the Play Store (also on P3) crashed, a stale Play Store bind lands in the new process, the Play Store crashes there (`SecurityException: Package com.android.vending does not belong to 10459`) and kills the Gecko child. Tab processes reusing slot 3 die the same way. | major | **FIX NOW** (Phase 1 round 9): (a) stop the Play Store crash inside the engine (remaining `does not belong to <host uid>` calls, e.g. content-provider/AppOps calls still carrying the guest package name); (b) make the engine reject a bind/service start whose target process does not match the slot's current owner, so one guest's crash can never kill another guest's process. |

## Fixed

| ID | What it was | Fixed in (commit) | Verified on (phone / emulator / code-only) |
|---|---|---|---|
| B-001 | Guest decor kept the phone size (2400x1080) on our 1280x720 display, so it was clipped. Fixed by an engine callback before the guest onCreate (patch in `android/engine/fetch-source.sh`) and a public `Resources.updateConfiguration` fallback. | round 5 commit (this review) | phone: manager re-run, display 66, `B1_BEFORE_MOVE size=1280x720`, whole Tasks screen, frame sha256 `31b931a2...` (same as the agent's) |
| B-002 | Guests saw no usable Google Play services ("Update Google Play Services", then `Play Store signature is invalid`, error 9). Three engine fixes in `android/engine/fetch-source.sh`: the GMS APK was misread as an APK set; a hard-coded fake Play Store PackageInfo had no signatures (now the normal virtual path); virtual `getPackageUid` now returns the virtual uid. | round 6 commit (this review) | phone: guest and host SHA-256 match for com.android.vending and com.google.android.gms (`f0fd6c5b...`); Tasks reaches its own Sign in screen (`round6_tasks_after_uid.png`, sha256 `9a8a6368...`). Residual `does not belong to` lines in logcat remain; not blocking. |
| B-005 | Typed text could not reach a Compose field in our Presentation (key events returned false). Now sent through the focused view's `InputConnection` (`commitText`, then a deferred IME action). | round 7 commit (this review) | phone: `round7_t2_final-frame-2.png` (sha256 `158190a8...`) shows `https://example.com` typed in Firefox's address bar before Go |
| B-006 | Firefox crashed on relayout of the moved decor (`WindowManager$LayoutParams` -> `MarginLayoutParams`), plus missing ViewTree owners and a detached-window crash. Fixed in `GuestPresentationHost.java`: owner tags copied, LayoutParams normalized, the guest Window callback guards the detached WindowManager. | round 7 commit (this review) | phone: one callback-protected run, none of the three crashes recurred (agent log); re-confirm in round 8 |
