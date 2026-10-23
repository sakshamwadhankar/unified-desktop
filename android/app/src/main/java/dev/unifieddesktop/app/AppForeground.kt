package dev.unifieddesktop.app

/**
 * Step 10: whether any of our activities is in front.
 *
 * The pairing notification is a heads-up for the case where the owner is looking at
 * something else. Showing it while our own home screen (which already shows the code and
 * the Yes/No buttons) is in front duplicates the prompt and steals focus from it.
 */
object AppForeground {
    @Volatile
    private var resumed = 0

    val isForeground: Boolean
        get() = resumed > 0

    @Synchronized
    fun resumed() {
        resumed++
    }

    @Synchronized
    fun paused() {
        if (resumed > 0) resumed--
    }
}
