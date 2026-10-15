# SKIPS: deferred work that still blocks a gate

Work deliberately moved past, usually because the phone was unavailable or a task hit its
time box. A phase listed here is **not closed** until its rows are done.

Rules:
- Add a row when a task is skipped: **why**, **what is needed** to do it, and **when it
  counts as done**.
- Delete the row once it is done and committed. Never leave a finished row here.

---

| Round 4 / T3 typed-key proof | The in-process taps still return true, but Tasks stops at its GMS prompt; no editable child is found and T/E/S/T/Delete/Enter dispatch returns false. Needed: expose working GMS or use a guest screen with a real focused editor, then prove the key result in a guest frame. | 2026-10-15 |
| Round 4 / T4 moving workload | The guest, host, and `:black` processes survived a watched ten-minute idle run, but the guest stayed on a static GMS prompt. No moving-guest FPS or repeated-tap CPU workload was recorded. Needed: reach a scrollable guest screen and record frame rate plus guest/host CPU for idle and input workloads. | 2026-10-15 |
| Round 4 / browser | Chrome still imports but the BlackBox resolver returns no launch activity; host resolution is `com.android.chrome/com.google.android.apps.chrome.Main`. Needed: a bounded resolver fix or another Google-Play browser with a virtual launch activity, then capture a browser frame. | 2026-10-15 |

The emulator limitation and all round-2 results are summarized in `docs/PHASE1_RESULT.md`.
