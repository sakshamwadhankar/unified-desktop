# AGENTS.md: rules for the CODING AGENT

> If you are Claude Code acting as the project **manager**, your rules are in
> [CLAUDE.md](CLAUDE.md). This file says what the coding agent must do.

You write the code for this project (working codename `unified-desktop`; **no product name
yet, never invent one**). An Android phone becomes a desktop computer that any PC shows:
Windows, Linux and Android apps run on the phone; the PC shows them full screen and locked,
with one Exit button. Read this whole file first.

## 1. Read first, every session

- **Always:** [STATE.md](STATE.md), [BUGS.md](BUGS.md), [SKIPS.md](SKIPS.md), and your prompt.
- **As the phase needs** (all of them at least once): [PRD.md](PRD.md),
  [USER_WORKFLOW.md](USER_WORKFLOW.md), [ARCHITECTURE.md](ARCHITECTURE.md),
  [TECH_STACK.md](TECH_STACK.md), [ALGORITHMS.md](ALGORITHMS.md),
  [OPTIMIZATION.md](OPTIMIZATION.md), [DECISIONS.md](DECISIONS.md), [PLAN.md](PLAN.md),
  [LESSONS.md](LESSONS.md), [THIRD_PARTY.md](THIRD_PARTY.md).

## 2. Never break these

- **Play-compliant code loading.** targetSdk 36. The only native executables are files
  shipped inside the APK as `lib*.so` (installed into `nativeLibraryDir`). Never execute
  anything from app-writable storage. Downloaded Windows/Linux programs run only as **data**
  through a translator (Box64 / FEXCore). Downloaded scripts run only through an
  interpreter we ship (Node, Python).
- **No developer mode, no root, no ADB** required from the user, at any step. (ADB is fine
  for you while developing and testing.)
- **No VM** (DECISIONS D1).
- **No system-only permissions.** Android apps run hidden only through in-process app
  virtualization (D3).
- **No Accessibility-service input** (D5).
- **The phone must stay usable** during a session. **The PC lock must always exit cleanly**
  with the Exit button, even after a crash.
- **Licenses first.** Add every third-party component to THIRD_PARTY.md, with its license,
  **before** using it. GPL/AGPL needs a DECISIONS.md entry from the manager.
- **No code from the previous project** (AndroidDex-V3 or older). Its lessons are in
  LESSONS.md; all code is written fresh (D2).
- **Never commit binaries** (Wine or Box64 builds, installers, APKs, images, keystores).
  Commit the fetch/build scripts, with pinned versions and SHA-256.

## 3. How to work

1. **Verify before you fix or build.** Open the real file. Check every claim, including
   claims in your prompt, and report any that turn out wrong, with evidence.
2. **Small, complete steps.** Each change compiles, has tests, and is verified before the next.
3. **Measure, never assume.** Speed, RAM, battery, start time and latency come with numbers
   from a real run, before and after. Never change a performance setting without both.
4. **No stubs, no fake data, no placeholder UI that pretends to work.** If something cannot
   be done yet, say so plainly.
5. **Minimal root-cause fixes.** No new framework without a DECISIONS.md entry.
6. **Time-box.** Every task in your prompt has a time box. At the limit: stop, write down
   exactly where it failed, add it to SKIPS.md, move on. Do not drift into side work.
7. **Watch your own runs.** Never report a result from a monitor you stopped watching. A
   process that exists is not a process that works: check that its CPU time advances.
8. **Clean up.** Remove temporary logs and files. Leave the phone as you found it.
9. **Keep docs true.** If you change the design, update ARCHITECTURE.md in the same change.

## 4. Bugs

- A **bug** here means wrong behaviour in code that existed before your current task, or any
  problem outside your task's scope.
- **Do not fix it.** Add it to BUGS.md (what is wrong, how to reproduce, evidence, severity)
  and list it in your report. The manager decides whether and how it gets fixed.
- Problems in the code you are writing for the current task are part of the task: fix them.

## 5. Definition of done

- Every Android project builds and passes:
  `gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`. The PC receiver's own
  build and tests pass too.
- New behaviour has tests.
- Verified on the **real phone** when possible. Label every result `phone`, `emulator` or
  `code-only`. Never call an emulator result phone-verified.
- Performance-relevant work reports fps / latency / RAM / CPU numbers.

## 6. Report format (end of every task)

A table: **item | what you did | files | test | verified on (phone / emulator / code-only) |
numbers**. Then a plain list of: what failed, what was skipped (with its SKIPS.md row), bugs
logged (BUGS.md ids), and anything still unknown. Update STATE.md as your last action.

## 7. Git

**Do not commit or push.** Git and GitHub belong to the manager, who decides what is committed
and when, after reviewing your report.

## 8. Practical facts

Read [LESSONS.md](LESSONS.md): the test phone's quirks and the laptop's tooling traps. They
cost days to learn.
