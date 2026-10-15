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
import android.view.ViewGroup;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.util.DisplayMetrics;

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
        // Tasks otherwise requests the phone's portrait orientation. The private display is
        // landscape, so make the guest create its content in the target orientation and
        // target density before its first layout.
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        applyGuestConfiguration(activity, display);
        Log.i(TAG, "prepared guest activity " + activity.getClass().getName() +
                " for private display=" + display.getDisplayId());
    }

    private void applyGuestConfiguration(Activity activity, Display display) {
        try {
            int width = display.getMode().getPhysicalWidth();
            int height = display.getMode().getPhysicalHeight();
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            Configuration config = new Configuration(activity.getResources().getConfiguration());
            config.screenWidthDp = Math.round(width * 160f / metrics.densityDpi);
            config.screenHeightDp = Math.round(height * 160f / metrics.densityDpi);
            config.smallestScreenWidthDp = Math.min(config.screenWidthDp, config.screenHeightDp);
            config.orientation = width >= height ? Configuration.ORIENTATION_LANDSCAPE :
                    Configuration.ORIENTATION_PORTRAIT;
            activity.applyOverrideConfiguration(config);
            Log.i(TAG, "B1_GUEST_OVERRIDE widthDp=" + config.screenWidthDp +
                    " heightDp=" + config.screenHeightDp + " densityDpi=" + metrics.densityDpi);
        } catch (Throwable t) {
            Log.w(TAG, "B1_GUEST_OVERRIDE_FAILED", t);
        }
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
            int width = display.getMode().getPhysicalWidth();
            int height = display.getMode().getPhysicalHeight();
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            Configuration config = new Configuration(activity.getResources().getConfiguration());
            config.screenWidthDp = Math.round(width * 160f / metrics.densityDpi);
            config.screenHeightDp = Math.round(height * 160f / metrics.densityDpi);
            config.smallestScreenWidthDp = Math.min(config.screenWidthDp, config.screenHeightDp);
            config.orientation = width >= height ? Configuration.ORIENTATION_LANDSCAPE :
                    Configuration.ORIENTATION_PORTRAIT;
            try {
                activity.getResources().updateConfiguration(config, metrics);
                Log.i(TAG, "B1_GUEST_CONFIGURATION widthDp=" + config.screenWidthDp +
                        " heightDp=" + config.screenHeightDp + " densityDpi=" + metrics.densityDpi);
            } catch (Throwable t) {
                Log.w(TAG, "B1_GUEST_CONFIGURATION_FAILED", t);
            }
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
            presentation = new GuestPresentation(activity, display, decor);
            presentation.show();
            Log.i(TAG, "B1_PRESENTATION_SHOWN guest=" + activity.getClass().getName() +
                    " display=" + display.getDisplayId() + " target=" + width + "x" + height +
                    " size=" + decor.getWidth() + "x" + decor.getHeight());
            if (activity.getIntent().getBooleanExtra("phase1_inject_input", false)) {
                decor.postDelayed(() -> injectInput(activity, decor), 700L);
            }
        } catch (Throwable t) {
            Log.e(TAG, "B1_PRESENTATION_FAILED guest=" + activity.getClass().getName(), t);
        }
    }

    private void injectInput(Activity activity, View root) {
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        final long downTime = System.currentTimeMillis();
        tap(root, root.getWidth() / 2f, Math.max(1f, root.getHeight() - 45f), "onboarding");
        root.postDelayed(() -> {
            tap(root, Math.max(1f, root.getWidth() - 70f), Math.max(1f, root.getHeight() - 70f), "add-task");
            root.postDelayed(() -> {
                View edit = findEditable(root);
                if (edit != null) {
                    edit.setFocusableInTouchMode(true);
                    edit.requestFocus();
                }
                Log.i(TAG, "B1_INPUT_FOCUS view=" + edit + " rootFocus=" + root.findFocus());
                int[] keys = {KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_T,
                        KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_ENTER};
                for (int key : keys) {
                    boolean keyDown = root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
                    boolean keyUp = root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
                    Log.i(TAG, "B1_INPUT_KEY code=" + key + " down=" + keyDown + " up=" + keyUp +
                            " focus=" + root.findFocus());
                }
            }, 800L);
        }, 1200L);
    }

    private void tap(View root, float x, float y, String label) {
        long downTime = System.currentTimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(downTime, downTime + 40L, MotionEvent.ACTION_UP, x, y, 0);
        boolean tapDown = root.dispatchTouchEvent(down);
        boolean tapUp = root.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
        Log.i(TAG, "B1_INPUT_TAP label=" + label + " x=" + x + " y=" + y +
                " down=" + tapDown + " up=" + tapUp);
    }

    private View findEditable(View view) {
        if (view instanceof android.widget.EditText) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = findEditable(group.getChildAt(i));
                if (result != null) return result;
            }
        }
        return null;
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
            decor.setLayoutParams(new ViewGroup.LayoutParams(width, height));
            setContentView(decor);
            decor.post(() -> {
                decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                decor.layout(0, 0, width, height);
                Log.i(TAG, "B1_PRESENTATION_LAYOUT size=" + decor.getWidth() + "x" + decor.getHeight());
            });
        }
    }
}
