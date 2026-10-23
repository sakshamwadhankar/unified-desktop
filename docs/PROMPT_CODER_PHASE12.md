> **SUPERSEDED 2026-10-24 01:48** by `docs/PROMPT_CODER_PHASE12_FINAL_2026-10-24.md` (same three open items, corrected facts and time boxes). Kept for history only.

# PROMPT — coding agent: COMPLETE PHASE 12

You are the coding agent for `unified-desktop` (working codename; never invent a product name). Read `AGENTS.md`, `STATE.md` (Phase 12 lines), `docs/PHASE12_RESULT.md`, `OPTIMIZATION.md`, `BUGS.md` (B-103, B-106, B-108), `SKIPS.md` (Wi-Fi typing row), and `docs/PHASE4_PROTOCOL.md` before touching anything. **Never commit or push.**

## What Phase 12 still needs (2026-10-23)

Phase 12 is otherwise PASS and closed by the manager. Only these are open:

1. **Wi-Fi typing, unmeasured** — the phone's Wi-Fi was off. The owner now has the phone and laptop on the **same Wi-Fi**. Measure PC typing over a **real Wi-Fi link** (not adb forward), n ≥ 25, in a real app, report p50/p90. Target **≤ 120 ms**.
2. **USB typing p50 90.8 ms** (target **≤ 80**; p90 115.3). The dominating stage is `drawn>encoded` **46–67 ms**, not transport.
3. **Notepad++ start 11.95 s warm / 15.88 s cold** (target **≤ 10**). Wine prewarm is ON by default (D21); the remaining time is Notepad++'s own paint under Box64/Wine.

Everything else is already PASS with numbers in `docs/PHASE12_RESULT.md` (connect 0.94 s, drag 42 fps, VS Code 13.48 s, thermal 1080p→540p, receiver 2.22 MB, APK 55 MB, audio/clipboard/files, soak).

## Build under test

The working tree also carries uncommitted **Phase 13** work (B-115..B-121); it is not committed. Do not revert it. Build the APK and receiver from this tree, record their **SHA-256 and size** in the report, and keep the same build for every BEFORE/AFTER pair. **Do not ship or use the unverified 16 KB `libbox64.so` candidate** for Phase 12 numbers — use the shipped 4 KB lib until Wine has booted with the candidate on the phone.

## Order of work

1. **Setup and baseline (15 min).** Phone `2c0f6edc`, charged > 20 %, unlocked, same Wi-Fi as the laptop; `stay_on_while_plugged_in=usb` only while testing. Install the final APK, start the receiver, confirm the link works over **Wi-Fi** without `adb forward` (phone IP from the app/receiver). Record connect time and the exact build SHA.
2. **Wi-Fi typing (45 min).** Type a fixed 25-key string into a real app (Notepad++ or the Browser address bar is acceptable; say which) with the receiver's `LATSTAGE total` probe. n ≥ 25, report **p50 and p90**; repeat once to show stability. This closes the SKIPS row if a clean number exists. Take one screenshot/log showing the text actually landed.
3. **USB typing: try to reach ≤ 80 (90 min).** Return to USB (cable). Before/after **one change at a time**, n ≥ 25 each:
   - the encoder latency profile (`p12lat` 0/1/2/3) and `--no-vsync`;
   - the `drawn>encoded` stage: encoder slices/redraw, keep only what measurably lowers `LATSTAGE` (report the stage split, not just the total);
   - if nothing wins, stop and keep profile 0; do not churn.
   Every candidate needs BEFORE and AFTER numbers from a fresh app+receiver. A win must be reproducible on a second run.
4. **Notepad++ ≤ 10 s (60 min).** Measure cold and warm on the final build with prewarm ON. If you change anything (prewarm timing, serialization, app cold-start contention), show BEFORE/AFTER. Report click→window-visible, not just log timestamps. If it stays over 10 s, keep the current default and document the miss honestly.
5. **Optional, only if the above is done:** B-103 paste into Notepad++ (no new idea in the last round; do not reopen without one).

## Measurement rules

- Phone only. Label every result `phone`; an emulator result is never phone-verified.
- n ≥ 25 per latency figure; give p50 and p90. One run is not a result.
- Same thermal state for BEFORE/AFTER (report `thermal`), same app, same text; phone cool before the baseline.
- The phone must stay usable; stop before it drops under 20 % battery.
- Keep evidence under `C:\Users\Asus\AppData\Local\Temp\opencode\p12final\` with clear names.

## Close-out

- If a change wins, make it the default and say so; if not, leave the code as it was and record the miss.
- Update `docs/PHASE12_RESULT.md` (Round 3 table with BEFORE/AFTER), `OPTIMIZATION.md`, `BUGS.md` (B-103/B-106 only), `SKIPS.md` (remove the Wi-Fi row **only** if measured), and `STATE.md` **last** (replace stale lines, no history).
- Gates: `cd android && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`; `cd pc && cargo test --release && cargo clippy --all-targets --release -- -D warnings`; `git diff --check`.
- Clean up: force-stop the app, stop the receiver, remove adb forwards, restore `stay_on=0`, leave the phone as found.
- Report in the AGENTS.md table format: item | what you did | files | test | verified on (phone / emulator / code-only) | numbers. Then failed/skipped/unknown. **Do not commit.**
