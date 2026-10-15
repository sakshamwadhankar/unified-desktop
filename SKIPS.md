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
| Round 6 / T3 moving workload | The final guarded measurement attempt could not start because the phone first rejected an unsupported `am start` option; the corrected attempt ran until the phone entered a call (`mCallState!=0`) and then disappeared from adb. No touch was made after the call state changed. Needed: a fresh reachable-phone run with Wikipedia input, changing guest-frame hashes, FPS/first-frame, per-process PSS/RSS/CPU and kill audit. | 2026-10-15 |
| Round 6 / T4 Firefox survival | Not run as a fresh ten-minute Firefox-page watch because the phone disconnected during the guarded T3 attempt. Existing round-4 ten-minute idle survival remains valid for the earlier Tasks guest, but does not close Firefox-page survival. Needed: fresh Firefox page run, ten-minute CPU-tick samples and exit-info. | 2026-10-15 |
| Round 7 / T1 page-content proof | Typed-before-Go is proven, but after Go no Example Domain body frame was captured; the latest frames are dark blank content. Needed: a phone run that visibly captures Example Domain text and confirms the Gecko content surface is composited. | 2026-10-16 |
| Round 7 / T3 moving metrics | Drag injection produced paired down/move/up sequences, but the run was invalid because Firefox was not focused in a fresh address field (`commit=false`). No valid Wikipedia workload metrics were collected. | 2026-10-16 |
| Round 7 / T4 Firefox survival | Not run because the page-content gate is still open. Needed: a valid loaded Firefox page, ten-minute guest CPU-tick watch, and exit/kill attribution. | 2026-10-16 |
| Round 8 / T1-T3 phone execution | The coding-agent shell became unreachable before the phone build/install command, so no adb run could start. Needed: restore the shell/adb tool, then run B-007 diagnostics, the independent moving workload, and the ten-minute survival watch. Counts done only when the phone evidence file contains actual logs, changing frame hashes, resource samples, and survival CPU samples. | 2026-10-16 |

The emulator limitation and all round-2 results are summarized in `docs/PHASE1_RESULT.md`.
