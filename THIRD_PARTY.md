# THIRD_PARTY: components and licenses

Add a row **before** using any third-party code, binary or data (ARCHITECTURE C6). GPL or
AGPL needs a DECISIONS.md entry first, because it affects how the app may be shipped.

## In use

| Component | Version / commit | License | Used for | Source URL |
|---|---|---|---|---|
| (none yet) | | | | |

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
