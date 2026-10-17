# Phase 2 round 2 result (2026-10-17)

Round 2 finished the Wine boot on the phone and proved the safety containment, but the
Phase 2 GUI gate is still blocked — now by one precise, documented cause.

## Works (phone-verified)

- **App boots the prefix end to end** (`wineboot -i` exit 0 via the app): kill server ->
  drop hives -> fresh server init (correct `CurrentControlSet` link) -> `wineboot.exe -i`
  through the direct loader -> `.ud-boot-ok` written. Numbers: first full wine.inf pass
  174.1 s; later boots (fast path) exit 0 in ~35 s, drain EOF 60.9 s; `WINESERVER ready`
  265 ms.
- **Prefix heal kept and hardened**: a poisoned `system.reg` (`"SymbolicLinkValue"=hex(0):`)
  found at boot is detected, the 3 hives are removed and the fresh init rebuilds them.
  Acceptance: marker 35 B, `"SymbolicLinkValue"=hex(6):` + the target path on disk,
  1009 modules in `drive_c/windows/system32`.
- **system32/syswow64 module symlinks (B-023, app-side)**: `winex11.drv` loads
  (`Using emulated .../winex11.so`) and `explorer.exe` executes; before the farm this failed
  with `c0000034` and the display driver never loaded.
- **Safety (B-022) proven in production**: the app cap tripped at 56 processes /
  MemAvailable 869 MB and killed the whole tree; the phone stayed fully usable. The laptop
  watchdog covered the rest of the round.
- **Build gates**: `:app:testDebugUnitTest` (4 classes, 7 tests, 0 failures),
  `:app:lintDebug`, `:app:assembleDebug`; APK `app-debug.apk` = 18,044,798 B.

## Does not work

- **Any top-level window.** The desktop window cannot be created on demand under box64:
  the server's `create_window(NULL, NULL, DESKTOP_ATOM, ...)` returns no window, wine logs
  `failed to create desktop window`, and every process then starts one `explorer.exe
  /desktop` whose init repeats it (linear chain; 2 -> 56 processes in under 40 s).
  Notepad++ launches (`WINE pid=31374 ... notepad++.exe`) but can never show a window;
  captures stay black (5336 B) or fail (58 B). This is B-022 and the single blocker of the
  GUI gate (window PNG, typed text, launch numbers, survival).
- **Wineserver restarts destroy the registry link** (B-021, root cause located): a server
  that loads saved hives loses the `CurrentControlSet` symlink and rewrites `system.reg` as
  `hex(0)`; registry access then stack-overflows. Worked around by always starting from
  empty hives; the upstream/box64 cause is still open.
- **`bin/wine` dies with `c0000018`** in `map_shared_user_data` (B-024). All app paths use
  the direct loader instead.

## Next needs (for the manager)

- A decision on the desktop-window blocker: pin a different box64/wine candidate (D13
  revision) or take it upstream (wine 11.19 under box64 on this 39-bit-VA device).
- Hardening pass listed in `SKIPS.md`: B-019 clean install from a wiped phone, the
  `android/win86` scratch-script keep/delete pass, D13/D4 + ARCHITECTURE.md updates.
