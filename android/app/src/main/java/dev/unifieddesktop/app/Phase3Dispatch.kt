package dev.unifieddesktop.app

import android.content.Context

/**
 * Phase-3 harness actions, driven by the debug receiver / activity extra
 * "phase3x" (same pattern as Phase2Dispatch, B-028: broadcasts never replay):
 *
 *   adb shell am broadcast -n dev.unifieddesktop.app/.HarnessReceiver --es phase3x probe
 *
 * Each action runs on its own thread; the result lands in files/phase3/p3.log.
 */
object Phase3Dispatch {
    fun runX(ctx: Context, what: String) {
        Thread({
            try {
                val arg = what.substringAfter(':', "")
                when (what.substringBefore(':')) {
                    "probe" -> Phase3Native.probeAll(ctx)
                    "exec" -> Phase3Native.probeExec(ctx)
                    "script" -> Phase3Native.runScriptFile(ctx, arg)
                    "node" -> Phase3Native.node(ctx, arg.ifEmpty { "console.log(process.version)" }, "node-arg")
                    "cs-fetch" -> Phase3Data.fetch(ctx, force = arg.trim() == "force")
                    "cs-verify" -> { Phase3Data.verify(ctx); Phase3Data.linkNativeModules(ctx) }
                    "cs-start" -> Phase3Server.start(ctx)
                    "cs-stop" -> Phase3Server.stop(ctx)
                    "cs-status" -> Phase3Server.status(ctx)
                    // The WebView surface. Started *by the app* because the gate
                    // activity is exported=false and shell (uid 2000) may not start
                    // non-exported activities on API 37 (measured: SecurityException).
                    "cs-web" -> {
                        Phase3Native.say("CS_WEB launch")
                        ctx.startActivity(
                            android.content.Intent(ctx, Phase3CodeActivity::class.java)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    // Run a DOM probe in the live WebView ("state" / "quick") and log it:
                    // the gate flow's eyes, since each step needs the right keystrokes.
                    "cs-dom" -> Phase3CodeActivity.probe(arg)
                    // Inject a key chord into the WebView, e.g. cs-key:c113_59_44 = Ctrl+Shift+P.
                    "cs-key" -> Phase3CodeActivity.chord(arg)
                    // code-server's ?folder= deep link: open a folder without the dialog
                    // (the workbench's own "open folder" operation, via URL navigation).
                    "cs-gofolder" -> Phase3CodeActivity.goFolder(arg)
                    else -> Phase3Native.say("PHASE3X unknown [$what]")
                }
            } catch (t: Throwable) {
                Phase3Native.say("PHASE3X_FAIL $what $t")
            }
            Phase3Native.writeLog(ctx)
        }, "phase3-x").start()
    }
}
