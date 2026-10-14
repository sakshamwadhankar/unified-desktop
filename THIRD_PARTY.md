# THIRD_PARTY: components and licenses

Add a row **before** using any third-party code, binary or data (ARCHITECTURE C6). GPL or
AGPL needs a DECISIONS.md entry first, because it affects how the app may be shipped.

## In use

| Component | Version / commit | License | Used for | Source URL |
|---|---|---|---|---|
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
| Box64 | MIT | x86 -> ARM translation |
| FEX / FEXCore | MIT | alternative translator |
| Wine | LGPL-2.1 | Windows layer |
| Winlator forks (Bionic, Ludashi, Bannerlator) | check each (original Winlator: MIT) | Android packaging of Box64 + Wine |
| BlackBox-family engines | check each fork | Android app virtualization |
| Node.js | MIT | runtime for VS Code's engine |
| code-server (Coder) | MIT | VS Code in a browser window |
| Python | PSF | built-in tool |
| Git | GPL-2.0 | built-in tool (DECISIONS entry needed) |
| DXVK / VKD3D-Proton | zlib / LGPL | DirectX -> Vulkan |
| Mesa Turnip | MIT | Vulkan driver for Adreno |
| winget-pkgs manifests | MIT | Windows app catalog data |
| Safe Exam Browser | MPL-2.0 (check) | study only for the PC lock; no code copied |
