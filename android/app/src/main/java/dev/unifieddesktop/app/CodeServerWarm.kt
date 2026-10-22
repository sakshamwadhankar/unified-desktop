package dev.unifieddesktop.app

import android.content.Context

/**
 * B3/Phase 12: one guarded path to "code-server is up", shared by the VS Code window and the
 * optional desktop pre-warm. `@Synchronized` matters: a user can click VS Code while the
 * pre-warm thread is still starting the server, and two `Phase3Server.start` calls would race
 * for the same session file and port.
 */
object CodeServerWarm {
    data class Result(val started: Boolean, val ok: Boolean, val port: Int, val waitMs: Long)

    @Synchronized
    fun ensureRunning(
        context: Context,
        timeoutMs: Long = CodeServerGate.MAX_MS,
    ): Result {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val sf = Phase3Server.sessionFile(context)
        var port = -1
        var code = 0
        if (sf.isFile) {
            sf.useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("port=")) {
                        port = line.substringAfter('=').trim().toIntOrNull() ?: -1
                    }
                }
            }
            if (port > 0) code = Phase3Server.healthz(port).first
        }
        P7Log.say(context, "VSCODE gate session=${sf.isFile} port=$port healthz=$code")
        if (!CodeServerGate.needsStart(sf.isFile && port > 0, code)) {
            return Result(false, true, port, android.os.SystemClock.elapsedRealtime() - t0)
        }
        P7Log.say(context, "VSCODE starting code-server (wait up to $timeoutMs ms)")
        val session = Phase3Server.start(context, timeoutMs)
        if (session == null) {
            P7Log.say(context, "VSCODE start FAILED")
            return Result(true, false, -1, android.os.SystemClock.elapsedRealtime() - t0)
        }
        P7Log.say(context, "VSCODE start ok port=${session.port} health=${session.startMs} ms")
        return Result(true, true, session.port, android.os.SystemClock.elapsedRealtime() - t0)
    }
}
