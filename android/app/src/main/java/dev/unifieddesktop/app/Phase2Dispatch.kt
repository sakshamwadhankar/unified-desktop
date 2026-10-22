package dev.unifieddesktop.app

import android.content.Context

/**
 * Shared dispatcher for phase-2 harness actions, used by both HarnessActivity
 * (intent extra "phase2x") and HarnessReceiver (broadcast). Every action runs
 * on its own thread; results go to files/phase2/{wine,x11}.log.
 */
object Phase2Dispatch {
    /** Run one action string, e.g. "wineboot", "npp", "type:hello world", "capture x11now". */
    fun runX(ctx: Context, what: String) {
        Thread({
            try {
                // B-064: split only the action name. Both "capture:x" and "capture x" must
                // work, and an argument may itself contain ':' (e.g. "runbin:direct:...").
                val cut = what.indexOfFirst { it == ':' || it == ' ' }
                val action = if (cut < 0) what else what.substring(0, cut)
                val arg = if (cut < 0) "" else what.substring(cut + 1)
                when (action) {
                    "tmp" -> Phase2X11.proveNoTmp()
                    "xvfb" -> { Phase2X11.proveNoTmp(); Phase2X11.startXvfb(ctx) }
                    "capture" -> if (Phase2X11.captureX11(ctx, arg.ifEmpty { "x11.png" }) == null) return@Thread
                    "pattern" -> if (Phase2X11.capturePattern(ctx, arg.ifEmpty { "pattern.png" }) == null) return@Thread
                    "place" -> Phase2X11.placeWindows()
                    "xeyes" -> {
                        Phase2X11.ensureXvfb(ctx)
                        val xb = java.io.File(Phase2Data.rootfsDir(ctx), "usr/bin/xeyes")
                        val pb = ProcessBuilder(
                            Phase2Box64.libbox64(ctx).path, xb.absolutePath,
                        ).apply { environment().clear(); environment().putAll(Phase2X11.clientEnv(ctx)) }
                        val pp = pb.start()
                        Phase2X11.say("XEYES pid=${Phase2X11.pidOf(pp)}")
                        Phase2X11.g3exe("XEYES", ctx, Phase2X11.pidOf(pp))
                        Thread { Phase2X11.drain("XEYES_ERR", pp.errorStream) }.apply {
                            name = "phase2-xeyes-err"
                        }.start()
                        Thread.sleep(3000)
                        // B-063: put Notepad++ and xeyes side by side (no window manager).
                        Phase2X11.placeWindows()
                        Phase2X11.captureX11(ctx, arg.ifEmpty { "xeyes.png" })
                    }
                    "wineboot" -> Phase2Wine.boot(ctx, arg.ifEmpty { null })
                    "npp" -> Phase2Wine.startNpp(ctx)
                    "pin" -> Phase2Wine.setPin(ctx, arg)
                    "fetchwine" -> Phase2Data.fetchWine(ctx)
                    "fetchnpp" -> Phase2Data.fetchNpp(ctx)
                    "fetchall" -> {
                        Phase2Data.fetchAll(ctx)
                        Phase2Data.say("PHASE2_T3_DONE what=all (broadcast)")
                    }
                    "type" -> Phase2X11.typeViaXtest(ctx, arg)
                    "xctrl" -> {
                        val sym = when (arg.trim().lowercase()) {
                            "a" -> 0x61
                            "c" -> 0x63
                            "v" -> 0x76
                            else -> 0
                        }
                        if (sym == 0) Phase2X11.say("PHASE2X xctrl wants a/c/v, got [$arg]")
                        else Phase2X11.typeCtrl(ctx, sym)
                    }
                    "wineexec" -> Phase2Wine.execTree(ctx, arg)
                    "wsrv" -> Phase2Wine.ensureWineserver(ctx)
                    "killall" -> { Phase2Wine.reapLeftoverProcesses(ctx, "killall"); Phase2Wine.killWine(); Phase2X11.killXvfb() }
                    "runbin" -> Phase2X11.runGuestShell(ctx, arg)
                    else -> Phase2X11.say("PHASE2X unknown [$what] codes=${what.map { it.code }.take(12)}")
                }
            } catch (t: Throwable) {
                Phase2X11.say("PHASE2X_FAIL $what $t")
            }
            Phase2Wine.writeLog(ctx)
            Phase2X11.writeLog(ctx)
        }, "phase2-x").start()
    }
}
