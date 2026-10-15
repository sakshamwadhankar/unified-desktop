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
| B-001 | 2026-10-15, P1 r3, manager | The moved guest decor keeps the phone's size (2400x1080) on our 1280x720 display, so the guest is clipped (Tasks' bottom button is cut off). | `B1_BEFORE_MOVE ... size=2400x1080` vs `createVirtualDisplay(...,1280,720,...)`; round-4 target configuration/layout logs and frame still show the lower action clipped: `evidence/phase1/round4_b001_phone_excerpt.txt`. | major | **FIX NOW** (round 4): lay the guest out at the private display's size and density (Presentation/viewHost size, guest Configuration). |
| B-002 | 2026-10-15, P1 r3, manager | Google Tasks inside the engine shows "Update Google Play Services": the guest does not see working Google Play services. Most Play apps need them. | Frame from display 25 (manager's run), same as agent's hash `619062b9...`; round-4 virtual `NameNotFoundException` and resolver evidence: `evidence/phase1/round4_gms_browser_phone_excerpt.txt`. | major | **FIX NOW** (round 4): find out why (BlackBox's GMS handling, the GMS package not visible or blocked in the engine) and whether host GMS can be used; report, do not fake it. |
| B-003 | 2026-10-15, P1 r3, agent | BlackBox `getLaunchIntentForPackage` returns null for Chrome, though Android resolves `com.google.android.apps.chrome.Main`. | `evidence/phase1/round3_browser_phone_excerpt.txt`; round 4 still returns `no launch activity`: `evidence/phase1/round4_gms_browser_phone_excerpt.txt`. | major | **FIX NOW** (round 4): find why the engine's lookup misses (activity-alias? split APKs?) and fix or resolve the launch activity another way. |
| B-004 | 2026-10-15, P1 r3, agent | The guest process exited within 60 s of the measurement run; the cause was not recorded. | `evidence/phase1/round3_t4_phone_excerpt.txt`; round 4 ten-minute idle survival did not reproduce the exit: `evidence/phase1/round4_survival_phone_excerpt.txt`. | major | **FIX NOW** (round 4): find the cause (logcat around exit: engine teardown, harness finishing, crash, kill) before measuring. |

## Fixed

| ID | What it was | Fixed in (commit) | Verified on (phone / emulator / code-only) |
|---|---|---|---|
| | | | |
