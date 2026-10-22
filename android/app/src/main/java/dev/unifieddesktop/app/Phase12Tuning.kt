package dev.unifieddesktop.app

/**
 * Phase 12 latency switches (B-051/B2). Every switch is off by default so a BEFORE run and an
 * AFTER run use the same build. The harness sets a named profile with
 * `phase4x p12lat:0|1|2|3`; encoder keys are read at the next video start, so the link is
 * restarted for an A/B run.
 *
 * Profiles (each one builds on 1):
 *   0  off (Phase 5 behaviour)
 *   1  A8: QTI slice spacing 4, one redraw 8 ms after a key, TCP_NODELAY
 *   2  1 plus slice spacing 1 (smaller slices) and an immediate redraw
 *   3  2 plus a second redraw 8 ms later (first one may still be the pre-key picture)
 *
 * Already on since Phase 5 and therefore not switched here: no B-frames, KEY_LOW_LATENCY,
 * Qualcomm low-latency vendor key, 60 fps pacing, 2-frame video queue cap, keyframe on
 * queue pressure, TCP_NODELAY on the PC side.
 */
object Phase12Tuning {
    /** Slice output on the encoder (smaller access units, less waiting for a full frame). */
    @Volatile var lowLatencyEncoder = false
        private set

    /** Queue redraw(s) right after a key is dispatched, so the flash frame leaves at once. */
    @Volatile var inputTriggeredFrame = false
        private set

    /** TCP_NODELAY on the phone's TLS socket (the PC already sets it). */
    @Volatile var tcpNoDelay = false
        private set

    /** Rows per QTI slice; 0 leaves the vendor key out. */
    @Volatile var sliceRows = 4
        private set

    /** Delay of the first input redraw; 8 ms lets the view draw the flash first. */
    @Volatile var inputRedrawDelayMs = 8
        private set

    /** Send a second redraw one 8 ms frame later (profile 3). */
    @Volatile var inputRedrawTwice = false
        private set

    /** Last profile applied, for logs and tests. */
    @Volatile var profile = 0
        private set

    fun setAll(on: Boolean) = setProfile(if (on) 1 else 0)

    fun setProfile(p: Int) {
        when (p) {
            0 -> apply(false, 4, 8, false)
            1 -> apply(true, 4, 8, false)
            2 -> apply(true, 1, 0, false)
            3 -> apply(true, 1, 0, true)
            else -> return
        }
        profile = p
    }

    /** Parse the harness argument. Unknown or out-of-range values change nothing. */
    fun configure(spec: String): Boolean {
        val p = spec.trim().toIntOrNull() ?: return false
        if (p !in 0..3) return false
        setProfile(p)
        return true
    }

    private fun apply(encoder: Boolean, rows: Int, delayMs: Int, twice: Boolean) {
        lowLatencyEncoder = encoder
        inputTriggeredFrame = encoder
        tcpNoDelay = encoder
        sliceRows = rows
        inputRedrawDelayMs = delayMs
        inputRedrawTwice = twice
    }

    /**
     * Redraw delays for one key-down, relative to dispatch. Empty means no input-triggered
     * frame. Pure so the profile plan is testable without an encoder.
     */
    fun redrawDelaysMs(): IntArray {
        if (!inputTriggeredFrame) return IntArray(0)
        val first = inputRedrawDelayMs.coerceAtLeast(0)
        return if (inputRedrawTwice) intArrayOf(first, first + 8) else intArrayOf(first)
    }

    fun describe(): String =
        "P12TUNING profile=$profile encoder=$lowLatencyEncoder inputFrame=$inputTriggeredFrame " +
            "nodelay=$tcpNoDelay slices=$sliceRows redrawMs=${redrawDelaysMs().joinToString(",")}"
}
