package dev.unifieddesktop.app;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback;

/**
 * Experimental path-B adapter. It deliberately uses only public window/view APIs: the
 * virtual activity is not launched with a display id; its decor is detached from its
 * original ViewRoot and installed as the content of a Presentation on our private display.
 */
final class GuestPresentationHost extends AppLifecycleCallback {
    private static final String TAG = "Phase1-B1";
    static final String PREFS = "phase1-path-b";
    static final String DISPLAY_ID = "display_id";
    private Presentation presentation;
    private final Set<Activity> moveScheduled =
            Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>());

    @Override
    public void beforeMainActivityOnCreate(Activity activity) {
        if (isHostActivity(activity)) return;
        Display display = targetDisplay(activity);
        if (display == null) return;
        // Make the activity window non-visible before the guest's onCreate. This is not an
        // accessibility or system permission; it is the normal public Window attribute.
        activity.getWindow().setDimAmount(0f);
        android.view.WindowManager.LayoutParams attrs = activity.getWindow().getAttributes();
        attrs.alpha = 0f;
        activity.getWindow().setAttributes(attrs);
        activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        Log.i(TAG, "prepared guest activity " + activity.getClass().getName() +
                " for private display=" + display.getDisplayId());
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
        if (isHostActivity(activity) || moveScheduled.contains(activity)) return;
        final Display display = targetDisplay(activity);
        if (display == null) return;
        moveScheduled.add(activity);
        // ActivityThread adds the decor during handleResumeActivity after onResume returns.
        // Post again so removeViewImmediate uses the same WindowManager after that add.
        activity.getWindow().getDecorView().postDelayed(new Runnable() {
            @Override public void run() { moveAfterResume(activity, display); }
        }, 200L);
    }

    private void moveAfterResume(Activity activity, Display display) {
        try {
            View decor = activity.getWindow().getDecorView();
            Log.i(TAG, "B1_BEFORE_MOVE attached=" + decor.isAttachedToWindow() +
                    " size=" + decor.getWidth() + "x" + decor.getHeight() +
                    " visibility=" + decor.getVisibility() + " token=" + decor.getWindowToken());
            if (!decor.isAttachedToWindow()) {
                Log.e(TAG, "B1_MOVE_NOT_READY guest=" + activity.getClass().getName());
                moveScheduled.remove(activity);
                return;
            }
            // Use the Activity's WindowManager: it is the manager that added this decor.
            WindowManager wm = activity.getWindowManager();
            wm.removeViewImmediate(decor);
            presentation = new GuestPresentation(activity, display, decor);
            presentation.show();
            Log.i(TAG, "B1_PRESENTATION_SHOWN guest=" + activity.getClass().getName() +
                    " display=" + display.getDisplayId() + " size=" +
                    decor.getWidth() + "x" + decor.getHeight());
            if (activity.getIntent().getBooleanExtra("phase1_inject_input", false)) {
                decor.postDelayed(() -> injectInput(activity, decor), 500L);
            }
        } catch (Throwable t) {
            Log.e(TAG, "B1_PRESENTATION_FAILED guest=" + activity.getClass().getName(), t);
        }
    }

    private void injectInput(Activity activity, View root) {
        final long downTime = System.currentTimeMillis();
        final float x = Math.max(1f, root.getWidth() / 2f);
        final float y = Math.max(1f, root.getHeight() - 60f);
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(downTime, downTime + 40L, MotionEvent.ACTION_UP, x, y, 0);
        boolean tapDown = root.dispatchTouchEvent(down);
        boolean tapUp = root.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
        Log.i(TAG, "B1_INPUT_TAP x=" + x + " y=" + y + " down=" + tapDown + " up=" + tapUp);
        int[] keys = {KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_T};
        for (int key : keys) {
            boolean keyDown = activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
            boolean keyUp = activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
            Log.i(TAG, "B1_INPUT_KEY code=" + key + " down=" + keyDown + " up=" + keyUp +
                    " focus=" + root.findFocus());
        }
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        if (isHostActivity(activity)) return;
        moveScheduled.remove(activity);
        if (presentation != null) {
            try { presentation.dismiss(); } catch (Throwable ignored) { }
            presentation = null;
        }
    }

    private Display targetDisplay(Activity activity) {
        DisplayManager manager = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
        // The display is owned by this UID and is visible from the guest process. Looking it
        // up by its stable diagnostic name avoids relying on host-process SharedPreferences;
        // BlackBox maps a guest's Context data directory separately.
        for (Display display : manager.getDisplays()) {
            if ("phase1-owned".equals(display.getName())) return display;
        }
        return null;
    }

    private boolean isHostActivity(Activity activity) {
        return "dev.unifieddesktop.app".equals(activity.getPackageName());
    }

    private static final class GuestPresentation extends Presentation {
        private final View decor;

        GuestPresentation(Context context, Display display, View decor) {
            super(context, display);
            this.decor = decor;
        }

        @Override
        protected void onCreate(Bundle state) {
            super.onCreate(state);
            setContentView(decor);
        }
    }
}
