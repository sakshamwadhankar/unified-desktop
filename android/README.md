# Phase 1 Android harness

This is a diagnostic harness only. It creates a private app-owned virtual display with
`OWN_CONTENT_ONLY`, launches our own control activity there, and captures real
`ImageReader` frames to `filesDir/phase1/*.png`. The screen labels this as control; it is
not evidence that a guest app is virtualized. No Accessibility or `INJECT_EVENTS` API is
used. Input dispatch is restricted to the resumed activity whose display id matches.

## Reproducible build

Use Java 17 and an existing Android SDK 36 installation (the project does not download an
SDK):

```sh
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

`gradlew` downloads Gradle 9.8.1 outside this repository and verifies SHA-256
`dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f`; no wrapper JAR is
stored here. Install the debug APK on a test device, open it, press **Create display**, then
**Launch control** and **Capture PNG**. The host control buttons exercise paired tap/key
dispatch and report actual errors.

For the round-2 display-pipe smoke test:

```sh
adb shell am force-stop dev.unifieddesktop.app
adb shell am start -n dev.unifieddesktop.app/.HarnessActivity --ez smoke true --ei duration 10
adb logcat -s Phase1:I
```

The smoke run creates an `OWN_CONTENT_ONLY` 1280x720 display, shows a changing public
`Presentation`, drains `ImageReader` frames with `acquireLatestImage()`, and reports the
frame count/fps/first-frame time. Samples stay in the app-private `filesDir/phase1/`.
The optional diagnostic flags `--ez b1 true` and `--ez b2 true` exercise the path-B
Presentation and `SurfaceControlViewHost` guest hosts. A working claim requires
`B1_PRESENTATION_SHOWN` or `B2_GUEST_VIEW_ATTACHED` plus captured ImageReader guest pixels.
