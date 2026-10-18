# Phase 3 phone gate (2c0f6edc)

Done 8 of 8 | Updated 16:47

HARD STOP: 35 min after start (go straight to step 8). No rebuild unless step 2 proves needed.

- [x] 1. Kill emulator-5554, check free RAM [2 min] DONE 16:18 - emu kill OK; free RAM 4,789 MB;
      phone mCallState=0, 1080x2400.
- [x] 2. install -r -t app-debug.apk on 2c0f6edc; sha256 nativeLibraryDir/libnode.so vs
      jniLibs arm64-v8a; stayon true; set-standby-bucket active; start HarnessActivity [5 min]
      DONE 16:18 - install Success; phone libnode sha 3db11b8a... == jniLibs (83,296,208 B,
      no B-010 reinstall); stayon+bucket+HarnessActivity set.
- [x] 3. phase3x node -> cs-fetch -> cs-start ("CS_START OK"); node + cold-start ms [10 min]
      DONE 16:30 - node v24.18.0 arm64 exit 0 (wall 313-314 ms); Wi-Fi download ~45 KB/s
      (~43 min ETA) REPLACED by laptop-staged byte-identical pin (USB 22.3 MB/s, 117,176,796 B,
      sha eabd4122... verified on-device + by the app "CSDL cached sha ok"); unpack hung once
      (B-042), retry 39,772 ms, verify files=9134 elf=0 broken=0 links_ok=7/7; cold CS_START OK
      healthz 200 first_response 1,899 ms port 33617.
- [x] 4. workspace/hello.txt; cs-web; cs-gofolder; screencap + PNG header [5 min] DONE 16:32 -
      hello.txt="phase3" via run-as; cs-web workbench DOM at +3 s; gofolder title
      "Welcome - workspace - code-server" (load ms=27958); p3phone-folder.png valid PNG
      (137 80 78 71, 193,758 B).
- [x] 5. Edit+save via taps/IME/Ctrl+S; terminal command files on disk (aarch64); screenshots
      [8 min] DONE 16:41 - explorer tap opened hello.txt (title check); line2 tap + `input text
      phase3-phone` + keycombination 113 47 -> disk phase3+phase3-phone; menu taps
      hBOX(132,225)->Terminal(720,606)->New Terminal(960,149); xterm textarea present;
      echo/pwd files: term.txt=phase3-phone-ok, machine.txt=aarch64, pwd.txt=workspace path
      (first batch lost the "-" in `uname -m` -> empty machine.txt, retyped); PNGs
      p3phone-saved.png (202,034 B), p3phone-terminal.png (244,949 B), both header-checked.
- [x] 6. Warm start ms, PSS, process count, 60 s idle CPU [4 min] DONE 16:43 - warm
      CS_START OK first_response_ms=1887 (STOP survived=0); PSS app 168,147 kB / node 28,766 /
      worker 57,105; family 8 procs (app, :black, + 6 libnode.so); idle node main 0
      utime+stime ticks / 60 s = 0.0% (WebView not attached after warm restart - caveat).
- [x] 7. cs-stop, kill -9 -1, force-stop, stayon false, 0 procs left [2 min] DONE 16:44 -
      CS_STOP survived=0, all killed, ps count 0, stayon false.
- [x] 8. PHASE3_RESULT.md phone section, SKIPS row, BUGS if needed, PNGs to evidence/phase3/,
      STATE.md LAST, report [4 min]
      DONE 16:47 - PHASE3_RESULT.md "Phone gate" section (numbers+PNG names); SKIPS row
      deleted (done); BUGS B-042 added; 3 PNGs copied to evidence/phase3/ (gitignored);
      STATE.md updated (16:47) as the last repo action before this report.

