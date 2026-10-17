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
| T6 gate (Wine + Notepad++ typed-text PNG): `wineboot -i` aborts 149 ms into the wine preloader under Box64 v0.4.4 (see BUGS B-017). Wine binary, Notepad++ data and the Xvfb/Xlib client stack are all in place and verified; only wine's own first boot is blocked. | Needs a manager decision: bump the Box64 pin beyond v0.4.4 (candidate fix upstream; D13 revision), or re-pin a different Kron4ek/other prebuilt Wine build whose preloader survives on this 39-bit-VA device. Evidence: `files/phase2/wine.log` dumps (both dynarec and interpreter variants), `ls files/win86/wineprefix` empty. Done when: `wineboot -i` exits 0, `files/win86/wineprefix` exists, and NPP starts a window on our X server with typed text captured to a PNG. | 2026-10-17 |

The emulator limitation and all round-2 results are summarized in `docs/PHASE1_RESULT.md`.
