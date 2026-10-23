# TEST REPORT: unified-desktop (tester run, 2026-10-23)

Status: IN PROGRESS (first save 11:53; last update 12:17).

## 1. Summary

- Date: 2026-10-23, run started 11:37 (laptop clock).
- Build tested:
  - APK `android/app/build/outputs/apk/debug/app-x86_64-debug.apk`, 49,889,581 B, built 2026-10-23 11:14:40;
    installed on the emulator at 11:14:34, on-device `base.apk` SHA-256 `3821AB6B...4B3160A881` = the file.
  - Receiver `pc/target/release/udreceiver.exe`, 2,326,016 B, built by me at 11:38:48 (`cargo build --release`,
    6 warnings, log `tester/cargo_build.log`).
- Devices: emulator `emulator-5554` (Pixel_8_Pro AVD, x86_64, Android 17, 16 KB pages, 1344x2992); laptop
  Windows 11, 1920x1200 at 125 %. Phone `2c0f6edc` not touched.
- Evidence folder: `C:\Users\Asus\AppData\Local\Temp\tester` (named `tester\...` below).

(totals filled in at the end)

## 2. Bugs (most severe first)

### T-001 (blocker, security) Desktop video is streamed to a PC that the user has not approved yet

- Area: phone link / pairing.
- Steps: phone has no trusted PC (Settings > Trusted PCs > Forget); PC still trusts the phone; phone Start
  desktop; PC connects (`udreceiver --window --addr 127.0.0.1:47600`, then Reconnect).
- Expected: nothing but the pairing exchange until the user taps Yes (USER_WORKFLOW 2: "a stranger's PC cannot
  connect without this step").
- Actual: the phone shows "Does your PC show this code? 927361" (`tester\e09-phone-pairing.png`) and drops input
  (`DROP input (gate=PAIRING)`), but it accepts the PC's `video-on`: `P5 video-on received`, encoder starts,
  `P5 VSTAT sent=16 bytes=86654` while still pairing. The PC's locked screen shows the phone desktop
  (`tester\p09-locked-desktop-conv.png`). Phone log in `tester\` (see T-002 evidence).
- Cause (code): `Phase4LinkService.kt:775` calls `p5.onControlMessage(text)` without the gate, so
  `Phase5Session.kt:518` `gateOpen` defaults to `true` and the B-047 check at `Phase5Session.kt:536` never refuses.
  `Phase4Protocol.mayProcessChannel` lets CH_CONTROL through in PAIRING (`Phase4Protocol.kt:95`). The `display`
  control is also honoured before Yes.
- Known as: new (B-047 is the "video only after video-on" rule; this is its gate hole).

### T-002 (major) One-sided trust: PC skips the pairing screen, locks the PC, then loops reconnecting

- Area: receiver GUI + phone link.
- Steps: phone Settings > Forget the PC (PC still trusts the phone). (a) With the phone's desktop still live:
  start the receiver window. (b) After phone Stop + Start desktop: Reconnect.
- Expected: the PC shows the 6-digit code ("Pair this phone") and waits for Yes; no lock before pairing.
- Actual (a): the GUI goes straight to `view --lock`; the phone refuses every connection
  (`SESSION refused different PC while desktop is live`) and the locked PC reconnects every ~250 ms:
  116 "phone closed the link" + 115 "RECONNECT ok ... reconnected silently to pinned phone" in 30 s, each with a
  new decoder and swap chain (`tester\p06-view-gui-forgotten.log`). The phone keeps saying "Waiting for your PC"
  with no hint. Only Exit/lock-exit ends it (I used `lock-exit` after 31 s).
- Actual (b): the phone shows a pairing code, but the PC never shows it: it is already in the locked desktop
  (`tester\p09-locked-desktop-conv.png`, `tester\p10-view-gui-lock2.log`); the user cannot compare codes.
- Cause (code): `session::connect_and_pair_with` treats a pinned phone as trusted after TLS ("reconnected
  silently") without the phone confirming; `gui.rs:575-580` then launches `view --lock`; the reconnect loop in
  `view.rs:355-391` has a 10 s deadline only for failed connects, so a connect that the phone closes at once
  loops forever with no backoff. Phone side: `Phase4LinkService.kt:625` refuses any unpinned PC while
  `desktopRuntime.isUp()`, silently.
- Known as: new.

### T-003 (major) arm64 APK has native libraries that are not 16 KB aligned (Google Play requirement)

- Area: build / Play (PRD R9).
- Evidence: the emulator (16 KB pages) shows "This app isn't 16 KB compatible. LOAD segment alignment check
  failed" (`tester\e02-16kb-dialog.png`, `tester\ui-home.xml`). My ELF check (`tester\elfalign.py`):
  arm64-v8a `libbox64.so` and `libdobby.so` have LOAD p_align 0x1000; x86_64 `libdobby.so` 0x1000.
- Why it matters: Google Play requires 16 KB page support for apps targeting Android 15+; on 16 KB phones
  the app runs in compatibility mode or not at all. The test phone has 4 KB pages, so this never showed there.
- Known as: new.

### T-005 (blocker) Files > Delete freezes the whole desktop (ANR); the PC cannot dismiss the dialog

- Area: desktop Files app.
- Steps: Files, click a file (it highlights), click the trash icon.
- Expected: a "Delete ... permanently?" confirmation on the desktop.
- Actual: no dialog. The phone log shows `BadTokenException: Unable to add window -- token null is not valid`
  at `FilesView.deleteSelected(Phase6Apps.kt:315)`, caught by the engine's crash handler ("Recovered: true",
  3 crash logs in 2 s, `tester\e18-crash-delete.log`). From then on the app's main thread is dead: the
  desktop clock stays at 12:08, the debug broadcast times out, Android raises ANR at 12:12:37
  (`am_anr ... dev.unifieddesktop.app`, `tester\e19-anr-dropbox.txt`) and shows "Unified Desktop isn't
  responding - Close app / Wait" both on the PC desktop (`tester\d21-state.png`, `tester\d22-anr-after-click.png`)
  and on the phone (`tester\e20-phone-anr.png`). Clicking "Close app" on the PC does nothing (PC input never
  reaches the system dialog on our display); only the phone can close it. Closing kills the session; after
  restart no window comes back.
- Cause (code): `Phase6Apps.kt:305-315` builds `AlertDialog.Builder(context)` with the desktop view context
  (no window token on the private display); the swallowed exception leaves the UI thread unusable.
- Known as: new.

### T-006 (major) Files: Rename does not ask for a name; folders cannot be selected

- Steps: Files > "+" (creates "New folder" at once, no name asked); click the folder (it opens instead of
  selecting); right-click (nothing); click a file then the pencil icon.
- Actual: the file `tester-file-1.bin` becomes `tester-file-1.bin-renamed` (extension broken;
  `tester\d19-files-file-rename.png`); a folder can never be renamed or deleted from the PC.
- Cause: `Phase6Apps.kt:297-300` `renameSelectedPrompt()` is a stub (`s.name + "-renamed"`); `:241-258`
  a click on a folder always navigates.
- Known as: new.

### T-007 (major) No-lock view steals focus every 2 s and injects a synthetic Alt key into other apps

- Steps: `udreceiver view --addr 127.0.0.1:47600 --no-lock`; let any other window take the foreground
  (here Windows Search).
- Actual: `[p5pc] keyboard focus reclaimed by the session window` 171 times in about 6 minutes
  (`tester\v2-view.log`); each attempt calls `keybd_event(VK_MENU)` down/up, which goes to whatever app is in
  front. Windows refused the focus change (foreground lock), so the Alt presses went to Windows Search.
- Cause: `view.rs:508-517` (every 2 s while not locked) + `view.rs:561-572` (Alt injection).
- Why it matters: in the help text F11 gives a windowed view; with it, the user cannot use another PC app
  (Alt opens menu bars, accelerators fire). Also my own Win key in no-lock mode opened both the Windows
  Start/Search and the desktop start menu (`tester\d12-after-reconnect2.png`).
- Known as: new.

### T-004 (minor) Back on the phone's Settings page closes the app instead of returning home

- Steps: home > Settings > Android Back. Expected: back to the home page. Actual: the launcher is shown
  (`mCurrentFocus=NexusLauncherActivity`), app process stays (pid 7070).
- Cause: `HomeActivity.kt` has no back handling for `showingSettings` (Settings is a view swap, `:712-721`).

(more bugs below as found)

## 3. Code-review findings

(in progress)

## 4. UX findings

(in progress)

## 5. What works

(in progress)

## 6. Not testable on the emulator

(in progress)

## 7. Suggested fix order

(in progress)
