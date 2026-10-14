# Phase 1 engine comparison

Checked 2026-10-14. Selection is provisional, not a manager decision or a phone GO.
Commit dates below are commit **committer dates**, not repository `pushed_at`.

| Candidate / pinned revision | Actual license | Last commit (UTC) | Stated support | Android 14/15/16 open issues | targetSdk 36 build |
|---|---|---|---|---|---|
| [ZENINXOP/BlackBox](https://github.com/ZENINXOP/BlackBox/tree/c74315792a366cfe865148fae4bcd4353d06bf18), `c74315792a366cfe865148fae4bcd4353d06bf18` | No root LICENSE and **no LICENSE anywhere in `Vspace-ze.zip`**. README says “Apache License, Version 2.0”; not treated as a verified source license. | 2026-09-22 12:53:59 | README: free 5–14, experimental 15, **16/17 not included**; paid 5–17. | No open issues returned. Absence is not compatibility evidence. | Not run: licensing/source qualification fails; advertised target is Android 14, compile 35. |
| [ALEX5402/NewBlackbox](https://github.com/ALEX5402/NewBlackbox/tree/89b59836c66f173756a4ae258cf379a957649820), `89b59836c66f173756a4ae258cf379a957649820` | Actual root LICENSE: “Apache License / Version 2.0, January 2004.” | 2026-07-18 21:47:07 | README says 5–14+; Android 15 troubleshooting advice is not a 16 proof. | [#27](https://github.com/ALEX5402/NewBlackbox/issues/27): WebView loading error on Android 16. [#37](https://github.com/ALEX5402/NewBlackbox/issues/37): Android 15 Play Store locale SecurityException, host target 28. [#60](https://github.com/ALEX5402/NewBlackbox/issues/60): IServiceConnection binder signature crash (version not established). Further issue search recorded with evidence. | Not run yet; source/toolchain comparison pending. |
| [Black00Z/Blacks-BlackBox](https://github.com/Black00Z/Blacks-BlackBox/tree/40282a7bf4500948cfd598fc67e6e63114b26dd9), `40282a7bf4500948cfd598fc67e6e63114b26dd9` | Actual root LICENSE: “Apache License / Version 2.0, January 2004.” | 2026-05-12 15:52:03 | README claims 14+ hardening and Brave new-tab launch on Android 16 (2026-03-27); ARM64 primary path. | Two open issues: [#1](https://github.com/Black00Z/Blacks-BlackBox/issues/1) WebView cache (no OS specified), [#2](https://github.com/Black00Z/Blacks-BlackBox/issues/2) device identity requests (no OS specified). Neither proves 14/15/16 success. | Upstream `compileSdkVersion=35`, `targetSdkVersion=28`, min 21, AGP 8.13.2. Our 36 integration result pending. |

## Pick and constraints

Pick **Blacks-BlackBox** for the proof: actual permissive license, inspectable Java/native
source, relevant browser claim, and installed-package/split handling. Do not ship this pick
without attribution and policy review. No GPL/AGPL engine selected.

- Its `Bcore/src/main/cpp/Android.mk` links prebuilt Dobby archives. Replace with a pinned
  source build; do not commit or trust those archives.
- Native code must load from installed APK paths, never host-writable copied guest libs.
  Use only the engine's installed-package path and audit native/resource redirection.
- BlackBox uses host-declared **separate guest processes**, not necessarily the host UI PID.
  Same UID and host proxy activity ownership, not merely “inside our process,” are what
  must be verified against display permission checks. No same-PID assumption is accepted.
- Hidden API access includes FreeReflection plus native ART access; reaching them on Android
  16 and exact failure logs require a real runtime test.
- ZENINXOP's broad description is contradicted by its own free/paid support table. No paid
  source was purchased, and no author was contacted.

## Sources and reproducibility

Read actual LICENSE files through raw GitHub URLs at the revisions above. Inspected
ZENINXOP's recursive tree (only `ReadMe.md` and `Vspace-ze.zip`) and ZIP member list.
GitHub `/commits?per_page=1` supplied exact revisions/dates; `/issues?state=open` and
issue searches supplied issue state. Public source was downloaded to a temporary audit
directory only; executable integration is permitted only after THIRD_PARTY registration.

The original upstream FBlackBox master currently contains documentation but no engine
source, so it is not a build candidate. This is an additional observation, not a fourth
engine compared.
