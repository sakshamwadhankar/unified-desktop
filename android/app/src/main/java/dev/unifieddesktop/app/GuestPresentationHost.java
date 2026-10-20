package dev.unifieddesktop.app;

import android.app.Activity;
import android.app.Presentation;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.os.SystemClock;

import java.security.MessageDigest;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback;

/**
 * Experimental path-B adapter. It deliberately uses only public window/view APIs: the
 * virtual activity is not launched with a display id; its decor is detached from its
 * original ViewRoot and installed as the content of a Presentation on our private display.
 */
final class GuestPresentationHost extends AppLifecycleCallback {

    /** Live guest activities in resume order; the phase-7 harness closes them headlessly. */
    private static final java.util.List<Activity> GUESTS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    static java.util.List<Activity> liveGuests() {
        return new java.util.ArrayList<>(GUESTS);
    }
    private static final String TAG = "Phase1-B1";
    /** The display the shell's Android window owns; the guest decor is presented onto it. */
    static final String DISPLAY_NAME = "phase1-owned";
    private static final int TARGET_WIDTH = 1280;
    private static final int TARGET_HEIGHT = 720;
    private static final int TARGET_DENSITY_DPI = 320;
    static final String PREFS = "phase1-path-b";
    static final String DISPLAY_ID = "display_id";
    private Presentation presentation;
    /** The live container holding the reparented guest decor, while the Presentation shows.
     * The shell's Android window dispatches its own PC input here (in-process, D3/D5-safe). */
    private static volatile View liveContent;

    static boolean dispatchGuestTouch(int action, float x, float y) {
        View content = liveContent;
        if (content == null) return false;
        long now = SystemClock.uptimeMillis();
        MotionEvent ev = MotionEvent.obtain(now, now, action, x, y, 0);
        ev.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try {
            return content.dispatchTouchEvent(ev);
        } finally {
            ev.recycle();
        }
    }

    static boolean dispatchGuestKey(int keyCode, boolean down, int meta) {
        View content = liveContent;
        if (content == null) return false;
        long now = SystemClock.uptimeMillis();
        KeyEvent ev = new KeyEvent(now, now, down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
                keyCode, 0, meta);
        ev.setSource(android.view.InputDevice.SOURCE_KEYBOARD);
        return content.dispatchKeyEvent(ev);
    }

    // ---------------------------------------------------------------- input bridge
    // The reparented decor lives in the GUEST process; the shell window that shows the
    // display surface lives in the HOST process. Both are the same uid, so PC input crosses
    // on a same-uid abstract LocalSocket (one request per event, reply "1"/"0" = handled).
    // No INJECT_EVENTS, no Accessibility (D3/D5): the event is dispatched to our own views.
    static final String INPUT_SOCKET = "dev.unifieddesktop.guestinput";
    private static LocalServerSocket inputServer;
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    private static volatile boolean rejectedUidLogged;
    /**
     * The app's REAL Linux uid, captured when the server starts. Inside a BlackBox guest
     * process `android.os.Process.myUid()` returns the VIRTUAL uid (measured 2026-10-20:
     * myUid=10009 while the process and our shell are 10476), so the same-uid rule compares
     * against the kernel uid from `Os.getuid()` as well.
     */
    private static volatile int realUid = -1;

    /** Real Linux uid straight from the kernel: the first number of /proc/self/status "Uid:". */
    private static int kernelUid() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader("/proc/self/status"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("Uid:")) {
                    String[] p = line.trim().split("\\s+");
                    if (p.length > 1) return Integer.parseInt(p[1]);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "B1_INPUT uid read failed: " + t);
        }
        return -1;
    }

    /** Called from the guest process when the presentation shows; idempotent. */
    static void ensureInputServer(Context context) {
        synchronized (GuestPresentationHost.class) {
            if (inputServer != null) return;
            if (realUid < 0) {
                // BlackBox virtualizes Process.myUid(), Os.getuid() AND the st_uid of
                // /proc/self (measured: all 10009 in the guest, while /proc/<pid>/status from
                // adb says 10476). The kernel text of /proc/self/status is the last honest
                // source, so parse its Uid line.
                realUid = kernelUid();
            }
            final LocalServerSocket server;
            try {
                server = new LocalServerSocket(INPUT_SOCKET);
            } catch (Throwable t) {
                Log.w(TAG, "B1_INPUT_FAILED listen " + t);
                return;
            }
            inputServer = server;
            Thread th = new Thread(() -> inputLoop(server), "b1-input");
            th.setDaemon(true);
            th.start();
            Log.i(TAG, "B1_INPUT listening " + INPUT_SOCKET + " realUid=" + realUid +
                    " myUid=" + android.os.Process.myUid());
        }
    }

    /**
     * B-073: accept only connections from our own uid (the shell window lives in the host
     * process of the same app), and keep serving after a bad connection. Only a failure of
     * the server socket itself ends the loop, and clears [inputServer] so a later
     * ensureInputServer() can start a fresh one.
     */
    private static void inputLoop(LocalServerSocket server) {
        while (true) {
            LocalSocket s;
            try {
                s = server.accept();
            } catch (Throwable t) {
                Log.w(TAG, "B1_INPUT accept failed - server stops: " + t);
                synchronized (GuestPresentationHost.class) {
                    if (inputServer == server) inputServer = null;
                }
                return;
            }
            try {
                int uid = s.getPeerCredentials().getUid();
                // B-073: the kernel uid is the only identity worth trusting inside a BlackBox
                // guest: Process.myUid() is virtualized to 10009 there, which on the phone is
                // another real app's uid. Accept the shell (same real uid) only.
                int expected = realUid >= 0 ? realUid : android.os.Process.myUid();
                if (uid != expected) {
                    if (!rejectedUidLogged) {
                        rejectedUidLogged = true;
                        Log.w(TAG, "B1_INPUT reject uid=" + uid + " (not " + expected +
                                (realUid >= 0 ? " realUid" : " myUid (no /proc)") +
                                "), connection closed");
                    }
                    continue;
                }
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(s.getInputStream(), "UTF-8"));
                String line = r.readLine();
                String reply = handleInput(line) ? "1" : "0";
                s.getOutputStream().write((reply + "\n").getBytes("UTF-8"));
                s.getOutputStream().flush();
            } catch (Throwable t) {
                Log.w(TAG, "B1_INPUT connection failed, still serving: " + t);
            } finally {
                if (s != null) try { s.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /** "T <action> <x> <y>" or "K <keyCode> <1|0 down> <meta>", dispatched on the UI thread. */
    private static boolean handleInput(String line) {
        if (line == null) return false;
        String[] p = line.trim().split(" ");
        final boolean[] out = new boolean[1];
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        Runnable work = () -> {
            try {
                if (p.length == 4 && "T".equals(p[0])) {
                    out[0] = dispatchGuestTouch(Integer.parseInt(p[1]),
                            Float.parseFloat(p[2]), Float.parseFloat(p[3]));
                } else if (p.length == 4 && "K".equals(p[0])) {
                    out[0] = dispatchGuestKey(Integer.parseInt(p[1]), "1".equals(p[2]),
                            Integer.parseInt(p[3]));
                } else if (p.length >= 1 && "C".equals(p[0])) {
                    // B-070: the shell window is closing. Its display is going away, so the
                    // guest activity must be finished; the next open builds a fresh window and
                    // relaunches the guest instead of presenting a dead display.
                    final String pkg = p.length > 1 ? p[1] : "";
                    for (Activity a : new java.util.ArrayList<>(GUESTS)) {
                        if (pkg.isEmpty() || a.getPackageName().equals(pkg)) {
                            Log.i(TAG, "B1_INPUT finish " + a.getClass().getName() + " pkg=" + pkg);
                            a.finish();
                        }
                    }
                    out[0] = true;
                }
            } catch (Throwable t) {
                Log.w(TAG, "B1_INPUT bad '" + line + "': " + t);
            } finally {
                done.countDown();
            }
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            work.run();
        } else {
            MAIN.post(work);
            try { done.await(500, java.util.concurrent.TimeUnit.MILLISECONDS); }
            catch (InterruptedException ignored) { }
        }
        return out[0];
    }
    private final Set<Activity> moveScheduled =
            Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>());
    private final Set<Activity> configurationPrepared =
            Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>());
    private final Set<Activity> signatureProbed =
            Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>());

    @Override
    public void beforeMainActivityOnCreate(Activity activity) {
        Log.i(TAG, "B1_BEFORE_MAIN_CREATE class=" + activity.getClass().getName() +
                " package=" + activity.getPackageName());
        if (isHostActivity(activity)) return;
        if (configurationPrepared.contains(activity)) return;
        Display display = targetDisplay(activity);
        // Make the activity window non-visible before the guest's onCreate. This is not an
        // accessibility or system permission; it is the normal public Window attribute.
        activity.getWindow().setDimAmount(0f);
        android.view.WindowManager.LayoutParams attrs = activity.getWindow().getAttributes();
        attrs.alpha = 0f;
        activity.getWindow().setAttributes(attrs);
        activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        // Tasks otherwise requests the phone's portrait orientation. The private display is
        // landscape, so make the guest create its content in the target orientation and
        // target density before its first layout.
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        applyGuestConfiguration(activity, display == null ? TARGET_WIDTH : display.getMode().getPhysicalWidth(),
                display == null ? TARGET_HEIGHT : display.getMode().getPhysicalHeight());
        if (display == null) {
            Log.w(TAG, "B1_BEFORE_MAIN_CREATE_NO_DISPLAY");
            return;
        }
        Log.i(TAG, "prepared guest activity " + activity.getClass().getName() +
                " for private display=" + display.getDisplayId());
    }

    private void applyGuestConfiguration(Activity activity, int width, int height) {
        try {
            // This callback is invoked before AppInstrumentation performs its normal activity
            // setup. Do not touch activity resources here: applyOverrideConfiguration() rejects
            // an Activity whose Resources object has already been initialized.
            Configuration config = new Configuration(Resources.getSystem().getConfiguration());
            config.densityDpi = TARGET_DENSITY_DPI;
            config.screenWidthDp = Math.round(width * 160f / TARGET_DENSITY_DPI);
            config.screenHeightDp = Math.round(height * 160f / TARGET_DENSITY_DPI);
            config.smallestScreenWidthDp = Math.min(config.screenWidthDp, config.screenHeightDp);
            config.orientation = width >= height ? Configuration.ORIENTATION_LANDSCAPE :
                    Configuration.ORIENTATION_PORTRAIT;
            try {
                activity.applyOverrideConfiguration(config);
            } catch (IllegalStateException alreadyInitialized) {
                // BlackBox/Android may initialize Activity resources during attach(), before
                // its earliest lifecycle callback. updateConfiguration() is the remaining
                // public pre-onCreate path on this device; unlike applyOverrideConfiguration,
                // it is allowed after Resources has been initialized.
                Log.w(TAG, "B1_GUEST_OVERRIDE_FAILED", alreadyInitialized);
                DisplayMetrics metrics = targetMetrics(width, height);
                activity.getResources().updateConfiguration(config, metrics);
                Log.i(TAG, "B1_GUEST_OVERRIDE_FALLBACK widthDp=" + config.screenWidthDp +
                        " heightDp=" + config.screenHeightDp + " densityDpi=" + metrics.densityDpi);
            }
            WindowManager.LayoutParams attrs = activity.getWindow().getAttributes();
            attrs.width = width;
            attrs.height = height;
            activity.getWindow().setAttributes(attrs);
            configurationPrepared.add(activity);
            Log.i(TAG, "B1_GUEST_OVERRIDE widthDp=" + config.screenWidthDp +
                    " heightDp=" + config.screenHeightDp + " densityDpi=" + config.densityDpi +
                    " window=" + width + "x" + height);
        } catch (Throwable t) {
            Log.w(TAG, "B1_GUEST_OVERRIDE_FAILED", t);
        }
    }

    @SuppressLint("WrongConstant")
    private DisplayMetrics targetMetrics(int width, int height) {
        DisplayMetrics metrics = new DisplayMetrics();
        metrics.densityDpi = TARGET_DENSITY_DPI;
        metrics.density = TARGET_DENSITY_DPI / 160f;
        metrics.scaledDensity = metrics.density;
        metrics.widthPixels = width;
        metrics.heightPixels = height;
        return metrics;
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle state) {
        Log.i(TAG, "callback_onActivityCreated class=" + activity.getClass().getName() +
                " package=" + activity.getPackageName());
        if (isHostActivity(activity)) return;
        Display display = targetDisplay(activity);
        if (display == null) return;
        Log.i(TAG, "B1_CREATED_WAITING_FOR_RESUME guest=" + activity.getClass().getName());
    }

    @Override
    public void afterMainActivityOnCreate(Activity activity) {
        // The vendored engine exposes this hook but does not dispatch it on current builds.
        // The reliable public lifecycle point is onActivityResumed below.
    }

    @Override
    public void onActivityResumed(final Activity activity) {
        if (isHostActivity(activity)) return;
        // B-061: a hidden Android app is now alive, so the engine's daemon service may run;
        // it is stopped again in onActivityDestroyed with the last guest.
        EngineDaemon.INSTANCE.guestStarted(activity.getApplicationContext(), activity);
        if (!GUESTS.contains(activity)) GUESTS.add(activity);
        P7Log.INSTANCE.say(activity, "GUEST resumed " + activity.getClass().getName() +
                " live=" + GUESTS.size());
        if (moveScheduled.contains(activity)) return;
        final Display display = targetDisplay(activity);
        if (display == null) return;
        moveScheduled.add(activity);
        // ActivityThread adds the decor during handleResumeActivity after onResume returns.
        // Post again so removeViewImmediate uses the same WindowManager after that add.
        activity.getWindow().getDecorView().postDelayed(new Runnable() {
            @Override public void run() { moveAfterResume(activity, display); }
        }, 200L);
    }

    @SuppressLint("WrongConstant")
    private void moveAfterResume(Activity activity, Display display) {
        try {
            View decor = activity.getWindow().getDecorView();
            int width = display.getMode().getPhysicalWidth();
            int height = display.getMode().getPhysicalHeight();
            Configuration config = new Configuration(activity.getResources().getConfiguration());
            config.densityDpi = TARGET_DENSITY_DPI;
            config.screenWidthDp = Math.round(width * 160f / TARGET_DENSITY_DPI);
            config.screenHeightDp = Math.round(height * 160f / TARGET_DENSITY_DPI);
            config.smallestScreenWidthDp = Math.min(config.screenWidthDp, config.screenHeightDp);
            config.orientation = width >= height ? Configuration.ORIENTATION_LANDSCAPE :
                    Configuration.ORIENTATION_PORTRAIT;
            try {
                DisplayMetrics metrics = new DisplayMetrics();
                metrics.densityDpi = TARGET_DENSITY_DPI;
                metrics.density = TARGET_DENSITY_DPI / 160f;
                metrics.scaledDensity = metrics.density;
                metrics.widthPixels = width;
                metrics.heightPixels = height;
                activity.getResources().updateConfiguration(config, metrics);
                Log.i(TAG, "B1_GUEST_CONFIGURATION widthDp=" + config.screenWidthDp +
                        " heightDp=" + config.screenHeightDp + " densityDpi=" + metrics.densityDpi);
            } catch (Throwable t) {
                Log.w(TAG, "B1_GUEST_CONFIGURATION_FAILED", t);
            }
            Log.i(TAG, "B1_BEFORE_MOVE attached=" + decor.isAttachedToWindow() +
                    " size=" + decor.getWidth() + "x" + decor.getHeight() +
                    " visibility=" + decor.getVisibility() + " token=" + decor.getWindowToken());
            P7Log.INSTANCE.say(activity, "B1 before-move attached=" + decor.isAttachedToWindow() +
                    " size=" + decor.getWidth() + "x" + decor.getHeight() +
                    " display=" + display.getDisplayId());
            if (!decor.isAttachedToWindow()) {
                Log.e(TAG, "B1_MOVE_NOT_READY guest=" + activity.getClass().getName());
                P7Log.INSTANCE.say(activity, "B1 move NOT ready (decor detached)");
                moveScheduled.remove(activity);
                return;
            }
            // Use the Activity's WindowManager: it is the manager that added this decor.
            WindowManager wm = activity.getWindowManager();
            // The guest is laid out at the target size below. Do not add a second transform
            // here: scaling the already target-sized root makes the window smaller and does
            // not change the guest's internal layout decisions.
            decor.setScaleX(1f);
            decor.setPivotX(0f);
            decor.setPivotY(0f);
            decor.setScaleY(1f);
            Log.i(TAG, "B1_GUEST_SCALE x=1.0 y=1.0 source=" + decor.getWidth() + "x" +
                    decor.getHeight() + " target=" + width + "x" + height);
            wm.removeViewImmediate(decor);
            suppressDetachedWindowUpdates(activity);
            presentation = new GuestPresentation(activity, display, decor);
            presentation.show();
            Log.i(TAG, "B1_PRESENTATION_SHOWN guest=" + activity.getClass().getName() +
                    " display=" + display.getDisplayId() + " target=" + width + "x" + height +
                    " size=" + decor.getWidth() + "x" + decor.getHeight());
            P7Log.INSTANCE.say(activity, "B1 moved to presentation display=" + display.getDisplayId() +
                    " target=" + width + "x" + height + " size=" + decor.getWidth() + "x" +
                    decor.getHeight());
            probeGuestSignatures(activity);
            if (activity.getIntent().getBooleanExtra("phase1_inject_input", false)) {
                decor.postDelayed(() -> injectInput(activity, decor), 700L);
            } else if (activity.getIntent().getBooleanExtra("phase1_inject_moving", false)) {
                // T3 is intentionally independent of browser input/page loading. Start the
                // paired drag workload on whatever guest screen is currently visible.
                decor.postDelayed(() -> startMoving(decor,
                        activity.getIntent().getIntExtra("phase1_move_seconds", 60)), 1200L);
            }
        } catch (Throwable t) {
            Log.e(TAG, "B1_PRESENTATION_FAILED guest=" + activity.getClass().getName(), t);
            P7Log.INSTANCE.say(activity, "B1 move FAILED " + t);
        }
    }

    private void suppressDetachedWindowUpdates(Activity activity) {
        Window.Callback callback = activity.getWindow().getCallback();
        if (callback == null || callback instanceof DetachedWindowCallback) return;
        activity.getWindow().setCallback(new DetachedWindowCallback(callback));
        Log.i(TAG, "B1_DETACHED_WINDOW_CALLBACK_INSTALLED guest=" + activity.getClass().getName());
    }

    /**
     * The guest Activity still owns the original phone Window after its decor is moved to the
     * private-display Presentation. Firefox changes flags on that Window when navigation starts;
     * forwarding onWindowAttributesChanged would ask the original WindowManager to update the
     * now-detached decor and crash. Other callbacks remain delegated unchanged.
     */
    private static final class DetachedWindowCallback implements Window.Callback {
        private final Window.Callback delegate;

        DetachedWindowCallback(Window.Callback delegate) {
            this.delegate = delegate;
        }

        @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
            return delegate.dispatchGenericMotionEvent(event);
        }
        @Override public boolean dispatchKeyEvent(KeyEvent event) {
            return delegate.dispatchKeyEvent(event);
        }
        @Override public boolean dispatchKeyShortcutEvent(KeyEvent event) {
            return delegate.dispatchKeyShortcutEvent(event);
        }
        @Override public boolean dispatchPopulateAccessibilityEvent(
                android.view.accessibility.AccessibilityEvent event) {
            return delegate.dispatchPopulateAccessibilityEvent(event);
        }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            return delegate.dispatchTouchEvent(event);
        }
        @Override public boolean dispatchTrackballEvent(MotionEvent event) {
            return delegate.dispatchTrackballEvent(event);
        }
        @Override public void onActionModeFinished(ActionMode mode) {
            delegate.onActionModeFinished(mode);
        }
        @Override public void onActionModeStarted(ActionMode mode) {
            delegate.onActionModeStarted(mode);
        }
        @Override public void onAttachedToWindow() {
            delegate.onAttachedToWindow();
        }
        @Override public void onContentChanged() {
            delegate.onContentChanged();
        }
        @Override public boolean onCreatePanelMenu(int featureId, Menu menu) {
            return delegate.onCreatePanelMenu(featureId, menu);
        }
        @Override public View onCreatePanelView(int featureId) {
            return delegate.onCreatePanelView(featureId);
        }
        @Override public void onDetachedFromWindow() {
            delegate.onDetachedFromWindow();
        }
        @Override public boolean onMenuItemSelected(int featureId, MenuItem item) {
            return delegate.onMenuItemSelected(featureId, item);
        }
        @Override public boolean onMenuOpened(int featureId, Menu menu) {
            return delegate.onMenuOpened(featureId, menu);
        }
        @Override public void onPanelClosed(int featureId, Menu menu) {
            delegate.onPanelClosed(featureId, menu);
        }
        @Override public boolean onPreparePanel(int featureId, View view, Menu menu) {
            return delegate.onPreparePanel(featureId, view, menu);
        }
        @Override public boolean onSearchRequested() {
            return delegate.onSearchRequested();
        }
        @Override public boolean onSearchRequested(android.view.SearchEvent event) {
            return delegate.onSearchRequested(event);
        }
        @Override public void onWindowAttributesChanged(WindowManager.LayoutParams attrs) {
            // Deliberately do not forward: the guest decor is no longer in this WindowManager.
        }
        @Override public void onWindowFocusChanged(boolean hasFocus) {
            delegate.onWindowFocusChanged(hasFocus);
        }
        @Override public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) {
            return delegate.onWindowStartingActionMode(callback);
        }
        @Override public ActionMode onWindowStartingActionMode(ActionMode.Callback callback,
                                                                 int type) {
            return delegate.onWindowStartingActionMode(callback, type);
        }
    }

    private void injectInput(Activity activity, View root) {
        View timer = activity.getWindow().getDecorView();
        tap(root, root.getWidth() / 2f, Math.max(1f, root.getHeight() - 45f), "onboarding");
        timer.postDelayed(() -> {
            View notNow = findText(root, "Not now");
            if (notNow != null) {
                tapView(root, notNow, "not-now");
            } else if ("org.mozilla.firefox".equals(activity.getPackageName())) {
                // Firefox's Compose onboarding text is not exposed as a TextView in the
                // virtual hierarchy. On its second page, the public Not now button is at
                // the center of the lower half of the 1280x720 target.
                tap(root, root.getWidth() / 2f, root.getHeight() * 0.7f, "firefox-not-now");
            } else {
                tap(root, Math.max(1f, root.getWidth() - 70f), Math.max(1f, root.getHeight() - 70f), "add-task");
            }
            timer.postDelayed(() -> {
                if ("org.mozilla.firefox".equals(activity.getPackageName())) {
                    // Firefox's home search is rendered by Compose and is not represented by an
                    // EditText in the guest view tree. Give it the same real touch path as a user
                    // before attempting keyboard input.
                    tap(root, root.getWidth() * 0.39f, root.getHeight() * 0.09f, "firefox-search");
                }
                timer.postDelayed(() -> commitGuestText(activity, root), 500L);
            }, 1200L);
        }, 1200L);
    }

    private void tap(View root, float x, float y, String label) {
        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(downTime, downTime + 40L, MotionEvent.ACTION_UP, x, y, 0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        boolean tapDown = root.dispatchTouchEvent(down);
        boolean tapUp = root.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
        Log.i(TAG, "B1_INPUT_TAP label=" + label + " x=" + x + " y=" + y +
                " down=" + tapDown + " up=" + tapUp);
    }

    private void tapView(View root, View target, String label) {
        int[] location = new int[2];
        target.getLocationOnScreen(location);
        tap(root, location[0] + target.getWidth() / 2f,
                location[1] + target.getHeight() / 2f, label);
    }

    private void drag(View root, float startX, float startY, float endX, float endY, String label) {
        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY, 0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        root.dispatchTouchEvent(down);
        down.recycle();
        for (int i = 1; i <= 8; i++) {
            float fraction = i / 8f;
            MotionEvent move = MotionEvent.obtain(downTime, downTime + i * 30L,
                    MotionEvent.ACTION_MOVE, startX + (endX - startX) * fraction,
                    startY + (endY - startY) * fraction, 0);
            move.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            root.dispatchTouchEvent(move);
            move.recycle();
        }
        MotionEvent up = MotionEvent.obtain(downTime, downTime + 300L, MotionEvent.ACTION_UP, endX, endY, 0);
        up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        root.dispatchTouchEvent(up);
        up.recycle();
        Log.i(TAG, "B1_INPUT_DRAG label=" + label + " start=" + startX + "," + startY +
                " end=" + endX + "," + endY);
    }

    private View findText(View view, String text) {
        if (view instanceof android.widget.TextView && text.contentEquals(((android.widget.TextView) view).getText())) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = findText(group.getChildAt(i), text);
                if (result != null) return result;
            }
        }
        return null;
    }

    private void commitGuestText(Activity activity, View root) {
        View focused = root.findFocus();
        EditorInfo info = new EditorInfo();
        InputConnection connection = createInputConnection(root, info);
        String text = activity.getIntent().getStringExtra("phase1_input_text");
        if (text == null || text.isEmpty()) text = "https://example.com";
        final String committedText = text;
        boolean committed = connection != null && connection.commitText(text, 1);
        Log.i(TAG, "B1_INPUT_CONNECTION target=" + inputTargetName(root) +
                " focus=" + focused + " commit=" + committed + " action=deferred " +
                " textLength=" + text.length());
        // Leave the committed address visible long enough for the harness to capture a frame
        // proving typed text before navigation. The action is still delivered through the same
        // InputConnection, not through the physical phone keyboard.
        View timer = activity.getWindow().getDecorView();
        timer.postDelayed(() -> {
            boolean action = connection != null && connection.performEditorAction(EditorInfo.IME_ACTION_GO);
            if (connection != null && !action) {
                connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
            }
            Log.i(TAG, "B1_INPUT_ACTION action=" + action + " textLength=" + committedText.length());
            if (activity.getIntent().getBooleanExtra("phase1_inject_moving", false)) {
                timer.postDelayed(() -> startMoving(root,
                        activity.getIntent().getIntExtra("phase1_move_seconds", 60)), 3500L);
            }
        }, 1800L);
    }

    private InputConnection createInputConnection(View view, EditorInfo info) {
        try {
            if (view.hasFocus() || view.onCheckIsTextEditor()) {
                InputConnection connection = view.onCreateInputConnection(info);
                if (connection != null) return connection;
            }
        } catch (Throwable t) {
            Log.w(TAG, "B1_INPUT_CONNECTION_VIEW_FAILED class=" + view.getClass().getName(), t);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                InputConnection result = createInputConnection(group.getChildAt(i), info);
                if (result != null) return result;
            }
        }
        return null;
    }

    private String inputTargetName(View view) {
        View focused = view.findFocus();
        return focused == null ? "null" : focused.getClass().getName();
    }

    private void startMoving(View root, int seconds) {
        final long end = SystemClock.uptimeMillis() + Math.max(5, Math.min(seconds, 60)) * 1000L;
        Log.i(TAG, "B1_MOVING_START seconds=" + Math.max(5, Math.min(seconds, 60)));
        final Runnable[] loop = new Runnable[1];
        loop[0] = () -> {
            if (SystemClock.uptimeMillis() >= end) {
                Log.i(TAG, "B1_MOVING_STOP");
                return;
            }
            drag(root, root.getWidth() / 2f, root.getHeight() * 0.82f,
                    root.getWidth() / 2f, root.getHeight() * 0.18f, "moving-up");
            root.postDelayed(loop[0], 650L);
        };
        root.postDelayed(loop[0], 1500L);
    }

    private void probeGuestSignatures(Activity activity) {
        if (signatureProbed.contains(activity)) return;
        signatureProbed.add(activity);
        final int flags = PackageManager.GET_SIGNATURES | PackageManager.GET_SIGNING_CERTIFICATES;
        String[] packages = {"com.android.vending", "com.google.android.gms"};
        for (String packageName : packages) {
            try {
                PackageInfo guest = activity.getPackageManager().getPackageInfo(packageName, flags);
                Log.i(TAG, "B1_SIGNATURE guest package=" + packageName + " " + signatureSummary(guest));
            } catch (Throwable t) {
                Log.w(TAG, "B1_SIGNATURE guest package=" + packageName + " error=" +
                        t.getClass().getName() + ":" + t.getMessage());
            }
            try {
                PackageInfo host = BlackBoxCore.getContext().getPackageManager().getPackageInfo(packageName, flags);
                Log.i(TAG, "B1_SIGNATURE host package=" + packageName + " " + signatureSummary(host));
            } catch (Throwable t) {
                Log.w(TAG, "B1_SIGNATURE host package=" + packageName + " error=" +
                        t.getClass().getName() + ":" + t.getMessage());
            }
        }
    }

    private String signatureSummary(PackageInfo info) {
        if (info == null) return "info=null";
        StringBuilder out = new StringBuilder("uid=")
                .append(info.applicationInfo == null ? -1 : info.applicationInfo.uid)
                .append(" signatures=").append(signatureDigests(info.signatures));
        if (info.signingInfo != null) {
            out.append(" apkContents=").append(signatureDigests(info.signingInfo.getApkContentsSigners()))
                    .append(" history=").append(signatureDigests(info.signingInfo.getSigningCertificateHistory()));
        } else {
            out.append(" signingInfo=null");
        }
        return out.toString();
    }

    private String signatureDigests(Signature[] signatures) {
        if (signatures == null) return "null";
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < signatures.length; i++) {
            if (i > 0) out.append(',');
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(signatures[i].toByteArray());
                for (byte value : digest) out.append(String.format("%02x", value));
            } catch (Throwable t) {
                out.append("error");
            }
        }
        return out.append(']').toString();
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        if (isHostActivity(activity)) return;
        moveScheduled.remove(activity);
        configurationPrepared.remove(activity);
        signatureProbed.remove(activity);
        if (presentation != null) {
            try { presentation.dismiss(); } catch (Throwable ignored) { }
            presentation = null;
            liveContent = null;
        }
        GUESTS.remove(activity);
        // B-061: with the last hidden app gone the engine daemon may stop.
        EngineDaemon.INSTANCE.guestStopped(activity.getApplicationContext(), activity);
        P7Log.INSTANCE.say(activity, "GUEST destroyed " + activity.getClass().getName() +
                " live=" + GUESTS.size());
    }

    private Display targetDisplay(Activity activity) {
        DisplayManager manager = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
        // The display is owned by this UID and is visible from the guest process. Looking it
        // up by its stable diagnostic name avoids relying on host-process SharedPreferences;
        // BlackBox maps a guest's Context data directory separately.
        for (Display display : manager.getDisplays()) {
            if (DISPLAY_NAME.equals(display.getName())) return display;
        }
        return null;
    }

    private boolean isHostActivity(Activity activity) {
        return "dev.unifieddesktop.app".equals(activity.getPackageName());
    }

    private static final class GuestPresentation extends Presentation {
        private final Activity ownerActivity;
        private final View decor;

        GuestPresentation(Context context, Display display, View decor) {
            super(context, display);
            this.ownerActivity = context instanceof Activity ? (Activity) context : null;
            this.decor = decor;
        }

        @Override
        protected void onCreate(Bundle state) {
            super.onCreate(state);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN |
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
            Display display = getWindow().getWindowManager().getDefaultDisplay();
            int width = display.getMode().getPhysicalWidth();
            int height = display.getMode().getPhysicalHeight();
            getWindow().setLayout(width, height);
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.BLACK));
            decor.setBackgroundColor(Color.BLACK);
            // Put the detached guest decor in a normal public container. Directly using the
            // decor as Presentation content lets Firefox's later Compose relayout encounter
            // its old WindowManager.LayoutParams in a MarginLayoutParams parent.
            NormalizingFrameLayout container = new NormalizingFrameLayout(getContext());
            container.setLayoutParams(new ViewGroup.LayoutParams(width, height));
            copyViewTreeOwnerTags(container, decor);
            container.addView(decor, new android.widget.FrameLayout.LayoutParams(width, height));
            setContentView(container);
            liveContent = container;
            ensureInputServer(getContext());
            container.post(() -> {
                container.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                container.layout(0, 0, width, height);
                decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                decor.layout(0, 0, width, height);
                watchGuestSurfaces(decor);
                Log.i(TAG, "B1_PRESENTATION_LAYOUT size=" + decor.getWidth() + "x" + decor.getHeight());
            });
        }

        private void watchGuestSurfaces(View view) {
            if (view instanceof SurfaceView) {
                watchSurface((SurfaceView) view);
            } else if (view instanceof TextureView) {
                watchTexture((TextureView) view);
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                group.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                    @Override public void onChildViewAdded(View parent, View child) {
                        Log.i(TAG, "B1_GUEST_CHILD_ADDED parent=" + parent.getClass().getName() +
                                " child=" + child.getClass().getName() + " attached=" +
                                child.isAttachedToWindow());
                        watchGuestSurfaces(child);
                    }

                    @Override public void onChildViewRemoved(View parent, View child) {
                        Log.i(TAG, "B1_GUEST_CHILD_REMOVED parent=" + parent.getClass().getName() +
                                " child=" + child.getClass().getName());
                    }
                });
                for (int i = 0; i < group.getChildCount(); i++) {
                    watchGuestSurfaces(group.getChildAt(i));
                }
            }
        }

        private void watchSurface(SurfaceView surface) {
            // GeckoView creates this child after the guest decor is moved. A SurfaceView keeps
            // its own layer; putting it on top of the Presentation is the public way to make
            // that layer participate in the new window rather than remaining behind the moved
            // decor's opaque background. The browser toolbar is outside this content-sized view.
            surface.setZOrderOnTop(true);
            Log.i(TAG, "B1_GUEST_SURFACE_FOUND class=" + surface.getClass().getName() +
                    " attached=" + surface.isAttachedToWindow() + " size=" +
                    surface.getWidth() + "x" + surface.getHeight() + " zOrder=top");
            surface.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
                @Override public void surfaceCreated(android.view.SurfaceHolder holder) {
                    Log.i(TAG, "B1_GUEST_SURFACE_CREATED class=" + surface.getClass().getName() +
                            " valid=" + holder.getSurface().isValid() + " attached=" +
                            surface.isAttachedToWindow() + " size=" + surface.getWidth() + "x" +
                            surface.getHeight());
                }
                @Override public void surfaceChanged(android.view.SurfaceHolder holder, int format,
                                                      int width, int height) {
                    Log.i(TAG, "B1_GUEST_SURFACE_CHANGED class=" + surface.getClass().getName() +
                            " valid=" + holder.getSurface().isValid() + " size=" + width + "x" + height);
                }
                @Override public void surfaceDestroyed(android.view.SurfaceHolder holder) {
                    Log.w(TAG, "B1_GUEST_SURFACE_DESTROYED class=" + surface.getClass().getName());
                }
            });
        }

        private void watchTexture(TextureView texture) {
            texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surface,
                                                                 int width, int height) {
                    Log.i(TAG, "B1_GUEST_TEXTURE_AVAILABLE class=" + texture.getClass().getName() +
                            " attached=" + texture.isAttachedToWindow() + " size=" + width + "x" + height);
                }
                @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture surface,
                                                                    int width, int height) {
                    Log.i(TAG, "B1_GUEST_TEXTURE_CHANGED size=" + width + "x" + height);
                }
                @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture surface) {
                    Log.w(TAG, "B1_GUEST_TEXTURE_DESTROYED");
                    return true;
                }
                @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surface) {
                    // A callback proves that Gecko is producing frames; do not log every frame.
                }
            });
            Log.i(TAG, "B1_GUEST_TEXTURE_FOUND class=" + texture.getClass().getName() +
                    " attached=" + texture.isAttachedToWindow() + " size=" + texture.getWidth() +
                    "x" + texture.getHeight());
        }

        private void copyViewTreeOwnerTags(View container, View decor) {
            // Compose obtains lifecycle/view-model owners from these public View tags. The
            // detached guest decor already received them from Firefox's Activity window, but a
            // newly-created Presentation container does not. Copy the existing owner when
            // present; otherwise the guest Activity is the same public owner implementation.
            String[] ownerTags = {
                    "view_tree_lifecycle_owner",
                    "view_tree_view_model_store_owner",
                    "view_tree_saved_state_registry_owner",
                    "view_tree_on_back_pressed_dispatcher_owner"
            };
            for (String name : ownerTags) {
                int id = getContext().getResources().getIdentifier(name, "id",
                        getContext().getPackageName());
                if (id == 0) {
                    id = getContext().getResources().getIdentifier(name, "id", "androidx.lifecycle");
                }
                if (id == 0) {
                    id = getContext().getResources().getIdentifier(name, "id", "androidx.savedstate");
                }
                if (id == 0) continue;
                Object owner = decor.getTag(id);
                if (owner == null && ownerActivity != null) {
                    owner = ownerActivity;
                }
                if (owner != null) {
                    container.setTag(id, owner);
                    Log.i(TAG, "B1_PRESENTATION_OWNER_TAG name=" + name + " id=" + id +
                            " owner=" + owner.getClass().getName());
                }
            }
        }

        private static final class NormalizingFrameLayout extends android.widget.FrameLayout {
            NormalizingFrameLayout(Context context) {
                super(context);
            }

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                normalizeMarginParams(this);
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }

            private static void normalizeMarginParams(ViewGroup group) {
                boolean marginParent = group instanceof android.widget.FrameLayout ||
                        group instanceof android.widget.LinearLayout;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View child = group.getChildAt(i);
                    ViewGroup.LayoutParams params = child.getLayoutParams();
                    if (marginParent && !(params instanceof ViewGroup.MarginLayoutParams)) {
                        int width = params == null ? ViewGroup.LayoutParams.MATCH_PARENT : params.width;
                        int height = params == null ? ViewGroup.LayoutParams.MATCH_PARENT : params.height;
                        child.setLayoutParams(new android.widget.FrameLayout.LayoutParams(width, height));
                        Log.w(TAG, "B1_NORMALIZED_LAYOUT_PARAMS parent=" +
                                group.getClass().getName() + " child=" + child.getClass().getName());
                    }
                    if (child instanceof ViewGroup) {
                        normalizeMarginParams((ViewGroup) child);
                    }
                }
            }
        }
    }
}
