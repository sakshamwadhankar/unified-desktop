# SKIPS: deferred work that still blocks a gate

Work deliberately moved past, usually because the phone was unavailable or a task hit its
time box. A phase listed here is **not closed** until its rows are done.

Rules:
- Add a row when a task is skipped: **why**, **what is needed** to do it, and **when it
  counts as done**.
- Delete the row once it is done and committed. Never leave a finished row here.

---

Nothing skipped. Phase 1 is concluded as a NO-GO on the real phone — the gates G1/G2/G3
cannot be met by this architecture because of an Android 16 platform rule, not because work
was deferred. Full result and evidence: `docs/PHASE1_RESULT.md`. The next move is an
owner/manager DECISION (pivot or stop), not a skipped task.

One thing NOT re-tested (not a gate blocker): a **browser** guest (e.g. Chrome). The simple
guest (Google Tasks) already demonstrated the blocker — it renders on the real screen, not
our display — so a browser would hit the same display rule. Re-test only if the architecture
pivots to a path that can host on our own surface.
