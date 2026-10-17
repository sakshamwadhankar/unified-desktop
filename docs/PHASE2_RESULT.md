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

## Round 3b notes (B-022: where win32u init breaks)

Answers to the round-3b questions (wine.log of the NPP run, 2026-10-17 ~22:23,
`WINEDEBUG=+syscall,+module`, server trace on, 5.8 MB):

- Q1 user32 DllMain in NPP? Yes. `MODULE_InitDLL (user32.dll, PROCESS_ATTACH)` runs on
  thread 0068 and calls `NtUserInitializeClientPfnArrays(0x7fffea8a40, ..., user32 base
  0x7fff470000)`. The exception below is swallowed, so the module load "succeeds"
  (`RETURN 1`) and `imm32.dll` loads right after.
- Q2 `NtUserInitializeClientPfnArrays` + init_user? Started, never completes. That
  function is `pthread_once(&init_once, init_user)` (win32u/class.c:342-352); init_user
  is `init_startup_info, shared_session_init, gdi_init, sysparams_init, winstation_init,
  register_desktop_class` (class.c:322-332). Every captured return is `c0000005`
  (STATUS_ACCESS_VIOLATION, 16/16); the server never sees create_winstation/create_desktop
  (`get_process_winstation() = 0 { handle=0000 }`), so the desktop window can never be
  created and `create_desktop_window()` spawns `explorer.exe /desktop` per attempt (B-022).
- Q3 box64 pthread_once wrapper? Not the fault. `my_pthread_once`
  (src/libtools/threads.c:805) runs the callback on the calling thread via `DynaCall`;
  the 10 ms "force" branch (L832) only matters for a *second* caller while init runs.
- Q4 which user32/win32u? Both load from the prefix: `C:\windows\system32\user32.dll`
  mapped at 0x7fff470000 (image 0x1f1000) and `win32u.dll` at 0x7fff430000 (0x3c000);
  both are `ensurePrefixBinaries` symlinks into the pinned 11.19 tree.

Where the AV actually fires (new; order verified on the raw bytes, not line grouping):

```
[BOX64] Error initializing native libfontconfig.so.1 (last dlerror is dlopen failed:
         library "libfontconfig.so" not found) Error loading needed lib libfontconfig.so.1
[BOX64] Warning: Cannot dlopen("libfontconfig.so.1"/0x3f021dedd9, 2)
[BOX64] Warning, program break not found
[BOX64]
26534|Ask to run at NULL, will segfault
0068:SysRet   NtUserInitializeClientPfnArrays() retval=c0000005
0068:SysCall  NtUserGetSystemDpiForProcess(00000000)
```

26534 is the notepad++ process (main thread 0068). This is inside
`init_freetype_lib()` (win32u/freetype.c:3901-3909), which right after
`init_fontconfig()` calls `NtQueryDefaultLocale()` (L3907): the fontconfig `dlopen`
fails and an emulated call lands on RIP=0 (box64 x64run.c:67-69); the exception
becomes that syscall's status. `NtQueryDefaultLocale` itself never appears in the
trace (0 hits in 5.8 MB). With the real `libfontconfig.so.1` present the failure is a
relocation error (symbols missing from box64's wrapped libc: `random_r`,
`initstate_r`, ...); with the file renamed away it is a clean ENOENT - the AV happens
in both modes, so the trigger is the failed `dlopen` path itself, not one specific
relocation.

Fix attempts this round (two steps, only the second works):

Step 1 - a 1840-byte placeholder `libfontconfig.so.1` (x86-64 ELF, no NEEDED, no
relocations, no exports; built with NDK clang `--target=x86_64-linux-gnu -nostdlib`)
placed at `rootfs/usr/lib/x86_64-linux-gnu/libfontconfig.so.1`. NOT ENOUGH: `dlopen`
succeeds but every `LOAD_FUNCPTR` `dlsym` still fails, `init_fontconfig()` returns
early, all `pFc*` stay NULL - and the crash site is NOT guarded by that: `enum_fallbacks`
(font.c:1030) always calls `font_funcs->enum_family_fallbacks` (wired at
freetype.c:3884) once index >= 3, which runs `fontconfig_enum_family_fallbacks` ->
`create_family_pattern` -> `call *pFcPatternCreate` == call to NULL. Same AV, same
`c0000005` (measured on the phone with the empty stub in place, wine9.log).

Step 2 (the fix) - a functional no-op `libfontconfig.so.1` (5640 B, x86-64, no NEEDED,
no relocations; sha256 0e967100613908e3b5769cf2c0e089f6d7f09c4f4b0907b3ae28415c393d3619)
exporting all 26 Fc* symbols wine dlsyms, with the semantics of "fontconfig present but
no config, no fonts": `FcInit` returns TRUE (so `fontconfig_enabled` is set and wine's
normal guarded paths stay intact), `FcFontMatch` returns NULL with `*result =
FcResultNoMatch` (wine reads that out-param; it is uninitialized stack otherwise),
`FcFontList` returns a static empty `FcFontSet`, `FcConfigGetCurrent` returns NULL (so
`load_fontconfig_fonts` bails out cleanly), every getter writes zero/NULL and returns
`FcResultNoMatch`, all destroy/set functions are tolerant no-ops. Result on the phone
(one `wineboot` run, same server-lifetime rules): `PREFIX_BOOT_OK wineboot -i exit 0;
.ud-boot-ok written`; `NtUserInitializeClientPfnArrays() retval=00000000` (was
c0000005 16/16); 0 `Ask to run at NULL` in the whole 1.0 MB log; wineserver log shows
`create_winstation() = 0 { handle=0078 }` and `create_desktop() = 0 { handle=007c }`;
our uid at 6 procs at rest / 9 during boot, no `explorer.exe` process anywhere; box64
log: `Using emulated .../rootfs/usr/lib/x86_64-linux-gnu/libfontconfig.so.1`. Two
handled `c0000005` SEH faults at `ntdll.so` +0x49876 (a write to a protected page,
info[0]=1) remain in the log; wineboot still exits 0 - noted, not blocking.

Source + build recipe: `android/win86/fontconfig-stub/` (stub.c, build.sh). Deployment
this round was phone-side (copy into `files/win86/rootfs/usr/lib/x86_64-linux-gnu/`),
the real 1.12.0 lib kept as `libfontconfig.so.1.12.0` + `.hidden` symlink for rollback;
wiring the stub into the in-app rootfs fetch (Phase2Data) is left for the next round.

## Round 3b results table (2026-10-18, phone)

| What | Number / result | When / where |
|---|---|---|
| wineboot, broadcast -> `.ud-boot-ok` | **8.1 s** (device clock, +-1 s; fresh server, fast path) | 00:13 phone; `files/phase2/wine.log` L3574 |
| boot fix signature | `PREFIX_BOOT_OK` x1; `NtUserInitializeClientPfnArrays retval=00000000`; `Ask to run at NULL` x0; box64 `Using emulated ...libfontconfig.so.1` | same 00:13 run |
| winstation / desktop | `create_winstation() = 0 { handle=0078 }`, `create_desktop() = 0 { handle=007c }` | wsrv.log 23:38 run (same boot path) |
| NPP launch -> process visible | 9884 ms; first frame: never (B-026) | 23:54 |
| PSS/RSS per process (NPP session) | app 38.0/155.8 MB, app:black 14.1/105.5 MB, Xvfb 7.1/15.6 MB, wineserver 6.2/14.5 MB, notepad++.exe 84.3/99.3 MB, services.exe 2.3/14.0 + 17.9/29.5 MB, explorer.exe 105.4/120.4 MB; sum PSS ~275 MB | 23:54 |
| our process count | 8 (NPP + explorer session); 6 at rest after fresh boot | 23:54 / 00:13 |
| idle CPU (8 procs incl. NPP) | sum CPU 30 -> 41 s over ~9.5 min (~1.9% of one core) | 23:45-23:55 |
| 10-minute survival | same 8 PIDs in all 10 samples; CPU advancing every sample; MemAvailable 1922-2318 MB; watchdog `procs=8` continuous | 23:45-23:55 |
| build gates | `:app:testDebugUnitTest` forced rerun (13 tests, 0 failures) + `:app:lintDebug` + `:app:assembleDebug` -> BUILD SUCCESSFUL; APK 23,477,499 B | 00:12-00:15 |
| box64 pin / scripts | `fetch-box64.sh` default = main `27860e6a...` (commit hash + tarball sha 80f836b4...); all anchors dry-run-validated (main + v0.4.4 shapes); `fontconfig-stub/build.sh` reproduces the stub bit-identical (5640 B, 0e967100...) | 00:10-00:15 |

Remaining blocker: B-026 (no wine window maps under Xvfb; `NtUserShowWindow` -> c0000005 in
wine's syscall dispatcher during win-proc callbacks). Skipped items 4-5 (window PNG, typed
text) are recorded in SKIPS.md T6 with the same cause.
