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
| T6 gate (Wine + Notepad++ typed-text PNG): the B-022 desktop-window blocker is FIXED (round 3b 2026-10-17: functional no-op fontconfig stub -> boot in ~20 s, `create_winstation`/`create_desktop` OK, 6-9 procs, no explorer chain), but the gate is still blocked one layer later: no wine window ever maps to X under box64. `NtUserShowWindow` on NPP's main window returns `c0000005` via a fault in wine's syscall dispatcher while window-proc callbacks run (BUGS B-026; screen stays black, 5336 B capture, while native xeyes on the same server renders: 9442 B). | Needs the B-026 root cause fixed (likely a box64 signal/dispatcher-nesting issue under win-proc callbacks): pin a different box64/wine candidate (D13 revision) or take it upstream. Done when: one Notepad++ window PNG from our Xvfb plus "hello from phase 2" typed via XTEST visible in a PNG. Evidence: `files/phase2/wine12.log` L13210-13316, `wine14.log` L12950-12980, captures npp*.png (5336 B) vs xeyes-b.png (9442 B). Round 4 (2026-10-18) added: the box64 rt_sigreturn-synthesis patch was tried in full and REVERTED (a host-side rt_sigreturn cannot consume the guest sigframe); root cause narrowed to wine's disabled SUD plus the synthesized-SIGSYS emulation returning c0000005 to callers (docs/PHASE2_RESULT.md "Round 4"); reproduced from a clean install (NPP dies 1.5 s in at comdlg32.dll load, capture bit-identical to the black reference; xeyes 9442 B renders fine). **Round 5 (2026-10-18): five more attempts all failed on the phone** - F1 (SIGSYS loop at 0x3f0004974c), F2 (244A0424: c0000005 storm 1248-1250 per 60 s), F3 (613AA82D: storm, fault 1457), F1-off A/B (613AA82D + 2-site ntdll c378cd70: storm, fault 1198), recipe control F69ACC14 (storm, fault 1658); every capture bit-identical to the black reference `527F19E0...` (5336 B); the sub1f-off hypothesis was refuted (compile errors prove 006841B5 already contained the sub1f masks). **Wine 11.0** (pre-SUD, manager note 3) + fontconfig stub on lib 006841B5: boots (`PREFIX_BOOT_OK`, 25 s) but NPP exits code 159 in 9-23 s with zero c0000005/SIGSYS, and a live mid-run capture is byte-identical to the black reference - still no window. Next: reproduce the baseline lib (wrapper-generation bisect), then the open leads in BUGS B-026. **Round 5c (2026-10-18): SOLVED on the phone - both gate PNGs exist.** Root cause: the guest SysV-shared-memory path (winex11 XShm) under box64; fixed by the F4 block in `fetch-box64.sh` (raw syscalls 29/30/31/67 + the wrapped-libc `my_shm*` stubs). Window PNG from the clean install `evidence/phase2/npp-window-clean-install.png` (17,998 B); "hello from phase 2" typed through the app path visible in `npp-typed-clean-install.png` (18,238 B) with the 30-s-later capture unchanged. Numbers + method in docs/PHASE2_RESULT.md "Round 5c". The wrapper-layer bisect is no longer gate-blocking: the recipe is proven not reproducible (BUGS B-030, three shas) and the shipped bytes are the tested artifact. Awaiting manager verification + commit. | 2026-10-17 |
| Phase 2 hardening: B-019 clean install from a truly wiped phone; a keep/delete pass over the ~90 scratch scripts under `android/win86` (am*.sh, runp*.ps1, patchxvfb*.py, ...); D13 (box64/wine pin) and D4 notes updated with the round-2 findings (B-020 patch, B-021 load-path poison, B-024 loader workaround); ARCHITECTURE.md H6/H7 rows refreshed. | Manager pass; deferred without code risk. **Update 2026-10-18 (agent, round 4): the clean-install part is DONE from a wiped phone** - see the B-019 update in BUGS.md and the round-4 results table in docs/PHASE2_RESULT.md (numbers + evidence). Still open from this row: the `android/win86` scratch-script keep/delete pass, D13/D4 notes, ARCHITECTURE.md refresh (manager). | 2026-10-17 |
| T7 survival soak (10 minutes with the NPP window up: same PIDs, CPU advancing, window in the end capture). | Skipped in round 4 - there is no window to keep alive (B-026; the NPP process self-dies 1.5 s in). Done when: B-026 is fixed and the window stays up; then run the 10-minute soak with `svc power stayon true` and the watchdog live. Round-4 substitute: the clean-install xeyes display proof + the 10/10 Xvfb re-verification (B-018) settle the server-side stability question. Round 5: still no window; longest NPP life measured = >180 s on the baseline lib (intentionally killed; CPU advancing), Wine 11.0 runs die in 9-23 s by themselves (exit 159); the 10-minute soak stays blocked by B-026. **Round 5c: DONE** - soak ran 10 min 26 s with the NPP window up and the typed text visible (same PIDs in every sample, CPU advancing, watchdog live; end capture `evidence/phase2/npp-window-11.19-10min.png` byte-identical to the typed frame `30F55E37...`); no LMK kill during that run. One earlier LMK kill (10:11:50, `lowmemorykiller ... free 18160`) is documented in B-026; the product-side answer is the Phase 4 foreground service (B-027). | 2026-10-18 |
| H3 "Box64 settings sweep" as a B-026 fix. | Skipped as meaningless after the round-4 diagnostics: there is no box64/wine setting that enables wine's SUD on this device (wine cannot find the restorer -> SUD stays off) and the synthesized SIGSYS is box64's intended emulation of that case. Done when: the real fix lands (wine-side SUD restorer visible to dl_iterate_phdr, or a box64-side guest-stack-correct SUD/rt_sigreturn); no separate settings work is planned. | 2026-10-18 |
| Round-5 B-026 follow-up (manager notes 2+3): the shipped baseline lib `006841B5` is not reproducible from `android/win86/fetch-box64.sh` (577,476 differing bytes, all in the regenerated wrapper layer `src/wrapped/generated/`; same size, identical dynsym), and the Wine `11.0` pin needs an x86-64 `libgcc_s.so.1` that neither the 11.0 tree nor the runtime-lib set provides (staged manually for the round-5b test; 11.19 needs none). | Needed before trusting any further lib A/B or making 11.0 a candidate: (a) build twice from the same source+script and diff `src/wrapped/generated/wrapper.c` + `functions_list.txt` (`rebuild_wrappers.py` determinism) until two builds are byte-identical; (b) if 11.0 stays a candidate: fetch `libgcc-s1` (pinned deb) like the other runtime libs and stage it into the wine tree's `lib64/` automatically. Done when: two builds from the same recipe are byte-identical and a clean install boots either pin with zero manual staging. **Round 5c: (a) is closed with a policy, not a fix** - the recipe is proven NOT reproducible (three different shas: shipped `e1c21ed2...`, `a8f3f177...`, `80b20b76...`; 745,057 differing bytes vs the shipped file) -> BUGS B-030; the shipped, phone-tested bytes are the artifact (sha recorded in `android/win86/fetch-box64.sh`, THIRD_PARTY.md and B-026; canonical backup outside the repo in gitignored `vendor/`). (b) stays open only for the day 11.0 becomes a candidate again (the shipping pin is 11.19 and needs no `libgcc_s.so.1`). | 2026-10-18 |
| **AOA round (Phase 4 item 8 follow-up):** the AOA leg is NOT PROVEN (see `docs/PHASE4_RESULT.md` section 7 and BUGS B-043): probing covered only the debugging-ON state and WinUSB-bound interfaces, and UsbDk is a signed detach route that has not been tried. | Needs the owner present for **one UAC click**; exact steps: (1) THIRD_PARTY.md row for UsbDk [done 2026-10-19]; (2) owner installs the UsbDk MSI (signed; one UAC click); (3) the receiver gets `aoa start --usbdk` (UsbDkHelper.dll: GetDevicesList, StartRedirect, then a control transfer on endpoint 0); (4) owner turns USB debugging OFF and plugs in (MTP/WPD state); (5) run `aoa start`, expect re-enumeration as `18D1:2D00` and our app opening the accessory, then pair + bench; (6) uninstall UsbDk if not kept. Done when: `aoa start` switches the phone from the normal (debugging-off) state and a bench runs over the accessory with evidence; or the manager drops AOA and the product ships Wi-Fi + tethering only. | 2026-10-19 |
| **Phase 6 carry-over from Phase 5:** (1) the 1 s keep-alive frame on a STATIC desktop (B-054) - **emulator-proven 2026-10-19**: with the Phase-6 shell sitting still the phone emits 1.0 fps (10 frames/10 s, `P5 VSTAT sent=` delta; 2.3 fps with a static Files window); the receiver-side ">= 1 frame/s at the receiver" proof is blocked on the emulator by B-057 and stays open for the phone round; (2) the B-039 WebView keyboard probe - **run 2026-10-19**: `route=edittext-ime` (PC keys reach the browser's address field through our EditText's IME route and a typed URL loads); the page-DOM keydown route stays open as B-039. | (1) done when a still desktop shows >= 1 fps at the receiver on the phone; (2) done when a PC key reaches the page itself (or the manager accepts the IME route as the design). Evidence: `docs/PHASE6_RESULT.md` sections 1-2, TODO item 5. | 2026-10-19 |
| **Phase 6 item 8 - phone round (the real gate):** round the shell on the test phone `2c0f6edc` - the phone was unplugged all run (polled from 06:09 to the hard stop) and never appeared in `adb devices`, so the phase's phone gate could not run. | Needs the owner to plug the phone in (USB debugging on). Done when these run on the phone with evidence in `docs/PHASE6_RESULT.md`: install + dismiss the OEM scan dialog with Ignore, Start link, `udreceiver view --addr auto` over Wi-Fi, receiver-window screenshots (start menu open, overlapping windows), 30 s drag fps + idle fps from the receiver's VSTAT, 20 typing samples into the browser address bar, stuck keys after 100 keys + 10 drags. Every check has an emulator stand-in (labelled) that does NOT close this row. | 2026-10-19 |


The emulator limitation and all round-2 results are summarized in `docs/PHASE1_RESULT.md`.

---

## No-adb / no-developer-mode proof (item 11, 2026-10-18; label: code-only)

Product rule (AGENTS.md): no developer mode, no root, no ADB from the user at any step.
Grep transcript over the shipping sources `android/app/src/main` (case-insensitive):

- **A. `adb` occurrences** (`Get-ChildItem -Recurse -Include *.kt,*.xml | Select-String "adb"`):
  real hits are **doc comments only**, in the debug-harness classes and two older notes:
  `HarnessActivity.kt:23,25`, `HarnessReceiver.kt:12`, `Phase3Dispatch.kt:9`,
  `Phase4Dispatch.kt:10` (usage examples for `adb shell am ...`),
  `Phase3CodeActivity.kt:60`, `Phase3Server.kt:107`, `Phase2X11.kt:100` (B-019 note).
  Every other match is the substring inside `readBytes` (e.g. `Phase2Data.kt:148`).
- **B. process starts** (`ProcessBuilder|Runtime.getRuntime`): all 20 sites exec the app's
  own `lib*.so` from `nativeLibraryDir` (Phase-2 Box64/Wine, Phase-3 Node). No `su`, no
  `adb`, no `sh -c` of anything that is not one of our own binaries.
- **C. developer-mode / root signals** (`ADB_ENABLED|development_settings|isRooted|
  /system/xbin|which su|su -c`): a single hit, `Phase3Native.kt:102`, which only sets the
  child-process env `PATH=/system/bin:/system/xbin` for the Node interpreter. No root
  binary is invoked and no developer setting is read anywhere.
- **D. manifest permissions**: `INTERNET`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_DATA_SYNC`,
  `POST_NOTIFICATIONS` - no privileged/system-only permission.

Conclusion: the shipping code path never reads a developer-mode setting, never calls a
privileged API and never spawns a process we do not ship; all `adb` mentions are comments
for our test harness.

**Owner check steps** (on a stock phone; keeps developer options OFF, USB debugging OFF):

1. Leave developer options disabled (never tap "Build number" seven times); keep USB
   debugging off.
2. Install the app normally and open it once. It asks for the notification permission
   (Android 13+ dialog; required for the `Pair this PC?` notification) and shows a
   `Start link` / `Stop link` pair of buttons with a status line. Tap `Start link`; the
   `Desktop link running` notification appears. No ADB, no harness broadcast.
3. Wi-Fi: phone and PC on the same network; on the PC run `udreceiver discover`, then
   `udreceiver pair --addr auto` (or `--addr <found-ip>:47600`). Expect the
   `Pair this PC?` notification with the same 6-digit code as the PC, then Yes -> the
   bench must run. No ADB, no driver.
4. USB: plug the phone in. Expect the Android accessory dialog for unified-desktop; the
   attach starts the link service itself and hands it the accessory FD (item 8; the TLS
   transport over that FD is not wired yet), or the tether path from item 9. No driver
   install is offered by us, no `adb` anywhere.
5. If any step demands developer mode, ADB or root, or a system dialog says "USB debugging
   required", that is a bug: screenshot it and file it in BUGS.md.

