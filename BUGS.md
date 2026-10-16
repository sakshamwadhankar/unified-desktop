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
| B-003 | 2026-10-15, P1 r3, agent | BlackBox `getLaunchIntentForPackage` returns null for Chrome, though Android resolves `com.google.android.apps.chrome.Main`. | Round 5 host audit found all five Chrome split names and alias target `org.chromium.chrome.browser.ChromeTabbedActivity`; BlackBox returned no virtual ActivityInfo for either alias or target and launch ended with `VIRTUAL_LAUNCH_COMPONENT_MISSING`. Firefox was installed from Google Play and launched, but remained on onboarding rather than a web page. Evidence: `evidence/phase1/round5_phone_results.txt`. | major | **FIX LATER** (Phase 8, App Center compatibility): Firefox covers the Phase 1 browser gate. |
| B-004 | 2026-10-15, P1 r3, agent | A guest process dies unexpectedly. | r3: exit within 60 s; r8 re-run: Firefox main killed after ~9 s, while the slot bug (B-007) was killing engine processes. Round 9 (after the slot fix): Firefox survived 10 min with the same PIDs (`evidence/phase1/round9_phone_results.txt`). | major | **WATCH** (manager, round 9): not seen since the slot fix. Re-check survival in Phase 5 (streaming) with longer sessions; reopen if it returns. |
| B-008 | 2026-10-16, P1 r9, manager | While a guest runs hidden, the real phone screen shows a full-screen black window (the engine's `ProxyActivity$P0`, the guest's placeholder activity on display 0) instead of the phone's own UI. This breaks the "phone stays fully usable" rule. | `evidence/phase1/round9_phone_results.txt` T2; real-screen capture at 11:56 (local only) is black with the status bar. | major | **FIX LATER** (Phase 4/5, when the phone-side UI and session lifecycle are built): the placeholder must not cover the phone (e.g. move its task to back or keep it invisible without stopping the guest). |
| B-009 | 2026-10-16, P1 r9, manager | Inside the engine, the Play Store's background processes (`com.android.vending:background`, `:quick_launch`) crash and restart in a loop, and Google Play services had an ANR. They no longer kill other guests (B-007 fix) but cost CPU: one sample showed `vending:quick_launch` at 191% CPU. Cause still in the log: `SecurityException: Package com.android.vending does not belong to 10459` on remaining paths. | `evidence/phase1/round9_phone_results.txt` T3/T4. | major | **FIX LATER** (Phase 8, App Center compatibility): find the remaining host-package/uid checks; or stop starting Play Store background services that guests do not need. |

## Fixed

| ID | What it was | Fixed in (commit) | Verified on (phone / emulator / code-only) |
|---|---|---|---|
| B-001 | Guest decor kept the phone size (2400x1080) on our 1280x720 display, so it was clipped. Fixed by an engine callback before the guest onCreate (patch in `android/engine/fetch-source.sh`) and a public `Resources.updateConfiguration` fallback. | round 5 commit (this review) | phone: manager re-run, display 66, `B1_BEFORE_MOVE size=1280x720`, whole Tasks screen, frame sha256 `31b931a2...` (same as the agent's) |
| B-002 | Guests saw no usable Google Play services ("Update Google Play Services", then `Play Store signature is invalid`, error 9). Three engine fixes in `android/engine/fetch-source.sh`: the GMS APK was misread as an APK set; a hard-coded fake Play Store PackageInfo had no signatures (now the normal virtual path); virtual `getPackageUid` now returns the virtual uid. | round 6 commit (this review) | phone: guest and host SHA-256 match for com.android.vending and com.google.android.gms (`f0fd6c5b...`); Tasks reaches its own Sign in screen (`round6_tasks_after_uid.png`, sha256 `9a8a6368...`). Residual `does not belong to` lines in logcat remain; not blocking. |
| B-005 | Typed text could not reach a Compose field in our Presentation (key events returned false). Now sent through the focused view's `InputConnection` (`commitText`, then a deferred IME action). | round 7 commit (this review) | phone: `round7_t2_final-frame-2.png` (sha256 `158190a8...`) shows `https://example.com` typed in Firefox's address bar before Go |
| B-006 | Firefox crashed on relayout of the moved decor (`WindowManager$LayoutParams` -> `MarginLayoutParams`), plus missing ViewTree owners and a detached-window crash. Fixed in `GuestPresentationHost.java`: owner tags copied, LayoutParams normalized, the guest Window callback guards the detached WindowManager. | round 7 commit (this review) | phone: one callback-protected run, none of the three crashes recurred (agent log); re-confirm in round 8 |
| B-007 | Firefox showed a blank page after Go. Cause: the engine reused a proxy process slot after the Play Store crashed in it; a delayed Play Store bind landed in the new process and crashed it, taking Firefox's GPU/tab process with it. Fixed by slot-owner checks in ProxyService / AppServiceDispatcher / BActivityThread / activity-manager callbacks, plus host-package fixes for system calls (`android/engine/fetch-source.sh`). | 35b467f | phone (manager, round 9): example.com body text on our display (`round9_example_page.png` sha256 `f3d41a35...`); Wikipedia scrolled at 22.2 fps |
