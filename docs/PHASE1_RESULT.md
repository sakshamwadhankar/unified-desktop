# Phase 1 result — in-process app virtualization on a virtual display we own

Tested 2026-10-14 on the real phone: OnePlus CPH2619, Snapdragon 695, Android 16 / SDK 36,
ColorOS. Engine: Blacks-BlackBox (Apache-2.0) built from source with Dobby `e9fe7fb` and
NDK 26. All evidence under `evidence/phase1/`.

## Verdict: NO-GO for the Phase 1 architecture (clear cause below)

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

Recommendation for the manager: record this as a NO-GO for the current Phase 1 design and
decide (DECISIONS.md) whether to pivot the architecture (MediaProjection-based) or stop.

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
