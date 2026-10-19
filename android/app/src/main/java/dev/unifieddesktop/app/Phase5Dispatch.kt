package dev.unifieddesktop.app

/**
 * Harness bridge for the running Phase-5 session (`adb shell am broadcast -n
 * dev.unifieddesktop.app/.HarnessReceiver --es phase4x p5test:12` / `p5text`).
 *
 * `p5text` answers with what the test desktop's EditText actually holds - the item-6
 * proof that PC keystrokes reached a real Android view - plus the bookkeeping counters.
 */
object Phase5Dispatch {
    @Volatile private var session: Phase5Session? = null

    fun bind(s: Phase5Session?) { session = s }

    fun session(): Phase5Session? = session

    fun lastText(): String {
        val s = session ?: return "(no live phase-5 session)"
        return s.debugText()
    }
}
