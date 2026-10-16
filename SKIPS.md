# SKIPS: deferred work that still blocks a gate

Work deliberately moved past, usually because the phone was unavailable or a task hit its
time box. A phase listed here is **not closed** until its rows are done.

Rules:
- Add a row when a task is skipped: **why**, **what is needed** to do it, and **when it
  counts as done**.
- Delete the row once it is done and committed. Never leave a finished row here.

---

| What | Why skipped / what is needed | Date |
|---|---|---|
| Phase 1 / loaded web page (B-007) | Rounds 6-8: Firefox toolbar and typed text work, page area blank. Cause found by the manager: engine slot reuse kills Gecko's GPU/tab process (BUGS B-007). Needed: the round-9 engine fix, then a frame with example.com's Example Domain text. | 2026-10-16 |
| Phase 1 / moving fps | Rounds 5-8: no valid scrolling run. Round 8 measured 0.05 fps on a near-static Firefox home screen; the pipe did 8.77 fps on animated content (round 3). Needed: a guest that really scrolls (a loaded Wikipedia page after B-007) with changing frame hashes. | 2026-10-16 |
| Phase 1 / survival with a loaded page | Round 8's 10-minute pass was Firefox's home screen, not a page; a manager re-run saw the Firefox main process killed after ~9 s (B-004). Needed: 10 min with a loaded page after the B-007 fix, exit-info per slot. | 2026-10-16 |

The emulator limitation and all round-2 results are summarized in `docs/PHASE1_RESULT.md`.
