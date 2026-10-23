# CONTEXT: the whole project in one file

> Written 2026-10-23 19:23 IST by the coding agent (pi), at the owner's request, from:
> the manager's complete Claude Code transcript
> (`C:\Users\Asus\.claude\projects\C--Users-Asus-Documents-Github-unified-desktop\2c45113a-bd32-457a-949f-39c2521d2112.jsonl`,
> 30,635 records, 2026-10-13 → 2026-10-23), the manager's memory folder, and the repository
> documents themselves. Every number below was read from the repo or the transcript; where a
> claim is the manager's or an agent's, it is labelled. If this file and the live docs disagree,
> **the live docs win** (STATE.md, BUGS.md, SKIPS.md, DECISIONS.md, PLAN.md, TODO.md).
>
> Companion file: **managercontext.md** — how the manager works, and how to take that role over.

---

## 1. What this project is

`unified-desktop` (working codename, **no product name yet** — never invent one) makes an
Android phone the computer and any PC the screen:

- The phone runs Windows apps (Wine + Box64), x86-64 Linux apps (Box64 + Xvfb), Android apps
  (in-process app virtualization, BlackBox family) and full-speed tools (Node.js +
  code-server / VS Code shipped as `lib*.so`).
- The PC runs a small native Rust receiver (`udreceiver`) that discovers, pairs, decodes and
  presents the phone's desktop full screen, captures mouse/keyboard, and locks the PC until
  one **Exit** button is pressed.
- The phone stays fully usable during a session (calls, WhatsApp, camera).

### Non-negotiable promises (PRD §3)

| # | Promise |
|---|---|
| R1 | Windows `.exe` and Linux apps run in our desktop; Android apps too |
| R2 | The phone does all the work; the PC is only screen + input |
| R3 | No VM, no "booting Linux"; apps start directly |
| R4 | Android apps run hidden (on the PC desktop, not the phone screen) |
| R5 | The phone stays fully usable during a session |
| R6 | PC lock, full screen, one Exit button, no password, instant restore |
| R7 | USB and Wi-Fi both; USB preferred when a cable is in |
| R8 | No developer mode, no root, no ADB for the user, at any step |
| R9 | Installable from Google Play (targetSdk 36; only APK `lib*.so` executes natively) |
| R10 | Light: runs on an old dual-core PC and a Snapdragon 695 phone |
| R11 | App Center installs by name and picks the best version of each app |
| R12 | Reconnect continues where the user stopped |

### Design targets (measured, never assumed)

| Measure | Target |
|---|---|
| Connect to usable desktop | ≤ 5 s |
| Typing latency, USB | ≤ 80 ms |
| Typing latency, 5 GHz Wi-Fi | ≤ 120 ms |
| Moving-window frame rate | ≥ 30 fps |
| Notepad++ start | ≤ 10 s |
| VS Code opens a small project | ≤ 15 s |
| Session on charger without being killed | 2 hours |
| Old PC (dual-core, 4 GB RAM, Windows 10) | 1080p full screen |

---

## 2. How it works (short)

```
PC: udreceiver (Rust)                     LINK: TLS 1.3, pinned keys, 6-digit pairing
  Media Foundation H.264 decode             input  > control > video  (64 KiB frames)
  D3D11 present, full screen
  mouse/keyboard capture  <===============> Phone: our Kotlin app (targetSdk 36)
  PC lock + Exit                            desktop shell (own window manager)
                                            Android apps (BlackBox, private displays)
                                            Windows/Linux (Wine + Box64 + Xvfb)
                                            VS Code (Node + code-server, lib*.so)
                                            GL compositor -> MediaCodec H.264 encoder
```

- **Discovery:** mDNS `_udlink._tcp`; USB via tethering (AOA not proven — SKIPS).
- **Pairing:** 6-digit code derived from both certificates; user taps Yes on the phone; keys
  pinned afterwards. A different PC cannot connect silently.
- **Desktop size:** fixed per session (height 1080, width from the client aspect, clamped
  1280–1920 and even; e.g. 1728x1080 for 16:10), chosen before `video-on` (DECISIONS D22).
- **Thermal scaling:** encoder surface only (e.g. 1296x810), desktop size unchanged, PC
  scales back up (D22).
- **Full wire format:** `docs/PHASE4_PROTOCOL.md`.

---

## 3. Authoritative documents (read these, in this order)

| File | Holds |
|---|---|
| `STATE.md` | where the work stands **now** (replace stale lines, never append history) |
| `TODO.md` | the coding agent's live to-do line: `Done X of Y`, `PROGRESS`, `PHONE ...`, `OWNER ...` |
| `PLAN.md` | the 14 phases and their gates |
| `DECISIONS.md` | settled choices D1–D22; do not re-argue without new evidence |
| `BUGS.md` | every bug, evidence, severity, manager decision (FIX NOW / FIX LATER / WON'T FIX) |
| `SKIPS.md` | every deferred item, why, what is needed, done-when |
| `LESSONS.md` | hard-won practical facts (phone quirks, tooling traps) |
| `AGENTS.md` | rules for the coding agent |
| `CLAUDE.md` | rules for the manager |
| `ARCHITECTURE.md`, `ALGORITHMS.md`, `OPTIMIZATION.md`, `TECH_STACK.md`, `THIRD_PARTY.md` | design, methods, performance, stack, licences |
| `docs/PHASEn_RESULT.md` | each phase's measured record |
| `docs/UX_AUDIT.md` | the owner's UI/UX complaints and the after-table |
| `docs/TEST_REPORT.md` | the independent tester's bug report (partial) |

---

## 4. Phase status (as of 2026-10-23 19:23 IST)

| # | Phase | Status | Evidence / notes |
|---|---|---|---|
| 1 | Hidden Android app | **PASS** | App virtualization works (Google Tasks runs inside our app on our display); input works; Play-policy check done. Took 6+ rounds. |
| 2 | Windows + Linux apps | **PASS** | Notepad++ window + an x86-64 Linux GUI app; Wine 11.0 + Box64 + Xvfb; measured. |
| 3 | Full-speed tools | **PASS** (emulator + phone) | VS Code in a WebView, terminal works, APK size measured. |
| 4 | Connection | **PASS** | Wi-Fi + USB tethering, 6-digit pairing, no developer mode. AOA not proven (separate round, SKIPS). |
| 5 | Video + input | **PASS** (phone) | hardware H.264, input, latency measured; took over the night when free models ran out. |
| 6 | Desktop shell | **PASS** (phone) | taskbar, start menu, windows, Files, browser, Settings. |
| 7 | All apps in one desktop | **PASS** (phone) | Android + Windows + Linux + VS Code windows; B-073/B-077/B-078 fixed. |
| 8 | App Center | **PASS** (phone) | 14 catalog apps, picker 11/11, 10/10 fresh content. |
| 9 | PC lock + Exit | **PASS** (Windows 11) | 8/8 keys blocked, Exit restores, crash recovery; Windows 10 skipped (no hardware). |
| 10 | Phone free, keep-alive, continuity | **PASS** (phone) | 2-hour charger soak, WhatsApp, real call, reconnects ≤ 3.7 s. |
| 11 | Audio, clipboard, files, webcam/mic | **PASS with known limits** | audio, clipboard both ways, files both ways, PC webcam/mic. B-103 (paste into Wine) still open. |
| 12 | Speed and lightness | **NOT complete (gate FAILED)** | Rec. 2026-10-24 02:45: the chat verdict "done apart from two misses" is wrong. `PLAN.md` §Phase 12 makes the PRD §4 targets the gate and `docs/PHASE12_RESULT.md`'s own header still says **"PHASE 12 GATE still FAILS"**: Test-shell typing 87.0 ms (≤ 80), Notepad++ 12.15 s warm (≤ 10), 5 GHz Wi-Fi typing unmeasured. |
| 13 | Polish: UI, UX, Chrome, bug sweep | **~90 % of the code, unproven live, uncommitted** | Rec. 2026-10-24: gates green on the tree (214/0/3, lint 0, PC 100/0) but B-115/B-116 and T-004…T-007 have never been executed; arm64 `libbox64.so` is still 0x1000. |
| 14 | Google Play readiness | **Not started** | Needs product name (owner/D12) + 16 KB page support (tester T-003). |

### Phase 12 numbers (final, phone `2c0f6edc`)

PASS: connect **0.94 s** (target 5); VS Code **13.48 s** cold (target 15); window drag
**42 fps** (target 30); old-PC 2-core proxy **~36 fps**, 0 dropped; thermal 1080p → 540p keeps
windows; receiver **2.57 → 2.22 MB**; per-ABI APK **93 → 55 MB**; sharp at 125 %; audio
1000/1000 packets; clipboard/files both ways; 2-hour soak.

MISSES (documented, not hidden — two code misses plus one unmeasured skip):
1. **USB typing p50 90.8 ms** (target 80; p90 115.3). The `drawn>encoded` stage (46–67 ms)
   dominates; transport is not the lever.
2. **Notepad++ first open 11.95 s warm / 15.88 s cold** (target 10). Wine prewarm is ON by
   default (D21), which removed the ~5.5 s boot, but Notepad++'s own paint under Box64/Wine
   still takes ~6 s.
3. **Wi-Fi typing not measured** — the phone's Wi-Fi was off (SKIPS row).

> ✅ Reconciled by the recovery manager on 2026-10-24 02:45 (`docs/RECOVERY_2026-10-24.md` §4):
> the "Phase 12 is closed" wording above came from a chat verdict, not from the gate. The phase's
> own record (`docs/PHASE12_RESULT.md`, header: "PHASE 12 GATE still FAILS") and `PLAN.md`
> outrank a chat line, so Phase 12 is **not** closed. `STATE.md` now says the same.

### Phase 13 — what is done (committed)

Design system + desktop shell; new wallpaper (no green); taskbar on top; start menu with
search/pinned/all apps; window controls, snap, double-click maximise, Alt+F4, Win/Esc,
right-click; App Center and Settings redesign; neutral full WebView browser with tabs/
downloads/shortcuts (Chrome abandoned: B-003/B-111); new Material 3 phone home screen;
PC receiver GUI; hot-phone encoder scaling (D22); B-108 security fix (code-server password).

Commits: `6e33bb6`, `06130f3`, `41922ec`, `c1bf4cf` (Phase 13 checkpoints); `aad9bbf` (tester
report). `docs/PHASE13_RESULT.md` has the before/after tables.

---

## 5. Live state when the manager stopped (this is the handover snapshot)

**What happened:** the manager's Claude session hit its usage limit at 13:39 IST, resumed at
14:13, again at 14:47 for the tester, then **login expired at 15:29 IST** and the session
ended with a `502 Upstream connection failed` at 16:27. The owner then typed "ls" and "hi";
there was no manager left to answer. The owner no longer has a Claude subscription.

**Coding agent (OpenCode, free model):** was working in a **code-only lane** (no emulator, no
APK installs, no receiver, while the tester owned those). Its last `TODO.md` line:
`PROGRESS 12:32 CODE-ONLY LANE: B-115/B-116 (tester T-001/T-002) written, B-109/B-110/B-112/
B-113/D12/D14 written, now compiling + unit tests.` Then the free limit blocked it (429) and
the watcher's retries could not get through.

**Uncommitted working tree (17 modified + 1 new test, ~837 insertions):**

```
 M BUGS.md  M TODO.md
 M android/.../AppCenter.kt Phase2X11.kt Phase4LinkService.kt Phase4Protocol.kt
              Phase5Session.kt Phase6Dispatch.kt Phase6Shell.kt Phase8Open.kt
 M android/.../test/Phase4ProtocolTest.kt Phase8OpenTest.kt
 M pc/udreceiver/src/gui.rs lock.rs phase5.rs session.rs view.rs
?? android/app/src/test/java/dev/unifieddesktop/app/Phase6DispatchTest.kt
?? undefined/            (stray empty-ish folder, not project code)
```

What the diff is for:
- **B-115** — pairing gate hole: an unapproved PC could get `video-on`/`display` before Yes.
  Fix: `gateOpen` defaults closed; `mayProcessControl` lets only empty control frames through
  in PAIRING/UNPAIRED.
- **B-116** — one-sided trust: a pinned PC was trusted without the phone confirming; locked
  and reconnected forever. Fix: PC says `trusted`/pairs, phone answers/refuses with a reason,
  reconnect backoff, safer GUI state.
- **B-109** — App Center claimed it opened an app that was not installed.
- **B-110** — WebView (VS Code/Browser) PC click mapping (PC/2 symptom).
- **B-112** — receiver GUI lock child after a forced kill.
- **B-113** — duplicate `p6:probe` harness branch.
- **D12/D14** — desktop toasts and the welcome card (were SKIP).
- Likely compile/test state: **not verified**; the agent was at "compiling + unit tests".

**Tester (separate Claude session):** ran 11:37–12:21, stopped by Claude's usage limit. Its
partial report is committed as `docs/TEST_REPORT.md` (`aad9bbf`). It found real blockers:
T-001 = B-115, T-002 = B-116, T-003 = 16 KB page alignment (Play blocker), T-004 = Back on
Settings closes the app, T-005 = Files Delete freezes the desktop (ANR), T-006 = Files rename/
folder selection broken, T-007 = no-lock view steals focus and injects Alt every 2 s.

**Gates at the last verified point:** Android `:app:testDebugUnitTest :app:lintDebug
:app:assembleDebug` PASSED 2026-10-23 11:24 — **206 tests / 0 failures / 3 skipped**, lint
0 errors / 137 warnings, assemble OK. PC: see `p13/pc-test-final.log` / `STATE.md`.

---

## 6. What is left — the concrete path to finish Phase 13

Do these **in order**; each needs proof and a BUGS/STATE update.

1. **Make the uncommitted tree compile and pass tests.** Run the three Gradle tasks and
   `cargo test --release` + `cargo clippy -- -D warnings`. Fix only what is broken by this
   work. Commit by name (manager protocol).
2. **Verify B-115/B-116 live** (emulator or phone): (a) unpaired PC gets nothing but the
   pairing exchange; tapping No leaves the desktop down; (b) after "Forget", the PC shows the
   code, never locks before Yes, and backs off instead of reconnecting every 250 ms.
3. **Fix the tester blockers** (BUGS.md already has manager decisions):
   - **T-003 / 16 KB pages** — rebuild `libbox64.so` and `libdobby.so` with 16 KB LOAD
     alignment (NDK r28+ linker or `-Wl,-z,max-page-size=16384`); verify with an ELF check on
     both ABIs. This is a Play requirement.
   - **T-005 Files Delete ANR** — use an Activity/application context with a valid window
     token (or a dialog hosted on the desktop window), and never swallow the exception.
   - **T-006 Files rename/select** — prompt for a name; clicking a folder should select, not
     always navigate; folders must be renameable/deletable.
   - **T-007 focus steal** — stop the 2 s `SetForegroundWindow` + synthetic Alt injection in
     `--no-lock`; only reclaim focus on user action.
   - **T-004 Back on Settings** — handle back by returning to home.
4. **Finish the queue:** B-109 (check installed record/exe before saying "opened"), B-110
   (client→desktop mapping at the governor size; unit test), B-112 (clean lock/journal after
   forced kill), B-113 (merge probe branches), B-103 (paste into Wine — still open).
5. **Step 15 final phone round** (when the phone is back): typing in real apps n ≥ 25,
   full first-time walk-through, thermal-3 real run, cleanup.
6. **Docs + gates + commit:** PHASE13_RESULT, UX_AUDIT status column, BUGS, SKIPS, STATE last.
7. **Then Phase 14 (Play readiness):** product name from the owner (D12), permissions
   declarations, data-safety form, signed release, internal-testing upload by the owner.

Known open bugs at handover (see BUGS.md for evidence):
`B-103` (Wine paste), `B-105` (fixed in tree), `B-107` (flaky lock test), `B-109`, `B-110`,
`B-111` (Chrome, won't fix — browser shipped), `B-112`, `B-113`, `B-115` (security blocker),
`B-116`. Tester T-003/T-004/T-005/T-006/T-007.

---

## 7. Decisions the reader must not re-open casually

| ID | Decision |
|---|---|
| D1 | No VM |
| D2 | Fresh code; never copy AndroidDex-V3 |
| D3 | Android apps via in-process app virtualization (BlackBox family) |
| D4 | Windows/Linux via Box64 + Wine |
| D5 | No Accessibility-service input |
| D6 | The most-used tools ship natively as APK `lib*.so` |
| D7 | App Center picks: built-in → Android → web → Linux → Windows |
| D8 | USB and Wi-Fi; USB preferred (AOA still unproven; tethering is the USB path) |
| D9 | Phone stays usable; only the PC locks; one Exit button |
| D10 | Native Windows receiver (Rust chosen in D16) |
| D11 | One virtual display per Android window (Phase 1/7) |
| D12 | No product name yet |
| D13 | Box64 + data-only x86-64 runtime + Xvfb |
| D14 | GPL programs fetched by the phone from upstream, never re-hosted |
| D15 | Full-speed tools from Termux/TUR Android builds, pinned |
| D16 | Rust receiver, WinUSB (no libusb/LGPL) |
| D17 | Phase 4 closes on Wi-Fi + tethering; AOA gets its own round |
| D18 | code-server `auth: none` — **WITHDRAWN**, replaced by D20 |
| D19 | Phase 13 polish before Play; Chrome attempt, WebView browser fallback |
| D20 | code-server password auth with per-start secret + app-side login |
| D21 | Wine prewarm on by default (only if a Windows app exists), serialized boots |
| D22 | Fixed desktop size per session; thermal scaling in the encoder path only |

---

## 8. Environment facts (verified in the transcript)

| Thing | Value |
|---|---|
| Owner laptop | Windows 11 Home, 15.6 GB RAM, Git Bash + PowerShell + WSL |
| Android SDK | `C:\Users\Asus\AppData\Local\Android\Sdk` |
| adb | `C:\Users\Asus\AppData\Local\Android\Sdk\platform-tools\adb.exe` |
| Test phone | OnePlus CPH2619, serial `2c0f6edc`, Android 16 / SDK 36, ColorOS, 4 KB pages |
| Emulator | AVD `Pixel_8_Pro`, x86_64, Android 17, 16 KB pages, 1344x2992 @480 dpi |
| Emulator policy | needs > 3 GB free laptop RAM; close Chrome/ChatGPT/Wispr when needed |
| Build (phone) | `cd android && ANDROID_HOME=/c/Users/Asus/AppData/Local/Android/Sdk ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` |
| Build (PC) | `cd pc && cargo build --release && cargo test --release` |
| Coding agent | OpenCode on Windows, free models (`deepseek-v4.1-flash-free`, `glm-5.3-flash-free`); CLI `C:/Users/Asus/AppData/Roaming/npm/node_modules/@opencode/cli/bin/opencode.exe`; DB `C:/Users/Asus/.local/share/opencode/opencode.db` (read-only) |
| Also usable | **pi** (the current harness) with the same free OpenCode models; same AGENTS.md rules, same no-commit rule. Its sessions live in `C:/Users/Asus/.pi/agent/sessions/--C--Users-Asus-Documents-Github-unified-desktop--/` |
| Manager alerts | ntfy.sh, short status only; the topic string is **kept out of the repo** — see `managercontext.md` §12 on the owner's disk, or ask the owner |
| Git remote | `https://github.com/sakshamwadhankar/unified-desktop` (private) |
| Commit identity | `sakshamwadhankar <sakshamwadhankar21@gmail.com>`, **no AI attribution** |
| Evidence root | `C:\Users\Asus\AppData\Local\Temp\opencode\p12`, `...\p13`, tester `...\tester` |

---

## 9. Rules that outrank convenience

- No developer mode, root or ADB for the user.
- Play-compliant code loading: only APK `lib*.so` executes; downloads are data.
- No VM, no system-only permissions, no Accessibility input.
- The phone stays usable; the PC lock always exits with one button.
- Licences first: add to THIRD_PARTY.md before use; GPL/AGPL needs a manager decision.
- Never commit binaries, installers, APKs, images, keystores; commit fetch/build scripts
  with pinned versions and SHA-256.
- Never copy code from the previous project (AndroidDex-V3).
- The coding agent never commits or pushes. Git belongs to the manager.
- No product name yet.

---

## 10. Where the manager came from (one-paragraph history)

The owner started Claude Code as **project manager** on 2026-10-13 with one instruction:
read every document, then write one prompt per phase for a separate coding agent (first
OmniRush/GPT, then OpenCode/DeepSeek/GLM). The manager wrote the prompts, verified every
report against real files and the phone, logged bugs and decisions, fixed the docs, and did
all git work. Over ten days it ran ~13 phases, including several overnight runs; when the free
models stopped, it either waited for the owner's IP reset or did the work itself. It kept a
`.claude` memory folder of owner rules, a 30-minute watcher on the OpenCode session database
with automatic "continue" after quota stops, an independent ntfy watchdog for the owner's
phone, and a private PIN-unlock duty for the test phone. On 2026-10-23 it also started an
independent **tester** Claude session, whose partial bug report drove the last fixes. The
session ended on a login expiry/502; this file and `managercontext.md` are the handover.
