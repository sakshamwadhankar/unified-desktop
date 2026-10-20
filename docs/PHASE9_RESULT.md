# Phase 9 result: PC lock + Exit

2026-10-20 20:45 - 2026-10-21 05:10 (T0 01:10; review rework 04:30-04:56; manager gate
05:10). Receiver: `pc/udreceiver`; phone 2c0f6edc (USB, `adb forward tcp:47600`); Windows 11
26H2 build 26300, UBR 9711, **not elevated**.

```
PHASE 9 GATE (Windows 11 26H2 build 26300.9711): PASS - private lock desktop, 8/8 key
combinations blocked, Exit restores, watchdog/phone-gone/frozen-video restore, crash guard
returns input to Default; Windows 10 and policies are declared SKIPs (no hardware / not admin);
multi-monitor is code-only
BLOCKED KEYS: Win yes | Alt+Tab yes | Alt+Esc yes | Ctrl+Esc yes | Taskbar yes
EXIT: button yes | lock-exit->exit 291 ms (switch 21, lock path 206) | frozen video yes | phone gone yes
CRASH RECOVERY: guard 12 ms (426 ms wall) | journal clean yes | second view refused 42 ms | next view after guard+lock kill recovers yes
FAIL-CLOSED: failed restore view exit 52 ms, journal unchanged, no lock | selftest refused
CLEAN RECOVERY: clean journal on lock desktop 75 ms | unreadable journal 59 ms, bytes unchanged
GATES: Android 149 tests / 0 failures / 3 skipped, lint+assemble BUILD SUCCESSFUL | PC 55/0 release, clippy clean | diff check 0
WINDOWS 10: SKIP - no Windows 10 hardware available this session; APIs are Windows 10-era
POLICIES (Ctrl+Alt+Del options): not-admin
```

## What was built

`pc/udreceiver/src/lock.rs` (2,830 lines) owns the whole lock; `view.rs`, `main.rs` and
`Cargo.toml` are the only other PC files touched. No new crate.

- **State machine.** `Normal -> Locking -> Locked -> Exiting -> Normal`. `transition()` rejects
  every other edge (`invalid lock transition ...`); `Lock::exit()` is idempotent, so a second
  exit does nothing.
- **Session guard.** `view` takes the single-instance mutex (`Local\udreceiver-lock-single`)
  and named event (`Local\udreceiver-lock-exit`) **before recovery**, calls
  `Session::recover()`, and passes the guard into `Lock::prepare`, which holds it through
  desktop and journal creation. `lock-selftest` does the same; `lock-recover` and the crash
  guard take the same mutex for their work. A second `view` therefore fails in ~42 ms before
  it connects to the phone or reads/rewrites the live journal. `--no-lock` keeps the guard
  for the whole view.
- **Separate desktop.** `Lock::prepare(opts, session)` creates `udreceiver-lock-<pid>` on
  `WinSta0`, starts the watchdog/status threads (on the default desktop), calls
  `SetThreadDesktop`, then creates the journal. The default desktop and its taskbar are never
  hidden, moved or modified.
- **Keyboard hook.** `WH_KEYBOARD_LL` is installed **on the lock desktop after `SetThreadDesktop`
  and before `SwitchDesktop`**. It swallows both `WM_KEYDOWN` and `WM_KEYUP` for LWIN, RWIN,
  Alt+Tab, Alt+Shift+Tab, Alt+Esc, Ctrl+Esc, Ctrl+Shift+Esc and Alt+F4, with one atomic counter
  per key `(down, up) => 2`. Mouse messages are never filtered. The injected-flag is not
  inspected, so the self-test exercises the same path as a physical key.
- **Exit overlay.** A separate top-level `WS_POPUP` window, `WS_EX_TOPMOST | WS_EX_TOOLWINDOW |
  WS_EX_NOACTIVATE`, 120x40 DPI-scaled at the primary monitor's top-right, paints "Exit" with
  its own wndproc (no child controls, no activation). A click calls `Lock::request_exit()`;
  `SetWindowPos(HWND_TOPMOST)` is re-asserted every 500 ms. It never sends input to the phone.
- **Every exit path ends in `Lock::exit()`:** the button, `WM_CLOSE`, Esc while locked, the
  pipeline ending (phone gone, `--seconds`), the watchdog, and internal errors. Order:
  unhook -> `SwitchDesktop(Default)` -> **verify `input_desktop == Default`**, retrying
  `ensure_default_input` -> restore policies -> journal clean/delete -> close handles ->
  stop the lock threads; the caller then stops the pipeline. If Default cannot be verified,
  the journal, Exit window, watchdog and guard stay in place, and the mutex is deliberately
  kept until process exit. A failed policy restore also leaves the journal dirty.
  `PostQuitMessage` cannot skip the restore because it is only posted after `exit()` returns.
- **Watchdog.** A thread created on the default desktop before the switch. It exits on the
  named event or `--lock-max-seconds`; if the owner thread does not return input to Default
  within 2 s, the watchdog calls `SwitchDesktop(Default)` itself.
- **Journal.** `%LOCALAPPDATA%\unified-desktop-receiver\lock-state.txt`, lines
  `clean=0|1`, `desktop=<name>`, `pid=<owner pid>`, and
  `policy=<HKLM|HKCU>|<path>|<name>|<type>|<value>`. It is written atomically (tmp + rename)
  **before** each policy application. The parser is strict: a missing `clean=`/`desktop=`,
  or a duplicate/malformed/unknown line, is an error. `Journal::start_at` refuses a dirty or
  unparseable existing journal, so a new lock can never overwrite the only undo record. `restore_and_clean`
  deletes the file before marking it clean; unreadable journals are left byte-for-byte
  untouched.
- **Crash guard (deviation, documented).** `taskkill /F` proved that Windows leaves a dead
  process's private desktop as the input desktop, so the next-start restore alone cannot
  unlock the screen. `Lock::prepare` starts `udreceiver lock-guard --parent <pid>` while
  the thread is still on the default desktop (same binary, `CREATE_NO_WINDOW`, log at
  `lock-guard.log`). The guard waits for the PID, takes the single-instance mutex (so it
  cannot race a new session), and restores only a journal whose `pid` and
  `udreceiver-lock-<pid>` desktop match the parent. If only the parent's desktop is left it
  switches that to Default and leaves a foreign journal alone; it never switches another
  private desktop. If the guard is killed too, the next `view` recovery still works (T17:
  guard and lock killed, next `view --no-lock` recovered the dirty journal, returned Default
  and removed it).
- **Policies.** Only when the process is elevated **and** `--policies` is passed; default off,
  no UAC prompt. Five HKLM exam-software values (Task Manager, lock workstation, logoff, fast
  user switching, change password) are journaled old-value-first and restored in reverse
  order. Not applied on this laptop (not admin); the HKCU equivalent is unit-tested.
- **Multi-monitor.** Black `udreceiver-lock-black` windows cover every monitor, the video
  window uses `--x/--y/--w/--h` (primary monitor's full rect by default), Exit stays on the
  primary monitor. Single-monitor laptop, so this layout is **code-only**.
- **CLI.** `view --lock | --no-lock | --lock-max-seconds <s> | --freeze-video-after <s> |
  --policies`; `--no-input` implies `--no-lock`. Lock is engaged only after
  `connect_and_pair` succeeds. **The default is now lock** (`DEFAULT_LOCK = true`), flipped
  in T6 after G4/G5 passed; `--no-lock` keeps the old desktop.
- **Diagnostics.** `lock-status` (input desktop, state, process, journal dirty/clean, exit
  rect, per-key counters), `lock-exit` (named event), `lock-recover`, `lock-selftest
  --seconds`, and the harness-only `lock-inject --keys | --click x,y | --click-exit | --shot |
  --windows | --expect` which runs on the lock desktop.

## Results

| Item | What was done | Files | Test | Verified on | Numbers |
|---|---|---|---|---|---|
| State machine | legal edges only, invalid transition error; `Lock::exit` is idempotent in code | `lock.rs` | `state_machine_chain_and_invalid_transitions` | code-only | 1 test |
| Key filter | eight combinations, down+up, shift precedence, normal keys pass | `lock.rs` | `hook_filter_blocks_all_eight_combinations_on_down_and_up`, `hook_filter_passes_normal_keys` | code-only | 8/8 + normal keys |
| Journal | atomic write, reverse restore, idempotent, strict parser, owner PID, refuse dirty/unreadable | `lock.rs` | `journal_round_trip`, `journal_restore_is_idempotent`, `journal_start_refuses_dirty_or_unreadable_and_preserves_bytes`, `failed_restore_keeps_the_journal_and_a_new_lock_cannot_overwrite_it`, `journal_records_owner_pid_for_the_crash_guard` | code-only | 5 tests |
| Registry policy | HKCU value journaled, changed, restored; missing value deleted | `lock.rs` | `policy_value_round_trip_current_user` | code-only (HKCU) | 1 test |
| Self-test | switch, inject all 8, restore | `lock.rs` | `lock-selftest --seconds 3` | **phone** | switch 11 ms; counters all 2; exit switch 18/total 95 ms; PASS 8/8, exit 0 |
| Lock desktop | input desktop is `udreceiver-lock-<pid>`, default never hidden | `lock.rs` | `view` + `lock-status` | **phone** | 9-15 ms switch; first frame 435-1033 ms |
| Taskbar | no shell/taskbar on the lock desktop | `lock.rs` | `lock-inject --windows` | **phone** (pre-review) | 11 windows; no `Shell_TrayWnd`/`Progman`, Exit topmost 120x40, video topmost full screen, black window non-topmost |
| Blocked keys | Win, Alt+Tab, Alt+Shift+Tab, Alt+Esc, Ctrl+Esc, Ctrl+Shift+Esc, Alt+F4 | `lock.rs` | `lock-inject --keys` | **phone** | 8/8 counters = 2; foreground stayed `udreceiver-view` |
| Exit button | click centre of the overlay, 2 runs; timing includes the injector start | `lock.rs` | `lock-inject --click-exit` | **phone** | click command -> view exit 984 / 641 ms; lock path total 211 / 100 ms (switch 18 ms) |
| Watchdog | `--lock-max-seconds 6` ends the session | `lock.rs` | timed run | **phone** (pre-review) | 109 ms lock path |
| `--seconds` | normal pipeline end must restore | `view.rs` | `view --seconds 8` | **phone** (pre-review) | 128 ms lock path |
| Phone gone | `am force-stop` while locked | `view.rs` | forced stop | **phone** | 1134 ms after stop; lock path switch 14/total 76 ms |
| Named event | `lock-exit` from another process | `lock.rs` | `lock-exit` | **phone** | lock-exit -> view exit 291 ms; switch 21/total 206 ms |
| Frozen video | `--freeze-video-after 5` stops Present, keeps last frame | `view.rs` | timed run + click | **phone** | froze at 5 s; click command -> exit 641 ms; lock path 18/100 ms |
| Crash guard | kill the lock PID only | `lock.rs` | `taskkill /F /PID` | **phone** | guard log 12 ms; Default observed 426 ms after kill; journal deleted, no guard left |
| Second view | start a second `view` while the first is locked | `view.rs` | second `view` | **phone** | refused in 42 ms before connect; first stayed alive (CPU advanced); phone live=1; journal hash unchanged |
| Next view after guard+lock killed | kill the guard, then the lock, then start a normal view | `lock.rs`, `view.rs` | T17 `view --no-lock` | **phone** | recovered the dirty journal before connect; Default; journal removed; 6 s phone view; no leftover process |
| lock-recover while locked | recovery must not touch a live session | `lock.rs` | `lock-recover` | **phone** | exit 1; journal hash and `Locked` state unchanged |
| Fail-closed recovery | dirty journal + denied HKLM restore (not elevated) | `lock.rs` | `view`, `lock-selftest` | **phone** | view exit 52 ms, no connect/lock; journal byte-identical; selftest exit 1 |
| PID-aware guard | guard for a wrong PID while another session's desktop is input | `lock.rs` | `lock-guard --parent 999999` | **phone** | journal and desktop untouched; following `lock-recover` 66 ms to Default |
| Leftover desktop | clean journal on a private lock desktop, no live session | `lock.rs` | clean journal + `lock-recover` | **phone** | 75 ms to Default; journal unchanged, exit 0 |
| Unreadable journal | malformed journal on a private lock desktop | `lock.rs` | malformed journal + `lock-recover` | **phone** | 59 ms to Default; journal hash unchanged, exit 1 |
| Default lock | no flags locks, `--no-lock` does not | `main.rs` | live runs | **phone** | no flags: `Locked`, journal dirty; `--no-lock`: Default, guard held, second view refused |
| PC gates | unit tests + clippy after the review fixes, re-run by the manager | `lock.rs`, `view.rs` | `cargo test --release`, `cargo clippy ... -D warnings` | code-only | **55/0 release**, clippy clean |
| B-093 | exe opens rename the reused X11 frame | `AppCenter.kt`, `Phase8Open.kt` | `p6:appcenter:exe`, `p6:rects`, `p6:shot` | **phone** | `X11 desktop — winemine` in title bar and taskbar; unit test `anExeOpenRenamesTheReusedFrameFromTheFileName` |
| Policies | apply+restore when elevated and requested | `lock.rs` | HKCU unit test only | code-only (`not-admin`) | 1 value round-trip |
| Multi-monitor | black every monitor, video rect, Exit on primary | `lock.rs` | code inspection only | code-only | 1 monitor available |

### Resource numbers (locked, phone streaming, 10 s sample)

| Metric | Value |
|---|---|
| CPU (receiver, locked, idle desktop) | **1.2 % of one core** (0.125 s over 10.04 s) |
| Working set | **133.9 MB** (same before/after the sample) |
| Video | `VSTAT fps=1.0 presented=decoded dropped=0`, decode 4.3-5.4 ms, D3D11 hardware |
| Engage | desktop switch 9-15 ms; first frame 435-1033 ms after engage |
| Exit | `lock-exit` -> view exit 291 ms (log switch 21 ms, `Lock::exit` total 206 ms); click command -> exit 641-984 ms, lock path 100-211 ms |

The latest lock path itself (unhook, switch, verify, restore, cleanup) is **100-211 ms**. The
click-command numbers include starting `lock-inject` and the receiver joining the phone
session; they are not the desktop-restore latency. The 291 ms named-event number is event to
process exit, while the view log's `switch_ms=21` is the verified return to `Default`.

## Evidence

All pre-review captures/logs are under `C:\Users\Asus\AppData\Local\Temp\opencode\`
(not committed). The agent's review runs (`p9t12`-`p9t16`) were temporary; they were removed
after their numbers were recorded in the table above. The manager's post-review T17 log is
kept under `p9t17/` for audit (not committed).

- `p9t4/` - first live lock: keys (8 counters = 2, foreground unchanged), Exit 841/655 ms,
  `locked.png`/`locked2.png` (lock desktop black + Exit), `phone.png` (phone harness),
  `exit-window.png` (PrintWindow of the 120x40 Exit overlay with the painted label).
- `p9t5/` - freeze (665 ms), watchdog (109 ms), `--seconds` (128 ms), phone gone
  (1.1 s/65 ms), named event (120 ms), restarts.
- `p9t6/` - kill without a guard: input stayed on the lock desktop (the fact that forced the
  guard), then explicit `lock-recover`.
- `p9t7/` - kill with the guard: `[lock-guard] restored ... in 17 ms`, journal deleted,
  `lock-status` Default.
- `p9t8/` - guard killed first, lock killed, next `view` restored the dirty journal in 0.6 s
  (pre-review; repeated after the Session rework as manager T17 below).
- `p9t9/` - fresh lock after the guard change; full `lock-inject --windows` list with no
  taskbar; Exit 678 ms.
- `p9t10/` - CPU/RAM/fps sample.
- `p9t11/` - default lock and `--no-lock` proof.
- `p9t17/` - manager, post-review: guard and lock killed, then the next
  `view --no-lock --seconds 6` recovered the dirty journal before connecting, returned
  `Default`, removed the journal, streamed the phone, and left no receiver or guard process.
- `p9b093/` - `b093.png`: `X11 desktop — winemine` title bar and taskbar over the board.

## Skips and open items

- **Windows 10: SKIP** (SKIPS.md row 2026-10-21). No Windows 10 machine was reachable this
  session and no VM is allowed. Every API used (`CreateDesktopW`, `SetThreadDesktop`,
  `SwitchDesktop`, `WH_KEYBOARD_LL`, `SetWindowPos`, registry, GDI) exists on Windows 10;
  this is an argument, not a proof. The code avoids Windows 11-only calls.
- **Policies: not-admin.** The laptop is not elevated, so `--policies` was not applied here;
  only the HKCU journal/restore path is tested. The HKLM path is the same code with a
  different root.
- **Multi-monitor: code-only.** One 1536x960 laptop panel; no second display was available.
- **Screen capture of the flip-model video** from the default desktop fails with "handle is
  invalid" (the desktop belongs to another process). `lock-inject` runs on the lock desktop
  and captures it; `PrintWindow` proves the Exit label. This is a measurement limitation,
  not a product behaviour.

## Bugs

- **B-093 FIXED (phone):** `AppCenter.runExe` now calls
  `shell()?.openApp(ShellRoot.App.X11, title = X11Title.exeTitle(f.name))` on the UI thread
  before Wine starts, so the reused frame is renamed from the `.exe` base name. Evidence:
  `p6:rects` `title="X11 desktop — winemine"` after xcalc had owned the frame,
  `APPCTR exe frame title "X11 desktop — winemine"`, `p9b093/b093.png`; unit test
  `anExeOpenRenamesTheReusedFrameFromTheFileName`.
- **Manager review corrections (in-scope, fixed):** two P1 defects found while reviewing
  the Phase 9 code: (1) recovery ran before the single-session mutex, so a second view or
  `lock-recover` could restore a live lock's journal and switch it to `Default`; (2) a failed
  recovery was ignored and a new lock could overwrite the journal. Both are fixed by taking
  the session guard before recovery and by failing closed on recovery/start; T12, T14, T15
  and T17 cover the corrected paths. They were defects in the code under review, not newly
  logged bugs outside the task.
- No new bugs were found outside this task's scope.

## Doc changes in this task

- `ARCHITECTURE.md` P1: session guard, strict journal (`pid`, refuse dirty/unreadable),
  verified `Default` exit, PID-aware guard.
- `ALGORITHMS.md` section 13: session-before-recovery, fail-closed exit/journal, recovery of a
  clean/missing/unreadable leftover desktop, PID-aware guard.
- `THIRD_PARTY.md` `windows` row: the three Phase 9 features (same crate/licence).
- `BUGS.md` B-093: OPEN -> FIXED with evidence (no new bug row from this review).
- `SKIPS.md`: Windows 10 row.
- `TODO.md`: Phase 9 items closed; review rework added.
- `STATE.md`: updated last.
