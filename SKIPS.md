# SKIPS: deferred work that still blocks a gate

Work deliberately moved past, usually because the phone was unavailable or a task hit its
time box. A phase listed here is **not closed** until its rows are done.

Rules:
- Add a row when a task is skipped: **why**, **what is needed** to do it, and **when it
  counts as done**.
- Delete the row once it is done and committed. Never leave a finished row here.

---

| Round 2 / T0 phone placement | The authorized phone `2c0f6edc` was not visible to the coding container, so the required clean Google Tasks phone run and same-moment phone PNG could not be captured. Needed: rerun the exact headless Google Tasks placement command on the phone with the screen kept on; done when `evidence/phase1/round2_placement.txt` contains one run's full `dumpsys activity activities` and `dumpsys window displays` from that phone and explains the activity/window display ids. | 2026-10-14 |
| Round 2 / T2 phone path-B proof | B1/B2 were exercised on the available Pixel_8_Pro emulator with DeskClock, but no phone-side guest frame could be proved. Needed: repeat B1/B2 with Google Tasks on the phone; done when a phone ImageReader frame contains the guest while the real-screen screencap shows something else, or the phone failure is recorded. | 2026-10-14 |
| Round 2 / T3 input and T4 measurements | T2 did not produce a guest frame, so injected input and guest performance/browser measurements were not meaningful. Needed: a successful phone/emulator T2 first; done when before/after guest frames and the requested timing, PSS/RSS, CPU, process, kill, and browser results exist. | 2026-10-14 |

The emulator limitation and all round-2 results are summarized in `docs/PHASE1_RESULT.md`.
