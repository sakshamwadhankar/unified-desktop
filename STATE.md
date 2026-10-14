# STATE: where the work stands

Rules: replace stale lines, never append history, never record a status that was not
verified this session. The coding agent updates this as its last action; the manager
corrects it after review.

## Now

- **Phase:** 1 of 13 (hidden Android app). **Round 1 ran and was cut off** (OmniRush
  model service unreachable mid-run). **Phase 1 is NOT passed: nothing proven on a device.**
- **Last updated:** 2026-10-14. Manager reviewed round 1 and committed the clean work.
- **Phone:** OnePlus CPH2619, adb serial `2c0f6edc`, Android 16 / SDK 36. **This run adb
  saw no phone and no emulator**, so no gate was tested on hardware.
- **Next action:** the manager writes the Phase 1 round-2 prompt (verify/fix the build,
  then do the real guest-app integration and the device proofs).

## Done (verified by the manager, round 1)

- **T1 engine choice** — `docs/PHASE1_ENGINE.md`: real licenses read from the LICENSE
  files, ZENINXOP rejected (no LICENSE in the ZIP; free edition excludes Android 16/17),
  pick is **Blacks-BlackBox** (Apache-2.0). Solid, trusted. Not yet a DECISIONS entry.
- **T2 project skeleton (partial)** — `android/` Gradle project, `:app` + `:engine`;
  engine brought in as pinned, SHA-256-checked source via `fetch-source.sh` (no binaries
  tracked: tarballs, `.gradle/`, `build/`, fetched `cpp/`/`java/` are all gitignored —
  manager confirmed with `git check-ignore`). `THIRD_PARTY.md` components registered.
  **The build is NOT verified** and the Gradle wrapper is incomplete (see SKIPS).

## Not done in Phase 1 (the actual bet is still open)

- **T3** — no Google-Play guest app was launched inside our process on our display. The
  harness only launches **our own** control activity (the agent's own README says so);
  this does not prove virtualization. This is the core bet and it is unproven.
- **T4** input is wired to our own activity only, not an injected guest.
- **T5** no measurements (no device this run). **T6** Play-policy doc missing.

## Open

| Item | Notes |
|---|---|
| Bets: Phases 1-4 | ARCHITECTURE.md section 6, questions Q1-Q6 |
