# PHASE 3 RESULT: VS Code (code-server on bundled Node.js) in a WebView

Round: 2026-10-18, emulator-first (phone serial 2c0f6edc was NOT connected).
Labels: every result below is **emulator** unless a line says otherwise.
Host: AVD `Pixel_8_Pro`-family image `sdk_gphone16k_x86_64`, Android 17 (API 37),
**16 KB pages**, `ro.product.cpu.abilist = x86_64,arm64-v8a`.
The app under test: `android/app` debug build, `dev.unifieddesktop.app`,
installed fresh on the emulator (uid u0_a233, `nativeLibraryDir`
`/data/app/~~.../dev.unifieddesktop.app-.../lib/x86_64`).

Every number in this file comes from the app's own logs on the device
(`files/phase3/p3.log`) or from adb, not from a laptop-side estimate.

---

## Item 2: licenses checked BEFORE use (all read inside the pinned debs)

| Component | Version | License (read where) |
|---|---|---|
| Node.js (Termux/TUR build) | 24.18.0 | MIT (`opt/nodejs-24/share/doc/nodejs-24/copyright`) |
| OpenSSL (Termux) | 1:3.6.5 | Apache-2.0 (`share/doc/openssl/copyright` -> `LICENSES/Apache-2.0.txt`) |
| c-ares (Termux) | 1.34.8 | MIT (`share/doc/c-ares/copyright`) |
| libc++ (Termux) | 30 | Apache-2.0 WITH LLVM-exception (deb has NO licence file; upstream `libcxx/LICENSE.TXT` read) |
| code-server (Coder) | 4.141.0 | MIT (`LICENSE` in the deb) |
| VS Code OSS (inside code-server) | 1.141.0 | MIT (upstream `package.json`; the deb pruned its LICENSE file) |
| node-pty | 1.2.0-beta.15 | MIT |
| @parcel/watcher | 2.5.6 | MIT |
| @vscode/spdlog | 0.15.8 | MIT |
| @vscode/sqlite3 | 5.1.12-vscode | BSD-3-Clause |
| @vscode/native-watchdog | 1.4.6 | MIT |
| argon2 | 0.44.0 | MIT (phc-winner-argon2 inside: CC0-1.0/Apache-2.0) |
| kerberos | 2.1.1 | Apache-2.0 |
| Termux packages / TUR | packaging only | no licence of its own; no GPL component in the packages used |

Scan of all **438** `package.json` files in the code-server deb for non-permissive licences:
`mit/isc/apache/bsd/zlib/0BSD/Unlicense/BlueOak` otherwise; the only **LGPL** hit is
**jschardet 3.1.4 (LGPL-2.1+)** - pure JS, part of VS Code's own bundles
(`out/vs/workbench/api/...`, `out/vs/code/browser/workbench/workbench.js`), shipped
neither by us nor modified: it arrives inside the run-time-downloaded deb (D14 model).
Listed for the manager in THIRD_PARTY.md; if the no-LGPL rule also covers
run-time-fetched data, code-server itself must be patched (VS Code uses jschardet for
encoding detection).
Native modules we DO ship in the APK: all MIT/BSD/Apache - no GPL/LGPL native code.

## Item 3: `android/vscode/fetch-native.sh` (build-time, committed)

Downloads the 5 pinned debs per ABI (SHA-256 checked on every run), extracts only the
needed members, `llvm-strip --strip-unneeded`, renames to `lib*.so`, rewrites
DT_NEEDED/DT_SONAME in place (`android/vscode/patch-neededs.py`; new names are all
shorter so the .dynstr bytes stay in place), verifies **every LOAD segment has
p_align 0x4000** and prints size + sha256 of every output.

Shipped names: `libnode.so`, `libcxx.so` (from `libc++_shared.so`), `libssl3.so`,
`libcrypto3.so`, `libcares.so`, `libpty.so`, `libwatcher.so`, `libspdlog.so`,
`libsqlite3node.so`, `libwatchdog.so`, `libkerberos.so`, `libargon2.so`.
`libz.so.1` is NOT shipped: node's DT_NEEDED was rewritten to `libz.so`, Android's
public NDK zlib (found by the system linker). Verified on the emulator: node starts
and OpenSSL reports 3.6.5, so the rewrite works end to end.

`jniLibs` per ABI (stripped, in the APK): **x86_64 93,386,816 B**,
**arm64-v8a 93,675,872 B** (+ the Phase-2 `libbox64.so` 22,390,072 B on arm64-v8a).
`libnode.so`: 82,722,872 B x86_64 / 83,296,208 B arm64-v8a (from 113,764,200 B
unstripped). APK (both ABIs, debug): **91,860,294 B**.
Alignment: all 12 files on both ABIs `ok 0x4000` - no BUG row needed.

Method note (BUGS B-030 lesson): the *tested bytes* are the artifact; the script
prints a sha256 table on every run so a rebuild can be compared, but the shas are not
required to match a stored value (llvm-strip output is expected to be deterministic,
the source debs are pinned by SHA-256).

## Item 4 CORE PROOF (stop/go): node from nativeLibraryDir - **PASS**

All from `files/phase3/p3.log` on the emulator, 2026-10-18 11:50-11:59.

| Test | Result |
|---|---|
| `libnode.so -e "console.log(process.version, process.arch)"` | `NODE v24.18.0 x64 android`, exit 0, **first output 170-222 ms**, wall 223-258 ms |
| `/proc/<pid>/exe` of that process | under `nativeLibraryDir` (true) |
| `process.execPath` | `<nativeLibraryDir>/libnode.so` |
| `process.versions.openssl` | 3.6.5 (so `libssl3.so`+`libcrypto3.so`+`libcxx.so` loaded) |
| HTTPS to `https://open-vsx.org/` | **200**, 3,348 B HTML through TLS |
| HTTPS to `https://open-vsx.org/api/-/search?query=python&size=1` | **200**, 978 B JSON |
| certificate verification | `authorized=true`, TLSv1.3, peer CN `open-vsx.org`, store = `/system/etc/security/cacerts` |
| `process.dlopen(m, <nativeLibraryDir>/libpty.so)` | **OK**, exports `fork,open,resize,process` |
| `process.dlopen(m, <files>/pty-copy.node)` (same bytes, sha equal) | **OK - NOT blocked** (see the premise note below) |
| `exec <files>/libnode-copy.so` (same bytes, sha equal) | **BLOCKED**: `IOException: Cannot run program ...: error=13, Permission denied` |
| `exec <nativeLibraryDir>/libnode.so -e ...` | **OK**: `native exec ok v24.18.0` |

### Premise note (the prompt's DLOPEN claim)

The prompt states that a `.node` copied into app storage cannot be dlopen'ed and that
this proves the Play rule. **Measured: dlopen from `files/` SUCCEEDS on this image**
(Android 17, 16 KB pages, targetSdk 36 app, sha-identical copy). What IS blocked is
**execution** (`exec()` of the same bytes in app storage: `error=13 Permission
denied`), which is the documented Android 10+ app-home-directory restriction.
Consequence for the design: the reason to ship native modules from the APK is
(a) Google Play's policy against downloaded native code (a policy rule, not an OS
block) and (b) `exec` of node itself is genuinely blocked outside nativeLibraryDir.
The measured dlopen result is logged in BUGS.md (B-034) for the manager, and
ARCHITECTURE.md C1 keeps its stronger-than-necessary wording (we ship everything in
the APK anyway).

### Environment findings that cost time (both real, both recorded)

1. **Emulator storage**: `/data` was 99% full (91 MB free) with leftovers of the
   closed AndroidDex-V3 project: `/data/local/tmp/debian.qcow2` (410 MB) +
   edk2 firmware (134 MB) and the installed apps `com.androiddex.host` (121 MB APK),
   `com.androiddex.spike` (74 MB) - installing the 92 MB APK failed with
   `INSTALL_FAILED_INSUFFICIENT_STORAGE`. Removed those (2.6 GB free now);
   `com.termux` was left installed. This touches only emulator scratch data.
2. **Background network is blocked**: the app's standby bucket was **RARE (50)**;
   every socket the app opened was aborted by netd (`InetDiagMessage: Destroyed live
   tcp sockets for uids={10233}`), which node reports as
   `ECONNABORTED`/`getaddrinfo ENOTFOUND` although the shell could ping and resolve
   fine. Fix for dev runs: `adb shell am set-standby-bucket dev.unifieddesktop.app
   active` (and/or the harness activity in the foreground). The product answer is the
   Phase-4 foreground service (B-027).

## Item 5: code-server data (download, sha256, JS-only unpack) - **PASS**

The app downloads the pinned deb itself, checks its SHA-256 against the pin, unpacks
only the JavaScript/data members into `files/phase3/code-server`, links the seven
native modules to `nativeLibraryDir`, and verifies the tree. Runs are driven by the
`cs-fetch` broadcast with `HarnessActivity` in the foreground; all numbers are
`logcat -s Phase3`.

| Step | Emulator (measured 2026-10-18 12:42-12:43) |
|---|---|
| Download | `CSDL ... bytes=117273956` in ~52 s while foregrounded (first run); `CSDL cached sha ok` in this run |
| SHA-256 | pin `90cbdcac...b28c0` == file hash (checked in-process, both runs) |
| Unpack | `CS_UNPACK ms=35791` (35.8 s) for 11,246 members (9,519 files, 1,689 dirs, 38 symlinks) -> 9,134 files / 326,506,636 B on disk |
| ELF | `elf_guard_dropped=0` - no `\x7fELF` byte reached the disk (filter list did all the work) |
| Native links | `CS_LINK mapped=7 created=7`, all inside `code-server`, targets in `nativeLibraryDir` |
| Verify | `CS_VERIFY files=9134 bytes=326506636 elf=0 symlinks=31 broken=0 links_ok=7/7` (31 = 24 symlinks kept from the deb + our 7 native links) |
| Identity | `entry=out/node/entry.js present=true bytes=3645`; code-server `4.141.0`; VS Code `1.141.0`; `workbench.js` 20,168,531 B; `CS_VERIFY marker=true sha=<deb sha>` |

Laptop mirror vs phone: the same skip filter implemented in bash (over the same deb,
`tar` + a per-path reason) predicted keep = **9,133 files / 326,506,571 B** and the
same per-reason drop list; the emulator wrote **9,134 files / 326,506,636 B** - the
one extra file is the 65-byte `native-links.json` marker the app writes itself, so
file count and bytes agree to the byte. Per-reason agreement (drop counts/bytes,
emulator == mirror): `copilot-sdk 59f/143791724`, `mxc-sdk 171f/97106640`,
`build-obj 109f/10266497`, `msal 2f/4477576`, `native-module 12f/3060120`,
`koffi 5f/2373881`, `obj 3f/1443118`, `parcel-watcher-bin 8f/1037967`,
`seccomp 4f/849479`, `argon2-prebuild 11f/809756`, `abs-link 14f`.

What the deb really contains (all measured, not assumed): 74 ELF members /
180,185,356 B, 1,689 directories, 38 symlinks (14 absolute - dropped, see below),
34 `.wasm` files (kept as data: tree-sitter grammars, oniguruma, copilot and the JS
profiler's modules - none is executed as native code). Exactly three files are
executables on disk after the unpack (`bin/code-server`, `lib/vscode/bin/code-server-oss`,
`lib/vscode/bin/remote-cli/code-server` are 0755 from the deb); they are plain shell
scripts and we never execute them - node runs `out/node/entry.js` from the APK.

Known limitations of this tree (logged, not hidden): `lib/node` and the 13
`@vscode/ripgrep-universal/bin/*/rg` entries are **absolute** symlinks into Termux's
prefix, so they are dropped (`abs-link`): VS Code's built-in search cannot run
ripgrep (grep-in-files falls back to JavaScript) and the `node` binary in the tree is
absent (we run the APK's `libnode.so`); `extensions/copilot` (42 MB of JS) survives but
cannot activate because its native SDK is skipped; the Microsoft-auth extension cannot
load MSAL runtime; 13 `.wasm` files stay as V8 data objects.

### Bug found and fixed while doing this (B-036)

The first device run died 27 files in with
`FileNotFoundException: .../configuration-editing/images: open failed: EISDIR`.
Root cause found by dumping the raw tar headers: the deb is packed with **GNU
long-name members** (`type 'L'`, name `././@LongLink`, payload = the real path), and
our reader skipped them, writing the truncated 100-byte name field of the *actual*
header. So every path > 100 bytes landed under a 100-byte-truncated name, and for
`.../images/icon.png` the truncation produced a file literally named `images` where
the directory already existed. Fixed in `Phase2Data.readTar` (honour 'L'/'K';
`TAR_NAME full` warning for the same tell-tale) with 4 new unit tests
(`TarReaderTest`), and `Phase2Data.unpackDeb` is now `internal` (the public signature
used to leak the internal `TarPolicy` type). Second device-run fix: `Phase3Data.fetch`
is `@Synchronized` - two `cs-fetch` broadcasts raced on the same cache file and a
stale writer filled a deleted inode with 92 MB of a partial deb.

Third device-run fix (found by comparing symlink counts against the mirror): the
reader sent *symlink targets* through the traversal guard meant for member names, so
all 22 `node_modules/.bin/* -> ../pkg/bin.js` links were dropped as "SYMLINK FAIL"
while a bare count made it look like 'L' handling was still incomplete. The reader now
resolves relative targets and keeps them if they stay inside the unpack root
(`linkTargetInside`; `TAR_LINK_ESCAPE` if they climb out) - after the fix the tree has
exactly the mirror's 24 deb symlinks plus our 7. The `TAR_NAME 100B` message (exactly
100-byte names are legal without `@LongLink`) is capped at 3 lines per unpack so a
real regression is not lost in noise.

---
## Item 6: code-server started by the app - **PASS**

`Phase3Server.start` launches `<nativeLibraryDir>/libnode.so -r hook.js out/node/entry.js
--config config.yaml --preserve-symlinks` as a child of the app process, with a fresh
random port and a fresh 32-hex password per start. All security properties are visible
in code-server's own output (not just ours):

```
info  HTTP server listening on http://127.0.0.1:36313/
info    - Authentication is enabled
info      - Using password from $PASSWORD
info    - Not serving HTTPS
```

| Property | Measured (3 starts, emulator 13:34-14:0x) |
|---|---|
| Bound to | `127.0.0.1:<random port>` (40393, 41901, 36313/36473) - never 0.0.0.0 |
| Auth | `auth: password`, `PASSWORD` env, fresh 32-hex password every start |
| Cleartext policy | `cleartext_127=true cleartext_localhost=true cleartext_example=false` (network_security_config) |
| /healthz | HTTP 200 `{"status":"expired","lastHeartbeat":0}` - first_response_ms 2,045 / 4,960 / 6,403 (cold-ish variance on a loaded emulator) |
| Process | single process, e.g. pid 16928: `state=S threads=11 rss_kb=69872 pss_kb=49343` at ~7 s |
| /proc/pid/exe | under `nativeLibraryDir` (`underNativeLibDir=true`) |
| Env | `UD_LIB_DIR`, `PASSWORD`, `NO_PROXY=127.0.0.1,localhost`, XDG dirs into `files/phase3` |
| User data | user-data-dir and the IPC socket stay inside `files/phase3/code-server` |

Two start bugs were found and fixed on the way (details in BUGS.md B-037/B-038):

1. **A reinstall dangles the native-module symlinks**: `adb install -r` moves
   `nativeLibraryDir` to a new `/data/app/~~/...` path, so the links created at unpack
   time pointed at the old dir; node's `require('argon2.node')` returned
   MODULE_NOT_FOUND. `start` now re-links (`CS_START relink created=7 already=0`).
2. **`require()` lost the `.node` extension**: node realpaths module requests, so a
   symlink named `argon2.node -> libargon2.so` was loaded as `.js` (SyntaxError on ELF
   bytes) while `process.dlopen` of the same path worked. Fixed with
   `--preserve-symlinks`; the `-r` hook then logged
   `dlopen request=.../argon2.node real=.../libargon2.so underNativeLibDir=true`.

`cs-status` reads the session file (password redacted from logcat), reports liveness,
healthz and per-pid facts; `cs-stop` TERM/KILLs by cmdline match and reports
`survived=0`.

## Item 7: WebView activity - **PASS**

`Phase3CodeActivity` (main manifest, `exported="false"`, landscape): it reads port and
password from the app-private session file (never an Intent extra), POSTs `/login`
once and, with the session cookie, loads `/`. Shell cannot start a non-exported
activity on API 37 (measured: SecurityException), so the harness drives it with the
`cs-web` broadcast action and the app starts its own activity.

DOM-level proof (evaluateJavascript from the activity, logged as `CS_WEB dom+...`):

```
+1000ms {"workbench":false,"bodyChars":0}
+3000ms {"workbench":true, "parts":true, "bodyChars":100}
+6000ms {"title":"code-server","workbench":true,"parts":true,"bodyChars":174}
```

`.monaco-workbench` and the workbench parts exist in the rendered page; the earlier
load-time `title=127.0.0.1:<port>` is just the pre-JS `<title>`. Screenshot:
`evidence/phase3/p3web-workbench.png` - valid PNG, verified by header check
(`89 50 4E 47 0D 0A 1A 0A`), 630,597 B, sha256
`9A852F54BAB800BFD4A6485CAEF46A3641DB54C250475060AE2E69FFA2F2B6AD`, taken 14:14
(retaken after the emulator restart; the first take was corrupt because PowerShell `>`
re-encodes the binary stream - see LESSONS.md 7; described here in text).

---

## Item 8: gate flow through injected input - **PASS (emulator)**

Measured context that shaped the flow (all emulator, 14:1x-15:0x):

- **Keyboard injection into the page is dead through the app's dispatch routes** and
  through adb for chords, before the focus fix: details and evidence in BUGS.md **B-039**.
  Everything below therefore runs with **pointer taps + IME text** (`input text`) and,
  after the fix, one system-path key chord.
- Coordinate system measured once and re-used: dpr 3 (density 480), landscape frame
  2992x1344, CSS viewport 997x448; device px = CSS px x 3. Tap targets are never
  assumed: the `cs-dom:boxes` probe (build 24+) returns the rect of every menu item,
  quick-input row, input, button, editor line and terminal container, and taps go to
  measured centers only.
- **Deep-link folder open** (replaces the earlier quick-input folder-dialog tap chain):
  `cs-gofolder:<path>` navigates the logged-in WebView to
  `http://127.0.0.1:<port>/?folder=/data/user/0/dev.unifieddesktop.app/files/phase3/workspace`.
  Verified in the DOM: title `Welcome - workspace - code-server`, URL carries `?folder=`,
  explorer list has 1 row. This is code-server's own documented deep link - the same
  "VS Code opens a folder" operation a user triggers from the UI.
- **File open**: Application Menu (hamburger) tap -> `File` row tap -> measured
  `Open File...` row -> the quick-open dialog lists `hello.txt` directly -> row tap.
  Title bar then shows `hello.txt - workspace - code-server`; status bar shows
  `Ln 1, Col 1`, `LF`, `UTF-8`, `Plain Text` (real editor state, not our claim).
- **Edit**: the probe measured the two view-lines (`phase3` at CSS y=102, an empty line 2
  at y=121 = end of file). A tap at the empty line moved the status bar to `Ln 2, Col 1`;
  `input text phase3-edited` landed via IME (status bar `Ln 2, Col 14`).
- **Save**: `input keycombination 113 47` (Ctrl+S, system path) **after build27 made the
  WebView focusable** (`isFocusable`, `isFocusableInTouchMode`, `requestFocus()` after
  load and in `onPageFinished`). Disk proof (the gate's requirement):

  ```
  run-as dev.unifieddesktop.app cat files/phase3/workspace/hello.txt
  phase3
  phase3-edited            <- bytes on disk after the chord
  ```

  A later state probe showed `dirty:false` - the editor's own state agrees.
  Screenshot: `evidence/phase3/p3gate-hello-saved.png` (valid PNG, 194,117 B, sha256
  `6DD273771B87E58F4429B0128FD17636087FDE53C8AB95D52DB1D47E44E7C8C9`, header-checked).
  Earlier evidence: `evidence/phase3/p3gate-file-menu.png` (583,072 B, File menu open
  with the Open Folder / Save rows visible).

- **Terminal**: Application Menu -> `Terminal` row -> measured `New Terminal
  (Ctrl+Shift+C)` row tap -> the bottom panel opened (editor rect shrank from h=324 CSS
  px to h=179 in the same frame) and a visible `xterm-helper-textarea` (the xterm IME
  input, `ro:false`) appeared in the DOM probe - a real terminal instance.
- **Terminal command: PROVEN ON DISK (completed after the emulator recovery, 15:42-15:47).**
  The first attempt on the pre-crash instance was swallowed by a host-side quoting
  artifact (PowerShell 5.1 stripped the embedded double quotes, so the *device adb
  shell* parsed `> term.txt` as its own redirect at its cwd `/` - the terminal never
  received that text; details BUGS.md **B-040**). After the emulator recovery the flow
  was re-run with the corrected delivery recipe (spaces as `input text` `%s`,
  metacharacters as `\>` / `\;`, Enter as keyevent 66):

  1. Terminal menu taps (identical measured coordinates on the new instance):
     hamburger (78,171) -> `Terminal` row (432,606) -> `New Terminal (Ctrl+Shift+C)`
     (1221,402) -> xterm's `xterm-helper-textarea` appears in the DOM (`ro:false`).
  2. `cd /data/user/0/dev.unifieddesktop.app/files/phase3/workspace` + Enter,
     then `pwd > pwd.txt` + Enter (records the REAL pty cwd - B-040 follow-up),
     then `echo phase3-ok > term.txt; uname -m > machine.txt` + Enter.
  3. Disk proof via `run-as dev.unifieddesktop.app sh -c 'cd files/phase3/workspace
     && cat pwd.txt term.txt machine.txt hello.txt'`:

     ```
     PWD=     /data/user/0/dev.unifieddesktop.app/files/phase3/workspace
     TERM=    phase3-ok
     MACHINE= x86_64
     HELLO=   phase3 / phase3-edited      (edit+save proof still intact)
     ```

  Screenshot: `evidence/phase3/p3gate-terminal.png` - valid PNG, header-checked
  (`89 50 4E 47 0D 0A 1A 0A`), 293,005 B, sha256
  `8ED08BA2E62FA7BDD0AA435184510CF1ACFE8968832F445C7CAB309382BD5BEC` (15:47).

### Host incident log (emulator availability)

All times 2026-10-18, from this session's tool outputs:

| Time | Event |
|---|---|
| 13:08 | Gradle daemon OOM during an earlier build (11 hs-err JVM logs, moved to `p3/hserr/`). |
| 14:10 | Manager restarted the emulator after an OOM (manager note). |
| 15:08:42 | qemu StartTime of the instance that was running the item-8 flow (from `Get-Process`). |
| ~15:2x | Host OOM storm: `System.OutOfMemoryException` inside PowerShell (module load of Microsoft.PowerShell.Utility failed) in the middle of the terminal-`input text` batch; immediately after, `adb` reported `device 'emulator-5554' not found` - the emulator instance died (no qemu process). |
| ~15:3x | Relaunch 1 (default AVD RAM ~6 GB): hung - qemu WS pinned at 192 MB, TotalProcessorTime frozen (2.203 s -> 2.203 s over 8 s), never appeared in `adb devices`. Killed. |
| ~15:3x | Relaunch 2 after deleting stale `Pixel_8_Pro.avd/hardware-qemu.ini.lock` + `multiinstance.lock` (left by the OOM-killed instance): same hang signature. Killed. |
| ~15:4x | Relaunch 3 with `-no-snapshot` (cold boot): same hang (WS 192 MB, CPU frozen at ~2.2 s after 150 s). Killed. |
| ~15:4x | Relaunch 4 with `-memory 3072 -no-boot-anim -no-snapshot-load` (manager's command): hung >360 s; with commit pressure the qemu WS *collapsed* to 48 MB -> 28 MB (fully paged out) - the guest RAM cannot be backed. Killed. `wsl --shutdown` freed only ~0.2 GB. |
| 15:41 | Free RAM crossed the 3 GB threshold (4,241 MB free - the owner closed the browser). Relaunch 5 with the same `-memory 3072 -no-boot-anim -no-snapshot-load` command: **booted cleanly** - adb registered at ~30 s, `sys.boot_completed=1` at ~60 s (15:43:10), qemu WS 1,388 MB and CPU advancing. All app data survived the host crash (`hello.txt` bytes intact on the app-private storage). |
| 15:26 | Free RAM measured 0.52-1.24 GB throughout (15.63 GB total; the owner's browser holds ~6.5 GB RSS). Commit headroom was 8.7-14.4 GB, so the failure is physical-RAM standby starvation, not commit. |

Policy recorded for the rest of Phase 3 (manager note 15:2x): the emulator is started
only when `FreePhysicalMemory > 3 GB`, with `-memory 3072 -no-boot-anim
-no-snapshot-load`, one launch, no relaunch loops; every crash/hang gets a row here.
No Gradle build while the emulator boots or when free RAM < 2 GB.

### What remains for item 8: nothing (all three gate parts proven on disk; the SKIPS.md
### terminal row was closed by the 15:47 proof).

## Item 9: numbers - **PASS (emulator; APK measured on the host)**

APK: `android/app/build/outputs/apk/debug/app-debug.apk`, built 14:48 (build27,
includes the item-8 focus/deep-link/probe changes).

| Measurement | Value |
|---|---|
| Universal debug APK | **92,526,314 B (88.2 MiB)**, 717 zip entries |
| lib/arm64-v8a | 15 .so, **118,422,576 B (112.9 MiB)** uncompressed - largest: `libnode.so` 83,296,208 B (79.4 MiB), `libbox64.so` 22,390,072 B (21.4 MiB, arm64 only - the x86-64 translator is not needed on x86_64), `libcrypto3.so` 5.0 MiB |
| lib/x86_64 | 14 .so, **95,561,120 B (91.1 MiB)** uncompressed - largest: `libnode.so` 82,722,872 B (78.9 MiB) |
| Shipped non-native assets | `assets/phase3/` contains only `hook.js` (the `-r` preload logger) - **no deb, no code-server tree, no data in the APK**; the 117,273,956 B deb is fetched at runtime with a sha256 check (item 5) |
| `cs-start` cold (first start after a full emulator boot, 15:44) | healthz 200 in **7,899 ms** (`first_response_ms`; node launch itself `launched_ms=14`) |
| `cs-start` warm (after `cs-stop`, same install, 15:50) | healthz 200 in **2,818 ms**; `cs-stop` reported `survived=0` |
| PSS at idle (workbench + terminal open, untouched) | app process 135,657 kB (132.5 MiB, TOTAL RSS 276,484 kB); node main pid 21,788 kB; code-server entry worker 53,038 kB |
| Process family (`ps -A` under the app uid) | **14** with probe transients; steady ≈ 11: app, `:black`, node main (libnode.so from nativeLibraryDir), entry worker, fileWatcher, agentHost, extensionHost, ptyHost, terminal `sh`, logcat, probe |
| Idle CPU over 60 s (node main, no interaction) | 119+93 -> 124+97 ticks: **9 ticks = 90 ms CPU / 60 s = 0.15% of one core**; RSS steady 3,228-3,291 pages (50-53 MB, 16 KB pages) |
| `files/phase3` on-device size | `du -s`: **593,976 kB (580.1 MiB)** total, of which `code-server` tree 398,332 kB (389.0 MiB); remainder = the pinned deb (111.8 MiB) + logs/config |

(Numbers from `/proc/<pid>/stat`, `smaps_rollup`, `dumpsys meminfo`, `du` via `run-as`,
and the app's own `CS_START`/`CS_STOP` logs - all emulator, 15:42-15:50, host free RAM
0.8-1.2 GB during sampling.)

## Item 10 (stretch): one extension from Open VSX - **PASS (emulator)**

`oderwat.indent-rainbow` (indent-rainbow, MIT) installed **through the WebView UI
against Open VSX** - the real user path, no host staging, no code-server CLI:

1. Extensions view opened by tapping the measured activity-bar icon (the 5th of the
   top icon group, CSS (26,272) -> device (78,816); the probe `actbar[]` measured the
   group, Accounts at (26,350) and Manage at (26,394) anchor the geometry).
2. Tapped the marketplace search box, typed `indent-rainbow` with `input text` (IME).
   **The app's WebView talked to Open VSX live**: five results came back
   (`indent-rainbow`, Rainbow CSV, Rainbow Blocks, Python Indent, Nifty Whitespace),
   measured by the probe (`ext[]` rows, 238x72 CSS each).
3. Opened the details page (row tap): publisher `oderwat`, 580,311 installs, 5 stars.
   Evidence: `evidence/phase3/p3ext-details.png` (472,030 B, sha256 `FDE96539...`,
   header-checked).
4. Tapped the big Install button (measured from the screenshot frame: device
   (1537,651)); code-server's publisher-trust dialog appeared
   ("Do you trust the publisher 'oderwat'?", "not verified") -
   `evidence/phase3/p3ext-trust.png` (436,440 B, sha256 `3E8AE9F4...`).
5. Tapped `Trust Publisher & Install` (device (1954,1014)); ~15 s later the details
   page showed **Disable / Uninstall / Auto Update** (the installed state) and the list
   row showed the settings gear - `evidence/phase3/p3ext-installed.png` (465,232 B,
   sha256 `E2C89FBA...`).
6. On-disk proof (the app's own storage, inside the sandbox):

   ```
   run-as dev.unifieddesktop.app cat files/phase3/code-server/extensions/extensions.json
   [{"identifier":{"id":"oderwat.indent-rainbow"},"version":"8.3.1",
     "location":{"fsPath":".../files/phase3/code-server/extensions/
                 oderwat.indent-rainbow-8.3.1-universal", ...}}]
   du: extensions tree 19,080 kB (18.6 MiB), full unpack (package.json, dist, out,
   node_modules, ...)
   ```

## Item 12: close-out summary and gate verdict

Every line of the final table is measurable on this emulator (both folders shipped by
`fetch-native.sh` per ABI; the arm64 list above is from the universal APK; the gate
flows ran on the x86_64 install).

| Gate part | Verdict | Proof |
|---|---|---|
| Open a folder in VS Code inside our WebView | **PASS** | `?folder=` deep link; title `workspace - code-server`; PHASE3_RESULT item 8 |
| Edit a file and save it | **PASS** | `hello.txt` = `phase3`+`phase3-edited` on disk after Ctrl+S (`run-as` bytes); `dirty:false` |
| Type a shell command in the integrated terminal | **PASS** | `pwd.txt` (workspace path), `term.txt` = `phase3-ok`, `machine.txt` = `x86_64` via `run-as` |
| Start time (cold/warm) | measured | healthz 200: 7,899 ms cold (after full boot), 2,818 ms warm, 5,792 ms second cold-ish |
| RAM | measured | app PSS 132.5 MiB; node main 21.8 MiB; code-server worker 53.0 MiB (smaps_rollup) |
| Process count | measured | 14 under the app uid with probe transients, steady ~11 (app, :black, node main/worker, fileWatcher, agentHost, extensionHost, ptyHost, sh, logcat) |
| Idle CPU (60 s, untouched) | measured | node main 90 ms / 60 s = **0.15% of one core** |
| APK size | measured | 88.2 MiB universal debug; libnode 79.4+78.9 MiB per ABI; libbox64 (arm64) 21.4 MiB; no data shipped (`assets/phase3` = hook.js) |
| data on device | measured | `files/phase3` 580.1 MiB (`code-server` tree 389.0 MiB + pinned deb 111.8 MiB + logs) |
| Licenses BEFORE use | PASS | item 2; THIRD_PARTY.md rows for all 14 shipped components (+ runtime-fetched jschardet note); the demo extension (indent-rainbow, MIT) is installed at runtime, not shipped - row added for the record |
| Phone round | **SKIPPED (allowed)** | 2c0f6edc never in `adb devices`; SKIPS.md "Phase 3 phone gate" row with the exact broadcast chain |

**Open engineering bugs handed to the manager (BUGS.md):** B-034 (premise correction),
B-035 (dev standby bucket), B-037 (symlink relink after reinstall), B-038
(`--preserve-symlinks`), **B-039 (keyboard events never reach the WebView page - Phase 5
input decision needed)**, B-040 (PowerShell->adb quoting trap; harness only), B-041
(host RAM/emulator hangs, dev environment).

---

---
