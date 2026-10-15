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
| B-002 | 2026-10-15, P1 r3, manager | Google Tasks inside the engine shows "Update Google Play Services": the guest does not see working Google Play services. Most Play apps need them. | Round 5 phone result: `isSupportGms=true`; `InstallResult success=true`, but `installedGms=false`, `traces=true`. Tasks still showed `Update Google Play Services`; virtual Firebase logs still had `NameNotFoundException: com.google.android.gms`. Host GMS PIDs 21326/27391 ran outside the app UID. Virtual Play Store service attempts also crashed with `SecurityException: Package com.android.vending does not belong to 10295`. Evidence: `evidence/phase1/round5_phone_results.txt`. | major | **FIX NOW** (round 5): exact engine/import failure needs manager decision. |
| B-003 | 2026-10-15, P1 r3, agent | BlackBox `getLaunchIntentForPackage` returns null for Chrome, though Android resolves `com.google.android.apps.chrome.Main`. | Round 5 host audit found all five Chrome split names and alias target `org.chromium.chrome.browser.ChromeTabbedActivity`; BlackBox returned no virtual ActivityInfo for either alias or target and launch ended with `VIRTUAL_LAUNCH_COMPONENT_MISSING`. Firefox was installed from Google Play and launched, but remained on onboarding rather than a web page. Evidence: `evidence/phase1/round5_phone_results.txt`. | major | **FIX NOW** (round 5): virtual alias/split resolver still needs a manager decision. |
| B-004 | 2026-10-15, P1 r3, agent | The guest process exited within 60 s of the measurement run; the cause was not recorded. | `evidence/phase1/round3_t4_phone_excerpt.txt`; round 4 ten-minute idle survival did not reproduce the exit: `evidence/phase1/round4_survival_phone_excerpt.txt`. | major | **FIX NOW** (round 4): find the cause (logcat around exit: engine teardown, harness finishing, crash, kill) before measuring. |
| B-005 | 2026-10-15, P1 r5, agent | Firefox's Compose onboarding did not expose its `Not now` action as a traversable TextView, and the public root MotionEvent dispatch did not advance it to an address field. No focused EditText was available for typed-key proof. | Firefox guest frame shows `Welcome to Firefox` onboarding; logs show `B1_INPUT_FOCUS view=null`, DecorView focus, and all paired T/E/S/T/Backspace events false. Evidence: `evidence/phase1/round5_phone_results.txt`; frame SHA-256 `b3f2392ba3d2aa94254af804110e781bccf3d0f0329c26fed54e44e3dbd00e25`. | major | **FIX NOW** (round 5): manager decides whether to add a different text-field guest or improve public Compose interaction. |

## Fixed

| ID | What it was | Fixed in (commit) | Verified on (phone / emulator / code-only) |
|---|---|---|---|
| B-001 | Guest decor kept the phone size (2400x1080) on our 1280x720 display, so it was clipped. Fixed by an engine callback before the guest onCreate (patch in `android/engine/fetch-source.sh`) and a public `Resources.updateConfiguration` fallback. | round 5 commit (this review) | phone: manager re-run, display 66, `B1_BEFORE_MOVE size=1280x720`, whole Tasks screen, frame sha256 `31b931a2...` (same as the agent's) |
