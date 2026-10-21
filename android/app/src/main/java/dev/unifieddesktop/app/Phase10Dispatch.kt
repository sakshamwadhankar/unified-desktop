package dev.unifieddesktop.app

import android.content.Context
import android.content.Intent

/** Debug-only harness actions for Phase 10 onboarding and runtime inspection. */
object Phase10Dispatch {
    fun runX(ctx: Context, what: String) {
        when (what.substringBefore(':')) {
            "onboarding" -> ctx.startActivity(
                Intent(ctx, HarnessActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra("show_phase10_onboarding", true)
            )
            "status" -> Phase4Log.say(
                ctx,
                "P10 status runtime=${Phase4LinkService.isRunning()} live=${Phase4LinkService.liveCount()}",
            )
            else -> Phase4Log.say(ctx, "PHASE10X unknown [$what]")
        }
    }
}
