# DECISIONS

Settled choices. Do not re-argue them without new evidence. Each entry: what was decided,
why, and what would make us revisit it. **PROVISIONAL** entries become settled when their
phase's gate passes.

### D1: No virtual machine
- **Decided:** 2026-10-13, by the owner.
- **Why:** PRD R3. The previous project ran Debian in QEMU inside an Android app: about 4.25x
  slower than native (16.5x on memory-heavy work), 2-3 minutes to cold boot, and it felt like
  "booting Linux". Google's fast VM (AVF) is limited to system and preinstalled apps.
- **Revisit:** only if the owner changes R3.

### D2: Start from scratch
- **Decided:** 2026-10-13, by the owner.
- **Decision:** no code is copied from the previous project (AndroidDex-V3). Its lessons are
  carried as text in LESSONS.md.

### D3: Android apps run hidden through in-process app virtualization (PROVISIONAL)
- **Decided:** 2026-10-13; settled if Phase 1 passes.
- **Why:** PRD R4/R5. Android 10+ lets an app launch only its own activities on a virtual
  display it created; launching other apps there needs a system-only permission, and the
  app-streaming role is limited to preinstalled apps. Running the app inside our process
  makes its activities ours. Precedent: app cloners such as Parallel Space on Google Play.
- **Known limits:** each copy has its own login; apps that refuse virtualized environments
  (typically banking) do not run.
- **Revisit:** if Phase 1 fails or Google Play rejects it.

### D4: Windows and x86-64 Linux apps through Box64 + Wine (PROVISIONAL)
- **Decided:** 2026-10-13; settled if Phase 2 passes.
- **Why:** PRD R1/R3/R9. Downloaded programs are read as data and translated in memory,
  which Android's exec rule allows. Box64 measured about 67% of native speed at the median,
  about 4x faster than QEMU (COSCUP 2026, Raspberry Pi 5). Precedent: GameHub on Google Play.
- **Revisit:** if Phase 2 fails, or speed on the test phone is unusable.

### D5: No Accessibility-service input
- **Decided:** 2026-10-13.
- **Why:** Google Play restricts Accessibility use, and hidden apps are our own windows (D3),
  so input reaches them directly.

### D6: Two speeds: the most-used tools ship natively inside the APK (PROVISIONAL)
- **Decided:** 2026-10-13; settled if Phase 3 passes.
- **Decision:** a small set of key tools (Node.js + code-server for VS Code, a shell, Git,
  Python) ships inside the APK as `lib*.so` and runs at full native speed; everything else
  goes through the translator.
- **Why:** files shipped in the APK come from Google Play and may run; it makes the most-used
  apps fast. Precedent: Termux is on Google Play again in 2026.
- **Revisit:** if APK size or Google Play policy makes it impossible.

### D7: App Center picks the best version of each app
- **Decided:** 2026-10-13.
- **Decision:** full-speed built-in, then Android (Google Play), then web, then Linux through
  Box64, then Windows through Wine. Ratings come from our own tests on the test phone.
- **Why:** PRD R11; users get the fastest working version without knowing how.

### D8: Both USB and Wi-Fi; USB preferred (PROVISIONAL on the USB method)
- **Decided:** 2026-10-13, by the owner (both links).
- **Decision:** USB through Android Open Accessory (no developer mode), USB tethering as
  fallback; Wi-Fi on the same network. USB is used automatically when a cable is in.
- **Why:** PRD R7. A cable has less and steadier delay and charges the phone; Wi-Fi is more
  convenient.
- **Revisit:** the USB method after Phase 4's measurements.

### D9: The phone stays usable; only the PC is locked; Exit button only
- **Decided:** 2026-10-13, by the owner.
- **Why:** PRD R5/R6. No password on exit; the Exit button must always work, even if the
  phone is gone or the receiver crashed earlier.

### D10: PC receiver is a native Windows app for Windows 10/11
- **Decided:** 2026-10-13. Language chosen in Phase 4 (Rust recommended) with its own entry.
- **Why:** PRD R10; lock features need low-level Windows APIs; must be small for old PCs.

### D11: One virtual display per Android window, composited by us (PROVISIONAL)
- **Decided:** 2026-10-13; checked in Phases 1 and 7.
- **Why:** it does not depend on Android's freeform-window support, and each app draws only
  its own window's pixels.

### D13: Phase-2 base: upstream Box64 + data-only x86-64 runtime + Xvfb as the X server (PROVISIONAL; settled if Phase 2 passes)
- **Decided:** 2026-10-16, by the coding agent under the Phase-2 prompt (T1). Should be
  numbered D9 by that prompt, but D9 exists (the phone stays usable); recorded here as the
  next free entry.
- **Decision:**
  - **Translator:** upstream **Box64** built from pinned release source with NDK 26
    (`-DANDROID=ON -DARM_DYNAREC=ON`), shipped in the APK as `lib*.so`
    (`libbox64.so`) and executed only from `nativeLibraryDir`. Not a Winlator fork
    build.
  - **x86-64 runtime (Wine, libs, Xvfb, downloaded apps): DATA.** Pinned + SHA-256,
    fetched over the network into app storage on first run. Nothing there is ever
    executed by the Android exec path; it is read and translated by `libbox64.so`.
    Specifically: **Kron4ek Wine amd64 build** (x86-64, LGPL-2.1), **Xvfb** + x86-64
    libraries (Debian/Ubuntu, MIT/X11-family and per-package licences), and user apps
    such as **Notepad++ portable** (GPL-2.0, see D14 note).
  - **X11 server:** **Xvfb run through Box64** (TCP-localhost transport,
    `-nolisten unix`). Not a bionic-built in-process server.
- **Why:** Play-compliance (targetSdk 36; only `lib*.so` executes; everything else is
  data). Xvfb+Box64+Wine is the classic headless combination, battle-tested together,
  with battle-tested licences. Version comparison done:
  - **Winlator Bionic (Pipetto lineage)/Ludashi: rejected.** Apps target SDK 28 and exec
    from app storage (Play-noncompliant model for us); repos carry an MIT badge while
    the inherited Winlator project is GPL-3.0, which would need a manager GPL decision;
    the original Winlator Bionic repo under FexEmu has also disappeared (404, checked
    2026-10-16).
  - **Bannerlator: rejected.** GPL-3.0 (needs manager decision), personal-build
    disclaimer, targets SDK 28 exec-from-storage, and needs the developer-options
    child-process restriction toggle for some paths.
  - **Upstream Box64: adopted** for the translator because it is MIT, very actively
    maintained (5.7k stars, CMake `ANDROID` and `TERMUX` targets exist), and has an
    upstream `androidshmem` wrapper for Wine's SysV-shm use on Android.
  - **X server:** the alternatives all fail a hard rule or a license rule: glibc Xvfb
    cannot use its default filesystem Unix socket on Android (apps cannot write `/tmp`),
    so Xvfb runs with `-nolisten unix` and everything talks over TCP `127.0.0.1:6000`
    inside the phone; the Winlator-family X11 server in Java is inherited GPL-3.0 code
    (its MIT badge on forks is unsupported by the lineage), which would need a manager
    GPL decision — it is NOT used; writing our own X protocol server is a phase-sized
    extra bet for Phase 2's Xvfb-satisfied need.
- **Revisit:** if Box64+Xvfb+Kron4ek-Wine is unworkable on the test phone (Phase 2 gate
  fails on core mechanics, not app-specific quirks).

### D14: Open-source (incl. GPL) programs are fetched by the user's phone from upstream, never shipped by us (PROVISIONAL)
- **Decided:** 2026-10-17, by the manager; the owner may overrule.
- **Decision:** Wine, Debian packages, Notepad++ and similar programs are downloaded on the
  phone from their official upstream URLs (pinned + SHA-256) as data. We do not put them in
  the APK and do not re-host them. The app shows each component's name, license and source
  link (Phase 8 App Center / About screen).
- **Why:** GPL duties fall on whoever distributes the program; here the user's phone fetches
  it from upstream. If we ever bundle or re-host GPL code, we must ship its license and
  offer the matching source: decide that then, with a new entry.
- **Revisit:** before the first Google Play release (legal check), or if we start re-hosting.

### D12: No product name yet
- **Decided:** 2026-10-13, by the owner. The owner chooses after checking domains. Never
  invent a name; `unified-desktop` is a codename.
