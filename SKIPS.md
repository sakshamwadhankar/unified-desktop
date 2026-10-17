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
| T6 gate (Wine + Notepad++ typed-text PNG): the B-022 desktop-window blocker is FIXED (round 3b 2026-10-17: functional no-op fontconfig stub -> boot in ~20 s, `create_winstation`/`create_desktop` OK, 6-9 procs, no explorer chain), but the gate is still blocked one layer later: no wine window ever maps to X under box64. `NtUserShowWindow` on NPP's main window returns `c0000005` via a fault in wine's syscall dispatcher while window-proc callbacks run (BUGS B-026; screen stays black, 5336 B capture, while native xeyes on the same server renders: 9442 B). | Needs the B-026 root cause fixed (likely a box64 signal/dispatcher-nesting issue under win-proc callbacks): pin a different box64/wine candidate (D13 revision) or take it upstream. Done when: one Notepad++ window PNG from our Xvfb plus "hello from phase 2" typed via XTEST visible in a PNG. Evidence: `files/phase2/wine12.log` L13210-13316, `wine14.log` L12950-12980, captures npp*.png (5336 B) vs xeyes-b.png (9442 B). | 2026-10-17 |
| Phase 2 hardening: B-019 clean install from a truly wiped phone; a keep/delete pass over the ~90 scratch scripts under `android/win86` (am*.sh, runp*.ps1, patchxvfb*.py, ...); D13 (box64/wine pin) and D4 notes updated with the round-2 findings (B-020 patch, B-021 load-path poison, B-024 loader workaround); ARCHITECTURE.md H6/H7 rows refreshed. | Manager pass; deferred without code risk. | 2026-10-17 |

The emulator limitation and all round-2 results are summarized in `docs/PHASE1_RESULT.md`.
