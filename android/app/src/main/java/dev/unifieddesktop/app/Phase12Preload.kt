package dev.unifieddesktop.app

import android.content.Context

/**
 * Phase 12 B3: warm-up of the two slow starts, one on by default.
 *
 * Notepad++ pays for Xvfb + a Wine prefix boot + the app process on the first open of a
 * session. VS Code pays for code-server start-up on the first VS Code window. Both can be
 * started while the user is looking at the desktop instead.
 *
 * **Wine warm-up is on by default, but only for users who plausibly run Windows programs**
 * (manager, 2026-10-23 03:12, D21): at least one catalog Windows program is installed on
 * disk, or a Windows program was opened in the last 7 days. A user who only uses Android
 * apps or the browser never pays the ~80 MB for Xvfb + wineserver. It starts 3 s after the
 * desktop is shown so it cannot compete with the connection and the first frames (the first
 * frame reaches the PC within ~1 s). It reuses a live prefix without booting
 * (`WINE_LIVE reuse prefix; no boot, MSI registration kept`), so a reconnect never disturbs
 * an open app; a cold prefix goes through the same boot the first app open would trigger.
 * Measured on the phone: first Notepad++ 28.23 s cold with no prewarm, 11.95-12.15 s with a
 * warm prefix; the boot itself is ~5.5 s. VS Code preload stays off: code-server costs
 * ~150 MB RAM and its cold start already passes at 13.48 s. The harness can still force
 * either with `phase4x p12warm:1|wine|code|0` (forced runs skip the user condition).
 */
object Phase12Preload {
    private const val OFF = 0
    private const val WINE = 1
    private const val CODE = 2
    private const val BOTH = 3

    private const val PREFS = "phase12"
    private const val KEY_LAST_WINDOWS_OPEN = "last_windows_open_ms"
    private const val WEEK_MS = 7L * 24 * 3600 * 1000

    @Volatile
    private var mode = WINE

    /** True when the harness chose the mode: a measurement wants the work, not the policy. */
    @Volatile
    private var forced = false

    /** Parse and apply a switch value. Returns the new mode as text for the log. */
    fun configure(spec: String): String {
        val m = parseMode(spec)
        mode = m
        forced = true
        return describe()
    }

    /** Pure parse, unit-tested: 1/all -> both, wine, code, 0/off -> nothing. */
    fun parseMode(spec: String): Int = when (spec.trim().lowercase()) {
        "1", "all", "both", "wine+code", "code+wine" -> BOTH
        "wine", "npp", "notepad++" -> WINE
        "code", "vscode", "vs code" -> CODE
        else -> OFF
    }

    fun wineEnabled(): Boolean = mode == WINE || mode == BOTH

    fun codeEnabled(): Boolean = mode == CODE || mode == BOTH

    fun describe(): String =
        "P12WARM wine=${wineEnabled()} code=${codeEnabled()} (mode=$mode)"

    /**
     * The user condition (D21): warm Wine only when Windows use is plausible. [installed] is
     * the number of catalog Windows programs found on disk; [lastOpenMs] is the last Windows
     * program open (0 = never). Pure and unit-tested.
     */
    internal fun wineWarmWanted(nowMs: Long, installed: Int, lastOpenMs: Long): Boolean =
        installed > 0 || (lastOpenMs > 0 && nowMs - lastOpenMs <= WEEK_MS)

    /** Remember that the user opened a Windows program (persists across app restarts). */
    fun noteWindowsOpen(ctx: Context) {
        prefs(ctx).edit().putLong(KEY_LAST_WINDOWS_OPEN, System.currentTimeMillis()).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun lastWindowsOpenMs(ctx: Context): Long =
        prefs(ctx).getLong(KEY_LAST_WINDOWS_OPEN, 0L)

    /** Catalog Windows programs whose executable is actually on disk. */
    private fun installedWindowsCount(ctx: Context): Int =
        Installed.read(ctx).keys.count { Phase2Wine.installedExe(ctx, it) != null }

    /**
     * Start the enabled warm-ups. Called when the desktop Presentation is shown, so the work
     * happens once per session on background threads and never blocks the UI.
     */
    fun start(ctx: Context) {
        if (!wineEnabled() && !codeEnabled()) return
        val app = ctx.applicationContext ?: ctx
        if (wineEnabled()) {
            Thread({
                val installed = installedWindowsCount(app)
                val last = lastWindowsOpenMs(app)
                val wanted = forced || wineWarmWanted(System.currentTimeMillis(), installed, last)
                android.util.Log.i(
                    "Phase12",
                    "P12PREWARM wine wanted=$wanted forced=$forced installed=$installed " +
                        "last_open_ms=$last"
                )
                if (!wanted) return@Thread
                // D21 (manager): not while the connection and the first frames settle.
                try {
                    Thread.sleep(3_000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val t0 = android.os.SystemClock.elapsedRealtime()
                val ok = Phase2Wine.prewarm(app)
                android.util.Log.i(
                    "Phase12",
                    "P12PREWARM wine ok=$ok ms=${android.os.SystemClock.elapsedRealtime() - t0}"
                )
            }, "p12-warm-wine").start()
        }
        if (codeEnabled()) {
            Thread({
                val t0 = android.os.SystemClock.elapsedRealtime()
                val r = CodeServerWarm.ensureRunning(app)
                android.util.Log.i(
                    "Phase12",
                    "P12PREWARM code ok=${r.ok} started=${r.started} port=${r.port} " +
                        "ms=${android.os.SystemClock.elapsedRealtime() - t0}"
                )
            }, "p12-warm-code").start()
        }
    }
}
