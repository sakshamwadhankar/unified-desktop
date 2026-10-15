# SKIPS: deferred work that still blocks a gate

Work deliberately moved past, usually because the phone was unavailable or a task hit its
time box. A phase listed here is **not closed** until its rows are done.

Rules:
- Add a row when a task is skipped: **why**, **what is needed** to do it, and **when it
  counts as done**.
- Delete the row once it is done and committed. Never leave a finished row here.

---

| Round 5 / T1 GMS phone run | The source fix calls `isSupportGms()` and `installGms(0)` and the required package queries are present, but the Windows adb server at `tcp:172.27.128.1:5037` timed out before the APK could be installed. Needed: install the rebuilt APK on `2c0f6edc`, record the exact InstallResult, relaunch Tasks, and capture whether GMS starts or requests sign-in. | 2026-10-15 |
| Round 5 / T2 sizing phone proof | The source now applies 320 dpi, 640x360 dp, landscape, and 1280x720 window attributes before guest onCreate. No `B1_BEFORE_MOVE` phone log or PNG was possible because adb timed out. Needed: run B1 on the phone and verify a 1280x720 whole guest frame. | 2026-10-15 |
| Round 5 / T3 typed-key proof | No phone run was possible after the GMS/sizing changes because adb timed out. Needed: use a focused guest EditText (Tasks after GMS or Firefox address bar), send paired T/E/S/T/Backspace events, and prove the text in the guest frame with no real-screen keyboard. | 2026-10-15 |
| Round 5 / T4 browser | The source now records Chrome splits/activities and tries the host launcher alias target. Firefox could not be installed from Play because the phone was unreachable. Needed: check Chrome's five splits and alias target on the phone, install Firefox from Google Play, then capture a browser frame or exact causes. | 2026-10-15 |
| Round 5 / T5 moving workload | No phone workload could run because adb timed out. Needed: moving guest FPS/first frame, PSS/RSS, host/`:black`/guest CPU for 60 s idle and 60 s injected input, process count, and kill-action audit. | 2026-10-15 |
| Round 5 / phone and emulator execution | The phone's Windows adb server timed out and the documented fallback AVD could not start because `/dev/kvm` is unavailable; its local config is only 2 GB. Needed: a reachable phone adb server, or a 6 GB hardware-accelerated emulator, to execute the round-5 APK and collect device evidence. | 2026-10-15 |

The emulator limitation and all round-2 results are summarized in `docs/PHASE1_RESULT.md`.
