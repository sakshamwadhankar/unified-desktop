package dev.unifieddesktop.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-057 (phone side): a sync-frame request that produces no keyframe within a second means the
 * encoder is stuck, and the session then restarts the whole encoder pipeline. The watchdog is a
 * pure state machine so it is testable on the JVM (no MediaCodec, no GL).
 */
class Phase5KeyframeWatchdogTest {
    @Test fun idleWatchdogNeverExpires() {
        val w = KeyframeWatchdog()
        assertFalse(w.expired(10_000))
    }

    @Test fun expiresOneSecondAfterTheRequest() {
        val w = KeyframeWatchdog()
        w.requested(1_000)
        assertFalse(w.expired(1_999))
        assertTrue(w.expired(2_000))
    }

    @Test fun aKeyframeEndsTheWait() {
        val w = KeyframeWatchdog()
        w.requested(1_000)
        w.keyframeSeen()
        assertFalse(w.expired(5_000))
    }

    @Test fun expiresOnceThenWaitsForANewRequest() {
        val w = KeyframeWatchdog()
        w.requested(1_000)
        assertTrue(w.expired(2_000))
        assertFalse(w.expired(2_001))
        assertFalse(w.expired(9_000))
        w.requested(9_000)
        assertFalse(w.expired(9_999))
        assertTrue(w.expired(10_000))
    }

    @Test fun aSecondRequestWhileArmedDoesNotPostponeTheDeadline() {
        val w = KeyframeWatchdog()
        w.requested(1_000)
        w.requested(1_800)
        assertTrue(w.expired(2_000))
    }

    @Test fun keyframeAfterTheDeadlineStillClearsTheState() {
        val w = KeyframeWatchdog()
        w.requested(1_000)
        assertTrue(w.expired(2_000))
        w.keyframeSeen()
        assertFalse(w.expired(3_000))
    }
}
