# PHASE 8 RESULT: App Center

## Current verified status (Round 4)

- B-083, B-085, B-086 and B-087 are fixed and proven on phone `2c0f6edc` (details below).
- The catalog contains 14 apps; the section-7 picker test is **11/11 PASS** and all 11
  selectable apps answer a summary (`usable=true`).
- Fresh-content proof is **8/10**: VS Code, Zoom, Excalidraw, Photopea, xeyes, xcalc,
  Notepad++ and 7-Zip. Minesweeper (B-089) and Google Tasks (B-090) have fresh titles and
  launch logs but no freshly painted guest content; their earlier phone captures are listed.
- The Phase 8 acceptance gate is **not claimed PASS**. New open bugs: B-088 (stale X11
  window/title), B-089 (winemine has no window after cold boot), B-090 (Tasks guest black
  after re-import), B-091 (focus does not raise a window above a maximized one). The manager
  decides.

## Round 4 (bug-fix + full acceptance round)

Date 2026-10-20, 17:30-19:00. Phone `2c0f6edc` over USB adb; receiver
`udreceiver view --addr 10.173.89.9:47600 --x 0 --y 0 --w 1366 --h 768 --no-input`.
All new pictures are in `C:\Users\Asus\AppData\Local\Temp\opencode\p8f\` with `_r8` names.

| Item | What was wrong | Fix | Evidence | State |
|---|---|---|---|---|
| B-083 Start-menu rows | fixed list only; uninstall could not remove an app row | `InstalledApp`/`InstalledStore`, pure `InstalledMenu.rows`, dynamic installed section; `OpenRoute.of` + `ShellRoot.openCatalogApp` is the one launch path | `menu_tasks_r8.png` (Google Tasks 2026.10 row), `menu_tasks_gone_r8.png` (row gone); `menurows` before/after; `openid:tasks` and `openid:sevenzip` | **FIXED (phone)** |
| B-085 green band | NV12 1920x1088 (1080 + 8 macroblock rows) scaled into 1366x768, green rows ~762-767 | `visible_size()` + `VideoProcessorSetStreamSourceRect` (hardware) / visible-rect crop (soft); test `macroblock_padding_is_cropped` | `overlap_r8.png`, `max_x11_r8.png`, `max_xcalc_r8.png`: zero green pixels in rows 760-767 | **FIXED (phone)** |
| B-086 winget uninstall | no MSI product code; `Uninstall` said "shipped with the app" | after install, `reg.exe query ...\Uninstall /s`, store the GUID; `msiexec /x {code} /qn` under the loader; `ensureLivePrefix` keeps the install session | Notepad++ `{5C6F8543-...}` and 7-Zip `{23170F69-...}`, both exit 0, `installed record cleared`, menu rows gone; `npp_r8.png`, `7zip_r8.png` | **FIXED (phone)** |
| B-087 X11 title | fixed `Notepad++ + xeyes (X11)` | `X11Title`, `ShellModel.rename`, reuse one X11 window | `xeyes_r8.png`, `xcalc_r8.png`, `npp_r8.png`, `7zip_r8.png`, `mines_r8e.png`; neutral `X11 desktop` | **FIXED (phone)** |
| Picker 11/11 | — | — | `p6:appcenter:select:<id>` for all 11 returns `usable=true` (18:31) | **PASS** |
| "Open any .exe" | — | `runExe` boots only when Wine/Xvfb are down | `exe_r8.png`: `started .../winemine.exe`; new `winemine.exe` pid 29509 | **PASS (launch)** |

### The 10 acceptance apps

| # | App | Fresh proof | Outcome |
|---|---|---|---|
| 1 | VS Code | `vscode_r8.png` (Explorer, workspace, code-server loaded) | content |
| 2 | Google Tasks | `tasks_r8e.png`/`tasks_pc_r8b.png` show the window, black content | **B-090**, earlier `tasks_pc_r2.png` shows "Welcome to Tasks" |
| 3 | Zoom Play page | `zoom_r8.png` (desktop Play page, Zoom Workplace, Install) | content |
| 4 | Excalidraw | `excal_r8.png` (`https://excalidraw.com`, loaded) | content |
| 5 | Photopea | `photo_r8.png` (`photopea.com`, "Free Online Photo Editor") | content |
| 6 | xeyes | `xeyes_r8.png`; title `X11 desktop — xeyes` | title; eyes are small |
| 7 | xcalc | `max_xcalc_r8.png` (PC, maximized) shows `DEC`; title `X11 desktop — xcalc` | content + labels |
| 8 | Notepad++ | install exit 0, `npp_r8.png` title `X11 desktop — Notepad++` | install/open |
| 9 | 7-Zip | install exit 0, `7zip_r8.png` title `X11 desktop — 7-Zip` | install/open |
| 10 | Minesweeper | `mines_r8e.png` title correct; X11 root black | **B-089**, earlier `mines_r2b.png` shows Board/Game/Help |

### Gates

- Android: `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` **BUILD SUCCESSFUL in 25 s**;
  **143 tests / 0 failures / 3 skipped** (23 suites), lint + assemble green.
- PC: `cargo build --release` ok; `cargo test --release` **45 passed / 0 failed**;
  `cargo clippy --all-targets` exit 0 with 0 warnings.
- `git diff --check` exit 0 (CRLF normalization warnings only). Nothing committed.

## Round 1 (initial App Center implementation)

Date 2026-10-20, 05:10-06:35. Phone 2c0f6edc. Every result below is labelled **phone**,
**phone, harness-driven**, or **code-only**. "Harness-driven" means the App Center was driven
with `p6:appcenter:*` broadcasts and the picture is the phone's own desktop surface
(`p6:shot`, 1920x1080); it proves the UI and the picker, not PC typing. Two apps were also
driven end-to-end from the PC (click, type, Install, Open) and captured on the PC.

## What was built

| Item | Where | State |
|---|---|---|
| Catalog (13 apps: the 11 section-7 names + GIMP and Firefox) | `android/app/src/main/assets/app_catalog.json` | done |
| Pure picker: best rating (`Works` > `Works, slow` > `Not yet`), then D7 kind order builtin > android > web > linux > windows, then highest version | `Catalog.kt` | done |
| Catalog loader (assets -> data classes, org.json) | `Catalog.kt` | done |
| 11-row section-7 test against the real asset file | `CatalogPickerTest.section7Table` | **11/11 PASS** (`tests=4 failures=0`) |
| App Center window: search (name + aliases), picked version/kind/rating, Install/Open/Uninstall, "Not yet" reason, "Open any .exe" | `AppCenter.kt`, `ShellRoot.App.APPCENTER` | done |
| Start-menu entry "App Center" (automatic, first row) | `Phase6Views.kt` (enum) | done |
| Harness driver `p6:appcenter:<summary|search|rows|select|install|run|uninstall|exe>` | `Phase6Dispatch.kt` | done |
| Builtin installer | `AppCenterView` opens the built-in window | done |
| Android installer | `EngineBridge.importInstalled` (Tasks: `ok=true`); absent package -> Play page in the Browser | done (Tasks proven) |
| Web installer | nothing to install; Open loads the URL in the Browser | done (Excalidraw proven) |
| winget installer (Notepad++) | catalog URL + SHA-256 == `Phase2Data.NPP_URL` / `NPP_SHA256`; `fetchNpp` downloads, verifies, unpacks. **This is a pinned-URL installer; the winget-manifest fetch is not written.** | done (proven) |
| 7-Zip winget installer | not written (rating is Works; button says "installer not available yet") | **not written** |
| Linux deb installer | not written (GIMP is an extra, Not yet) | **not written** |
| "Open any .exe" | `Phase2Wine.openExe`: boot Wine, run the chosen file as data through the loader | implemented, **not exercised** |

## Section 7: the 11 rows

| # | Name | Picked kind / rating | What Install actually did | Open evidence |
|---|---|---|---|---|
| 1 | VS Code | builtin / Works | nothing to install (built-in code-server; the VS Code window starts it) | picker: `round/1_vscode.png` (phone, harness-driven); the VS Code window itself was proven in Phase 7 (`p8/vsc2.png`) |
| 2 | Google Tasks | android / Works | **import ok=true** `APPCTR install pkg=com.google.android.apps.tasks ok=true` (05:50:16, re-import 06:08:32 after the uninstall proof) | `p8/pc0.png` (phone, PC capture): Tasks' own "Welcome to Tasks" screen |
| 3 | Zoom | android / Works | not exercised; code path: package not on phone -> Play page in the Browser | picker only: `round/3_zoom.png` |
| 4 | Excalidraw | web / Works | nothing to install ("web needs no install") | **PC end-to-end**: `p8/excalidraw_open_pc.png` shows excalidraw.com loaded in the Browser |
| 5 | Photopea | web / Works | same as Excalidraw (not exercised) | picker: `round/5_photopea.png` |
| 6 | xeyes | linux / Works | shipped; no install | picker: `round/6_xeyes.png`; `Phase2X11.startXeyes` wired, not exercised tonight |
| 7 | xcalc | linux / Works | shipped; no install | picker: `round/7_xcalc.png`; `Phase2X11.startXcalc` wired, not exercised tonight |
| 8 | Notepad++ | windows / Works | **official URL + pinned SHA-256 (from the Phase 2 pin), downloaded, verified and unpacked**: status "install Notepad++: ok (pinned zip verified)" (`p8/npp_install_pc.png`). The winget-manifest fetch (microsoft/winget-pkgs) is NOT built. | **PC end-to-end**: `p8/npp_open_pc.png` shows Notepad++'s own window (X11) |
| 9 | 7-Zip | windows / Works | installer not written yet | picker: `round/9_7zip.png` |
| 10 | Minesweeper | windows / Works | built into Wine, no install | picker: `round/10_minesweeper.png`; `openCatalogApp` starts `winemine.exe`, not exercised |
| 11 | Paint.NET | windows / **Not yet** | refused: "Not yet: the .NET desktop runtime does not run under Wine on this phone yet"; `pickUsable` = null | `round/11_paintnet.png` shows the reason |

Extras (not in section 7): GIMP linux Not yet (deb installer not written), Firefox android
Not yet (APK not bundled). Both are shown greyed with their reason.

Picker test output: `SECTION7 1/11 ... 11/11 PASS`, from
`android/app/build/test-results/testDebugUnitTest/TEST-dev.unifieddesktop.app.CatalogPickerTest.xml`.

## Pictures (all in `C:\Users\Asus\AppData\Local\Temp\opencode\p8`)

| File | What it shows | Label |
|---|---|---|
| `round/1_vscode.png` ... `round/11_paintnet.png` | each section-7 name in the search box, its picked kind/rating, and (Paint.NET) the Not yet reason | phone, harness-driven |
| `ac_note.png` | "note" typed from the PC into the App Center, filtered to Notepad++, windows Works | **phone, PC typing** |
| `npp_pick_pc.png` | "Notepad++" typed from the PC, row selected, detail shows windows 8.9.8.1 Works | **phone, PC typing** |
| `npp_install_pc.png` | Install pressed from the PC -> "install Notepad++: ok (pinned zip verified)" | **phone, PC** |
| `npp_open_pc.png` | Open pressed from the PC -> X11 window with Notepad++ content | **phone, PC** |
| `excalidraw_open_pc.png` | Excalidraw typed/selected/Install/Open from the PC -> Browser on excalidraw.com | **phone, PC** |
| `pc0.png` | Android window with Tasks content | phone, PC capture (open was harness-driven) |
| `round/tasks_uninstall.png` | App Center with Google Tasks selected (the status line is not in the shot) | phone, harness-driven |
| `round/tasks_open.png` | white Android window 12 s after launch (not proof; kept as the timing finding) | phone |

Uninstall proof: `APPCTR uninstall tasks pkg=com.google.android.apps.tasks ok=true msg=uninstalled`
(06:07:16), then re-import `ok=true` (06:08:32). Log: `files/p7.log`.

## Numbers

- App Center loaded 13 apps from assets and rendered in < 100 ms (log 05:44:04.690/697).
- Notepad++ winget install: click 05:53:36 -> status ok at 05:56:08 (includes download +
  SHA-256 + unzip; the earlier pin was already on disk, so this is a re-verify).
- Notepad++ window: X11 connected 05:56:43; NPP content visible in the 05:57:33 capture
  (~50 s including wineboot).
- Excalidraw: Open 05:59:17 -> "loaded https://excalidraw.com/" within the 20 s capture.
- Tasks: import ~0.3 s; Android window content took > 12 s to draw, visible at 05:52:20
  (`pc0.png`); the 05:50:33 surface shot was still white.

## What is not done / open

- 7-Zip winget installer, GIMP linux-deb installer, generic manifest installer, and the
  microsoft/winget-pkgs manifest fetch: not written. "Open any .exe" is implemented but not
  exercised.
- SKIPS.md has the rows; BUGS B-078 is the VS Code Explorer/Quick-Open failure from Part A.
- The receiver's video window died twice during this round (B-072/B-067 family); each time
  the session and the shell were restarted with `phase4x start`.

## Round 2 (2026-10-20, 09:15-11:15): installers and the phone round

Every row is **phone**; "harness" means the App Center was driven by `p6:appcenter:*` and the
picture is the phone's own surface (`p6:shot`, 1920x1080). The PC view was used only for
Tasks (`tasks_pc_r2.png`); the owner closed the view at 10:15, so the rest of the round ran
on the harness-only `p5test` session.

| Name | Expected | Picked | What Install did | Opened, own content | Driven by | Verified on | Evidence |
|---|---|---|---|---|---|---|---|
| VS Code | builtin | builtin 4.95 Works | nothing to install | yes: workbench, Explorer `phase7phone.txt` | harness | phone | `p8f/vsc_r2.png` |
| Google Tasks | Android import | android 2026.10 Works | `import ok=true` (0.3 s) | yes: "Welcome to Tasks" after 15 s | harness + PC capture | phone | `p8f/tasks_pc_r2.png` |
| Zoom | Play page only | android 6.2 Works | opened Play page (not on the phone) | **no**: Play redirected to `intent://...` -> `ERR_UNKNOWN_URL_SCHEME` (B-082) | harness | phone | `p8f/zoom_r2.png` |
| Excalidraw | web | web Works | nothing to install | yes: excalidraw.com loaded | harness | phone | `p8f/excal_r2.png` |
| Photopea | web | web Works | nothing to install | yes: photopea.com loaded | harness | phone | `p8f/photo_r2.png` |
| xeyes | linux shipped | linux Works | nothing to install | yes: X11 window with the eyes | harness | phone | `p8f/xeyes_r2.png` |
| xcalc | linux shipped | linux Works | nothing to install | **no**: white xcalc window; `XCALC_ERR Warning: Unable to load any usable fontset` (B-080) | harness | phone | `p8f/xcalc_r2b.png` |
| Notepad++ | winget 8.9.8.1 | windows 8.9.8.1 Works | **winget download + SHA-256 OK, installer exit=1, nothing installed** (B-081); Open fell back to the Phase-2 pinned NPP | yes, fallback content (`npp8npp8npp8q`) | harness | phone | `p8f/npp_r2.png`; `files/p7.log` `APPCTR ... installer exit=1` |
| 7-Zip | winget 26.04 | windows 26.04 Works | manifest + SHA-256 OK (1,664,082 B in 20 s), installer exit=1 in 8 s, nothing installed (B-081) | no | harness | phone | `files/p7.log` 09:49:35-09:50:04 |
| Minesweeper | Wine builtin | windows wine Works | nothing to install | yes: board (needed a fresh app process after B-084) | harness | phone | `p8f/mines_r2b.png` |

Extra (not one of the ten): **xmessage** from the Debian `x11-utils 7.7+5` .deb: manifest
URL + SHA-256, 202,012 B downloaded and verified, data.tar unpacked into the rootfs in 0.4 s,
then opened with its own message "xmessage - installed from Debian x11-utils 7.7+5"
(`p8f/xmsg_r2.png`). This is the `.deb` proof; the App Center catalog now has 14 apps.

### Uninstall and the start menu

`APPCTR uninstall tasks pkg=com.google.android.apps.tasks ok=true msg=uninstalled`
(10:16:47). The requested "its start-menu entry is gone" proof **cannot be made in this
tree**: the start menu is a fixed list (Programs, App Center, Files, Browser, Settings,
Test, then window titles), not a list of installed apps (B-083). Before/after pictures are
`p8f/menu_before_r3.png` and `p8f/menu_after_r3.png`; both show the same rows. Uninstall
itself works at the engine level; the menu does not change.

### "Open any .exe"

With Wine already up (Minesweeper), `p6:appcenter:exe:<...>/winemine.exe` started a second
`winemine.exe` (pid 22837) without reaping Xvfb; the App Center status shows
`started /data/.../winemine.exe` and the X11 window still shows the board. First attempt at
10:04 failed because `runExe` booted unconditionally and B-084 reaped Xvfb; fixed in
`AppCenter.kt` (boot only when Xvfb or wineserver is down) + `Phase2Wine.wineserverAlive`.
Evidence: `p8f/exe_r3.png` (status) and `p8f/exe_r3b.png` (board after the second start).

### Gate

`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` -> **BUILD SUCCESSFUL in 18 s**
(101 tasks), 128 tests / 0 failures / 3 skipped (`p8f/gate2.log`). Picker section-7 table:
**11/11 PASS**. Round result: **FAIL** as a Phase 8 gate: 7-Zip and Notepad++ winget
installers fail (B-081), xcalc is blank (B-080), Zoom's Play page is not renderable
(B-082), and the uninstall start-menu proof is impossible (B-083). Opened with own content:
7/10 (VS Code, Tasks, Excalidraw, Photopea, xeyes, Notepad++ via the Phase-2 fallback,
Minesweeper); installed and opened strictly through the App Center: 6/10.

## Round 3 (2026-10-20, 11:40-13:00): fixes and the completed phone round

Every row is **phone** unless labelled otherwise. The receiver ran **full screen on the
wireless extended display** (`udreceiver view --addr 192.168.1.16:47600 --x 1920 --y 0
--w 1366 --h 768 --no-input`, `rect5.ps1` -> `rect=1920,0,1366,768`). `--no-input` makes the
view display-only (`WS_EX_NOACTIVATE`, physical mouse/key ignored), so the owner's own mouse
and focus were never borrowed; the desktop was driven only by the harness and the virtual
pointer (`p6:move/tap/key`, e.g. `P6 moved` to 960,540). Receipts are PC `CopyFromScreen` of
that display or `p6:shot` where it was clean.

### What round 3 fixed

| Item | Root cause found | Fix | Phone evidence |
|---|---|---|---|
| B-081 winget installers exit 1 | `runInstaller` started `msiexec` on a wineserver that had **loaded saved hives**, so every registry access recursed to the stack overflow (a recurrence of B-021), not an MSI problem | `runInstaller` boots from empty hives and waits for the boot marker before the installer; MSI preferred with `/qn`; `installedExe` also accepts Wine's `windows/syswow64` install dir | 7-Zip 26.04 `installer exit=0` 11:58:13, `APPCTR installed sevenzip=26.04`; files in `drive_c/windows/syswow64/7-Zip/7zFM.exe`, runs (`7zip_r5.png`). Notepad++ 8.9.8.1 `exit=0` 12:01:12, runs (`npp_r6.png`). ~14-15 s per install including boot |
| B-080 xcalc blank | not only the fontset: xcalc's button **labels are application resources** shipped in `etc/X11/app-defaults/XCalc`, which Xt does not search by default (the compiled path is `/usr/share/X11/app-defaults`) | `startXcalc` sets `LANG/LC_ALL=C`, `-fn fixed` and `XAPPLRESDIR=<rootfs>/etc/X11/app-defaults` | keypad with labels and LCD `0` in `xcalc_r5.png` (12:24) |
| B-082 Zoom `intent://` | the shell Browser sent the WebView UA, so Play answered with an intent redirect | desktop Chrome UA in `Phase6Apps.kt` | Play desktop page in `zoom_r4.png` (12:47); Excalidraw/Photopea rechecked after the UA change (`excal_r3.png`, `photo_r3.png`) |
| B-084 cold boot left no Xvfb | `boot()` reaps Xvfb; its static `Process` still looked alive, so `ensureXvfb` skipped the restart and the next app got `ECONNREFUSED` on port 6000 | `xvfbRunning()` proves liveness by connecting to 6000; `killXvfb()` waits (2 s) for the port; `boot()` forgets the reaped server before `ensureXvfb` | after the fix, NPP and 7-Zip open right after an install: `ps` Xvfb + `notepad++.exe` / `7zFM.exe` |

### The ten section-7 names, with their own content

| Name | Expected | Picked | Round-3 state | Evidence |
|---|---|---|---|---|
| VS Code | builtin | builtin 4.95 Works | open with workbench (round 2; code unchanged) | `p8f/vsc_r2.png` |
| Google Tasks | Android import | android 2026.10 Works | open with "Welcome to Tasks" (round 2); uninstalled and re-imported in round 3 | `p8f/tasks_pc_r2.png`; `p7.log` 12:48:31 uninstall, 12:49:06 `import ... ok=true` |
| Zoom | Play page only | android 6.2 Works | Play page renders (B-082 fixed) | `p8f/zoom_r4.png` |
| Excalidraw | web | web Works | loads after the desktop UA change | `p8f/excal_r3.png` |
| Photopea | web | web Works | loads after the desktop UA change | `p8f/photo_r3.png` |
| xeyes | linux shipped | linux Works | open (round 2) | `p8f/xeyes_r2.png` |
| xcalc | linux shipped | linux Works | keypad labels render (B-080 fixed) | `p8f/xcalc_r5.png` |
| Notepad++ | winget 8.9.8.1 | windows 8.9.8.1 Works | MSI installs **and** opens from `windows/syswow64` (B-081 fixed) | `p8f/npp_r6.png` |
| 7-Zip | winget 26.04 | windows 26.04 Works | MSI installs **and** opens (File Manager) (B-081 fixed) | `p8f/7zip_r5.png` |
| Minesweeper | Wine builtin | windows wine Works | board renders; captured on the receiver because `p6:shot` hit B-085 | `p8f/mines_only.png` |

10/10 show their own content. Six were captured in round 3; the four round-2 captures are
unchanged code paths (VS Code, Tasks, xeyes) or were rechecked this round (Tasks re-import).

### Uninstall and the start menu (B-083, still impossible)

`APPCTR uninstall tasks pkg=com.google.android.apps.tasks ok=true msg=uninstalled` 12:48:31,
Tasks re-imported at 12:49:06. The "start-menu entry is gone" proof **cannot be made**: the
menu is a fixed list, not installed apps. Before/after, identical: `p8f/menu_before_r4.png`,
`p8f/menu_after_r4.png`. B-083 stands, not faked.

### "Open any .exe"

`p6:appcenter:exe:<prefix>/drive_c/windows/system32/winemine.exe` started a second
`winemine.exe` (pid 28269) with Wine and Xvfb already up; App Center status
`started /data/.../winemine.exe` (`p8f/exe_r4.png`). The screen duplicated the two open
windows in that shot (B-085); closing the top window restored it (`p8f/mines_only.png`).

### Update label

`Winget.needsUpdate` is covered by `InstallersTest.updateIsOfferedOnlyForAnOlderInstalledVersion`
(catalog newer -> Update; equal/older/empty -> Install). No installed app was older than the
catalog version this round, so the label is **code-only + unit test**, not a phone shot.

### Gate

- Android: `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` -> **BUILD SUCCESSFUL
  in 49 s** (101 tasks), **128 tests / 0 failures / 3 skipped** (`p8f/gate3.log`).
- PC: `cargo test --release` -> **44 passed / 0 failed** (`p8f/pc_test3.log`);
  `cargo clippy --release --all-targets` -> exit 0 (`p8f/pc_clippy3.log`).
- Receiver: `VSTAT fps=6-9` during interaction, decode 3.8-4.6 ms, dropped=0,
  `rect=1920,0,1366,768` (`p8f/view10.log`).

### New bugs logged this round

- **B-085** (major): overlapping windows / maximized X11 draw repeated tiles with a green
  background, in `p6:shot` and on the receiver; workaround one window at a time, no `p6:max`
  (`p8f/mines_max.png`, `p8f/exe_r4b.png`, normal `p8f/mines_only.png`).
- **B-086** (major): winget-installed Windows apps cannot be uninstalled from the App Center
  (`uninstallSelected` says "cannot uninstall: shipped with the app"); no MSI uninstall path.
- **B-087** (minor): the X11 shell window keeps the fixed title "Notepad++ + xeyes (X11)"
  while showing xcalc or 7-Zip, so the title bar/taskbar name the wrong app.

### Round 3 result

All four round-2 blockers B-080, B-081, B-082 and B-084 are **fixed and phone-verified**, the
ten section-7 names all show their own content, and both build gates are green. The round's
only structural gap is the start-menu uninstall proof (B-083), a product decision, plus the
three new defects above. **Phase 8 is not claimed PASS**; the manager decides. No Phase 9 work
started, no commits made.

## Round 5 (2026-10-20, 19:05-20:05): the last four bugs and fresh acceptance

Phone `2c0f6edc` over USB adb; receiver `udreceiver view --addr 10.173.89.9:47600 --x 0 --y 0
--w 1366 --h 768 --no-input`; captures in `C:\Users\Asus\AppData\Local\Temp\opencode\p8g_*.png`.

### Fixes (phone-verified)

| Bug | Root cause | Fix | Proof |
|---|---|---|---|
| B-088 | No window manager; the old Wine/X11 client stayed mapped after a new app opened, so its content sat under the new title. | `Phase2Wine.closeCatalogApps` closes the previous catalog client (argv-token match: winemine, notepad++, 7zFM, xeyes, xcalc, xmessage) before every catalog launch; `openExe` and Windows uninstall use it too. | Open Minesweeper then xcalc: `CATALOG_CLOSE pid=5999 app=minesweeper`, `pid=6050`; `ps` shows only `xcalc`; `p8g_b088_after.png` shows only the calculator under `X11 desktop — xcalc`. Unit test `Phase8CatalogProcessesTest`. |
| B-089 | A duplicate slow client could block on the same wineserver; the original `pipe_read` state was not reproduced after the change. | The same close-before-launch rule removes the duplicate/old-client path. | Fresh cold boot after install: `PREFIX_BOOT_OK`, first winemine launch, board visible in `p8g_b089_after.png`. |
| B-090 | Uninstall left the old Android shell window/TextureView alive; re-import re-focused it, so the new guest had no fresh display. | `ShellRoot.closeGuestWindow(pkg)` on uninstall and import success; opening a different Android package also closes the previous Android window. | Tasks uninstall `ok=true`, re-import `installed tasks=2026.10`, new window; `p8g_b090_after.png` shows the Tasks welcome screen. |
| B-091 | The model already raised focus; the frame drew no opaque body, so the maximized window showed through the focused window. The title bar also ignored `win.title`. | Opaque body in `ShellWindowView.onDraw`; title bar uses `win.title`. | Files focused over maximized Settings: `p8g_b091_after.png`; 0 green samples in `b091_after`, `b090_after`, `b088_after`; unit test `focusingANormalWindowRaisesItAboveAMaximizedWindow`. |
| B-087 (correction) | The model/taskbar title was correct, but the frame's `titleText()` still called `app.title()` and always showed `X11 desktop`. | Frame title now reads `win.title`. | `p8g_b088_after.png` (`X11 desktop — xcalc`), `p8g_b089_after.png` (`X11 desktop — Minesweeper`), `p8g_xeyes.png`/`xmessage` titles. |

### Fresh acceptance

| App | Fresh proof | Result |
|---|---|---|
| VS Code | `p8g_vscode2.png` (Explorer, Welcome, localhost:41013) | own content |
| Google Tasks | `p8g_b090_after.png` (Welcome to Tasks) | own content |
| Zoom | package `us.zoom.videomeetings` not installed; `import ok=false`; `p8g_zoom2.png` black | **FAIL (B-092)** |
| Excalidraw | `p8g_excalidraw2.png` (excalidraw.com, toolbar, blank canvas) | own content |
| Photopea | `p8g_photopea.png` (Free Online Photo Editor) | own content |
| xeyes | `p8g_xeyes.png` | own content |
| xcalc | `p8g_b088_after.png` | own content |
| Notepad++ | `p8g_npp2.png` (editor, menus) | own content |
| 7-Zip | `p8g_7zip.png` (file manager) | own content |
| Minesweeper | `p8g_b089_after.png` (board) | own content |

**FRESH CONTENT: 9/10.** Zoom cannot be counted: it is not installed on the phone, no APK
exists in app data or Download, and `cmd package install-existing` finds no package. The
Android open path still creates a black window instead of refusing (new B-092).

Other phone proofs: Start-menu rows `... | Google Tasks | xmessage` and `... | Notepad++`;
after Notepad++ uninstall the row is gone (`p6:menurows` 19:43:16 vs 19:43:53, record cleared).
Notepad++ and 7-Zip were re-installed from winget, opened, then uninstalled with `msiexec /x`
exit 0 and records cleared. "Open any .exe": `p8g_exe.png` shows
`started .../winemine.exe`. Overlap/maximize/focus captures have no green pixels, no tiled
copies and no stale content.

### Gates

- Android `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`: **BUILD SUCCESSFUL**;
  147 tests / 0 failures / 3 skipped (`p8g_gate_android.log`).
- PC `cargo build --release`: success; `cargo test --release`: **45 passed / 0 failed**;
  `cargo clippy --all-targets`: exit 0 (only the workspace profile warning).
- `git diff --check`: exit 0.

**Phase 8 gate: FAIL.** All four round-4 bugs are fixed and phone-verified, and the fresh
content count is 9/10; Zoom's missing package (B-092) blocks the required 10/10. No Phase 9
work started, nothing committed or pushed.

## Round 6 (2026-10-20, 20:08-20:32): B-092 and the final 10/10 gate

Phone `2c0f6edc` over USB adb; receiver `udreceiver view --addr 10.173.89.9:47600 --x 0
--y 0 --w 1366 --h 768 --no-input`; Round-6 captures in
`C:\Users\Asus\AppData\Local\Temp\opencode\p8r6_*.png`.

### B-092 fix (phone-verified)

`ShellRoot.openCatalogApp` now checks the Android package with PackageManager before any
guest work. A missing package is converted by `AndroidOpen.browserFallback` into a Browser
target carrying the official Play URL; `AppCenter.installSelected` uses the same
`AndroidOpen.playUrl` fallback and sets a truthful status. There is no APK download, no
guest window and no recorded installed state.

- Open: `p6:openid:zoom` -> `Zoom is not installed; opened its Google Play page.`;
  `p6:rects` lists only the Browser (focused) and no new Android window.
- `p8r6_zoom.png` shows the official Zoom Workplace Play page with
  `https://play.google.com/store/apps/details?id=us.zoom.videomeetings` and its Install
  button; Install was never tapped.
- Install: `APPCTR install zoom: not on the phone, opened Play page
  https://play.google.com/store/apps/details?id=us.zoom.videomeetings`; the same Browser
  page is shown, with no Android window.
- Unit test `Phase8OpenTest.missingAndroidPackageRoutesToBrowserPlayPage` proves the
  missing-package route is Browser/Play even when the catalog `fetch` is not a Play URL.

### Fresh 10/10 content (all captured in this round)

| App | Fresh proof | Result |
|---|---|---|
| VS Code | `p8r6_vscode.png` (workbench, Explorer, Welcome) | own content |
| Google Tasks | `p8r6_tasks.png` ("Welcome to Tasks") | own content |
| Zoom | `p8r6_zoom.png` (official Google Play page + URL) | Browser/Play page (B-092) |
| Excalidraw | `p8r6_excalidraw.png` (excalidraw.com toolbar/canvas) | own content |
| Photopea | `p8r6_photopea.png` (editor page) | own content |
| xeyes | `p8r6_xeyes.png` | own content |
| xcalc | `p8r6_xcalc.png` | own content |
| Notepad++ | `p8r6_notepadpp.png` (editor, menus) | own content |
| 7-Zip | `p8r6_sevenzip.png` (file manager) | own content |
| Minesweeper | `p8r6_minesweeper.png` (board after a cold boot) | own content |

Round-6 guard proofs:

- Start menu after both uninstalls: `Programs | App Center | Files | Browser | Settings |
  Test | Google Tasks | xmessage` (no Notepad++ or 7-Zip row).
- Notepad++ and 7-Zip: installed through winget, opened, then uninstalled in the same live
  Wine session; `WINE_UNINSTALL exit=0` for both and `installed.json` contains only
  `xmessage` and `tasks`. A cold boot drops the MSI registration (`boot()` drops the hives),
  so the supported sequence is install -> open -> uninstall without a prefix restart.
- Open any .exe: `APPCTR exe=.../winemine.exe pid=15411`; `p8r6_exe.png` shows the started
  program. The reused X11 frame keeps its old title (B-093, minor, non-blocking).
- Overlap/maximize: `p8r6_overlap.png` shows the opaque
  `X11 desktop — xcalc [max]` frame under the focused App Center; no transparency, no green
  band. Catalog X11 titles are fresh and correct (`xcalc`, `Notepad++`, `7-Zip`,
  `Minesweeper`).

### Gates

- Android `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`: **BUILD SUCCESSFUL**;
  **148 tests / 0 failures / 3 skipped** (`p8r6_gate_android.log`).
- PC `cargo build --release`: success; `cargo test --release`: **45 passed / 0 failed**;
  `cargo clippy --all-targets`: exit 0 (only the workspace profile warning).
- `git diff --check`: exit 0.

**Phase 8 gate: PASS.** Fresh content is **10/10**; Zoom's missing-package Open and Install
both route to the official Browser Play page with no Android window (B-092 fixed and
phone-verified). B-093 is a minor, non-blocking title cleanup for the arbitrary-.exe path.
No Phase 9 work started; nothing committed or pushed.

`--no-input` and the virtual pointer were removed on 2026-10-21 (owner); historical runs above used them.
