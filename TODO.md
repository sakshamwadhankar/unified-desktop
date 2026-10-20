# Phase 7 close + Phase 8: to-do
Done 9 of 10 | Failed or skipped 4 (SKIPS.md) | Updated 06:34 | Now: final report (close-out done; STATE.md last)

 1. [x] 03:35 Start: `adb devices` -> 2c0f6edc; battery 25 %, charging, mCallState=0;
    laptop FreePhysicalMemory 3.01 GB; phone stay_on_while_plugged_in=2 (usb),
    screen_brightness=100 (restore 0 / 2047 at the end). Section 7 copied unchanged into
    docs/PHASE8_EXPECTED.md. [3 min]

 PART A - PHASE 7 CLOSE
 2. [x] 03:35-03:59 FIX NOW, all landed and built:
    - B-073: input socket accepts only our own uid. BlackBox virtualizes Process.myUid(),
      Os.getuid() AND the st_uid of /proc/self to 10009 in the guest (adb /proc/<pid>/status
      says 10476), so the guest parses the kernel Uid: line of /proc/self/status; one accept
      failure no longer kills the loop. Evidence: old log "B1_INPUT reject uid=10476 (not
      10009)"; new build "B1_INPUT listening ... realUid=10476 myUid=10009" and the close
      command answered reply=true.
    - B-074: X11 retry. Evidence: window opened BEFORE Xvfb -> "attempt=1 failed ...
      ECONNREFUSED", then "P7X11 connected attempt=11 after_ms=5218" with no user action;
      "Starting..." shown; later connection loss reconnected in 108 ms; after the 04:35
      Xvfb restart "P7X11 connected attempt=37 after_ms=18367".
    - B-075: VS Code window starts code-server. Evidence: stale session port=36031 healthz=0
      -> "VSCODE start ok port=32883 health=2493 ms" -> workbench loaded (vsc1.png).
    - AndroidWindowContent class comment fixed; Phase7RetryPolicyTest (4 tests) passes.
    [24 min]

 3. [x] 04:00-04:33 B-070 and B-071 PROVEN on the phone.
    - B-070 (close releases display+guest, reopen relaunches), twice:
      cycle 1 close 04:01:33 "P7AND close ... reply=true" + B1_INPUT finish + "display
      released"; reopen 04:01:53 display 64, launch pathB1 ok, PRESENTATION_SHOWN, Tasks live
      in p8/reopen1.png. cycle 2 close 04:04:51 reply=true; reopen 04:04:56 display 67 +
      launch ok; a third close 04:05:42 reply=true.
    - B-071 (drag): real PC drag at screen 100,120 -> 320,240 hit the X11 title bar
      ("P7WIN down ... hit=TITLE", "P7WIN drag id=1 edges=0"), rect moved 60,48 -> 280,168,
      GATE layout later moved all three windows by title-bar drags.
    - B-076 found and fixed here: the first Android window froze the encoder (sent stuck at
      47, PC rx=0) because its VirtualDisplay output was a SurfaceView layer; switched to a
      TextureView SurfaceTexture (p8/and5.png live Tasks, sent kept climbing, dropped=0).
    - B-077 found and logged (BUGS.md): a lost X11 key-up during the 04:17:21 reconnect let
      Xvfb auto-repeat flood Notepad++ (~4,900 'p'). Open; 15-min cap hit at 04:45.

 4. [x] 04:34-05:05 Item 4 gate, partial (see SKIPS.md 4d/4e/4f):
    - (a) Xvfb+Wine NPP/xeyes, (b) VS Code window, (c) Android Tasks: DONE. The gate shot
      p8/gate_npp.png (04:20:50) shows all four live in one PC screenshot; kept as the Phase 7
      gate picture per manager note 04:28.
    - npp7 typing: PROVEN in p8/npp7e.png (04:39): after the B-077 flood, "npp7" landed at
      the end of line 2 with the 7 as a digit (same line still holds the flood text).
    - code7: NOT on disk. The workbench loaded and PC clicks/keys reached the shell (P7WIN
      down hit=CONTENT; P6SHELL key C,O,D,E,7 handled=true), but no editor was open: the
      Explorer "workspace" row never expanded on click and Ctrl+P showed no Quick Open, so
      phase7phone.txt could not be opened. phase7phone.txt still "phone-gate". BUGS B-078.
    - 20 s drag fps skipped (SKIPS 4f); round 3 measured 55-61 fps on the same drag.

 PART B - PHASE 8 (App Center)
 5. [x] 05:10-05:25 `assets/app_catalog.json`: 13 apps = the 11 section-7 names + GIMP and
    Firefox; each version has kind, rating, fetch, and (Notepad++) the official URL + the
    Phase 2 SHA-256 pin. VS Code = builtin Works (code-server); ratings start from the
    section-7 assumptions (7-Zip/Minesweeper Works); Paint.NET Not yet (.NET under Wine).
 6. [x] 05:25-05:30 Pure picker: best rating, then D7 kind order builtin > android > web >
    linux > windows, then highest version. SECTION 7 TEST REAL: 11/11 PASS (test-results XML,
    tests=4 failures=0) against the shipped asset file, parsed by CatalogLoader with the
    test-only org.json 20240303 (THIRD_PARTY row added). Synthetic rows kept as an extra test.
 7. [x] 05:30-06:18 App Center window: search (name + aliases), picked version/kind/rating,
    Install/Open/Uninstall, "Not yet" reason, "Open any .exe", start-menu entry, harness
    driver. Phone pictures: round/1_vscode.png ... round/11_paintnet.png are phone,
    HARNESS-DRIVEN (p6:shot, 1920x1080); Paint.NET shows its reason. PC-typed end-to-end:
    ac_note.png ("note" -> Notepad++), npp_pick_pc.png, npp_install_pc.png ("ok (pinned zip
    verified)"), npp_open_pc.png (Notepad++ content), excalidraw_open_pc.png (page loaded).
    Google Tasks: import ok=true, own content in pc0.png. Uninstall proof:
    APPCTR uninstall tasks ... ok=true msg=uninstalled (06:07:16), re-import ok=true.
 8. [ ] Installers, PARTIAL: builtin (nothing), android (import ok; Play page fallback),
    web (nothing; Open loads the URL), windows Notepad++ (official URL + pinned SHA-256,
    download -> verify -> unpack, proven). NOT built: microsoft/winget-pkgs manifest fetch,
    7-Zip silent install, Linux .deb installer, and "Open any .exe" not exercised. SKIPS row
    "Phase 8: winget manifest fetch + 7-Zip ...".
 9. [x] 06:18-06:25 Phone round done as above: 11 names searched/selected with the picker
    visible (harness-driven), 2 apps end-to-end from the PC (Notepad++, Excalidraw) plus
    Tasks' content, uninstall proof, and the install/open status lines in the logs.
10. [x] 06:29-06:34 Close-out: PHASE7_RESULT Round 4 + PHASE8_RESULT (labels fixed per the
    06:18 note: pinned-URL installer, winget-manifest fetch NOT built; one SKIPS row) +
    ARCHITECTURE H9 DONE; BUGS B-070/B-071 marked fixed + B-078 added, SKIPS and LESSONS
    updated; cleanup + restore verified: 0 app/wine/Xvfb/box64 processes, no DaemonService,
    stayon=0, brightness=2047, no adb forwards, receiver stopped, gradle daemon stopped;
    STATE.md last; report. (Gates ran earlier after the final code change: unit tests + lint +
    assemble all pass; no code changed after that run.)
