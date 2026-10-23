> **SUPERSEDED 2026-10-24 01:18** by `docs/PROMPT_CODER_CONTINUE_2026-10-24.md` (Phase 13 Part 1/Part 2). Kept for history only.

# PROMPT — coding agent (manager gives this to the coder)

You are the **coding agent** for `unified-desktop` (working codename; never invent a product name). Read `AGENTS.md`, `STATE.md` (the 2026-10-23 22:30 "Continuation" bullet), `BUGS.md` rows **B-117..B-121**, `docs/TEST_REPORT.md` T-003..T-007, and `context.md` §1–6 before touching anything. The coding agent never commits or pushes.

## Current state you inherit (verify it, do not trust it)

- The working tree already compiles and passes: Android **214 tests / 0 failures / 3 skipped**, lint 0 errors, assemble OK; PC **100/0** + `lock_crash` 1/0, clippy `-D warnings` clean. Last commit `aad9bbf`; the tree is uncommitted.
- In the tree already: T-004 (Back on Settings), T-005 (Files Delete modal), T-006 (rename/new-folder prompts, folder select), T-007 (no 2 s focus steal / Alt), Dobby 16 KB alignment. BUGS B-117..B-121 describe them.
- A 16 KB `libbox64.so` candidate is at `android/win86/vendor/libbox64-16kb.so`; it is **not shipped** yet.

## Part 1 — Emulator live proof (do this first; the tester's emulator is free)

Use AVD `Pixel_8_Pro` (x86_64, Android 17, 16 KB pages) with the SDK at `C:\Users\Asus\AppData\Local\Android\Sdk`. Install the freshly built `android/app/build/outputs/apk/debug/app-x86_64-debug.apk`. Save evidence under `C:\Users\Asus\AppData\Local\Temp\opencode\p13fix\` with clear names.

1. Start the desktop and open Files.
   - Click a file, then the trash icon: a confirmation must appear **on the desktop**; Delete deletes it, Cancel keeps it, and the desktop must stay responsive (clock keeps ticking, no ANR). Before the fix this was `BadTokenException` + frozen UI (B-118).
   - Click a folder once: it must be **selected**, not opened; click it again quickly: it opens.
   - Pencil on a file/folder: a name field appears; type a new name with the PC keyboard; the extension must be preserved exactly as typed. Rename a folder too.
   - "+" must ask for a folder name; try an empty name and `../x` — both must be refused.
2. Phone home: open Settings, press the phone's Back — the app must return to the home page, not the launcher (B-121).
3. Install/launch on the **16 KB** emulator: the "This app isn't 16 KB compatible" dialog must be gone for the x86_64 APK (B-117, Dobby half).
4. PC focus (B-120): run `pc/target/release/udreceiver.exe view --addr 127.0.0.1:47600 --no-lock` against the emulator over `adb forward tcp:47600 tcp:47600`; put another Windows window in the foreground and leave it for at least 60 s. There must be **no** "keyboard focus reclaimed by the session window" line and no synthetic Alt reaching the other window. Then click our view and confirm typing still reaches the desktop.

Fix only what these checks prove is broken in the work you inherit. Anything older/outside this scope goes to BUGS.md, not into this task.

## Part 2 — Phone (`2c0f6edc`) when attached

1. **box64 16 KB swap, carefully:** push the candidate as a test only. Boot Wine with it and open Notepad++. Only if the window renders and typing works, replace `android/app/src/main/jniLibs/arm64-v8a/libbox64.so` with the candidate and rebuild. If it storms/does not boot (B-026), keep the shipped lib, log it, and leave T-003's box64 half open with the candidate path.
2. Re-run the **T-003 ELF check** on the final built APKs: every `lib*.so` for arm64-v8a and x86_64 must have LOAD `p_align` 0x4000 (`llvm-readelf -lW`). Report every file, not just box64/dobby.
3. **B-115 live proof:** Forget the PC on the phone, start the desktop, connect an unpaired receiver. The phone must send only the pairing exchange; `video-on`/`display` must be dropped until Yes; tapping No must leave the desktop down and the PC unlocked. Then pair and confirm video starts.
4. **B-116 live proof:** with the PC still pinning a phone that has forgotten it, start the receiver: it must show the 6-digit code and **not lock** before Yes; a refusal must give up with a plain message; a network failure must back off 0.5/1/2/4/8/10 s, not 250 ms.
5. If the phone is not attached, say so plainly and stop Part 2 instead of waiting.

## Part 3 — Close-out

- Re-run the full gates: `gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`; `cargo test --release`; `cargo clippy --all-targets --release -- -D warnings`; `git diff --check`.
- New behaviour needs a test; the emulator/phone evidence must be named in the report.
- Update `BUGS.md` (B-117..B-121 status + evidence), `SKIPS.md` if anything is deferred, `docs/PHASE13_RESULT.md`, and `STATE.md` **as your last action**.
- Report in the AGENTS.md format: item | what you did | files | test | verified on (phone / emulator / code-only) | numbers. Then what failed, what was skipped, and what is still unknown.
- Clean up: remove temp logs/files, force-stop the app, stop the receiver, restore `stay_on`, leave the phone as you found it. Do **not** commit.
