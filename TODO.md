# Phase 2 round 5c: to-do (B-026 fix -> items 5-11)

Done 8 of 8 | Updated 11:12 | B-026 SOLVED (window + typed text + soak + clean install, all phone-verified); closing out

Rule: after EACH item tick it ([x] only if it truly works; [-] plus a one-line reason),
update the top line, THEN start the next item. Past a time box: write by how much and why.
HARD STOP: 12:30. Safety: in-app guard 40 procs / 600 MB live during every run; killall +
am force-stop after every run; phone kept usable. (Laptop watchdog: the round-5 instance had
gone stale - it cached uid u0_a467 and logged procs=0 all round; the in-app guard was the only
live cap. Noted in the report; LESSONS.md updated. It was stopped at cleanup.)

- [x] 4. B-026 fix. DONE 11:00 - root cause: the guest SysV-shared-memory path (winex11's XShm).
  F4 block in `android/win86/fetch-box64.sh` intercepts it on both guest routes: (a) the
  wrapped-libc `my_shmget/shmat/shmctl/shmdt` stubs (+`GO->GOM` rewrite) and (b) the raw guest
  syscalls 29/30/31/67 in `x64Syscall_linux`. Which path fired: (b) alone removed the `exit=159`
  (128+31 = SIGSYS) deaths (process then lived but stayed black at ~100% CPU), (a) produced the
  window. `COMCTL32.dll.381 aborting` was logged BEFORE the SIGSYS on pre-fix builds (the abort
  path releases the kill) and is gone after F4. Shipping lib = F4 `e1c21ed2...` (22,390,072 B)
  in jniLibs, embedded sha verified in the APK (B-010). The recipe is NOT reproducible (three
  shas: `e1c21ed2`/`a8f3f177`/`80b20b76`, 745,057 differing bytes vs shipped) -> BUGS B-030; the
  tested bytes are the artifact, backups in gitignored `vendor/` + the p2r5 dir. XTEST fixed on
  the way: QueryExtension +2 pad bytes, reply present@8/opcode@9 (+ mainline fallback), FakeInput
  36 B; stuck-key flood fixed with the KEYMAP release sweep (B-032). [~170 min incl. the
  manager-ordered box64 rebuild check]
- [x] 5. Notepad++ window PNG + numbers. DONE 10:00 / 11:00 - window renders: `evidence/phase2/`
  `npp-window-11.19.png` (18,001 B, `047AD40B...`, run 2 byte-identical) and from the clean
  install `npp-window-clean-install.png` (17,998 B, `B8535120...`). Launch-to-first-frame:
  process visible +4.4 s, first window paint **+27.0 s**, settled (caret blink) +29.9 s -
  baseline-diff watcher against a valid black baseline, 120 ms polls (laptop Python = diagnostic
  only). PSS: app 45,983 kB, Xvfb 58,667, wineserver 17,894, notepad++ 191,814 (RSS 46,524),
  2x services.exe, explorer = **8 procs** of our uid. Idle CPU over 60 s: notepad++ +2 s,
  wineserver +1 s, Xvfb +1 s, rest 0 (~6.7% of one core). [~50 min]
- [x] 6. Type "hello from phase 2" via XTEST. DONE 10:53 - app-side `type:` broadcast (mCallState=0
  checked first), `XTEST present=1 opcode=131` + `TYPED 18 chars` in x11.log;
  `npp-typed-clean-install.png` shows "hello from phase 2", length 18, Ln 1 Col 19; a second
  capture 30 s later is unchanged (`npp-typed-clean-install-30s.png`; the two files differ only
  in the caret-blink state). The laptop-bench PNG (`npp-typed-hello.png`) is recorded as a
  DIAGNOSTIC, not gate evidence. Stuck-key flood (B-032) found + fixed in this same path. [~40 min]
- [x] 7. 10-minute survival. DONE 10:26 - 10 min 26 s with the window + typed text up, same PIDs
  (npp pid 26264 from 10:15:47), CPU advancing, no LMK kill in the run; end capture
  `npp-window-11.19-10min.png` byte-identical to the typed frame (`30F55E37...`). One earlier run
  was LMK-killed at 10:11:50 (B-033, acceptable `[-]` per manager note 6; product answer = the
  Phase 4 foreground service, B-027). [~15 min soak + monitoring]
- [x] 8. Clean install (broadcast-only). DONE 10:52 - uninstall -> install (uid u0_a475) -> ONLY
  harness broadcasts: `pin:11.19`, `fetchwine` (101,940,736 B, ms=25,459, unpack 61,802),
  `fetchall` (96 pkgs + bundle + NPP); F1 OFF verified in the live logs (2-site ntdll
  `c378cd70...`); F4 lib in jniLibs with its sha recorded in `fetch-box64.sh` + THIRD_PARTY.md
  (recipe-rebuild check done - NOT reproducible, stated plainly, B-030); Xvfb `-extension MIT-SHM`
  verified in the live cmdline; NPP window PNG + typed PNG come from exactly this install; fresh
  first boot ~5 min, warm re-boot 4 s. B-029 reaper verified: `REAP tag=killall candidates=10
  killed=9` - only app + `:black` left. [~40 min]
- [x] 9. Build gates. DONE 11:05 - `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
  BUILD SUCCESSFUL (up-to-date; the last full execution was 10:38 on the same code - zero code
  changes since; 7 classes / 21 tests + the round-5 test additions all green); APK 23,479,603 B;
  embedded `libbox64.so` sha verified = the jniLibs file (B-010). [~10 min]
- [x] 10. Docs. DONE 11:12 - BUGS.md: B-026 close-out (F4 a/b + which path fired + COMCTL32 order
  + XTEST wire bugs + race + LMK), B-029 update (reaper verified), NEW rows B-030 (box64 not
  reproducible, 3 shas), B-031 (cold-start death race, 5/5 vs 5/5), B-032 (stuck-key flood,
  fixed, manager to verify), B-033 (LMK kill 10:11:50); THIRD_PARTY.md + `fetch-box64.sh` record
  the shipped sha + non-reproducibility + vendor backup; SKIPS.md rows updated (T6/T7 solved-on-
  phone, round-5 row re-scoped); LESSONS.md round-5c facts; `docs/PHASE2_RESULT.md` round-5c
  table + gate line; STATE.md updated LAST. [~20 min]
- [x] 11. Cleanup + report. DONE 11:06 - killall -> 0 leftovers; force-stop -> 0 processes of our
  uid; `svc power stayon false`; `/data/local/tmp/libgcc_s.so.1` removed; `adb forward` removed;
  all watchdog instances stopped; no git commits (manager owns git); no binaries in the repo
  (evidence PNGs live in gitignored `evidence/phase2/`; the p2r5 dir keeps the round logs).
  Final report sent; final line "PHASE 2 GATE: PASS". [~15 min]
