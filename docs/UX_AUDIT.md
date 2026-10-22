# UX AUDIT: what the user sees, what is wrong, what to build (Phase 13)

Written by the manager on 2026-10-23 at 00:30, from our phone app on 2c0f6edc, the Phase 8 and
Phase 12 receiver pictures, and the code. The coding agent adds its own walk-through in
section 6 and keeps the status column in section 7 true. Pictures: before in
`C:\Users\Asus\AppData\Local\Temp\opencode\p13\before`, after in `...\p13\after`.

## 1. What the owner said (2026-10-23)

- The phone app is "so white and cheap". The UX is "illogical". Make the best UI and UX for the
  phone app, the desktop and the PC receiver. "Users should be attracted to it."
- "Why is the background so greeny on the desktop?"
- "What is that test app that a ball is running in?"
- No basic browser on the desktop: Chrome (or something as good that fits perfectly).
- Typing is "very laggy". It must be much faster.

Answers: the green is a hard-coded wallpaper colour `0xFF10312B` (`Phase6Shell.kt`). The ball
app is the Phase 5 speed-test window ("Test"), still in the start menu. Both go.

## 2. One design system for all three surfaces

Use these tokens in the phone app, the desktop shell and the PC receiver. Put them in one
place per codebase (a Kotlin `Theme` object; a Rust `theme` module), never as colour literals
scattered through views.

| Token | Dark (default) | Light (phone app only) |
|---|---|---|
| background | `#0B0E14` | `#F6F7F9` |
| surface | `#141922` | `#FFFFFF` |
| surface-raised (menus, title bars) | `#1C2230` | `#EEF1F5` |
| border | `#2A3140` | `#D9DEE6` |
| text | `#E8ECF2` | `#12161C` |
| text-secondary | `#A7B0BE` | `#5B6472` |
| accent | `#4C8DFF` | `#2F6FE4` |
| success / warning / danger | `#3FB950` / `#D29922` / `#F85149` | same |

- **Wallpaper:** the default is a calm dark gradient (deep indigo `#1B2140` to slate `#0E1220`
  with a soft radial light near the top). No green. Settings offers 4-6 built-in wallpapers,
  generated in code (gradients), so there is no image licence question.
- **Type:** the system sans (Roboto on Android, Segoe UI on Windows). Desktop at 1920 wide:
  caption 12, body 14, title 16, heading 20 (px at 100 %); weights 400, 500, 600.
- **Spacing:** 4, 8, 12, 16, 24. **Corners:** buttons 6, windows 10, menus and cards 12.
  Soft shadow under windows and menus.
- **Icons:** one vector icon set for the shell's own controls (Material Symbols, Apache 2.0:
  THIRD_PARTY row first). App icons come from the app: the Android app's own icon, the `.exe`
  icon when Wine gives one, and for our built-ins a rounded square in an accent colour with a
  glyph.
- **Motion:** 120-160 ms fade/scale for menus and opening windows; nothing that delays typing.
- **Words:** the user never sees internal words: link, acc, guest, harness, display, X11, phase,
  B1, xeyes, package id. Plain verbs: Connect, Open, Install, Exit.

## 3. Desktop shell (what the PC shows)

| # | Seen now | Build |
|---|---|---|
| D1 | Dark green wallpaper | The design wallpaper; picker in Settings and on desktop right-click. |
| D2 | "Test" (moving ball) in the start menu | Remove from every user path; keep only behind the harness command used for measurements. |
| D3 | Start menu is a plain text list with developer names: "Notepad++ + xeyes (X11)", "Android app", "Test"; no icons, no search; it covers App Center's buttons | A start menu with a search box at the top (typing filters at once), pinned apps as an icon grid, "All apps" list, recently opened, one entry per installed app with its real name and icon (B-083). Esc or a click outside closes it. Win key opens it. |
| D4 | Taskbar: a text "Start" button, text-only app buttons, clock with seconds | Start button with an icon; app buttons with icon + short title, an underline for open and a highlight for focused; hover states; clock HH:MM with the date; a small status area (phone battery, USB/Wi-Fi, volume). Always above every window. |
| D5 | VS Code opened past the right edge and over the taskbar; windows can open off screen | New windows open centred in the work area (above the taskbar) at about 70 % of it; drag and resize are clamped so a title bar is always reachable; each app's last position is remembered. |
| D6 | Title bars: big text, square glyph buttons, same look focused or not | 32-36 px title bar with app icon, title, minimise/maximise/close icons; focused vs unfocused colours; double-click the title to maximise; drag to the top edge = maximise; to the left/right edge = snap to half; rounded corners and shadow. |
| D7 | VS Code's window carries the browser bar ("< > Reload Go address (https://...)") | Only the browser has an address bar. VS Code fills its whole window. |
| D8 | Black bars above and below the picture on the 1920x1200 (16:10) PC; a thin green strip at the bottom edge | The desktop takes the PC screen's aspect (the receiver sends its size; the phone sizes the desktop to it within the quality profile). Find the green strip (likely padded rows of the video frame shown as YUV zero = green): crop to the real frame size. |
| D9 | Text and controls look oversized at 1080p | Pick the density so the desktop feels like Windows at 100-125 % on a 1080p screen. |
| D10 | App Center: a search box and a raw text line "Google Tasks · android 2026.10 · Works"; "Open any .exe" as a loose field | A store page: search, result cards (icon, name, source chip Android / Windows / Linux / Web / Built-in, rating chip Works / Works, slow / Not yet, one Install or Open button), an Installed tab with Uninstall, a progress bar while installing, plain errors. "Open a Windows program (.exe)" as a normal button with a file picker. |
| D11 | Settings shows stale "Link: waiting..." and a hard-coded "1920x1080 @ 60 fps" (B-105) | Real Settings: Display (wallpaper, size, quality Auto/Sharp/Smooth), Connection (PC name, USB or Wi-Fi, live latency), Sound, Apps, About (version, licences). Live values only. |
| D12 | Nothing tells the user when something happens | Small toasts: "Copied", "File received: name", "Phone battery low", "Connection lost, reconnecting..." |
| D13 | Window titles name the wrong app (B-087/B-088/B-093); a focused window can stay behind a maximised one (B-091); doubled tiles with overlapping windows (B-085) | Fix (prompt step 12). |
| D14 | Empty desktop on first start | A one-time welcome card: "Open apps from Start. Press Exit to get your PC back." A few desktop shortcuts (Files, Browser, App Center), user-removable. |
| D15 | Browser is a basic WebView with text buttons | Chrome per DECISIONS D19 (prompt step 9). |
| D16 | Typing feels laggy | Prompt steps 2-3: measure inside the real apps, then cut the encoder stage. |

## 4. Phone app

What opens from the launcher today is the **"Phase 1 harness"**: grey test buttons (CREATE
DISPLAY, RELEASE, CAPTURE PNG, a "guest package id" field, IMPORT+LAUNCH GUEST, START LINK, STOP
LINK, GRANT AUDIO CAPTURE, BACKGROUND ACTIVITY SETUP) and a debug line
`link: running=false live=0 acc=false last=idle` on a white page. The title runs under the
status bar icons (no window insets). A user cannot tell what to do.

Build a real home screen (Material 3, our tokens, dark and light follow the system, portrait
and landscape, edge-to-edge with correct insets):

| # | Part | What it does |
|---|---|---|
| P1 | Connection card | One clear state at a time: **Not connected** ("Connect this phone to a PC" + 3 short steps: install the PC app, plug in USB or join the same Wi-Fi, confirm the code) -> **Looking for your PC** -> **Pairing** (the 6-digit code large, "Does your PC show the same code?" Yes / No) -> **Connected** (PC name, USB or Wi-Fi, time connected, one Stop button) -> **Problem** (plain message + Try again). |
| P2 | First-run setup checklist | Notifications, Background activity (the OEM screen with what to tap), Sound to the PC (explains the system dialog before it shows). Each with a tick when done and one line of why. Hidden once all are done; reachable from Settings. |
| P3 | Trusted PCs | List with "Forget". |
| P4 | Settings | Quality (Auto / Sharp / Smooth), Sound to PC on/off, About (version, licences, privacy). |
| P5 | Notification while running | "Desktop running on <PC name>" with a Stop action. |
| P6 | App name | No product name yet (D12): use the existing app-name string resource as the only place the name appears, so the owner's name drops in later. Never show "harness" or phase names. |

HarnessActivity stays for the test commands (all `am start -n .../.HarnessActivity` scripts keep
working) but loses the LAUNCHER category in release builds.

## 5. PC receiver

| # | Seen now | Build |
|---|---|---|
| R1 | Only command lines (`udreceiver view --addr ...`, `pair`, `trust`) | Double-clicking `udreceiver.exe` with no arguments opens a small window (about 480x560, dark tokens, Segoe UI, app icon). Every CLI subcommand keeps working (attach to the parent console). |
| R2 | - | Screens: **Looking for your phone** (USB and Wi-Fi, with "Plug in your phone, or put both on the same Wi-Fi") -> **Phone found** (name, Connect) -> **Pairing** (the 6-digit code, "Check that your phone shows the same code") -> **Connecting** -> full-screen desktop with Exit -> after Exit, back to this window with **Reconnect**. Remembers the trusted phone and connects by itself when it is plugged in. |
| R3 | - | Plain errors with a next step: "No phone found after 30 s: check the cable, or that both are on the same Wi-Fi", "Windows Firewall blocked the connection: allow it here". |
| R4 | Exit overlay | Same design; always visible but small; hint "Exit gives your PC back". One click, no password (owner's rule). |

## 6. Agent walk-through (the coding agent fills this in at Phase 13 step 6)

Run on 2026-10-23 03:07-03:18 on the final Phase-12 build (app on 2c0f6edc, receiver
`udreceiver view --addr 127.0.0.1:47600 --no-lock`, PC window 1920x1200). Pictures:
`p13\before\d0-desktop-empty.png`, `d0-phone-app.png`, `d1-start-menu.png`,
`d2-appcenter.png`, `d3-settings.png`, `d4-browser.png`, `d5-files.png`,
`d6-focus-over-max.png`.

Row by row (what the pictures show):

- **D1** - the wallpaper is one flat dark green (`#10312B`); no gradient, no depth. Confirmed.
- **D2** - "Test" is the fifth row of the start menu. Confirmed.
- **D3** - the menu is a plain text list on a flat panel, 420 px wide: "Programs" header,
  then App Center, Files, Browser, Settings, Test, then "Notepad++" in green with its version
  (`8.9.8.1`) after a dot. No search, no icons, no pinned grid, no way to close with Esc.
- **D4** - the taskbar has a text "Start" box, no icon, a clock with seconds (`03:07:50`),
  and no window buttons although two windows were open. In `d5`/`d6` the labels on the
  taskbar are cut off at x~120 and the clock is gone: a window is drawn over the taskbar, so
  it is not above every window. The empty desktop's taskbar (d0) is fully visible.
- **D5** - App Center's Install / Open / Uninstall row is cut by the bottom edge (d2): the
  buttons are half below the visible work area. Confirmed.
- **D6** - an equally flat full-width title bar; three equal rounded-square cells for
  minimise/maximise/close; white bold text; focused and unfocused differ only in blue vs grey.
  The maximised title says "Settings [max]" (an internal word). No icon, no hover.
- **D7** - the VS Code window is a `BrowserView`, so it carries the "address" bar (code read;
  visually the browser's toolbar in d4 is the same widget).
- **D8** - the PC is 1920x1200 (16:10) and the picture is 16:9: black bars top and bottom;
  the last rows of the video are a bright green strip (YUV padding, not the wallpaper).
- **D9** - the start-menu rows and the phone-style text are far too large for 1080p; 22 sp at
  240 dpi is 33 px, about twice a Windows menu row.
- **D10** - App Center is a text list; the details pane prints internal sentences ("fetch:
  code-server, built into the app (Phase 3)"); no cards, chips, progress or Installed tab;
  "Open any .exe" is not a button in the picture.
- **D11** - Settings says "Link: waiting for the receiver's status line" while the session is
  live, and "Video: 1920x1080 @ 60 fps paced" while the stream is 960x544 (B-105). About
  shows "unified-desktop (working codename) - Phase 6 shell".
- **D14** - the empty desktop has no shortcuts and no first-run hint.
- **D15** - the Browser page is blank white; the toolbar is plain text ("<", ">", "Reload",
  "Go", an "address (https://...)" field). No home page, no icons, no tab.
- **D16** - measured in Phase 12: Browser 65.7/74.5, Notepad++ 66.2/73.7, shell 87.0/97.0
  (p50/p90, n=25). Two of three pass; the shell still misses.
- **§4 phone app** - the launcher opens the "Phase 1 harness": title under the status-bar
  icons, grey buttons, a wrapping debug line, internal words (link, acc, guest, harness).
- **P1-P6 / R1-R4** - nothing on the phone or the receiver beyond the harness and the CLI
  exists yet, as the audit says.
- **B-091** - reproduced on this build: maximised Settings stays above focused Files
  (`d6-focus-over-max.png`); its title bar is drawn across the top while Files has focus.
- **B-105** - reproduced in `d3-settings.png` (stale link text, hard-coded video line).

New findings not in the audit table (A rows):

- **A1 (taskbar z-order)** - a window drawn while Settings is maximised covers the taskbar,
  including the clock; only ~120 px of "Start" remains. This is the same defect as D4's
  "always above every window"; the pictures make it reproducible.
- **A2 (no window buttons)** - the taskbar showed no buttons for the two open windows (the
  button list was built but not visible), so windows can only be reached through the desktop.
- **A3 (window title)** - a maximised window appends "[max]" to its title; an internal state
  word the user does not need.
- **A4 (menu clipping)** - the start menu is a fixed 420 px wide panel; the installed row is
  indented by 40 px and its version suffix reads like developer output.
- **A5 (phone status bar)** - the harness title sits under the status-bar icons; the screen
  is edge-to-edge without insets.
- **A6 (App Center buttons)** - the action row is not just clipped by the window; it is
  below the bottom edge of the work area, so no size of window shows it completely (D5).

## 7. Status

Every row above (D1-D16, P1-P6, R1-R4, plus the agent's own rows) gets one status: **DONE**
(with before/after pictures), **SKIPS** (row in SKIPS.md) or **DECIDED** (by the manager, with
the reason).
