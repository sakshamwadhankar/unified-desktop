# SKIPS: deferred work that still blocks a gate

Work deliberately moved past, usually because the phone was unavailable or a task hit its
time box. A phase listed here is **not closed** until its rows are done.

Rules:
- Add a row when a task is skipped: **why**, **what is needed** to do it, and **when it
  counts as done**.
- Delete the row once it is done and committed. Never leave a finished row here.

---

| Phase / task | Why skipped | What is needed | Done when |
|---|---|---|---|
| P1 T2 build (G6) | Round 1 cut off; build never verified. Gradle wrapper is incomplete in the tracked tree (no `gradle/wrapper/` jar or `.properties`, no `gradlew.bat`), so `./gradlew` likely cannot bootstrap from a clean checkout. | Complete/repair the wrapper (commit `gradle-wrapper.jar` + `gradle-wrapper.properties` + `gradlew.bat`), run `fetch-source.sh`, then pass `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`. | The three gradle tasks pass and the command/output is in the report. |
| P1 T3 (G1) | Cut off before engine integration. Only our own control activity runs on the display; no guest app. | Integrate Blacks-BlackBox, launch a Play-installed app (simple app + a browser) on our virtual display, save ImageReader PNGs. | PNGs show the guest app on our display, on the phone. |
| P1 T3 (G2) | Not reached. | Real-screen `screencap` at the same moment showing something else. | Paired PNG exists on the phone. |
| P1 T4 (G3) | Input wired to our own activity only. | Inject tap + keys into the guest app from in-process, before/after PNGs. | PNGs prove the guest changed, on the phone. |
| P1 T5 (G5) | No device this run (adb saw nothing). | Phone or emulator available; run the T5 measurements. | Numbers (first-frame ms, PSS/RSS, CPU, process count, kills) in the report. |
| P1 T6 | Cut off. | Write `docs/PHASE1_PLAY_POLICY.md` per the prompt. | The one-page doc exists with sources. |
