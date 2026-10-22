package dev.unifieddesktop.app

/**
 * Phase 12 B3: warm-up of the two slow starts, one on by default.
 *
 * Notepad++ pays for Xvfb + a Wine prefix boot + the app process on the first open of a
 * session. VS Code pays for code-server start-up on the first VS Code window. Both can be
 * started while the user is looking at the desktop instead.
 *
 * **Wine is on by default** (order of the manager, Phase 12 round 2). It reuses a live
 * prefix without booting (`WINE_LIVE reuse prefix; no boot, MSI registration kept`), so a
 * reconnect never disturbs an open app; a cold prefix goes through the same boot the first
 * app open would trigger. Measured on the phone: first Notepad++ 28.23 s cold with no
 * prewarm, 11.95 s with a warm prefix; the boot itself is ~5.5 s and starts after the
 * desktop is shown, on a background thread. VS Code preload stays off: code-server costs
 * ~150 MB RAM and its cold start already passes at 13.48 s. The harness can still force
 * either with `phase4x p12warm:1|wine|code|0`.
 */
object Phase12Preload {
    private const val OFF = 0
    private const val WINE = 1
    private const val CODE = 2
    private const val BOTH = 3

    @Volatile
    private var mode = WINE

    /** Parse and apply a switch value. Returns the new mode as text for the log. */
    fun configure(spec: String): String {
        val m = parseMode(spec)
        mode = m
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
     * Start the enabled warm-ups. Called when the desktop Presentation is shown, so the work
     * happens once per session on background threads and never blocks the UI.
     */
    fun start(ctx: android.content.Context) {
        if (!wineEnabled() && !codeEnabled()) return
        val app = ctx.applicationContext ?: ctx
        if (wineEnabled()) {
            Thread({
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
