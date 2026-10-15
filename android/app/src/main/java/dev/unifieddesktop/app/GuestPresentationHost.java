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
import android.view.View;
import android.view.WindowManager;

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
        try {
            View decor = activity.getWindow().getDecorView();
            WindowManager wm = activity.getWindowManager();
            wm.removeViewImmediate(decor);
            presentation = new GuestPresentation(activity, display, decor);
            presentation.show();
            Log.i(TAG, "B1_PRESENTATION_SHOWN guest=" + activity.getClass().getName() +
                    " display=" + display.getDisplayId());
        } catch (Throwable t) {
            Log.e(TAG, "B1_PRESENTATION_FAILED guest=" + activity.getClass().getName(), t);
        }
    }

    @Override
    public void afterMainActivityOnCreate(Activity activity) {
        if (isHostActivity(activity)) return;
        Display display = targetDisplay(activity);
        if (display == null) return;
        try {
            View decor = activity.getWindow().getDecorView();
            WindowManager wm = activity.getWindowManager();
            wm.removeViewImmediate(decor);
            presentation = new GuestPresentation(activity, display, decor);
            presentation.show();
            Log.i(TAG, "B1_PRESENTATION_SHOWN guest=" + activity.getClass().getName() +
                    " display=" + display.getDisplayId());
        } catch (Throwable t) {
            Log.e(TAG, "B1_PRESENTATION_FAILED guest=" + activity.getClass().getName(), t);
        }
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        if (isHostActivity(activity)) return;
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
