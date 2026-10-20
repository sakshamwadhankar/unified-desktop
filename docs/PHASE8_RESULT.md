# PHASE 8 RESULT: App Center

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
