package dev.unifieddesktop.app

import android.content.Context
import android.content.Intent

/**
 * B-061: the engine's DaemonService is not started by the engine itself any more
 * (`isEnableDaemonService() = false` in [EngineApplication]'s ClientConfiguration), so an idle
 * app carries no foreground service and no notification. This object starts it when a hidden
 * Android app is launched and stops it when the last one closes.
 *
 * The service lives in the engine's `:black` process and is declared by the merged engine
 * manifest, so a plain start/stop by class name is enough; nothing here is privileged.
 *
 * The state is per process (app, `:black`, guest `:pN` processes each have their own instance);
 * that is enough because only the process that owns a guest activity makes the decisions, and
 * the worst case of a competing instance is one extra start/stop pair.
 */
object EngineDaemon {
    private const val SERVICE = "top.niunaijun.blackbox.core.system.DaemonService"

    private val lock = Any()
    private val live = LinkedHashSet<Any>()
    private var started = false

    /** A hidden app is being launched: bring the daemon up before the engine needs its services. */
    fun starting(ctx: Context) {
        val go = synchronized(lock) { !started }
        if (go) startService(ctx, "hidden app launching")
    }

    /** A guest activity resumed: idempotent per activity (resume can repeat without a destroy). */
    fun guestStarted(ctx: Context, key: Any) {
        val go = synchronized(lock) {
            live.add(key)
            !started
        }
        if (go) startService(ctx, "first guest resumed (live=${guests()})")
    }

    /** A guest activity was destroyed: stop the daemon with the last one. */
    fun guestStopped(ctx: Context, key: Any) {
        val stop = synchronized(lock) {
            live.remove(key)
            live.isEmpty()
        }
        if (!stop) return
        try {
            ctx.stopService(Intent().setClassName(ctx.packageName, SERVICE))
            synchronized(lock) { started = false }
            P7Log.say(ctx, "DAEMON stop (last hidden app closed, live=0)")
        } catch (t: Throwable) {
            P7Log.say(ctx, "DAEMON stop failed: $t")
        }
    }

    /**
     * App start: while no hidden app is alive the daemon must not run. Belt and braces for engine
     * builds whose internal start paths ignore the client configuration.
     */
    fun ensureIdle(ctx: Context) {
        val hasGuests = synchronized(lock) { live.isNotEmpty() }
        if (hasGuests) return
        try {
            ctx.stopService(Intent().setClassName(ctx.packageName, SERVICE))
            synchronized(lock) { started = false }
            P7Log.say(ctx, "DAEMON idle (no hidden app): stop requested")
        } catch (t: Throwable) {
            P7Log.say(ctx, "DAEMON idle stop failed: $t")
        }
    }

    fun guests(): Int = synchronized(lock) { live.size }

    private fun startService(ctx: Context, why: String) {
        try {
            ctx.startForegroundService(Intent().setClassName(ctx.packageName, SERVICE))
            synchronized(lock) { started = true }
            P7Log.say(ctx, "DAEMON start ($why)")
        } catch (t: Throwable) {
            synchronized(lock) { started = false }
            P7Log.say(ctx, "DAEMON start failed ($why): $t")
        }
    }
}
