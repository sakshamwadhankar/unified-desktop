package dev.unifieddesktop.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase-7 log that survives the phone's fast logcat rotation: every line goes to logcat and is
 * appended to the app-private `files/p7.log` (read with `run-as dev.unifieddesktop.app`).
 */
object P7Log {
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun say(ctx: Context, line: String) {
        val stamped = fmt.format(Date()) + " " + line
        android.util.Log.i("Phase7", line)
        try {
            File(ctx.filesDir, "p7.log").appendText(stamped + "\n")
        } catch (_: Throwable) {
            // A log line must never break the caller.
        }
    }
}
