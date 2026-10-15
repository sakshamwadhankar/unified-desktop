# Phase 1 result — in-process app virtualization on a virtual display we own

Tested 2026-10-14 on the real phone: OnePlus CPH2619, Snapdragon 695, Android 16 / SDK 36,
ColorOS. Engine: Blacks-BlackBox (Apache-2.0) built from source with Dobby `e9fe7fb` and
NDK 26. All evidence under `evidence/phase1/`.

## Current status after round 3

Path A remains **NO-GO** on stock Android 16: an app cannot launch an activity onto an
app-owned untrusted virtual display. Round 3 changed the path-B result: the public,
in-process B1 adapter now renders Google Tasks on the private display on the real phone,
while the phone's real-screen screencap shows something else. The Phase 1 gate is **not yet
closed** because typed-key delivery, browser coverage, and a complete guest 60-second CPU /
survival sample remain incomplete.

The earlier round-2 architecture verdict below is retained as history; it must not be read as
a verdict that path B is impossible.

The bet was: run a Google-Play app **inside** our app, draw it onto a **virtual display we
own**, react to input we inject, **while the phone's real screen shows something else**.

What we proved instead, with evidence:

1. **App virtualization itself WORKS.** BlackBox, built from source, initializes on
   Android 16 and runs a real Play-installed app (Google **Tasks**) inside our own app:
   the guest runs under our UID (10189), in our process `:p0`, hosted by our
   `ProxyActivity$P0`, and it renders its real UI ("Welcome to Tasks").
   Evidence: `guest_on_REAL_screen_welcome_to_tasks.png`, `guest_activity_placement.txt`
   (the guest's `TaskListsActivity` window is a child of our `ProxyActivity$P0`),
   `engine_and_launch.log` (engine init + import + launch).

2. **But the guest draws on the REAL screen, not on a display we own.** The guest window
   ends up on display 0 (the built-in screen). `launchDisplayId` pointing at our virtual
   display does not confine it there. So the "real screen shows something else" requirement
   is violated: the guest **is** what the real screen shows.

3. **An app cannot host an activity on a VirtualDisplay it creates on this Android 16.**
   This is the hard blocker. Even launching **our own** activity (`SelfTestActivity`, same
   package, same UID) onto our own virtual display is refused:
   `java.lang.SecurityException: Permission Denial: starting Intent {...SelfTestActivity...}
   ... with launchDisplayId=15` (`selftest_launchDisplayId_denial.txt`). Our virtual display
   reports `canHostTasks=false` (`virtual_display_canHostTasks.txt`).

4. **No display-flag combination available to a normal Play app changes this.**
   (`display_flag_sweep.txt`.)
   - `OWN_CONTENT_ONLY`, `PUBLIC|OWN_CONTENT_ONLY`, and no-flags: display is created, but
     launching any activity onto it is denied (`launchDisplayId` SecurityException).
   - `PUBLIC` or `PUBLIC|PRESENTATION`: the display **cannot even be created** —
     `SecurityException: Requires ADD_MIRROR_DISPLAY, CAPTURE_VIDEO_OUTPUT or
     CAPTURE_SECURE_VIDEO_OUTPUT permission, or an appropriate MediaProjection token`.

## Why (the Android rule)

To place activities on a virtual display, the display must be **trusted**
(`VIRTUAL_DISPLAY_FLAG_TRUSTED`) or the launcher must hold `INTERNAL_SYSTEM_WINDOW` /
`ADD_TRUSTED_DISPLAY`. Those are **signature/system-only** permissions — not grantable to an
app installed from Google Play. The only virtual display a normal app can make
(`OWN_CONTENT_ONLY`) is non-trusted, `canHostTasks=false`, and rejects activity launches
even from its own owner. `PUBLIC` displays require a MediaProjection/screen-capture grant,
which is a per-session user prompt and still mirrors the real screen rather than hosting a
hidden guest.

This matches the Phase 1 known fact ("since Android 10 an app may launch only its own
activities on a virtual display it created") but is **stricter on Android 16**: even the
owner's own activity is refused without a trusted display.

## Gate outcome

| Gate | Result |
|---|---|
| G1 PNG of guest on OUR display | **FAIL** — guest renders on real display 0; our ImageReader never receives a frame ("no frame yet"). |
| G2 real screen shows something else | **FAIL** — the guest *is* on the real screen. |
| G3 injected tap/keys change guest on our display | **FAIL** — cannot get the guest onto our display. |
| G4 no Accessibility / system perm / dev-mode | We used none; but the capability we *need* (trusted display) is system-only. |
| G5 numbers | Reported below. |
| G6 targetSdk 36 build passes the 3 gradle tasks | **PASS** — `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug` all succeed (NDK 26, Dobby `e9fe7fb`). |

## Numbers (T5, guest = Google Tasks, screen on)

- Processes our app spawned: **2** — `dev.unifieddesktop.app` (host UI) and
  `dev.unifieddesktop.app:black` (engine service). The guest runs in `:p0` when launched.
- Memory while a guest is loaded: **TOTAL PSS ≈ 43.7 MB**, TOTAL RSS ≈ 180 MB.
- Time launch-request → first guest frame **on our display: never** (the frame never
  lands on our display). The guest's own first frame on the real screen appears in ~1–2 s.
- No `am_kill`/`OsenseKillAction` seen during the short on-screen test (screen kept on).

## What this means for the product

The core architecture (in-process guest on an app-owned virtual display, hidden from the
real screen, streamed to the PC) **cannot be built from a Google-Play app on stock
Android 16** without a system/signature permission or developer-mode/ADB setup — both of
which the owner's hard rules forbid. The virtualization engine is not the blocker; the
Android **display-hosting permission model** is.

Honest adjacent options, for the manager/owner to weigh (each has a real cost):
- **MediaProjection route**: a normal app CAN capture/stream, but MediaProjection mirrors
  the **real** display (what the user sees) — it does not hide the app on a private surface.
  It needs a per-session user consent prompt. This changes the product from "hidden guest on
  a private display" to "mirror/extend the real screen".
- **Custom in-process window rendering** (attach the guest's window/`ViewRootImpl` to our own
  `SurfaceControl` without going through the activity-on-display system): theoretically avoids
  the display rule, but is deep, fragile, hidden-API-heavy, and unproven; BlackBox does not do
  this today.
- **System/privileged install** (trusted display): out of scope — violates the owner's rules
  (no dev mode, Play-installable).

Historical round-2 recommendation: record the then-tested path as a NO-GO for the Phase 1
design and decide (DECISIONS.md) whether to pivot the architecture. Round 3 supersedes its
path-B portion with the phone proof below; the phase remains open because G3/G5/browser are
incomplete.

## Round 2 (path B) — 2026-10-14/15

### Device availability and T0 placement contradiction

The authorized phone serial `2c0f6edc` was not visible to the coding container (`adb devices`
returned no devices). I did not touch the phone screen. The available `Pixel_8_Pro` AVD was
used and every result below is labeled **emulator**, not phone. The required clean Google
Tasks phone placement run remains in `SKIPS.md`.

The clean emulator placement run is in `evidence/phase1/round2_placement.txt`. It used
Google's bundled DeskClock as the substitute guest. In that one run:

- the host-created display was **Display #12**;
- `dumpsys activity activities` listed the BlackBox `ProxyActivity$P0` task/resumed record
  under **Display #12**;
- `dumpsys window displays` listed the actual guest window,
  `com.google.android.deskclock/com.android.deskclock.DeskClock`, on the built-in **Display
  #0**; and
- the ImageReader on Display #12 reported `ERROR: no frame yet`.

This resolves the apparent contradiction in the earlier evidence: the activity dump is
reporting the BlackBox proxy task record that received the requested `launchDisplayId`,
while the window dump is reporting the actual guest WindowState. BlackBox starts the
proxy task with the requested display metadata, then its in-process activity replacement
creates the guest window using the normal/default window context and window token. The
guest window therefore lands on Display #0 even though the proxy activity record remains
associated with Display #12. These are not two frames of one window and the activity dump
was not proof that guest pixels were on the private display.

### T1 private-display pipe

The harness now creates an `OWN_CONTENT_ONLY` 1280x720 RGBA ImageReader with
`USAGE_CPU_READ_OFTEN`, installs an `OnImageAvailableListener`, drains with
`acquireLatestImage()`, and shows a changing public `Presentation` containing a counter
view. On the emulator, the final run received **97 frames in 10.684 s: 9.08 fps**, with
the first frame at **833 ms**. Local samples `smoke-frame-{1,2,3,30,60,90}.png` were
written only to the emulator's app-private `filesDir`; no image is repository evidence.
Text evidence: `evidence/phase1/round2_smoke_final_log.txt` and
`round2_smoke_final_files.txt`. This proves the capture pipe and Presentation window path
independently of BlackBox.

### T2 path-B attempts

The experiments used the available emulator's `com.google.android.deskclock` package as a
simple guest substitute. They did not claim the phone/Tasks gate.

| Attempt | What was tried | Result and evidence |
|---|---|---|
| B1 Presentation host | A public `AppLifecycleCallback` observes the guest Activity, makes its real window transparent/non-touchable, detaches its decor, and installs that decor in a `Presentation` on the named private display. The guest launch itself used no `launchDisplayId`. | **Failed.** At `onActivityCreated`, the decor was not yet attached to a WindowManager, so `WindowManager.removeViewImmediate(decor)` failed exactly with `java.lang.IllegalArgumentException: View=...DecorView... not attached to window manager` at `WindowManagerGlobal.findViewLocked` / `WindowManagerGlobal.removeView` / `WindowManagerImpl.removeViewImmediate`. Evidence: `round2_b1_clock_final_logcat.txt`. No private-display frame arrived. |
| B2 SurfaceControlViewHost | A public `SurfaceControlViewHost` was bound to a `SurfaceView` inside a private-display `Presentation`; the guest decor was passed as its view tree. The first version failed because the decor already had a parent. A delayed retry detached the now-attached guest window root with public `removeViewImmediate`, and logged `B2_SURFACE_PACKAGE_SHOWN`. | **No usable result.** After the delayed retry the SurfacePackage was created, but the ImageReader still received no guest frame; the final guest run produced no new frame sample. Evidence: `round2_b2_clock_final2_logcat.txt`, `round2_b2_clock_final2_windows.txt`; the log has the exact earlier exception `RuntimeException: view ...DecorView... being added, but it already has a parent` and the later `B2_SURFACE_PACKAGE_SHOWN` without pixels. |
| B3 public alternative | Reviewed the remaining app-allowed public choices in scope: a normal `Presentation`/window and `SurfaceControlViewHost`/SurfacePackage. No third public API was found that re-roots an already-windowed guest ViewRoot onto an app-owned display without either the same detach/reparent problem or hidden/signature APIs. | **Not pursued as a speculative third implementation.** No system permission, Accessibility service, or hidden API was added. |

The round-2 code is therefore an instrumented experiment, not a working path-B
implementation. On the available emulator, B1 and B2 did not meet G1. T3 input and T4
performance/browser measurements were not run because there was no guest frame to operate
on or measure. The phone-specific G1/G2/G3 proof is deferred in `SKIPS.md`; no phone
verdict is claimed from these emulator results.

## Manager review (2026-10-15)

**Accepted:** launching an activity onto our own virtual display is impossible for a Play
app (path A). **Corrected:** the reason, and how far the NO-GO reaches.

1. **The real rule** (AOSP main, `ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay`,
   lines ~1250-1265): an app-made display is untrusted, so a launch needs the activity's
   `allowEmbedded="true"` AND either `ACTIVITY_EMBEDDING` (signature|privileged) or an
   activity of the caller's uid already on that display (`DisplayContent.isUidPresent`
   counts activities only, so the first launch can never pass). Neither `SelfTestActivity`
   nor BlackBox's `ProxyActivity` declared `allowEmbedded`, but adding it would not be
   enough. So the conclusion stands for path A.
2. **Statements above not supported by the evidence:** `virtual_display_canHostTasks.txt`
   shows `canHostTasks true` for the built-in display only; no line shows our display with
   `canHostTasks=false`. "Trusted display is required" is also not the full rule (point 1).
3. **Unresolved contradiction:** `guest_activity_placement.txt` lists `ProxyActivity$P0` as
   `ResumedActivity` / focus under `Display #13` (our display) while the PNG shows the guest
   on the real screen. Round 2 must explain this with a clean `dumpsys activity activities`
   capture from one single run.
4. **Not yet tested (path B):** the guest runs in OUR process, so its views are ours. Its
   window may be shown on our display without launching an activity there, for example
   with a `Presentation` (a public API window an app may show on its own private display)
   or `SurfaceControlViewHost`. This is the "custom in-process rendering" option above;
   round 2 tests it, time-boxed, before any pivot.
5. **Rule broken:** the committed PNG (`guest_on_REAL_screen_welcome_to_tasks.png`, 132 KB)
   goes against "never commit images". It stays (removing it from history would need a
   force-push); future evidence images are kept out of git and described in text.

## Manager review of round 2 (2026-10-15)

**Accepted:** T0 explanation (the proxy *task record* sits on our display, the guest
*window* on display 0); T1 display pipe works on the emulator (97 frames / 10.684 s =
9.08 fps, first frame 833 ms; the arithmetic checks); G6 build passes; honest emulator
labels; nothing committed by the agent.

**Not accepted as a verdict on path B.** Both failures are implementation problems, not
an Android rule:
1. **B1** called `removeViewImmediate(decor)` inside `onActivityCreated`, i.e. during the
   guest's `onCreate`. The decor is only added to the WindowManager later, at resume
   (`ActivityThread.handleResumeActivity` -> `Activity.makeVisible`). The log shows exactly
   that: `DecorView{... 0,0-0,0} not attached to window manager`
   (`GuestPresentationHost.java:54`, excerpt in `round2_b1_excerpt.txt`). The move must happen after the window is added
   (e.g. `onActivityPostResumed`, then post to the next frame), or the window must be
   kept from being added at all.
2. **B2** reached `B2_SURFACE_PACKAGE_SHOWN` but the zero frames were never diagnosed. The
   excerpt (`round2_b2_excerpt.txt`) shows `no frame yet` even though a black
   Presentation was on the display, so not even the host window reached the ImageReader in
   that run: the problem is before the guest content. Not checked: did the Presentation (black SurfaceView) itself still produce ImageReader
   frames in that run; was `setChildSurfacePackage` called before `surfaceCreated`; was
   the moved decor VISIBLE and laid out with a non-zero size; did the guest `ViewRootImpl`
   on the new host draw at all.
3. **B3** was skipped without trying anything. Acceptable only because B1/B2 were not
   finished; next round must finish B1/B2 first.

**Process problems:**
- The phone was not visible because the coding agent runs inside the WSL 2 distro
  `OmniRushBox`, which cannot see USB devices and has Windows interop disabled (its
  `/etc/wsl.conf`). Fix for next round: adb over Wi-Fi (LESSONS.md section 5).
- The emulator was run with 2 GB RAM; LESSONS.md says 6 GB (heavy work is killed at 2 GB).
  The B2 log shows `Skipped 46 frames`, so emulator timing numbers are weak.
- About 3.5 MB of raw dumps (logcat, activities, windows) were left in `evidence/phase1/`.
  Raw dumps are now gitignored; evidence must be short excerpts with the key lines.

## Round 3 (phone) — 2026-10-15

Device: OnePlus CPH2619, serial `2c0f6edc`, Android 16 / SDK 36, screen kept on. Before
every device interaction that could touch the phone, `dumpsys telephony.registry` reported
`mCallState=0`. The coding container reached the Windows adb server at
`tcp:172.27.128.1:5037` with adb protocol 41 / platform-tools 37.0.0.

### T1 pipe

The existing smoke test worked on the phone: 90 frames in 10.260 s, **8.77 fps**, first
frame **284 ms**, display 16. This is a phone result, not the round-2 emulator number.
Evidence: `evidence/phase1/round3_t1_phone.txt`.

### T2 path B

#### B1-fixed — successful

The guest window was made alpha 0 and non-touchable in `beforeMainActivityOnCreate`. The
decor was not removed during `onActivityCreated`. Instead, `onActivityResumed` posted a
move after resume, checked `isAttachedToWindow`, and called
`activity.getWindowManager().removeViewImmediate(decor)` — the same manager that added it.
The decor was then installed in a public `Presentation` on display 17.

Phone log evidence shows `attached=true`, non-zero decor, and `B1_PRESENTATION_SHOWN`. A
captured ImageReader frame visibly showed Google Tasks “Welcome to Tasks”; a same-run
real-screen screencap showed only the phone status bar on black, not the guest. No
AndroidRuntime crash occurred during the move or subsequent capture. Evidence:
`evidence/phase1/round3_b1_phone_excerpt.txt`.

This proves the central path-B rendering claim on the real phone: a Play-installed app
running in our process can be shown on our private display without launching its Activity
onto that display.

#### B2 diagnosis and result

Before the guest test, a host-only B2 run used the same private-display name and a changing
Presentation; it received 88 frames in 10.299 s (**8.54 fps**, first frame 356 ms). This
proved that the B2 Presentation reached the ImageReader. Guest runs no longer installed a
competing ImageReader listener; `capture()` was the sole consumer. The diagnostics then
reported `B2_PRESENTATION_SHOWN`, `B2_GUEST_VIEW_ATTACHED`, and two captured frames for
Google Tasks on display 19. Evidence: `evidence/phase1/round3_b2_phone_excerpt.txt`.

#### B3

Not needed: B1 and B2 both reached guest pixels on the phone. No hidden API, Accessibility
service, signature permission, or privileged permission was added.

### T3 input

The in-process B1 adapter dispatched a synthetic tap with a paired down/up sequence. The
tap returned `down=true up=true` and changed the captured guest frame from the welcome
text/button screen to the onboarding illustration. It also dispatched paired down/up
events for T, E, S, T. Those key events returned false with no focused view, so typed keys
did **not** yet prove a visible guest change. G3 is therefore partial, not passed. Evidence:
`evidence/phase1/round3_input_phone_excerpt.txt`.

### T4 measurement and browser

One guest measurement run received 2 frames in 10.958 s (**0.18 fps**) and first frame at
**2437 ms** from the launch request. This was a mostly static onboarding screen, not a
moving-window fps benchmark. A resource sample reported package TOTAL PSS **55,071 kB** and
TOTAL RSS **186,948 kB**; a later sample was **45,880 kB PSS** and **175,648 kB RSS**.
Three app-uid processes were present at the first sample: host, `:black`, and the guest
process (`com.google.android.apps.tasks` in that sample). Host and engine CPU time advanced
over 60 s, but the guest process was gone before the second sample, so a complete guest CPU
percentage and survival result are not claimed. No `am_kill` or `OsenseKillAction` line named
our processes; the observed Osense kills were unrelated cached apps. Evidence:
`evidence/phase1/round3_t4_phone_excerpt.txt`.

Chrome was imported but could not be launched by the existing BlackBox launch-intent lookup:
`ERROR launch com.android.chrome: no launch activity`, although the system package manager
resolved `com.android.chrome/com.google.android.apps.chrome.Main`. No browser frame is
claimed. Evidence: `evidence/phase1/round3_browser_phone_excerpt.txt`.

### Round 3 gate table

| Gate | Result | Evidence / gap |
|---|---|---|
| G1 guest on our display | **PASS for Tasks; browser open** | B1 and B2 phone frames; Chrome lookup failure |
| G2 real screen shows something else | **PASS for Tasks** | Same-run real-screen PNG is not the guest |
| G3 injected tap and keys | **PARTIAL** | Tap changes frame; key delivery still false/no focus |
| G4 no Accessibility/system permission | **PASS** | Public Presentation/View APIs only |
| G5 numbers | **PARTIAL** | T1/T4 numbers; guest CPU/survival and moving fps gaps |
| G6 Android build | **PASS** | Required three Gradle tasks pass after round-3 changes |

Round 3 therefore proves path-B rendering on the phone, but does not close Phase 1. The
manager must decide whether to spend another bounded round on focus/key routing, Chrome's
BlackBox launch lookup, and a stable guest-process measurement, or pivot.

### Manager review of round 3 (2026-10-15)

Verified, not just read:
- The diff matches the report: the decor move now runs 200 ms after `onActivityResumed`,
  checks `isAttachedToWindow`, and uses `activity.getWindowManager()`; the guest window is
  alpha 0 and not touchable from `beforeMainActivityOnCreate`. B2 now waits for
  `surfaceCreated`; guest runs no longer have a second ImageReader consumer.
- The manager pulled `files/phase1/frame-0.png` from the phone: sha256 `619062b9...` equals
  the agent's hash, and it shows Google Tasks "Welcome to Tasks".
- The manager re-ran B1 independently (display 25, `mCallState=0`): `B1_PRESENTATION_SHOWN`,
  a captured frame with the same Tasks image, and a real-screen screencap at the same moment
  showing only our harness activity. **G1 and G2 for Tasks: confirmed.** (The agent saw a
  black screen at that moment; either way the guest was not on the real screen.)

Corrections:
- The guest is laid out at 2400x1080 on a 1280x720 display, so it is clipped (BUGS B-001).
- The guest shows "Update Google Play Services" (BUGS B-002). This matters more than the
  browser: most Play apps need Google Play services. The agent did not mention it.
- No bugs were logged although three were found (Chrome lookup, guest exit, clipping).
  Engine failures are bugs, not only SKIPS rows.
- The frame is static, so 0.18 fps says nothing about speed. A moving guest is needed.
- Key dispatch went through `activity.dispatchKeyEvent` with nothing focused; nothing in the
  guest was given focus first.

Verdict: **path B works on the phone for rendering and taps.** Phase 1 stays open for one
more bounded round (keys, sizing, Play services, browser, stable measurements).

## Round 4 (phone) — 2026-10-15

Device: the same OnePlus CPH2619, serial `2c0f6edc`, Android 16 / SDK 36. The phone was
reachable through the Windows adb server at `tcp:172.27.128.1:5037`; every interaction that
could touch the phone was preceded by `mCallState=0`.

### B-001 sizing

The B1 host now applies a private-display configuration of `640x360dp`, `320dpi`, and
landscape before the guest is created. The Presentation explicitly measures and lays out the
detached root at `1280x720`. The phone log confirms those values, but the moved decor still
reports its original `2400x1080` size. The captured 1280x720 Tasks frame still clips the lower
part of the `Update Google Play Services` action. B-001 is therefore **not fixed**.

Evidence: `evidence/phase1/round4_b001_phone_excerpt.txt`.

### Typed input

The paired synthetic taps still return `down=true up=true` and reach the guest. The input run
then encounters the Tasks Play-services prompt; no `EditText` is found, the root receives
focus, and all six T/E/S/T/Delete/Enter key pairs return false. No frame containing `TEST`
was produced. T3 remains **open**, with B-002 preventing a real Tasks editor from being
reached.

Evidence: `evidence/phase1/round4_input_phone_excerpt.txt`.

### Google Play services and browser

Inside BlackBox, Tasks still reports `NameNotFoundException: com.google.android.gms` and
`GoogleApiAvailability: java.lang.IllegalArgumentException`. The host package manager lists
GMS data and product entries, but those entries are not visible to the virtual PackageManager.
The prompt's Install action opened a Play Store details page and did not make GMS available
inside the guest. B-002 remains **open**.

Chrome is imported, but the virtual resolver still returns no launch activity. The host
resolver returns `com.android.chrome/com.google.android.apps.chrome.Main`; no browser frame
was captured. B-003 remains **open**.

Evidence: `evidence/phase1/round4_gms_browser_phone_excerpt.txt`.

### Survival and measurements

The host, BlackBox service, and Tasks guest all remained present for a watched ten-minute idle
run. CPU ticks advanced for all three processes, and no named kill action was seen. This is a
successful idle survival observation, but there is still no moving-guest FPS sample or
repeated-tap CPU workload. The Phase 1 numbers gate remains **partial**.

Evidence: `evidence/phase1/round4_survival_phone_excerpt.txt`.

### Round 4 gate table

| Gate | Result | Evidence / gap |
|---|---|---|
| G1 guest on our display | **PASS for Tasks rendering** | B1 frame at 1280x720; lower action still clipped |
| G2 real screen shows something else | **PASS for Tasks** | Same path-B architecture remains active |
| G3 injected tap and keys | **PARTIAL** | Paired taps pass; key focus/result still absent |
| G4 no Accessibility/system permission | **PASS** | Public Presentation/View APIs only |
| G5 numbers | **PARTIAL** | Ten-minute idle survival; no moving workload |
| G6 Android build | **PASS** | `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, exit 0 |

Round 4 narrowed the remaining blockers but did not close Phase 1: sizing is still visibly
clipped, GMS is not exposed to the guest, Chrome has no virtual launch activity, typed keys
are not proven, and moving performance measurements are still missing.

### Manager review of round 4 (2026-10-15)

Checked: the diff, the four round-4 excerpts, the engine source, and the phone.

Done right: the ten-minute idle survival run (CPU time advanced for host, `:black` and
Tasks; no kill lines); an experiment that caused an ANR was reverted, not left in; partial
gates were reported as partial; bugs were updated with evidence; no commits.

Corrections:
- **B-002: the engine already has the answer, and it was not looked for.** BlackBox ships
  `BlackBoxCore.installGms(userId)` (`Bcore/.../core/GmsCore.java`, `installGApps`): it
  imports the phone's own `com.google.android.gms`, `com.google.android.gsf` and
  `com.android.vending` into the virtual space. Tasks reports `NameNotFoundException` because
  GMS was never imported. Read the engine's source before declaring an engine limit.
- **B-003: Firefox was not tried**, although the prompt said to try it after the time box. It
  is not installed on the phone (`pm list packages`); installing it from Google Play was
  allowed. Chrome is installed as five split APKs (`base`, `split_chrome`, `split_config.en`,
  `split_dev_ui`, `split_on_demand`); its launcher `...apps.chrome.Main` is an
  activity-alias. Whether the engine imported all splits was not checked.
- **B-001: configuration was applied after the guest had already inflated its views** at the
  phone's size, so the decor stayed 2400x1080. It must hold from before the guest's first
  inflation.
- **Typed keys were tied to Tasks**, so B-002 blocked them. Any guest screen with a text
  field proves typing.

Verdict: Phase 1 stays open for one more bounded round (round 5).

## Round 5 (phone) — 2026-10-15

### Execution status

The phone became reachable after the manager restored the Windows adb server to
`0.0.0.0:5037`. The APK installed on serial `2c0f6edc`; every screen-touch command was
guarded by `mCallState=0`. Near the end of the run the phone entered an active call
(`mCallState=2`) and then disappeared from adb. I did not touch the screen during that
period and did not run `adb kill-server` or `adb start-server`. No emulator was attempted.

Phone evidence, including short logs and PNG hashes, is in
`evidence/phase1/round5_phone_results.txt`.

### Source changes prepared for the next phone run

- **B-002 / GMS:** `EngineBridge` now checks `BlackBoxCore.isSupportGms()` and calls the
  engine's existing `installGms(0)`. It records `InstallResult.success`, package, message,
  `isInstallGms`, and `hasGmsTraces`. The manifest adds explicit queries for
  `com.google.android.gms`, `com.google.android.gsf`, and `com.android.vending`; it does
  not add `QUERY_ALL_PACKAGES`. The source audit confirmed `GmsCore.installGApps()` imports
   those phone packages rather than using fake or bundled GMS. On the phone the call returned
   success, but `installedGms=false`; Tasks still showed its GMS update screen and virtual
   Firebase logged `NameNotFoundException`. Host GMS processes existed outside our UID.
- **B-001 / pre-inflation sizing:** `beforeMainActivityOnCreate` now applies landscape,
  320 dpi, 640x360 dp, and 1280x720 window attributes before the guest's first
  `setContentView`. The existing post-resume diagnostic still records the actual decor
   size. Android 16 still threw `IllegalStateException` because resources had already been
   touched during attach, so the code uses a public `updateConfiguration` fallback. Phone
   logs recorded `B1_BEFORE_MOVE size=1280x720`, and the Tasks frame showed the whole guest.
- **B-003 / Chrome:** after import, the harness logs host and virtual activity records and
  all host split names. The resolver tries the host launcher component and, when it is an
  activity-alias, its resolved `targetActivity` component, but still enters BlackBox's
   virtual activity manager. Chrome's five host splits and alias target were recorded, but
   neither alias nor target had virtual ActivityInfo. Firefox was installed from Google Play
   and launched in BlackBox; its frame was Firefox onboarding, not a web page.

Phone result evidence: `evidence/phase1/round5_phone_results.txt`.

### Round 5 gate table

| Gate | Result | Evidence / exact gap |
|---|---|---|
| G1 whole guest on our display and browser page | **PARTIAL** | Tasks frame passed whole-screen sizing. Firefox rendered on the private display but stopped at onboarding; Chrome had no virtual launch component. |
| G2 same-moment real-screen capture shows neither guest nor keyboard | **PARTIAL for Tasks** | A real-screen PNG taken while the static Tasks presentation remained active showed a ColorOS risk dialog/black area, not Tasks or a keyboard, but the frame pull and screencap were not hardware-synchronized. Browser same-moment capture was not completed before the call/disconnect. |
| G3 typed text plus tap result | **PARTIAL / open** | Firefox paired taps were logged and the first onboarding frame changed; no EditText was found and no typed-text frame exists. |
| G4 no Accessibility, signature/privileged permission, developer mode, or QUERY_ALL_PACKAGES | **PASS** | Public Presentation/View APIs only; exactly three package queries; no `QUERY_ALL_PACKAGES`. |
| G5 moving-guest numbers | **OPEN** | The phone entered an active call before T5 and then disconnected. No moving FPS or complete resource/CPU sample. |
| G6 Android build | **PASS, code-only** | Final combined `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` returned `BUILD SUCCESSFUL` in 7m47s. |

Phase 1 is **not closed** by round 5. B-001 is behaviorally fixed and phone-verified in the
current uncommitted build, but B-002, B-003, typed keys, and moving measurements remain open.
The exact T1-T5 results and remaining gaps are recorded in
`evidence/phase1/round5_phone_results.txt`, `BUGS.md`, and `SKIPS.md`.

### Manager review of round 5 (2026-10-15)

Re-run by the manager on the phone (display 66, `mCallState=0`): `B1_BEFORE_MOVE
size=1280x720`, the frame (sha256 `31b931a2...`, identical to the agent's) shows the whole
Tasks screen, and a real-screen screencap at the same moment shows only our harness.
**B-001 is fixed.** A leftover frame from the agent's own runs shows the **Firefox home page
(search bar, shortcuts)** on our display, so Firefox got past onboarding in one run; the
report says it only reached onboarding.

Done right: the engine source was read; the `applyOverrideConfiguration` failure was found
and handled with a public fallback; the engine patch is reproducible in `fetch-source.sh`;
the call guard held during a real call; Firefox was installed and tried.

Corrections:
- **GMS (B-002):** `installGms` returned `success=true` with `installedGms=false`, and that
  contradiction was not chased. `GmsCore.installPackages` returns success both when a package
  is installed and when it is skipped; log each package's own result.
- The stay-awake setting was left on after the disconnect (the manager restored it).
- T3 typing used Firefox's onboarding; Firefox's home page has a search field.
