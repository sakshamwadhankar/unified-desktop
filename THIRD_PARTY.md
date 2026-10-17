# THIRD_PARTY: components and licenses

Add a row **before** using any third-party code, binary or data (ARCHITECTURE C6). GPL or
AGPL needs a DECISIONS.md entry first, because it affects how the app may be shipped.

## In use

| Component | Version / commit | License | Used for | Source URL |
|---|---|---|---|---|
| Box64 | v0.4.4 release tarball (`99c6de4f509e46ab1de15df740d0e0ea338a7790efa3f67510dfbb975cc24029`), built from source with NDK r27c into `libbox64.so` (22 MB stripped; `ANDROID=ON ARM64=1`) | MIT (LICENSE read at the pinned tag) | x86-64 -> ARM64 translation; local patch: the argv[0] `S_IXUSR` check in `src/core.c` is skipped for ANDROID builds (guests are runtime-downloaded data under Android W^X: they cannot get an exec bit and Android itself refuses to exec app storage; box64 only reads + translates). Patch is applied reproducibly by `android/win86/fetch-box64.sh` | https://github.com/ptitSeb/box64 |
| XZ for Java (org.tukaani:xz) | 1.10 (Maven Central) | Public-domain-equivalent (XZ for Java licence, per-repo LICENSE to re-read before shipping) | pure-Java .xz decompression to unpack Wine tarballs / Debian packages at runtime | https://central.sonatype.com/artifact/org.tukaani/xz |
| Wine (Kron4ek prebuilt amd64) | 11.19 `wine-11.19-amd64.tar.xz` (`40068b3825887224d4b95364e3f49342d4bf7efac5486614b8bc4e7173bbef3e`, asset digest verified) | LGPL-2.1 (Wine itself; Kron4ek builds are unmodified Wine) | Windows API layer for Windows apps; DATA only, translated by Box64, never executed by Android | https://github.com/Kron4ek/Wine-Builds/releases/tag/11.19 |
| Xvfb + x86-64 server libraries (Debian/Ubuntu) | pinned .deb set with SHA-256, fetched at runtime | MIT/X11 (X.Org Foundation); per-package licences as distributed | Headless X11 server (screen buffer) our app captures from; DATA only, run through Box64 | Debian/Ubuntu package pools (URLs in fetch script) |
| x86-64 library bundle (Box64) | v0.4.4 `box64-bundle-x86-libs-v0.4.4.tar.gz` (`1d4a8787a8a92267c1be673ec194c69e3bb33d14c800575d94131084142fcff0`, verified locally at download) | MIT-licensed packaging of third-party libs; per-file licences as distributed | x86-64 libraries that box64 does not wrap, for Wine/Xvfb/GUI apps | https://github.com/ptitSeb/box64/releases/tag/v0.4.4 |
| Notepad++ portable x64 | 8.9.8.1 `npp.8.9.8.1.portable.x64.zip` (`beddf5548e75c97930d1575ba1c7d113e6e2b33d0a200310e734ca08e225f414`, asset digest verified) | GPL-2.0 — see DECISIONS D14 (pending manager); data only, never committed | Phase-2 gate app: a real Windows x64 program | https://github.com/notepad-plus-plus/notepad-plus-plus/releases/tag/v8.9.8.1 |
| xeyes / xcalc x86-64 (Debian x11-apps client set) | pinned .deb set with SHA-256, fetched at runtime | MIT/X11-family per package | Phase-2 small Linux GUI app | Debian package pool (URLs in fetch script) |
| dash (x86-64, Debian) | `dash_0.5.12-2_amd64.deb` (`33ea40061da2f1a861ec46212b2b6a34f0776a049b1a3f0abce2fb8cb994258f`), pinned in runtime-libs.lock | LGPL-2.1 (dash sources; Debian package as distributed) | guest `/bin/sh` fallback inside the rootfs (Xvfb spawns `sh -c` for xkbcomp; `bin/sh` currently points to our own `udsh`, dash kept at `bin/dash`, BUGS B-016); DATA only, run through Box64. Runtime-downloaded LGPL library/binary — see DECISIONS D14 (pending manager decision) | deb.debian.org (URL in runtime-libs.lock) |
| xkbcomp / x11-xkb-utils (x86-64, Debian) | `x11-xkb-utils_7.7+7_amd64.deb` (`b25970e444fadf4717e5624bc2a2eecef785d09d731b571da8d43f5297b43b12`), pinned in runtime-libs.lock | MIT/X11-family (xkbcomp sources as distributed by Debian) | Xvfb's keymap compiler (`server-0.xkm` chain, invoked from the server via guest `sh -c`); DATA only, run through Box64, never executed by Android | deb.debian.org (URL in runtime-libs.lock) |
| Blacks-BlackBox (including bundled BlackReflection/compiler) | `40282a7bf4500948cfd598fc67e6e63114b26dd9` | Apache License, Version 2.0 (root LICENSE read); bundled attribution audit still required before shipping | Phase 1 source integration candidate | https://github.com/Black00Z/Blacks-BlackBox |
| Dobby | `a588a0df35b11f7b18ffb2070d64cd94131cc937` (2023-04-25; NOT master — master is mid-refactor and does not compile) | Apache License, Version 2.0 (LICENSE read) | Build native hooks from source; do not use fork's prebuilt `.a` files | https://github.com/jmpews/Dobby |
| xDL | fork-bundled source at Blacks-BlackBox commit above | MIT License (upstream LICENSE read); fork has stripped headers, restore notice before distribution | Native symbol lookup | https://github.com/hexhacking/xDL |
| FreeReflection | 3.2.2; upstream LICENSE checked at `550e36e464539fe59fcc6e49c8f54bfbf376dbfe` | MIT (actual LICENSE text read) | Engine hidden-API access | https://github.com/tiann/FreeReflection |
| Android SDK / build tools / emulator | platform 36; build-tools 36.0.0; exact emulator version recorded if installed | Android SDK License Agreement; platform source Apache-2.0 | Build and emulator-only validation; tools not shipped | https://developer.android.com/studio/terms |
| Android Gradle Plugin | 9.4.1 (has built-in Kotlin; no separate kotlin-android plugin) | Apache-2.0 | Android build | https://android.googlesource.com/platform/tools/base/ |
| Gradle | 9.8.1 | Apache-2.0 | Build tool (download checksum verified) | https://github.com/gradle/gradle |
| Kotlin | AGP 9.4.1 built-in compiler (2.2.x); no explicit kotlin-stdlib pin — the round-1 pin to 2.4.21 broke the build (metadata too new for the bundled compiler) | Apache-2.0 | App language/compiler and stdlib | https://github.com/JetBrains/kotlin |
| Android NDK | 26.1.10909125 (NDK 29 rejected Dobby's arm64 assembly) | Android SDK License Agreement | Native hook compile | https://developer.android.com/ndk |
| AndroidX (appcompat, annotation and transitive AndroidX libraries) | engine declared appcompat 1.2.0; resolved versions to be reported | Apache-2.0 | Engine Android compatibility helpers | https://android.googlesource.com/platform/frameworks/support/ |
| Material Components Android | 1.3.0 (engine declaration) | Apache-2.0 | Engine dependency | https://github.com/material-components/material-components-android |
| toml4j | 0.7.2 | MIT | Engine configuration parsing | https://github.com/mwanji/toml4j |
| Gson (toml4j dependency) | 2.3.1 (declared transitively) | Apache-2.0 | TOML conversion | https://github.com/google/gson |
| JavaPoet | 1.13.0 | Apache-2.0 | Reflection annotation processor | https://github.com/square/javapoet |
| AutoService / Auto Common / Guava and transitive annotations | AutoService 1.1.1; resolved versions recorded by build | Apache-2.0; annotation dependencies Apache-2.0 / MIT as declared in their POMs | Compile-time processor only | https://github.com/google/auto |
| JUnit / Hamcrest | 4.13.2 / 1.3 | Eclipse Public License 1.0 / BSD-3-Clause | Local unit tests only | https://github.com/junit-team/junit4 ; https://github.com/hamcrest/JavaHamcrest |

## Candidates (not used yet; licenses to confirm from the real LICENSE file)

| Component | Expected license | For |
|---|---|---|
| FEX / FEXCore | MIT | alternative translator (not chosen in Phase 2; D13 chose Box64) |
| Winlator forks (Bionic/Ludashi/Bannerlator) | lineage carries GPL-3.0 contradictions (Ludashi badge MIT vs inherited GPL-3.0); Bannerlator states GPL-3.0 | NOT used; would need a manager GPL DECISIONS entry and targetSdk 28 exec-model rebuild |
| BlackBox-family engines | check each fork | Android app virtualization (Phase 1 engine is in use above) |
| Node.js | MIT | runtime for VS Code's engine |
| code-server (Coder) | MIT | VS Code in a browser window |
| Python | PSF | built-in tool |
| Git | GPL-2.0 | built-in tool (DECISIONS entry needed) |
| DXVK / VKD3D-Proton | zlib / LGPL | DirectX -> Vulkan |
| Mesa Turnip | MIT | Vulkan driver for Adreno |
| winget-pkgs manifests | MIT | Windows app catalog data |
| Safe Exam Browser | MPL-2.0 (check) | study only for the PC lock; no code copied |
