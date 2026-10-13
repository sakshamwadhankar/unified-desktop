# TECH STACK: the tools and why

Everything here was researched on 2026-10-13. Re-check before relying on it: Android and
Google Play change. "Verify" marks what a phase must confirm.

## 1. Chosen

| Area | Tool | Why | Verify in |
|---|---|---|---|
| Phone app | Kotlin, Android SDK, **targetSdk 36**, minSdk 29 (provisional), NDK for native code | Play requires a current targetSdk; Android 10+ has the virtual-display rules we rely on | Phase 1 |
| Android apps, hidden | **App-virtualization engine** (BlackBox family) | Runs installed apps inside our process, so their windows are ours. App cloners like Parallel Space do this on Google Play today. Forks claim Android 14-17 support (self-reported) | Phase 1 (pick a fork, check its license) |
| Windows apps | **Wine** (LGPL) + **Box64** (MIT); **FEXCore** (MIT) as an option | Box64 translates x86 code to ARM. In a 2026 benchmark it ran at about 67% of native speed (median), about 4x faster than QEMU. GameHub, a Wine + Box64 app, is on Google Play | Phase 2 |
| Base for Windows/Linux | A Winlator fork (Winlator Bionic, Ludashi, Bannerlator) or upstream Box64 + Wine | Proven Android packaging of Box64 + Wine. The original Winlator's newest source was never released, so only the open forks are candidates | Phase 2 |
| Linux apps (x86-64) | **Box64** + x86-64 Debian packages | Same translator, no VM | Phase 2 |
| Full-speed tools | **Node.js** (MIT), **code-server** (MIT) for VS Code, **Python**, **Git**, a shell, shipped in the APK as `lib*.so` | Native ARM speed. Shipped from Google Play, so allowed to run. People already run code-server on Android phones (Termux), and Termux itself is on Google Play again in 2026 | Phase 3 |
| VS Code extensions | **Open VSX** registry | Microsoft's marketplace is meant for Microsoft's own VS Code builds | Phase 3 |
| Built-in browser | **Android System WebView** (Chromium) | Native speed, always up to date, no download | Phase 6 |
| Video | **MediaCodec** hardware encoder (H.264; HEVC optional) on the phone, hardware decode on the PC | Cheap for the phone; any PC can decode H.264 | Phase 5 |
| USB link | **Android Open Accessory (AOA)**, with **USB tethering** as fallback | AOA is an official Android USB mode and needs no USB debugging. Tethering is a normal Settings switch | Phase 4 |
| Wi-Fi link | Same network; discovery by NSD/mDNS; UDP-based protocol (QUIC recommended) | Low latency; no internet needed | Phase 4 |
| Pairing | Key exchange + **6-digit code** shown on both sides | Stops a stranger's PC from connecting | Phase 4 |
| PC receiver | **Native Windows app**, Windows 10/11; **Rust recommended** (small, fast, safe) | Must run on old PCs; needs low-level keyboard hooks and hardware decode | Phase 4 (DECISIONS entry) |
| PC lock | The **Safe Exam Browser** approach: own desktop, keyboard hook, policies (study only, no code copied) | Proven exam-lockdown method, open source to learn from | Phase 9 |
| Windows app catalog | **winget-pkgs** manifests (official installer URL + SHA-256 per app) | Thousands of apps with official download links; we host nothing | Phase 8 |
| Android app installs | **Google Play** (Play page on the phone, or play.google.com remote install) | Google's rule: Android apps come from Play | Phase 8 |
| 3D for Windows apps | **DXVK / VKD3D** on a Vulkan driver (**Turnip** for Adreno) | Translates DirectX to Vulkan | Phase 12 |

## 2. Rejected (and why)

| Option | Why not |
|---|---|
| QEMU / any VM | Owner rule (no VM). Measured before: about 4.25x slower than native (16.5x on memory-heavy work) and 2-3 minutes to boot |
| Google's AVF / Linux Terminal | Only for system and preinstalled apps |
| ADB-based tools (scrcpy, Shizuku, wireless debugging) | Need developer mode |
| Accessibility-service control | Google Play restricts it, and hidden apps are our own windows anyway |
| Screen mirroring (MediaProjection) as the main way | Ties up the phone's screen. Kept only as an idea for apps that refuse to be copied (banking) |
| Running downloaded ARM programs directly | Blocked by Android's exec rule and Google Play policy. Only shipped `lib*.so` files run natively |

## 3. Sources

- GameHub on Google Play: https://www.androidauthority.com/gamehub-windows-emulator-play-store-3614337/
- Box64 vs QEMU benchmark (COSCUP 2026): https://pretalx.coscup.org/coscup-2026/talk/PKYW3X/
- Winlator Bionic: https://github.com/FexEmu/Winlator-Bionic
  · Ludashi: https://github.com/StevenMXZ/Winlator-Ludashi
  · Bannerlator: https://github.com/The412Banner/Bannerlator
- Parallel Space on Google Play: https://play.google.com/store/apps/details?id=com.lbe.parallel.intl
- BlackBox fork (Android 5-17 claim): https://github.com/ZENINXOP/BlackBox
- Virtual display launch rules: https://source.android.com/docs/core/display/multi_display/activity-launch
- AVF is system-only: https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/tags/aml_net_351410000/libs/framework-virtualization/README.md
- Android Open Accessory (no USB debugging needed): https://source.android.com/devices/accessories/aoa
- Termux builds on Google Play: https://github.com/termux-play-store/termux-apps/releases
- code-server on Termux: https://gitea.ocram85.com/OCram85/code-server/raw/tag/v3.11.1/docs/termux.md
- winget manifests: https://www.mintlify.com/microsoft/winget-cli/concepts/packages
- Safe Exam Browser: https://safeexambrowser.org/windows/win_usermanual_v2.4.1_en.html
- Zoom join from browser: https://community.zoom.com/meetings-2/how-do-i-join-a-meeting-on-browser-without-app-82403
- Remote install from play.google.com: https://www.slashgear.com/1403632/install-android-apps-from-computer/
- USB vs Wi-Fi latency test: https://www.ispreview.co.uk/index.php/2021/04/usb-vs-wifi-tethering-4g-broadband-to-a-laptop-via-android-phones.html
