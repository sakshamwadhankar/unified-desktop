package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 12 round 2: the pure decisions behind the X11 frame loop (skip identical pictures,
 * poll fast right after a key). The X socket and the video encoder need the phone.
 */
class Phase7X11FramesTest {

    @Test
    fun noPreviousPictureIsAlwaysAChange() {
        assertFalse(x11ImageUnchanged(null, byteArrayOf(1, 2, 3)))
    }

    @Test
    fun identicalBytesAreUnchanged() {
        assertTrue(x11ImageUnchanged(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
    }

    @Test
    fun oneDifferentPixelIsAChange() {
        assertFalse(x11ImageUnchanged(byteArrayOf(1, 2, 3), byteArrayOf(1, 9, 3)))
    }

    @Test
    fun aDifferentLengthIsAChange() {
        assertFalse(x11ImageUnchanged(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun fastPollRightAfterAKeyThenIdle() {
        assertEquals(8, x11PollDelayMs(0))
        assertEquals(8, x11PollDelayMs(299))
        assertEquals(120, x11PollDelayMs(300))
        assertEquals(120, x11PollDelayMs(10_000))
    }
}
