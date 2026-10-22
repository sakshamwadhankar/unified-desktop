package dev.unifieddesktop.app

import android.content.Context
import android.content.Intent

/**
 * Phase-4 harness actions, driven by the debug receiver / activity extra "phase4x"
 * (same pattern as Phase2Dispatch/Phase3Dispatch, B-028: broadcasts never replay):
 *
 *   adb shell am broadcast -n dev.unifieddesktop.app/.HarnessReceiver --es phase4x start
 *
 * Actions: start | stop | status | dump | trust | forget[:<prefix>] | aoa-check | aoa-open
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
                        "STATUS running=${Phase4LinkService.isRunning()} live=${Phase4LinkService.liveCount()}" +
                            " acc=${Phase4LinkService.accessoryOpen()} last=${Phase4LinkService.stateLine()}"
                    )
                    "dump" -> {
                        // Thread dump for evidence (kill -3 goes to /data/anr which shell
                        // cannot read on this image; debuggerd -j needs root).
                        val stacks = Thread.getAllStackTraces()
                        Phase4Log.say(ctx, "DUMP threads=${stacks.size}")
                        for ((th, stack) in stacks) {
                            if (!th.name.startsWith("p4-")) continue
                            Phase4Log.say(ctx, "THREAD ${th.name} state=${th.state} alive=${th.isAlive}")
                            for (el in stack) Phase4Log.say(ctx, "  at $el")
                        }
                        Phase4Log.say(ctx, "DUMP done")
                    }
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
                    "p5test" -> ctx.startService(
                        Intent(ctx, Phase4LinkService::class.java)
                            .setAction(Phase4LinkService.ACTION_P5TEST)
                            .putExtra("seconds", arg.toIntOrNull() ?: 12)
                    )
                    "p5text" -> Phase4Log.say(
                        ctx,
                        "P5TEXT ${Phase5Dispatch.lastText()}"
                    )
                    "p6" -> Phase4Log.say(ctx, "P6 ${Phase6Dispatch.run(ctx, arg)}")
                    "p12lowres" -> Phase4Log.say(
                        ctx,
                        "P12LOWRES ${Phase5Dispatch.session()?.governorLowerResolution() ?: "no session"}"
                    )
                    "p12lat" -> {
                        if (Phase12Tuning.configure(arg)) {
                            Phase4Log.say(ctx, Phase12Tuning.describe())
                        } else {
                            Phase4Log.say(ctx, "P12TUNING unknown profile [$arg]; keep ${Phase12Tuning.describe()}")
                        }
                    }
                    "p12warm" -> Phase4Log.say(ctx, Phase12Preload.configure(arg))
                    else -> Phase4Log.say(ctx, "PHASE4X unknown [$what]")
                }
            } catch (t: Throwable) {
                Phase4Log.say(ctx, "PHASE4X_FAIL $what ${t.javaClass.simpleName} ${t.message.orEmpty().take(80)}")
            }
        }, "p4-dispatch").start()
    }
}
