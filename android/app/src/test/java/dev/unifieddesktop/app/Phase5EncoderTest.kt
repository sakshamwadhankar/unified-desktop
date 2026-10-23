package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 13 latency: a frame composed while the typing-urgency window is open must skip the
 * governor's fps pacing; outside it, pacing must apply again (sustained animation stays at
 * the thermal-safe rate).
 */
class Phase5EncoderTest {

    @Test
    fun frame_is_urgent_only_inside_the_window() {
        val u = Phase5Encoder.UrgentFrames(windowNs = 150_000_000L)
        val t0 = 1_000_000_000L
        assertFalse("no input yet: paced", u.due(t0))
        u.arm(t0)
        assertTrue("the frame the key caused", u.due(t0))
        assertTrue("just inside", u.due(t0 + 149_000_000L))
        assertFalse("window over", u.due(t0 + 150_000_000L))
        assertFalse("later still paced", u.due(t0 + 400_000_000L))
        assertEquals("the pre-input drop needs the arm time", t0, u.armedAt())
    }

    @Test
    fun a_second_key_extends_the_window() {
        val u = Phase5Encoder.UrgentFrames(windowNs = 150_000_000L)
        val t0 = 5_000_000_000L
        u.arm(t0)
        u.arm(t0 + 100_000_000L)
        assertTrue("extended past the first window", u.due(t0 + 200_000_000L))
        assertFalse("still expires", u.due(t0 + 250_000_000L))
    }

    @Test
    fun thermal_size_keeps_the_aspect_and_even_dimensions() {
        assertEquals(1296 to 810, Phase5Encoder.lowPowerSize(1728, 1080))
        assertEquals(1440 to 810, Phase5Encoder.lowPowerSize(1920, 1080))
        assertEquals(960 to 540, Phase5Encoder.lowPowerSize(1280, 720))
        assertEquals(1296 to 810, Phase5Encoder.lowPowerSize(1729, 1081))
    }
}
