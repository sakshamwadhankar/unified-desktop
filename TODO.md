# Phase 9: PC lock + Exit

Done 8 of 8 | Next: Phase 10 (manager) | Updated 2026-10-21 05:10 (manager gate) | Phase 9 PASS

1. [x] T0 Baseline + safety net: code 45/0, APK installed, phone link view 10 s, TODO, phone stay-on.
2. [x] T1 Lock core: `lock.rs` state machine, separate desktop, WH_KEYBOARD_LL, watchdog,
       `lock-exit`, `lock-status`, `lock-selftest`.
3. [x] T2 Exit overlay, crash journal, policies (admin-only), multi-monitor, unit tests.
4. [x] T3 Wire `view` (`--lock`, `--no-lock`, `--lock-max-seconds`, `--freeze-video-after`).
5. [x] T4 Windows 11 + live phone gate G1-G4 (keys, taskbar, Exit 3x, phone gone, frozen).
6. [x] T5 Crash recovery G5 + numbers (engage/exit, CPU/RAM, fps).
7. [x] T6 Windows 10 SKIP, B-093, docs, default flip, gates, cleanup, STATE last.
8. [x] T7 Manager review rework: session mutex before recovery, fail-closed journal/start,
       PID-aware guard, verified Default exit; phone re-test and docs.

Results:
- T0: Windows 11 26H2 build 26300 (UBR 9711); registry ProductName is the legacy
  "Windows 10 Home Single Language" (known lie). Not elevated. Phone 2c0f6edc online,
  battery 53 % USB-powered, stay_on_while_plugged_in=0, brightness 2047.
- T0: pc `cargo test --release` 45 passed / 0 failed (baseline); final 51/0 at T6, 55/0 after T7.
- T1-T3: private desktop, 8/8 hook, Exit overlay, watchdog, journal, guard, policies,
  multi-monitor; `view` wired and lock default flipped after G4/G5.
- T4 (phone): input `udreceiver-lock-<pid>`; no taskbar (`lock-inject --windows`); 8/8
  counters = 2 with focus unchanged; Exit restores 655/665/678 ms (earlier 841); frozen
  video 665 ms; watchdog 109 ms; `--seconds` 128 ms; phone gone 65 ms; named event 120 ms.
- T5 (phone): kill with guard -> Default in 17 ms; guard killed first -> next `view`
  restores the dirty journal in 0.6 s. Locked CPU 1.2 % of a core, WS 133.9 MB, 1.0 fps,
  decode 4.3-5.4 ms.
- T6: Windows 10 SKIP (no hardware; SKIPS.md row); B-093 fixed and phone-verified
  (`X11 desktop — winemine`); docs updated; Android 149 tests/0/3 skipped, lint+assemble
  BUILD SUCCESSFUL; PC 51/0, clippy 0. Nothing committed.
- T7 (phone): second view refused in 42 ms before connect; first stayed live (`live=1`,
  journal hash unchanged). `lock-recover` while locked exit 1, journal unchanged. Normal
  `lock-exit` 291 ms (switch 21, lock path 206). Guard 12 ms (426 ms wall). 8/8 counters = 2.
  Phone gone 1134 ms (lock path 76); frozen video exit 641 ms (lock path 100). Dirty journal
  + failed HKLM restore: view exit 52 ms, journal byte-identical, no lock; selftest exit 1.
  Wrong-parent guard left journal/desktop untouched; clean journal on lock desktop 75 ms;
  malformed journal 59 ms with bytes unchanged. Selftest PASS 8/8, exit 0. T17: guard and
  lock killed, next `view --no-lock` recovered the dirty journal before connect, returned
  Default and removed it (6 s phone view). Gates: Android `testDebugUnitTest --rerun-tasks`
  **149/0/3**, `lintDebug` + `assembleDebug` BUILD SUCCESSFUL; PC `cargo test --release`
  **55/0**, clippy clean; `git diff --check` exit 0. Manager commit: Phase 8 + Phase 9, pushed.
