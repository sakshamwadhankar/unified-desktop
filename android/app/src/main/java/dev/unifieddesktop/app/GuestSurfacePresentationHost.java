package dev.unifieddesktop.app;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback;

/** B2 experiment: host the guest decor in a public SurfaceControlViewHost package. */
@android.annotation.TargetApi(30)
final class GuestSurfacePresentationHost extends AppLifecycleCallback {
    private static final String TAG = "Phase1-B2";
    private SurfaceControlViewHost viewHost;
    private Presentation presentation;
    private View pendingDecor;

    @Override
    public void onActivityCreated(Activity activity, Bundle state) {
        if (android.os.Build.VERSION.SDK_INT < 30) return;
        if ("dev.unifieddesktop.app".equals(activity.getPackageName())) return;
        Display display = null;
        android.hardware.display.DisplayManager manager =
                (android.hardware.display.DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
        for (Display candidate : manager.getDisplays()) {
            if ("phase1-owned-b2".equals(candidate.getName())) {
                display = candidate;
                break;
            }
        }
        if (display == null) return;
        final Display target = display;
        try {
            android.view.WindowManager.LayoutParams attrs = activity.getWindow().getAttributes();
            attrs.alpha = 0f;
            activity.getWindow().setAttributes(attrs);
            activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            pendingDecor = activity.getWindow().getDecorView();
            presentation = new SurfacePresentation(activity, target);
            presentation.show();
            SurfaceView surface = ((SurfacePresentation) presentation).surface;
            surface.postDelayed(() -> attach(surface, target, activity), 100L);
        } catch (Throwable t) {
            Log.e(TAG, "B2_SURFACE_PACKAGE_FAILED guest=" + activity.getClass().getName(), t);
            release();
        }
    }

    private void attach(SurfaceView surface, Display display, Activity activity) {
        if (pendingDecor == null) return;
        try {
            if (surface.getHostToken() == null) {
                throw new IllegalStateException("SurfaceView.getHostToken() returned null after attach");
            }
            // The guest ActivityThread has attached DecorView by the time this delayed
            // callback runs. Detach that public window root before handing the view tree to
            // the SurfaceControlViewHost; otherwise ViewRootImpl rejects a second parent.
            ((android.view.WindowManager) activity.getSystemService(Context.WINDOW_SERVICE))
                    .removeViewImmediate(pendingDecor);
            viewHost = new SurfaceControlViewHost(activity, display, surface.getHostToken());
            int width = Math.max(1, display.getMode().getPhysicalWidth());
            int height = Math.max(1, display.getMode().getPhysicalHeight());
            viewHost.setView(pendingDecor, width, height);
            surface.setChildSurfacePackage(viewHost.getSurfacePackage());
            Log.i(TAG, "B2_SURFACE_PACKAGE_SHOWN guest=" + activity.getClass().getName() +
                    " display=" + display.getDisplayId());
        } catch (Throwable t) {
            Log.e(TAG, "B2_SURFACE_PACKAGE_FAILED guest=" + activity.getClass().getName(), t);
            release();
        }
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        if (!"dev.unifieddesktop.app".equals(activity.getPackageName())) release();
    }

    private void release() {
        if (viewHost != null) {
            try { viewHost.release(); } catch (Throwable ignored) { }
            viewHost = null;
        }
        if (presentation != null) {
            try { presentation.dismiss(); } catch (Throwable ignored) { }
            presentation = null;
        }
        pendingDecor = null;
    }

    private static final class SurfacePresentation extends Presentation {
        final SurfaceView surface;

        SurfacePresentation(Context context, Display display) {
            super(context, display);
            surface = new SurfaceView(context);
            surface.setBackgroundColor(Color.BLACK);
        }

        @Override
        protected void onCreate(Bundle state) {
            super.onCreate(state);
            FrameLayout root = new FrameLayout(getContext());
            root.addView(surface, new FrameLayout.LayoutParams(-1, -1));
            setContentView(root);
        }
    }
}
