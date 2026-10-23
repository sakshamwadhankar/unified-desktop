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
- **Status after Phase 2 round 2 (manager, 2026-10-17):** the base holds for Linux apps
  (xeyes renders) and Wine now boots a prefix. But the current pin, **Wine 11.19 (a dev
  release) + Box64 v0.4.4**, needed four workarounds (B-020/21/23/24) and still cannot
  create a desktop window (B-022), so no Windows GUI app can show. Round 3 keeps the base
  and changes only the pin. It tries other Kron4ek amd64 builds (stable 10.x and 9.x first,
  then a wow64 build) and a newer Box64, one at a time, and stops at the first that shows a
  Notepad++ window. Public docs and settings of other Box64+Wine projects may be read as
  facts; their code is never copied (D2, GPL lineage).

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

### D15: Full-speed tools come from Termux/TUR Android builds (Phase 3)
- **Decided:** 2026-10-18, by the manager (Phase 3 passed on the emulator and the phone).
- **Decision:** Node.js (TUR `nodejs-24`) and code-server (TUR `code-server`) are taken from
  the Termux/TUR Android builds, pinned by URL + SHA-256. Every native file (node, its libs,
  the Android-built `.node` modules) ships inside the APK as `lib*.so`
  (`android/vscode/fetch-native.sh`). code-server's JavaScript is data: the app downloads
  the same pinned deb at run time and unpacks only non-ELF files.
- **Why:** building Node.js for Android ourselves costs hours and a lot of RAM; the TUR
  builds are bionic-native, 16 KB-aligned and match each other's Node ABI. Google Play forbids
  downloaded native code, so native code comes only from the APK (D6).
- **Revisit:** if a pinned URL disappears (then re-host or build), or before the first Play
  release (license check of the shipped set, THIRD_PARTY.md).

### D16: PC receiver in Rust, no LGPL USB library
- **Decided:** 2026-10-18, by the manager (D10 left the language to Phase 4).
- **Decision:** the Windows receiver is written in Rust (a new top-level `pc/` folder). USB
  goes through Windows' own WinUSB API (the `windows` crate), not libusb (LGPL-2.1).
  Encryption and pairing use a standard, maintained construction (for example TLS 1.3 with
  pinned self-signed keys, or the Noise protocol through a maintained library); no
  home-made cryptography.
- **Why:** PRD R10 (small, native, low-level Windows APIs for the lock); Rust 1.95 and the
  MSVC build tools are already on the laptop. Avoiding libusb keeps the receiver free of
  LGPL duties.
- **Revisit:** if WinUSB cannot drive Android Open Accessory without a driver hack (ARCHITECTURE Q5).

### D17: Phase 4 closes on Wi-Fi + USB tethering; AOA gets its own round
- **Decided:** 2026-10-19, by the manager (the PLAN gate reads "USB (AOA, or tethering)").
- **Decision:** Phase 4 is closed as PASS on the real phone with Wi-Fi (mDNS discovery, no IP
  typed in) and USB tethering, both with 6-digit pairing and the app's own user path. AOA is
  NOT PROVEN (B-043) and is moved to a separate short "AOA round" (SKIPS.md). That round tries
  UsbDk (Apache-2.0, signed, admin once) with USB debugging off, and needs the owner at the
  laptop. Until AOA is proven, the USB link is tethering (D8).
- **Why:** tethering measured RTT p50 4.06 ms vs Wi-Fi 14.88 ms, at similar throughput
  (8.9-12.1 vs 8.7-11.5 MB/s). That is enough for Phase 5 video (10-20 Mbps). AOA needs one
  UAC click and a physical USB-debugging toggle that nobody could do overnight.
- **Revisit:** after the AOA round. If AOA works, it becomes the default USB link; if it needs
  more than one signed, installer-shippable driver, drop AOA and keep tethering.

### D18: code-server runs loopback-only with `auth: none` (B-106) - **WITHDRAWN 2026-10-22 by the manager, see B-108**
- **Decided:** 2026-10-22, by the manager, during Phase 12.
- **Decision:** the code-server child listens on `127.0.0.1:<random port>` only, with
  `auth: none`; the app's WebView loads `/?folder=...` directly. No password, no `/login`
  POST, no `0.0.0.0`, no `--bind-addr 0.0.0.0`.
- **Why:** the Phase-3 password flow worked, but in the shell the WebView stopped on
  `/login` and code-server logged `Failed login attempt` (B-106). The only client is our own
  WebView in the same app over loopback; the phone's app sandbox is the boundary.
- **Revisit:** if a browser or another app on the phone must reach code-server, or if Android
  changes loopback isolation. Then bind to a Unix socket or restore a per-start token.
- **Withdrawn (manager, 2026-10-22 21:55):** the premise is wrong. Android does NOT isolate loopback
  per app: any app on the phone can connect to `127.0.0.1:<port>`, and code-server has a terminal,
  so `auth: none` lets any installed app run code inside our app. The manager's own 20:50 note
  asked for `--auth none`; that instruction was the mistake. Replace it: a per-start random
  secret that only our WebView gets (password auto-login or cookie), or a Unix socket in our
  private files dir behind an in-app proxy. B-108, FIX NOW. **Replaced by D20 (2026-10-23).**

### D19: Insert a polish phase (13) before Google Play readiness (now 14)
- **Decided:** 2026-10-23 00:40, by the manager, on the owner's request.
- **Decision:** after Phase 12, Phase 13 is "Polish: UI, UX, Chrome, bug sweep". Google Play
  readiness moves to Phase 14.
- **Why:** the owner judged the phone app, desktop and browser not good enough for users
  ("so white and cheap", "illogical", "so greeny", laggy typing, use Chrome). Play readiness
  needs the product name, which the owner picks later (D12). The store listing should show the
  polished app, so polish comes first.
- **Chrome:** first try the phone's own Chrome through the engine (B-003). If it cannot run in a
  desktop window, the built-in browser becomes a full browser on Android System WebView (the
  same Chromium engine as Chrome, 0 extra MB). It must not be called "Chrome" or use Chrome's
  logo unless it really is Google's Chrome app (trademark; Play impersonation policy).

### D20: code-server password auth with a per-start secret and an app-side login
- **Decided:** 2026-10-23, by the agent (B-108 FIX NOW), replacing D18.
- **Decision:** code-server runs `auth: password` on `127.0.0.1:<random free port>`. Every
  start generates 32 random bytes (`SecureRandom`) and writes them only to the app-private
  `files/phase3/config.yaml` and `files/phase3/session.txt`; the child process gets
  `--config <path>` only, never the secret in argv or in its environment. The app itself POSTs
  `/login` over loopback before any WebView load and hands the returned `code-server-session`
  cookie to the WebView's CookieManager. No password in a URL, no log line, no cookie on disk.
- **Why:** D18's premise (loopback is private to the app) is false on Android, and `auth: none`
  therefore let any installed app use our code-server terminal and file APIs. A password is
  the least invasive way to keep the proven WebView path. The cookie login is deterministic
  because it happens before the first page load, which fixes B-106's race as a side effect.
- **Revisit:** if code-server ever offers a per-process Unix-socket-only listener, that is
  stronger (no TCP port at all); or if Android adds per-uid loopback isolation.

### D21: Wine prewarm on by default at desktop start; boots are serialized
- **Decided:** 2026-10-23 03:02, by the agent on the manager's Phase 12 round 2 direction
  ("Notepad++ start, 40 min: make the winners default").
- **Decision:** `Phase12Preload` starts in WINE mode: when the desktop Presentation is shown,
  a background thread runs `Phase2Wine.prewarm` (ensure Xvfb + a live Wine prefix). **Manager
  change 03:12:** it runs only when at least one catalog Windows program is installed on disk
  or a Windows program was opened in the last 7 days (`wineWarmWanted`, unit-tested), and it
  starts **3 s after the desktop is shown**, so a user who never uses Windows apps pays
  nothing and the connection/first frames are never competing with a boot. A live prefix is
  reused without booting, so a reconnect never touches an open app. code-server warm-up stays
  off. Every path that boots Wine (`ensureLivePrefix` for app opens and installers,
  `bootAndWait` for MSI installs) now runs under one monitor, so a user click during the
  warm-up waits for that boot and reuses its result instead of starting a second boot that
  reaps the first.
- **Why:** measured on `2c0f6edc`: first Notepad++ open **28.23 s cold with no warm-up** and
  **11.95 s with a warm prefix**; the boot is ~5.5 s and starts after the desktop is shown,
  so it does not extend connect (0.944 s visible) and is hidden while the user looks at the
  desktop. The 10 s target is still missed by ~2 s (Notepad++'s own paint); this decision
  removes the boot from the click, it does not claim the target.
- **Cost:** one background boot per cold session (Xvfb + wineserver + prefix stay resident);
  measured PSS after the flip in PHASE12_RESULT section 8. No new dependency or framework.
- **Revisit:** if the owner wants instant first-open, pre-launch the app process itself
  (Notepad++), which costs more RAM and is a separate decision.

### D22: One desktop size per session; thermal scaling happens in the encoder path only
- **Decided:** 2026-10-23, by the agent (night run step 7); accepted by the manager with a
  change at 05:33.
- **Decision:** the desktop and VirtualDisplay size are chosen once, before `video-on`, from
  the receiver's `CH_CONTROL display WxH`: height 1080, width from the client aspect clamped
  1280-1920 and even (16:10 -> 1728x1080, 16:9 -> 1920x1080). The shell lays out against that
  runtime size and input maps from it; a mid-session desktop resize is forbidden.
  The Phase 12 thermal step returns in the **encoder path only**: when the governor says hot,
  the GL `drawFrame` scales the fixed desktop into a smaller encoder surface (e.g. 1296x810
  or 864x540); the PC scales the stream back up, and input mapping is unchanged because the
  desktop size did not change. The encoder rebuild should be tied to a keyframe so the
  receiver gets a clean new sequence. This encoder-only step is scheduled after step 9
  (45-minute box) and must be measured on a thermal-3 run (fps, dropped frames, encoder ms)
  before D22 is considered finished.
- **Why:** on 2026-10-23 a governor change to the VirtualDisplay size made the composer crop
  the new 1728x1080 desktop into the top-left of the old 1920x1200 surface: windows at 2.2x
  and the taskbar off-screen. Keeping the desktop fixed preserves window rectangles; letting
  only the encoder surface shrink keeps most of the Phase 12 thermal saving. A reconnect is
  a new session and may pick a new size.
- **Status:** the fixed desktop size is implemented and verified. The encoder-only thermal
  step is **not implemented yet** (2026-10-23); the current governor lowers fps (30/15) and
  bitrate only, at the same surface size.

### D12: No product name yet
- **Decided:** 2026-10-13, by the owner. The owner chooses after checking domains. Never
  invent a name; `unified-desktop` is a codename.
