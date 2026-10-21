# Phase 10 result: phone-first session continuity

**Date:** 2026-10-21  
**Status:** **INCOMPLETE / not a phase pass**  
**Phone:** `2c0f6edc`, OnePlus CPH2619, Android 16, SDK 36, ColorOS  
**PC:** Windows 11 26H2 build 26300, Intel UHD hardware decode  
**Transport label:** phone smoke was run through the development `adb forward tcp:47600`
harness; this is not a no-ADB user-path proof.

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
| Android build | Unit tests, lint, debug APK | `android/app/build/` | `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` — BUILD SUCCESSFUL; 152 tests, 0 failures, 3 skipped | code-only |
| PC build | Receiver tests and static checks | `pc/target/release/` | `cargo test --release` — 57 passed / 0 failed; clippy clean; release build successful | code-only / Windows 11 |
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

## Gate status

| Gate | Status | Reason |
|---|---|---|
| Activity-free WhatsApp 60 s | **OPEN** | No final 60-second foreground-app capture with process/CPU/RSS evidence. |
| Real call for 1 minute | **OPEN** | Not run in this session. |
| 2-hour charger soak | **OPEN** | Not run; no phone survival/LMK/standby/wakelock time series. |
| Three real reconnects | **OPEN** | Only development-harness reconnect evidence exists; no physical link-pull timing or guest PID proof. |
| Same trusted PC / different PC policy | **PARTIAL** | Code path exists; different-PC refusal was not exercised on the phone. |
| OEM onboarding persistence | **OPEN** | UI and intent are implemented; ColorOS setting and confirmation persistence were not manually verified. |
| Resource governor | **PARTIAL** | Thermal status 3 exercised all policy actions; lower-resolution output change and before/after resource numbers are not proven. |
| B-049 clock | **PARTIAL** | Full-width stamps and wrap tests pass; 71.6-minute drift proof is not run. |
| B-072 continuous TLS | **PARTIAL** | Timeout removal, watchdog, and timed shutdown are implemented; full continuous TLS soak is not run. |
| Phase 9 Exit after soak | **OPEN** | No post-soak lock/Exit run. |

The open work is recorded in `SKIPS.md`. Phase 10 must not be marked PASS until those rows are
closed with phone-labelled evidence.

## Cleanup

- The final timed receiver process exited; no `udreceiver.exe` was left running.
- The development forward was used only for verification and should be removed before the
  next phone gate or final handoff.
- No APK, receiver binary, or other build artifact was added to the repository; only source,
  docs, and text evidence are in the working tree.
