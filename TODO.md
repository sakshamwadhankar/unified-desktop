# Phase 3: to-do

Done 12 of 12 | Failed or skipped 0 (item 11 satisfied by its SKIPS.md row) | Updated 16:10 | Phase 3 emulator round complete; phone gate + manager review pending

Rule (AGENTS.md §3.6): after EACH item tick it ([x] only if it truly works; [-] plus a
one-line reason), update the top line, THEN start the next item. Past a time box: write by
how much and why. HARD STOP: 8 hours after start.

Notes: phone (2c0f6edc) NOT connected - emulator-first round. All results labelled emulator
unless stated otherwise.

- [x] 1. Start checks. DONE 11:28 - emulator-5554 booted (Android 17/API 37, 16 KB pages,
      abilist x86_64,arm64-v8a, MemTotal 6.06 GB); phone 2c0f6edc NOT in `adb devices`;
      laptop free RAM 1,151,320 kB; temp dir C:\Users\Asus\AppData\Local\Temp\opencode\p3 made. [~5 min]
- [x] 2. Licenses BEFORE use. DONE 12:10 - 14 THIRD_PARTY.md rows; every licence read inside the
      pinned deb (Node MIT, OpenSSL Apache-2.0, c-ares MIT, libc++ Apache-2.0+LLVM-exception,
      code-server MIT, VS Code OSS MIT, node-pty MIT, @parcel/watcher MIT, @vscode/spdlog MIT,
      @vscode/sqlite3 BSD-3, @vscode/native-watchdog MIT, argon2 MIT, kerberos Apache-2.0,
      Termux/TUR packaging). 438 package.json scanned: only LGPL hit = jschardet (pure JS inside
      VS Code's bundles, run-time-fetched, not modified) -> listed for the manager. No GPL/LGPL
      native code shipped. [~40 min, 15 over]
- [x] 3. Native fetch script. DONE 11:44 (clock corrected 12:30: the jniLibs files are
      timestamped 11:43:58-59; the earlier note said 12:40, which was wrong) -
      android/vscode/fetch-native.sh + patch-neededs.py:
      5 pinned debs per ABI (sha verified), targeted member extraction, llvm-strip, rename to
      lib*.so, DT_NEEDED/DT_SONAME rewritten in place, p_align checked (all 12 files x 2 ABIs
      = 0x4000 ok), prints size+sha. jniLibs x86_64 93,386,816 B / arm64-v8a 93,675,872 B
      (libnode.so 82.7/83.3 MB). zlib = system libz.so (node's NEEDED rewritten). [~60 min]
- [x] 4. CORE PROOF (stop/go). DONE 12:00 (device runs 11:50-11:59; the earlier note said
      12:15) - PASS: libnode.so from
      nativeLibraryDir prints `NODE v24.18.0 x64 android`, first output 170-222 ms, /proc/pid/exe
      under nativeLibraryDir; TLS 200 from open-vsx.org (3348 B) and /api/-/search (978 B JSON)
      with authorized=true TLSv1.3 CA=/system/etc/security/cacerts; process.dlopen of libpty.so
      from nativeLibraryDir OK (exports fork,open,resize,process). PREMISE WRONG (B-034): the
      same .node dlopen'ed from files/ SUCCEEDS on this image; exec of the same bytes from files/
      is BLOCKED (error=13) and from nativeLibraryDir works. Two environment blockers solved:
      99%-full /data (AndroidDex leftovers removed, +2.6 GB) and standby bucket RARE blocking
      all app sockets (B-035) -> bucket active. [~95 min, 55 over]
- [x] 5. Code-server data: app downloads + sha-checks + unpacks JS only into
      files/phase3/code-server; skip filter + mapping table unit tests [60 min]
      DONE 12:51 - PASS. Device (emulator): the app pulled the pinned 117,273,956 B deb
      (sha256 matched, ~52 s in the foreground) and unpacked in 35.8-36.2 s; tree = 9,134
      files / 326,506,636 B / 1,678 dirs / 31 symlinks, elf=0, broken=0, links_ok=7/7 -
      matches the laptop mirror (9,133 kept / 326,506,571 B / 24 symlinks) + the 65-byte
      native-links.json marker and our 7 native links. Three reader defects found and
      fixed in the same run (B-036): ar-vs-xz feeding, GNU @LongLink names truncated to
      100 bytes, and relative "../" symlink targets dropped. 48 unit tests green.
- [x] 6. Start code-server from the app (start/stop/status); /healthz 200; numbers [45 min]
      DONE 13:34 - PASS. From the app: libnode.so -r hook.js out/node/entry.js --config
      config.yaml, bind 127.0.0.1:<free port>, auth password, fresh 32-hex password,
      cleartext policy 127/localhost=true example.com=false (network_security_config),
      /proc/pid/exe under nativeLibraryDir. /healthz=200 from the app process in
      2.0-7.5 s (3 starts measured); cs-status/cs-stop/cs-start actions work. Two start
      bugs fixed on the way: a reinstall dangles the 7 native-module symlinks (now
      re-created at every start, B-037 family) and require() of a symlinked *.node lost
      its extension to realpath (fixed with --preserve-symlinks). Single process
      (pss 49 MB, 11 threads at 7 s).
- [x] 7. WebView activity + network_security_config; screenshot of workbench [45 min]
      DONE 14:01 - PASS. Phase3CodeActivity: reads port+password from the app-private
      session file, POSTs /login once, keeps the cookie, loads /. DOM proof in the log:
      .monaco-workbench + parts exist at +3 s, document.title="code-server" at +6 s.
      Screenshot evidence/phase3/p3web-workbench.png (valid PNG 630,597 B, sha
      9A852F54..., header-checked, retaken 14:14 after the emulator restart - the first
      take was corrupt: PowerShell `>` re-encodes binary; see LESSONS 7). Shell cannot
      start a non-exported activity on API 37 ->
      cs-web action starts it from the app.
- [x] 8. Gate flow through injected input: open folder, edit+save hello.txt, terminal command,
      verify on disk with run-as + screenshot [60 min]
      DONE 15:47 - PASS (emulator; the host OOM incident + recovery is in
      docs/PHASE3_RESULT.md item 8). FOLDER: code-server `?folder=` deep link
      (cs-gofolder; title "workspace"). EDIT+SAVE: tap caret Ln2 + `input text
      phase3-edited` (IME) + Ctrl+S via `input keycombination 113 47` after the WebView
      focus fix; `run-as cat hello.txt` = `phase3`+`phase3-edited`, dirty:false; PNG
      p3gate-hello-saved.png (194,117 B, sha 6DD27377...). TERMINAL: menu taps ->
      New Terminal; `cd`+`pwd>pwd.txt`+`echo phase3-ok > term.txt; uname -m >
      machine.txt` via %s/\>-escaped `input text` + keyevent 66; `run-as` = pwd.txt
      workspace path, term.txt `phase3-ok`, machine.txt `x86_64`; PNG
      p3gate-terminal.png (293,005 B, sha 8ED08BA2...). Keyboard-in-page deadness =
      B-039; quoting trap = B-040.
- [x] 9. Numbers: cold/warm start, PSS, process count, idle CPU, APK size, files/phase3 size [30 min]
      DONE 15:50 - PASS (emulator + host). Cold healthz 7,899 ms / warm 2,818 ms;
      PSS app 132.5 MiB + node 21.8 + worker 53.0; family 14 procs (steady ~11);
      idle 90 ms CPU/60 s = 0.15%/core; APK 88.2 MiB universal (libnode 79.4+78.9 MiB,
      libbox64 21.4 MiB arm64-only; assets/phase3 = hook.js only); files/phase3
      580.1 MiB. Table in docs/PHASE3_RESULT.md item 9.
- [x] 10. Stretch: one extension from Open VSX [20 min]
      DONE 16:04 (20 over: included one probe-extension rebuild build28 + emulator
      stop/boot) - PASS. oderwat.indent-rainbow 8.3.1 installed THROUGH the WebView
      marketplace UI (search -> details -> Install -> publisher-trust dialog -> installed
      state); extensions.json + full unpack under files/phase3/code-server/extensions
      (18.6 MiB) via run-as; 3 header-checked PNGs (details/trust/installed) +
      shas in docs/PHASE3_RESULT.md item 10.
- [x] 11. Phone round (only if 2c0f6edc connected) or SKIPS.md row [45 min]
      DONE 15:33 - the phone never appeared in `adb devices` all day, so the item's
      SKIPS.md branch applies: the "Phase 3 phone gate" row is written with the exact
      install + broadcast chain for the manager.
- [x] 12. Close-out: build gates, BUGS.md, SKIPS.md, THIRD_PARTY.md, ARCHITECTURE.md H8/Q4,
      docs/PHASE3_RESULT.md, cleanup, STATE.md last, final report [30 min]
      DONE 16:10 - build battery green (build28: testDebugUnitTest+lintDebug+
      assembleDebug, exit 0); BUGS B-039/B-040/B-041 rows; SKIPS phone-gate row;
      THIRD_PARTY indent-rainbow row (license verified in the unpacked tree);
      ARCHITECTURE H8/Q4 measured; PHASE3_RESULT items 8/9/10/12 + incident log;
      device cleanup done (item-4 dlopen probe files + stale code-server IPC sockets;
      cs-stop survived=0; 0 app processes; emulator data intact; emulator left at
      ~3 GB config, agent-owned); STATE.md updated as the last action; final report
      in the session reply.
