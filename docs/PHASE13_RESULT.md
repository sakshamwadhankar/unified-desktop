# PHASE 13 RESULT: polish (UI, UX, browser, bug sweep)

Run: 2026-10-23 00:30-14:00. Working tree, uncommitted (the manager commits).
Evidence root: `C:\Users\Asus\AppData\Local\Temp\opencode\p13`.
BEFORE pictures: `p13\before`; AFTER pictures: `p13\after`.

Every result carries one label: **phone**, **emulator**, **PC-only** or **code-only**. The
emulator is `emulator-5554` (Pixel_8_Pro, x86_64, 1344x2992 @480 dpi); the test phone
`2c0f6edc` left at 08:45 and was absent for the rest of the run. An emulator result is never
called phone-verified.

## 1. What shipped

- A single dark design system for the desktop shell, phone app and receiver: calm wallpaper,
  surfaces, accent, 4/8/12/16 spacing, 6/10/12 corners, vector controls, no developer words.
- Desktop shell: icon Start menu with search/pinned/All apps, icon taskbar above windows,
  centred and clamped windows, title-bar icons, double-click maximise, edge snap, drag-to-top,
  Win/Esc menus, Alt+F4, right-click desktop menu, remembered window places.
- Browser: the Chrome attempt is closed (B-003/B-111); the shipping browser is a full WebView
  browser with tabs, one Google search/address box, back/forward/reload, neutral start page,
  downloads to Files, Ctrl+T/W/L/Tab, F5, zoom. Start page has no Google logo (D19 note).
- Phone home: Material 3 dark/light, connection states, pairing code, first-run checklist,
  trusted PCs, quality/sound settings, running notification, real app name resource.
- Receiver GUI: looking/found/pairing/connecting/desktop/reconnect, plain firewall error,
  Exit and Reconnect; every CLI subcommand still works.
- D22: encoder-only thermal scaling, fixed desktop size, PC scales the stream back up.

## 2. AFTER walk-through (side by side)

### Desktop shell (emulator stream shown on the 1920x1200 PC)

| # | BEFORE | AFTER | Status | Evidence |
|---|---|---|---|---|
| D1 | `before/d0-desktop-empty.png` flat green | `after/a0-desktop.png`, `a6-desktop.png`, `a10-desktop.png` indigo/slate gradient; Settings picker | **DONE** | emulator |
| D2 | `before/d1-start-menu.png` Test row | `after/a1-start-menu.png`, `a3-start-menu-1728.png`; Test only behind harness | **DONE** | emulator |
| D3 | `before/d1-start-menu.png` plain list | `after/a1-start-menu.png`, `a3`, `a4-menu-current.png`, `a5-menu.png`, `a7-menu.png`: search box, pinned icon grid, All apps, Esc/Win | **DONE** | emulator |
| D4 | `before/d0`, `d5` text Start/clock, taskbar covered | `after/a0`, `a6`, `a10`: icon Start, icon window buttons, HH:MM + date, battery, always above windows | **DONE** | emulator |
| D5 | `before/d2-appcenter.png`, `d6-focus-over-max.png` off-screen/clipped | `after/a6`, `a8-two-windows.png`, `a10`: centred, clamped, places remembered | **DONE** | emulator |
| D6 | `before/d6` flat title bar | `after/a8`: icon/title/min/max/close; snap-left, drag-to-top, double-click restore, Win/Esc, Alt+F4 proven by `p6:rects` and `p6:key` logs | **DONE** | emulator |
| D7 | `before/d4-browser.png` VS Code carries address bar | `after/a9-vscode.png`, `a16-vscode.png`: VS Code fills its window; only Browser has the bar | **DONE** | emulator |
| D8 | `before/d0` 16:9 picture on 16:10 PC, green strip | `after/a2-desktop-1728.png`, `a10`, `e38-thermal3-final-pc.png`: 1728x1080 desktop fills 1920x1200 with no bars or green strip | **DONE** | emulator |
| D9 | `before/d1`, `d2` oversized text | `after/a0`, `a1`, `a10`: Windows-like density at 1080p | **DONE** | emulator |
| D10 | `before/d2` text list, clipped buttons | `after/a13-appcenter.png`, `a14-installed.png`, `a15-pick.png`, `a15-pick2.png`: cards, source chips, Install/Open/Uninstall, progress, file picker | **DONE** | emulator |
| D11 | `before/d3-settings.png` stale link/video | `after/a18-settings.png`, `a18-settings2.png`: real desktop size, live connection + RTT, wallpaper/quality; B-105 fixed in tree | **DONE** | emulator |
| D12 | nothing | none | **SKIP** | SKIPS row: desktop toasts not implemented |
| D13 | `before/d6` focused window behind maximised | `after/a8`, `a10`, `a14`: B-091 body paint/raise, B-085 crop, B-083/087/088/093 titles and one X11 window; B-105 live settings | **DONE** | emulator |
| D14 | `before/d0` empty desktop | none | **SKIP** | SKIPS row: welcome card/shortcuts not implemented |
| D15 | `before/d4` blank WebView, text buttons | `after/e33-browser-start3.png` neutral start, `e32-browser-tabs6.png` three tabs, `e28-files-downloads.png` downloaded zip, `e18-browser-search.png`; Chrome closed B-003/B-111 | **DONE** | emulator |
| D16 | Phase 12 before table | Phase 12 final phone: Browser **65.7/74.5**, Notepad++ **66.2/73.7**, VS Code **75.5/103.5** (urgent build), Test shell **87.0/97.0**; n=25 | **DONE with caveat** | phone (Phase 12); VS Code final-build re-run blocked by B-110; step 15 phone re-run SKIP |

### Phone app (phone `2c0f6edc`; emulator cross-check)

| # | BEFORE | AFTER | Status | Evidence |
|---|---|---|---|---|
| P1 | `before/d0-phone-app.png` harness buttons | `after/a20-home-final.png`, `a23-connected-final.png`, `a28-pairing.png`, `a28-paired.png`; emulator `e1-home.png`-`e6-paired.png`: Not connected / Looking / Pairing code / Connected / Problem, one Stop | **DONE** | phone |
| P2 | none | `after/a20-home-final.png`, `a27-home-dark-default.png`: Notifications, Background activity, Sound checklist with ticks and one-line reasons | **DONE** | phone |
| P3 | none | `after/a26-settings-forget.png`, `a26-forget-after.png`: trusted PC row + Forget | **DONE** | phone |
| P4 | none | `after/a21-settings.png`, `a21-settings-connected.png`, `a27-settings-light.png`: quality Auto/Sharp/Smooth, sound, About/version/licences/privacy | **DONE** | phone |
| P5 | none | running notification "Desktop running on <PC>" with Stop; proof was text-only (`dumpsys notification --noredact`); no screen image retained to protect the owner's other notifications | **DONE** | phone |
| P6 | harness title | `after/a20-home-final.png`: only the app-name string resource; no "harness"/phase words; `HarnessActivity` no LAUNCHER in release | **DONE** | phone |

### PC receiver (laptop, PC-only)

| # | BEFORE | AFTER | Status | Evidence |
|---|---|---|---|---|
| R1 | CLI only | `after/e7-gui-found.png`, `e10-gui-state.png`: no-argument GUI opens, dark tokens, app icon; CLI still works | **DONE** | PC-only |
| R2 | none | `after/e7`, `e8-gui-pairing.png`, `e10`, `e12-gui-reconnect.png`: looking/found/pairing/connecting/desktop/Reconnect, trusted phone remembered | **DONE** | PC-only |
| R3 | none | `after/e14-gui-retry.png`: "Windows Firewall blocked the connection" with a next step; no console window | **DONE** | PC-only |
| R4 | none | desktop Exit/Reconnect path proven in the GUI no-lock run (`e11-chrome-desktop.png`, `e12`); normal lock path was proven earlier, forced-kill restart is B-112 | **DONE with caveat** | PC-only |

### Agent rows (A1-A6)

| # | Finding | AFTER | Status | Evidence |
|---|---|---|---|---|
| A1 | Taskbar covered by a window | `after/a0`, `a6`, `a10`: taskbar is the top layer; clock always visible | **DONE** | emulator |
| A2 | No window buttons | `after/a8`: icon + title buttons for each open window, focused underline | **DONE** | emulator |
| A3 | `[max]` in title | `after/a8`, `a10`: no internal state word | **DONE** | emulator |
| A4 | Start menu clipping/version suffix | `after/a1`, `a3`, `a5`: content-sized panel, no versions, rows reachable | **DONE** | emulator |
| A5 | Phone title under status icons | `after/a20-home-final.png`, `a27`: edge-to-edge with correct insets | **DONE** | phone |
| A6 | App Center action row below work area | `after/a13`, `a14`, `a15`: actions inside the window and work area | **DONE** | emulator |

## 3. Browser (D15/D19)

The manager's 11:08 note is implemented: the local start page is neutral (`after/e33-browser-start3.png`)
with one search box and the plain words "Search with Google"; it has no Google logo or colour
marks. `https://www.google.com` is loaded only when the user navigates there. DECISIONS D19
records the choice.

Proven on the final browser build (emulator): tabs/new/close/switch with independent titles
(`e32-browser-tabs6.png`), search-or-URL (`e18-browser-search.png`), back/forward/reload,
download to `files/desktop/Downloads` and visibility in Files (`e28-files-downloads.png`),
Ctrl+T/W/L/Tab, F5 (keycode 135), zoom +/-/0. F1 (131) is correctly unhandled.

## 4. D22 thermal scaling (encoder path only)

Implementation: `Phase5Encoder` captures the fixed desktop in its SurfaceTexture and configures
MediaCodec + GL viewport at 3/4 per axis; `Phase5Session.governorLowerResolution` rebuilds the
encoder, rebinds the display to the new capture surface and requests a keyframe. Input mapping
and the VirtualDisplay never change.

Measurement (emulator, Android thermal status overridden to 3; **not a phone thermal run**):

| Metric | BEFORE (thermal 0) | AFTER (thermal 3, 1296x810) |
|---|---|---|
| Encoder | 1728x1080, 12 Mbps, 60 fps | 1296x810, 6 Mbps, 30 fps |
| Encoded frames | ~53 fps | **28.6 fps** (172 / 6.006 s) |
| PC presented | 53 fps | **29.0 fps** |
| Decode | 1.45 ms | **1.11-1.20 ms** |
| Dropped | 0 | **0** |
| Receive rate | ~1.7 Mbps | **~0.4-0.7 Mbps** |
| Picture | full desktop | full desktop + taskbar, no crop (`e38-thermal3-final-pc.png`, `e39-thermal3-final-phone.png`) |

A real-phone thermal-3 run is in SKIPS.md; the phone was absent.

## 5. Bugs found or touched (see BUGS.md)

- B-003/B-111: Chrome first-run blank; missing `NativeOnlySandboxedProcessService0`; browser
  fallback shipped.
- B-105: Settings live values fixed in the working tree.
- B-112: receiver GUI lock child fails after a forced kill; no-lock path works.
- B-113: duplicate `p6:probe` harness branch (page probe unreachable).
- B-114: 1920x1200 encoder start unsupported on the test phone; 1728x1080 used.

## 6. Gates and limits

Final Android gate (`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`) **PASSED**
(2026-10-23 11:24): **206 tests / 0 failures / 3 skipped**, lint **0 errors / 137 warnings**,
`assembleDebug` BUILD SUCCESSFUL in 1m42s (`p13/gate-final.log`). PC build/tests: see
`p13/pc-test-final.log` / `p13/pc-build-final.log` and `STATE.md`. The working tree is
uncommitted.

Limits: D12 and D14 are SKIPS; D16's VS Code final-build re-run is blocked by B-110; the
phone thermal-3 and step-15 phone re-run are SKIPS; the GUI forced-kill lock restart is B-112.
