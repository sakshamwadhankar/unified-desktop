# Phase 10: phone-first session continuity

Done 4 of 8 | Next: finish the real-phone Phase 10 gates | Updated 2026-10-21

1. [x] Implement service-owned session lifetime: `Phase10Runtime`, partial wakelock, session
       guard, activity-free Presentation detach/rebind, `onTaskRemoved`, trim cleanup, and
       same-trusted-PC reconnect/refusal policy.
2. [x] Implement and unit-test the pure phone-first governor: hidden-window stop, 30 FPS,
       15 FPS, lower-resolution request, and PC warning; include low-memory, trim, thermal,
       and zero-available-memory boundaries.
3. [x] Implement receiver continuity changes: remove the 2 ms TLS read timeout, add the
       cloned-socket dead-link watchdog, keep-alives inside the receive loop, full-width clock
       stamps, mDNS retry, and pinned-address reconnect.
4. [x] Run code gates and short phone/receiver smoke tests on phone `2c0f6edc`:
       Android build successful; PC 57/0 release tests, clippy clean, release build; service
       context, wakelock acquire/release, governor actions, hardware decode, and `--seconds`
       shutdown were observed over the development ADB-forwarded link.
5. [ ] Make the lower-resolution governor action change the encoder output and measure the
       before/after FPS, latency, RAM, and CPU numbers.
6. [ ] Run the real phone activity-free gate: WhatsApp foreground for 60 s plus a real call
       for 1 min; capture PID/CPU/RSS/standby/wakelock/FGS and desktop continuity evidence.
7. [ ] Run the required 2-hour charger soak, three real link pulls/reconnects with the same
       guest PIDs/window list, B-049 71.6-minute clock proof, B-072 continuous TLS proof, and
       final Phase 9 Exit-after-soak proof.
8. [ ] Update `SKIPS.md`, `docs/PHASE10_RESULT.md`, `ARCHITECTURE.md`, and `STATE.md` last;
       leave the phone and PC clean and report every remaining gate honestly.

Current verified numbers:

- Android: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` —
  `BUILD SUCCESSFUL` on 2026-10-21; test reports show 152 tests, 0 failures, 3 skipped.
- PC: `cargo test --release` — 57 passed / 0 failed; `cargo clippy --all-targets` clean;
  `cargo build --release` successful.
- Phone smoke: real OnePlus CPH2619, Android 16 / SDK 36; service logged wakelock acquire
  and release, `context=service`, and the ordered governor actions under thermal status 3.
- Receiver smoke: D3D11 hardware decode, 1920x1080 input, `--seconds 3` now exits with
  `pipeline stopping (release-all sent)` and `window closed`.

Not a Phase 10 PASS yet. The unrun gates and their reasons are recorded in `SKIPS.md`.
