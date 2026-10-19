# Phase 6 result: the desktop shell (taskbar, start menu, windows, Files, Browser, Settings)

> **Label note - read first.** Everything in this phase was measured and verified on the
> **Android 17 emulator** (`emulator-5554`, Pixel_8_Pro AVD, Android 17, 240 dpi, desktop
> VirtualDisplay 1920x1080 inside a 2992x1344 landscape screen), driven from the **real PC
> window** (mouse + keyboard through the Phase-5 link: `adb forward tcp:47600` then
> `udreceiver view`, so the transport is TCP on this laptop, not Wi-Fi).
> The **phone round (item 8) is OPEN**: the test phone `2c0f6edc` was unplugged at 06:09 and
> never appeared in `adb devices` (polled until the end of the run) -> SKIPS.md.
> No number in this file may be called "phone-verified".

## 1. Gate

| Gate line (prompt item) | Result | Evidence |
|---|---|---|
| Shell core: taskbar, start menu, windows (move/resize/minimize/maximize/restore/close), model tests | PASS (emulator): shell live with 4 windows; 600-move title-bar drag moved the window; start menu opens/closes; `Phase6ShellModelTest` 18/18; `:app:testDebugUnitTest` 100 tests, 0 failures | `f-01-startmenu-*.png`, `f-02-windows-*.png`; TODO item 4 |
| Apps: Files, Browser, Settings, Test (minimal, real) | PASS (emulator): Files created "New folder" in the app-private `files/desktop` (real `java.io.File`); Browser loaded `https://example.com/` with the URL typed on the PC keyboard; Settings shows its text; Test hosts the Phase-5 desktop in a window | shots `04-files-*.png`, `03-browser-*.png`; TODO item 5 |
| PC keyboard/mouse drive the shell | PASS (emulator, via the link): 100 keys + 10 drags from the PC; keys reach the focused widget (keyFlashes == key presses); pointer events are real MotionEvents (SOURCE_MOUSE) | `p6:shell` counters; TODO items 5/7 |
| Core gate screenshots (4) | PASS: four app-side captures of the live `ShellRoot` hierarchy (the same views the VirtualDisplay composes), each PNG starts `89 50 4E 47` | `01-startmenu-1792373789635.png` 64,396 B; `02-windows-1792373791063.png` 40,187 B; `03-browser-1792373722669.png` 65,396 B; `04-files-1792373759531.png` 65,552 B (on the phone in `filesDir`, copies in the temp p6 dir) |
| The PC window shows the desktop pixels | **NOT VERIFIED on this stream** (limit): the PC window stayed black - the decoder drops every frame of the emulator's stream (BUGS **B-057**). The screenshots above are the desktop as composed on the phone, not PC-window captures | `view11.log` `presented=0 decoded=0 dropped=19163..33789 seq=0` |
| B-039 WebView keyboard probe (item 5) | PROBED (emulator): PC keys reach the browser through the **address EditText's IME route** (`route=edittext-ime`); the page's own DOM keydown listener still gets nothing from the link (`pageKeys=(pending)`) - unchanged B-039 | `p6:probe:harness`, `p6:keys` |
| Measurements: drag fps, idle fps, typing samples, stuck keys | PASS (emulator; phone-side counters, see section 2) | TODO item 7 |
| Build gates | PASS: gradle `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` -> `BUILD SUCCESSFUL`, exit 0 (three runs: 07:16; 07:32 after the toolbar fix; **07:38 on the final tree** - all green, junit 100 tests / 0 failures / 3 skipped), `cargo build --release` exit 0; `cargo test --release` 41/41; `cargo clippy --release --all-targets` exit 0 | `gate-gradle.log`, `gate-gradle2.log`, `gate-gradle3.log`, `gate-cargo.log`, `gate-cargo-test.log` (temp p6 dir) |
| Phone round (item 8) | **SKIPPED - phone never appeared** (see SKIPS.md) | `adb devices` = emulator only, every poll 06:09-08:3x |
| Licenses | No new third-party component: the shell uses only the Android SDK (`View`/`ViewGroup`, `WebView`, `java.io.File`, `Paint`) | THIRD_PARTY.md unchanged |

## 2. Numbers (all emulator, phone-side counters; the receiver cannot be used for fps here - B-057)

| Measurement | Result | Method / caveat |
|---|---|---|
| Idle fps, **no animating window** (0 windows open) | **1.0 fps** (10 frames/10 s; earlier 21 frames/21.1 s) | phone `P5 VSTAT sent=` delta; the 1 s keep-alive holds |
| Idle fps, Files window open (static) | 2.3 fps (23/10 s) | same |
| Idle fps, Files + Browser with `example.com` loaded | 1.0 fps (10/10 s) | same; the browser does not keep the compositor running |
| Fps with the **Test** window open | 55.6 fps (556/10 s) | by design: the Phase-5 test desktop animates continuously. An earlier 53.9 fps "idle" reading was this animation, not a defect (corrected in TODO item 7) |
| Title-bar drag fps (Files, 600 moves) | **30.6 fps** over 30.4 s (929 frames) | phone encoder counter; the receiver VSTAT reads 0 (B-057) |
| Typing samples (PC -> focused Test window) | 19 samples from 20 keys (1 key coalesced): medians **recv->dispatch 0.14 ms, dispatch->drawn 8.4 ms, drawn->encoded 26.8 ms** (p90 2.1 / 15.4 / 41.7; max 2.5 / 15.9 / 44.4) | phone `P5 LAT` lines; the PC-side stages (incl. `pc_rx>presented` and the end-to-end total) are NOT measurable because no frame ever presents (B-057) |
| Stuck keys / buttons after stress | **keysDown=0 buttonsDown=0** after 100 keys + 10 drags (keysAllTime 49->149, keyFlashes 49->149, buttonsAllTime 6->16) | phone `p6:shell` counters |

Fixed 60 fps encoder pacing (Phase 5) plus a static shell gives a quiet idle (1 fps) and a
30 fps drag; a text field costs ~35 ms to the encoder output on the phone side.

## 3. Method

- **Harness**: `p6:<command>` broadcasts (`HarnessReceiver` -> `Phase6Dispatch`) drive the live
  shell and answer in one logcat line (`p6.ps1`); the PC side posts `WM_*` messages straight to
  the receiver window (`p6input.ps1`, no focus needed) and the receiver maps client -> desktop
  through the letterbox (`fit()` scale 0.8, oy 48).
- **Screenshots**: `p6:shot[:name]` draws the live `ShellRoot` hierarchy into an ARGB bitmap and
  writes `filesDir/<name>-<millis>.png` (1920x1080); pulled with `adb exec-out run-as ... cat`
  (`adb pull` of app-private files is refused).
- **fps / typing**: the phone's own `P5 VSTAT` (cumulative `sent=`) and `P5 LAT` lines taken
  from logcat; deltas between two reads 10 s apart (a `logcat -c` + immediate read is NOT valid -
  the first attempt of this kind produced garbage and was discarded).

## 4. Fixes made while building (self-found, in-scope)

1. **Start menu anchored bottom-start** with `bottomMargin = TASKBAR_H` (a fixed-height panel
   clipped the 4th entry at other window heights).
2. **Key routing keeps the widget the mouse focused** (`content.findFocus()`; only falls back to
   the deepest focusable leaf) - the WebView was stealing focus from our own EditText.
3. **Files toolbar wrap** ("Del"/"ete" at 240 dpi in a narrowed window, seen in the item-5
   screenshot): toolbar is now a horizontal scroll view with single-line buttons and trimmed
   padding - all four labels fit at the default window width
   (`filesfinal-1792375311960.png`), and a narrowed window scrolls instead of wrapping
   (`filesfix2-1792375185695.png`).
4. Encoder hardening for the emulator: see B-056.

## 5. Bugs logged (not fixed - manager decides)

- **B-056** - emulator encoder rejects `KEY_PREPEND_HEADER_TO_SYNC_FRAMES` with `BAD_VALUE`;
  mitigated in the code (optional keys dropped and retried on a configure failure).
- **B-057** - the PC `view` stays black on the emulator stream: every frame dropped while
  `awaiting_keyframe`, the keyframe request is issued once, the emulator encoder ignores it
  (the phone's encoder honoured it in Phase 5). This is why the gate screenshots are app-side
  and why the PC-side typing stages could not print.

## 6. Still unknown (until the phone round happens)

- The whole shell on the phone: real encoder, real Wi-Fi, real OEM dialogs; whether the
  `c2.qti` encoder + the Phase-5 decoder chain show the shell without B-057.
- Phone-side numbers (idle/drag/typing) and the stuck-key stress on the real device.
- The Files toolbar at the phone's density (the wrap was fixed at 240 dpi; the phone is 480).
