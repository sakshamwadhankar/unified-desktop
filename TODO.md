# One mouse + Phase 10 finish: to-do

**Final status 2026-10-22 02:06:** 8/8 items attempted; **Phase 10 is NOT PASS**. 4a, 4c
(manager's connected-first gate), 4d, 4e, the 2-hour soak, B-049, B-072, B-094 and ONE MOUSE
passed; 4b is SKIPPED (no qualifying call); **Phase 9 Exit FAILED** (lock restored in 78 ms,
view process alive > 60 s; B-098 open); resource governor remains **PARTIAL** (lower-resolution
output and before/after resource numbers unproven). No commit or push.

1. **Start [done]:** phone `2c0f6edc`, Wi-Fi `192.168.1.16:47600`; no ADB forward for the
   final 4c/soak video transport. All inputs guarded by `mCallState=0|0`.
2. **ONE MOUSE [done, phone PASS]:** PC shows the only arrow (`view.rs:471`, `574-580`);
   phone hides its pointer (`Phase5Desktop.kt:126`); `hCursor=0x10003`, real click
   `(70,1108)` opened Start. Evidence `p10/phone-b095-*`, BUGS B-095.
3. **B-094 [done, phone PASS]:** after real cold stops (`run-as ... killall`; `pidof` empty)
   App Center uninstalled Notepad++ 8.9.8.1 exit 0 and 7-Zip 26.04 exit 0; MSI state restored
   3/3 and cleared; menu rows lost both entries. Evidence `p10/b094-result.txt`,
   `p10/b094-p7.log`, `p10/b094-wine.log`.
4. **Phase 10 short gates [done]:** 4a PASS; 4b SKIPPED; 4c PASS 3/3, connected -> picture
   `3668/2798/2774 ms` (legacy enable -> picture `5954/5329/5322 ms`, TLS
   `40.5/48.5/42.2 ms`); 4d different PC refused; 4e OEM persistence PASS. X11/App Center
   rects and guest PIDs unchanged; B-097 not reproduced under script-only input.
5. **Two-hour soak [done, phone PASS]:** 25 samples / 121.0 min, app pid 28212 unchanged, CPU
   advancing 24/24, LMK 0, TLS 0, battery 47 -> 57 %, max temp 38.2 C, thermal 3; receiver
   43824 continuous 105.4 min with dropped=0.
6. **Phase 9 after soak [done, FAIL]:** lock-exit restored Default in 78 ms but the view did
   not exit; `phase9_exit.ps1` timed out at 60,107 ms and killed it; PC recovered clean
   (`lock-state=Normal`, journal clean). B-098; do not fix in this task.
7. **Gates [done]:** Android `155 tests / 0 failures / 3 skipped`, lint+assemble BUILD
   SUCCESSFUL; PC `cargo test --release` 58/0, clippy clean; `git diff --check` exit 0.
8. **Docs, cleanup, report [done]:** `docs/PHASE10_RESULT.md` updated; `docs/PHASE9_RESULT.md`
   carries the post-soak failure; BUGS/SKIPS updated; no `udreceiver.exe`, no ADB forwards;
   phone `stay_on=0`, brightness `699`, Wi-Fi ON; `STATE.md` updated last. Evidence stays in
   `C:\Users\Asus\AppData\Local\Temp\opencode\p10`.
