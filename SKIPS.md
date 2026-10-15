# SKIPS: deferred work that still blocks a gate

Work deliberately moved past, usually because the phone was unavailable or a task hit its
time box. A phase listed here is **not closed** until its rows are done.

Rules:
- Add a row when a task is skipped: **why**, **what is needed** to do it, and **when it
  counts as done**.
- Delete the row once it is done and committed. Never leave a finished row here.

---

| Round 5 / T3 typed-key proof | Tasks remained at the GMS prompt. Firefox launched and rendered onboarding, but its Compose `Not now` action did not advance through the public root dispatch; no EditText was found and all paired T/E/S/T/Backspace events returned false. Needed: a focused guest EditText and a typed-text frame with no real-screen keyboard. | 2026-10-15 |
| Round 5 / T5 moving workload | The phone entered an active call (`mCallState=2`) before the moving run. Polling continued without touching the screen; the phone then disappeared from adb. Needed: restore phone reachability and collect moving FPS/first frame, PSS/RSS, host/`:black`/guest CPU for 60 s idle and 60 s input, process count, and kill-action audit. | 2026-10-15 |

The emulator limitation and all round-2 results are summarized in `docs/PHASE1_RESULT.md`.
