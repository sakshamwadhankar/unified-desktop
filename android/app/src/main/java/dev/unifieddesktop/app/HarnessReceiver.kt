package dev.unifieddesktop.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Headless action channel for the phase-2 harness. Unlike the activity intent
 * (which needs the activity to be resumed) a manifest broadcast always reaches
 * the app process, so every T2..T6 flow can be driven with:
 *
 *   adb shell am broadcast -n dev.unifieddesktop.app/.HarnessReceiver --es phase2x wineboot
 *
 * /proc/<pid>/exe and log evidence stay identical to the activity path because
 * both funnel into Phase2Dispatch.runX().
 */
class HarnessReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val p4 = intent.getStringExtra("phase4x")
        if (p4 != null) {
            android.util.Log.i("Phase4", "BROADCAST $p4")
            Phase4Dispatch.runX(ctx.applicationContext, p4)
            return
        }
        val p3 = intent.getStringExtra("phase3x")
        if (p3 != null) {
            android.util.Log.i("Phase3", "BROADCAST $p3")
            Phase3Dispatch.runX(ctx.applicationContext, p3)
            return
        }
        val what = intent.getStringExtra("phase2x") ?: return
        android.util.Log.i("Phase2", "BROADCAST $what")
        Phase2Dispatch.runX(ctx.applicationContext, what)
    }
}
