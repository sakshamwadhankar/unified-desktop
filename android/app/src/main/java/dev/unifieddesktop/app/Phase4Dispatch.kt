package dev.unifieddesktop.app

import android.content.Context
import android.content.Intent

/**
 * Phase-4 harness actions, driven by the debug receiver / activity extra "phase4x"
 * (same pattern as Phase2Dispatch/Phase3Dispatch, B-028: broadcasts never replay):
 *
 *   adb shell am broadcast -n dev.unifieddesktop.app/.HarnessReceiver --es phase4x start
 *
 * Actions: start | stop | status | trust | forget[:<prefix>] | aoa-check | aoa-open
 * Each runs on its own thread; every line lands in files/p4/p4.log.
 */
object Phase4Dispatch {
    fun runX(ctx: Context, what: String) {
        Thread({
            try {
                val arg = what.substringAfter(':', "")
                when (what.substringBefore(':')) {
                    "start" -> {
                        val intent = Intent(ctx, Phase4LinkService::class.java)
                            .setAction(Phase4LinkService.ACTION_START)
                        try {
                            ctx.startForegroundService(intent)
                        } catch (t: Throwable) {
                            Phase4Log.say(
                                ctx,
                                "FGS_START refused (${t.javaClass.simpleName}); run 'am start' the app first, then retry"
                            )
                        }
                    }
                    "stop" -> ctx.startService(
                        Intent(ctx, Phase4LinkService::class.java).setAction(Phase4LinkService.ACTION_STOP)
                    )
                    "status" -> Phase4Log.say(
                        ctx,
                        "STATUS running=${Phase4LinkService.isRunning()} last=${Phase4LinkService.stateLine()}"
                    )
                    "trust" -> {
                        for (r in Phase4Crypto.trustedList(ctx)) {
                            Phase4Log.say(ctx, "TRUST fpr=${r.first} label=${r.second} added=${r.third}")
                        }
                        Phase4Log.say(ctx, "TRUST count=${Phase4Crypto.trustedList(ctx).size}")
                    }
                    "forget" -> {
                        val n = Phase4Crypto.trustedForget(ctx, arg)
                        Phase4Log.say(
                            ctx, "FORGET prefix=${arg.take(6)} removed=$n pins=${Phase4Crypto.trustedList(ctx).size}"
                        )
                    }
                    "forget-all" -> {
                        var c = 0
                        for (r in Phase4Crypto.trustedList(ctx)) if (Phase4Crypto.trustedForget(ctx, r.first.take(3))) c++
                        Phase4Log.say(ctx, "FORGET_ALL removed=$c pins=${Phase4Crypto.trustedList(ctx).size}")
                    }
                    "accept" -> ctx.startService(
                        Intent(ctx, Phase4LinkService::class.java).setAction(Phase4LinkService.ACTION_PAIR_YES)
                    )
                    "deny" -> ctx.startService(
                        Intent(ctx, Phase4LinkService::class.java).setAction(Phase4LinkService.ACTION_PAIR_NO)
                    )
                    "aoa-check" -> Phase4Aoa.check(ctx)
                    "aoa-open" -> Phase4Aoa.openFirst(ctx)
                    else -> Phase4Log.say(ctx, "PHASE4X unknown [$what]")
                }
            } catch (t: Throwable) {
                Phase4Log.say(ctx, "PHASE4X_FAIL $what ${t.javaClass.simpleName} ${t.message.orEmpty().take(80)}")
            }
        }, "p4-dispatch").start()
    }
}
