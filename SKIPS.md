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
| T6 gate (Wine + Notepad++ typed-text PNG): still blocked, now by wine's desktop window instead of the preloader. `wineboot -i` boots the prefix via the app (exit 0, marker written, `winex11.drv` loads; B-017/B-018/B-020/B-021/B-023/B-024 are fixed or worked around), but no top-level window can exist: every GUI process's forced desktop-window create fails under box64 (BUGS B-022), and wine's per-process `explorer.exe /desktop` fallback chains (2 -> 56 processes in under 40 s; contained by the app cap + watchdog, proven in production). | Needs a manager decision on the desktop-window blocker: pin a different box64/wine candidate (D13 revision) or take it upstream. Done when: one Notepad++ window PNG from our Xvfb plus "hello from phase 2" typed via XTEST visible in a PNG. Evidence: `files/phase2/wine.log` + captures (all black, 5336 B, or failed, 58 B). | 2026-10-17 |
| Phase 2 hardening: B-019 clean install from a truly wiped phone; a keep/delete pass over the ~90 scratch scripts under `android/win86` (am*.sh, runp*.ps1, patchxvfb*.py, ...); D13 (box64/wine pin) and D4 notes updated with the round-2 findings (B-020 patch, B-021 load-path poison, B-024 loader workaround); ARCHITECTURE.md H6/H7 rows refreshed. | Manager pass; deferred without code risk. | 2026-10-17 |

The emulator limitation and all round-2 results are summarized in `docs/PHASE1_RESULT.md`.
