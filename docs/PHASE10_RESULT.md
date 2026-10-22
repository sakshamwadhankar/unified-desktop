# Phase 10 result: phone-first session continuity

**Date:** 2026-10-22 (Round 2 post-soak)

**Status:** **FAIL - resource governor is still PARTIAL (lower-resolution/output and before-after resource proof); all phone gates pass, including the owner call (manager, 08:20); Phase 9 Exit PASS**
**Phone:** `2c0f6edc`, OnePlus CPH2619, Android 16, SDK 36, ColorOS  
**PC:** Windows 11 26H2 build 26300, Intel UHD hardware decode  
**Transport label:** the Round 1 short smoke used the development `adb forward tcp:47600`
harness. The final 4c and soak runs used the phone's Wi-Fi address
`192.168.1.16:47600`; ADB was used only to drive test commands and never as the video
transport. This is not a no-ADB user-path proof.

## Scope implemented

- Added the `WAKE_LOCK` manifest permission and service-owned `Phase10Runtime`.
- Kept the existing `connectedDevice` foreground service and added a partial wakelock only
  while a live session exists.
- Added explicit same-trusted-PC session ownership and refusal/replacement handling.
- Kept the private desktop alive across a link loss and added activity-free Presentation
  surface detach/rebind support.
- Added `onTaskRemoved`, trim-memory handling, service cleanup, and a polling governor.
- Added ColorOS/OnePlus/OPPO onboarding with a public app-details intent and an explicit
  confirmation preference; no code claims to detect the OEM setting.
- Added a pure, unit-tested governor policy with the order: hidden windows, 30 FPS, 15 FPS,
  lower-resolution request, PC warning.
- Removed the PC's 2 ms rustls read timeout, added a cloned-socket dead-link watchdog, moved
  clock-sync keep-alives into the receive loop, widened local clock stamps, and added pinned
  reconnect plus mDNS retry.
- Fixed `udreceiver view --seconds N` so the duration is checked inside the blocking receive
  loop and the window closes instead of running indefinitely.

## Verification table

| Item | What was done | Evidence | Test / result | Verified on |
|---|---|---|---|---|
| Android build | Unit tests, lint, debug APK | `android/app/build/` | `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` — BUILD SUCCESSFUL; 155 tests, 0 failures, 3 skipped | code-only |
| PC build | Receiver tests and static checks | `pc/target/release/` | `cargo test --release` - 73 passed / 0 failed after the B-098 fix; clippy clean; release build successful | code-only / Windows 11 |
| Wakelock | Session acquire/release | `evidence/phase10/t10-final-view.log`; phone `files/p4/p4.log` | `WAKELOCK acquire session=4`; release after `--seconds 3` session end | phone via dev harness |
| Service-owned desktop | Link attached with service context | phone `p4.log` | `P5 desktop up ... context=service (video-on)` | phone via dev harness |
| Governor | Real thermal status observed during a short session | phone `files/p4/p4.log` (manager re-read 2026-10-21) | Phone: `GOVERNOR action=SetFps(fps=15)`, `LowerResolution` (logged `resolution request deferred` - **not applied**), `WarnPc`, all on `thermal=3` (SEVERE). PC: the warning line is **not** in the cited `t10-final-view.log` (manager check) | phone; PC side unproven |
| PC decode | Stream stayed on hardware path | `evidence/phase10/t10-final-view.log` | D3D11/NV12, 1920x1080 input, no dropped frames in short run | Windows 11 |
| Timed view exit | Regressed duration handling | `evidence/phase10/t10-final-view.log` | `--seconds 3 reached`, `pipeline stopping (release-all sent)`, `window closed` | Windows 11 |
| Clock wrap | Forward/backward low-word wrap | `pc/udreceiver/src/view.rs` tests | both wrap tests passed in the 57-test run | code-only |

## Short phone smoke numbers

The final short session logged:

```text
WAKELOCK acquire session=4
P5 desktop up 1920x1080 encoder=c2.qti.avc.encoder context=service (video-on)
P5 VSTAT sent=5 ... frames=5 draws=5
P5 VSTAT sent=7 ... frames=7 draws=7
WAKELOCK release why=session 4 ended
```

The receiver reported hardware D3D11 decode and presented frames with `dropped=0` during the
3-second timed run. This proves the service-context smoke path, not long-run survival.

## Reconnect evidence

`evidence/phase10/t4-live-reconnect.log` contains repeated development-harness cycles of:

```text
phone closed the link
RECONNECT start_us=...
RECONNECT ok_us=... reconnected silently to pinned phone ...
```

The same trusted phone identity was reused and the receiver continued to decode after the
reconnect attempts. A controlled physical cable/Wi-Fi pull with guest PID and window-list
comparison was not run, so the required 3/3 under-5-second gate remains open.

## Round 2 final phone gates (2026-10-22)

| Item | Result | Evidence | Numbers / notes | Verified on |
|---|---|---|---|---|
| 4a activity-free WhatsApp 60 s | **PASS** | `p10/whatsapp60.csv` | App pid 608 unchanged; CPU ticks 5569/3584 -> 9754/5799; P5 `sent=302 -> 1406`; PC 13.9-15.0 fps; no input/screenshots during the run | phone |
| 4b real call for 1 minute | **PASS** (manager, 2026-10-22 08:20) | `p10/call-test-manager.txt` | Owner's real call 08:20:05-08:21:42 (1 min 37 s, mCallState=2) during a live Wi-Fi session: the same app PID 23648 in all 15 in-call samples, CPU 1488 -> 3167 advancing in every sample, PC frames 56 -> 154 during the call and 205 after, dropped=0 (`p10/call-test-manager.txt`). |
| 4c three Wi-Fi reconnects, connected-first gate | **PASS 3/3** | `p10/reconnect-retest2.csv`, `p10/reconnect-retest2-rects.txt`, `p10/reconnect-retest2.log` | Cycle times (Wi-Fi CONNECTED -> first new frame): **3668 / 2798 / 2774 ms** (all <= 5000). Legacy enable -> picture: **5954 / 5329 / 5322 ms**; TLS handshake 40.5 / 48.5 / 42.2 ms. X11 id=6 stayed `96,80,900,620` and App Center id=3 stayed `60,48,900,620` before/after all cycles. Xvfb 29047, wineserver 29086, explorer 29110 and notepad++ 30161 were unchanged. B-097 did **not** reproduce under script-only input; it remains FIX LATER as real-PC-input, not a reconnect layout regression. | phone |
| 4d different-PC policy | **PASS** | `p10/different-pc.txt` | Fresh PC identity `231A34891876AE5A`; receiver printed `PAIR FAILED: phone closed the connection while pairing`; phone log `SESSION refused different PC while desktop is live fpr=231A34891876AE5A`; the first view continued (sent 1158 -> 1325, windows=3) | phone |
| 4e OEM onboarding persistence | **PASS** | `p10/phase10-4e.txt` | ColorOS "Allow background activity" checked=true; summary "No restrictions on any background activity."; after force-stop/restart `shared_prefs/phase10.xml` still `<boolean name="oem_background_confirmed" value="true" />`; new tap logged `10-21 23:18:42.102 ... Phase10 : OEM background activity user-confirmed` | phone |
| 5 two-hour charger soak | **PASS** | `p10/soak.csv`, `p10/soak-note.txt`, `p10/soak-view-restarts.txt` | **25 samples, 121.0 min**, 23:45:42 -> 01:46:42; battery 47 -> 57 %, max battery temp **38.2 C**, thermal max 3, app pid **28212 unchanged**, CPU ticks **90,749 -> 265,728**, **CPU advancing 24/24 intervals**, LMK **0**, TLS failures **0**. Receiver 43824 viewed continuously **00:01:36-01:47:02 (105.4 min)**, `dropped=0`; earlier 37532/10912 restarts are in `p10/soak-note.txt`. | phone |
| B-049 clock | **PASS (phone)** | `p10/soak-view.log`, `p10/soak.csv` | Full-width stamps and wrap tests pass; receiver 43824 ran 105.4 min continuously with 2,866+ `clock: phone-pc=... us` lines and no gap; exceeds the 71.6-minute proof. | phone |
| B-072 continuous TLS | **PASS (phone)** | `p10/soak-view.log`, `p10/soak-view.err`, `p10/soak.csv` | 105.4 min continuous TLS, 4,663+ VSTAT, `dropped=0`, empty stderr, zero `cannot decrypt`. | phone |
| B-094 cold-Wine uninstall | **PASS (phone)** | `p10/b094-result.txt`, `p10/b094-p7.log`, `p10/b094-wine.log` | After `run-as ... killall` left `pidof` empty, Notepad++ exit **0** (`{5C6F8543-7EA8-40B9-B78F-04C78300DE7F}`, restore 3/3, clear true) and 7-Zip exit **0** (`{23170F69-40C1-2702-2604-000001000000}`, restore 3/3, clear true). Both cold boots logged `PREFIX_BOOT_OK`; menu rows went `... Test / Notepad++ / 7-Zip` -> `... Test / 7-Zip` -> `... Test`. | phone |
| ONE MOUSE | **PASS (phone)** | `BUGS.md` B-095; `p10/phone-b095-*` | `Phase5Desktop.kt:126 it.setPcPointerVisible(false)`; `view.rs:471` `hCursor: LoadCursorW(None, IDC_ARROW)`, `view.rs:574-580` `WM_SETCURSOR` -> `SetCursor(IDC_ARROW)`; phone `hCursor=0x10003`, exactly one arrow, real click `(70,1108)` opened Start. | phone |
| Resource governor | **PARTIAL** | `docs/PHASE10_RESULT.md`; phone `files/p4/p4.log` | Thermal status 3 exercised the policy actions; the lower-resolution output change and before/after CPU/RAM/FPS numbers are still not proven. | phone; PC unproven |
| Phase 9 Exit after soak | **PASS** | `p10/phase9-click2.log`, `p10/phase9-result.txt`, `p10/phase9-exit.txt`; B-098 fixed | Actual Exit-button click -> PC `Default` in **657 ms** (`[lock] exited: switch_ms=22 total_ms=162`); after the B-098 fix the named-event path exits in **473 ms** with `phone closed the link` / `window closed`; journal clean, no receiver left. | Windows 11 + phone |

## Gate status

| Gate | Status | Reason |
|---|---|---|
| Activity-free WhatsApp 60 s | **PASS** | Round 2, phone: pid 608 unchanged; CPU ticks `5569/3584 -> 9754/5799`; P5 `sent=302 -> 1406`; PC 13.9-15.0 fps (`p10/whatsapp60.csv`). |
| Real call for 1 minute | **PASS** (manager, 2026-10-22 08:20) | Owner's real call 08:20:05-08:21:42 (1 min 37 s, mCallState=2) during a live Wi-Fi session: the same app PID 23648 in all 15 in-call samples, CPU 1488 -> 3167 advancing in every sample, PC frames 56 -> 154 during the call and 205 after, dropped=0 (`p10/call-test-manager.txt`). |
| 2-hour charger soak | **PASS** | 121.0 min, 25 samples, app pid 28212 unchanged, CPU advancing 24/24, LMK 0, TLS 0, max temp 38.2 C, receiver 43824 continuous 105.4 min (`p10/soak.csv`). |
| Three Wi-Fi reconnects | **PASS 3/3** | Connected -> picture 3668 / 2798 / 2774 ms under the manager's 23:50 definition; legacy enable -> picture 5954 / 5329 / 5322 ms; X11/App Center rects and guest PIDs unchanged (`p10/reconnect-retest2*`). |
| Same trusted PC / different PC policy | **PASS** | Fresh PC identity refused; live view continued (`p10/different-pc.txt`). |
| OEM onboarding persistence | **PASS** | ColorOS checked=true; preference survived force-stop/restart (`p10/phase10-4e.txt`). |
| Resource governor | **PARTIAL** | Policy exercised on thermal 3; lower-resolution output/before-after resource numbers still missing. |
| B-049 clock | **PASS (phone)** | 105.4 min continuous, 2,866+ clock lines, no gap. |
| B-072 continuous TLS | **PASS (phone)** | 105.4 min, dropped=0, zero `cannot decrypt`, empty stderr. |
| B-094 cold uninstall | **PASS (phone)** | Notepad++ exit 0, 7-Zip exit 0 after real cold stops; MSI state restored and cleared. |
| Phase 9 Exit after soak | **PASS** | Actual Exit click 657 ms; named-event path 473 ms after B-098 fix; PC Default, journal clean, no receiver. |

**Phase 10 is not a pass.** All phone session gates and the Phase 9 Exit gate pass, but the
resource governor lower-resolution/output change and the before/after CPU/RAM/FPS proof
remain **PARTIAL**. B-098 is fixed and phone/PC-verified (named event 473 ms; actual Exit
click 657 ms). Multi-monitor is a declared **SKIP**: only one display was attached
(`\\.\DISPLAY1`, 1536x960). The open work is recorded in `SKIPS.md` and `BUGS.md`.

## Cleanup

- Final Phase 10 teardown at 08:00: no `udreceiver.exe` running, no ADB forwards, phone app
  0 processes and 0 services, `stay_on_while_plugged_in=0`, `screen_brightness=699`, Wi-Fi
  enabled and connected on `192.168.1.16`; PC `input-desktop=Default`, `lock-state=Normal`,
  journal clean.
- A concurrent Phase 11 `view --no-lock` test started at 08:00:17; it was left running by
  the other workstream, so the phone app was live again at 08:02. This close-out did not
  kill that test.
- The development forward was used only for verification and was removed; the final 4c/soak
  used the phone's Wi-Fi address.
- No APK, receiver binary, installation file, or other build artifact was added to the
  repository; only source, docs, and text evidence are in the working tree.
- Evidence stays under `C:\Users\Asus\AppData\Local\Temp\opencode\p10`, not in the repo.
