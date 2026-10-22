package dev.unifieddesktop.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase12TuningTest {
    private fun reset() = Phase12Tuning.setProfile(0)

    @Test
    fun profile_zero_turns_everything_off() {
        Phase12Tuning.setProfile(3)
        reset()
        assertEquals(0, Phase12Tuning.profile)
        assertFalse(Phase12Tuning.lowLatencyEncoder)
        assertFalse(Phase12Tuning.inputTriggeredFrame)
        assertFalse(Phase12Tuning.tcpNoDelay)
        assertEquals(0, Phase12Tuning.redrawDelaysMs().size)
    }

    @Test
    fun profile_one_is_the_a8_set() {
        reset()
        Phase12Tuning.setProfile(1)
        assertEquals(1, Phase12Tuning.profile)
        assertTrue(Phase12Tuning.lowLatencyEncoder)
        assertTrue(Phase12Tuning.inputTriggeredFrame)
        assertTrue(Phase12Tuning.tcpNoDelay)
        assertEquals(4, Phase12Tuning.sliceRows)
        assertArrayEquals(intArrayOf(8), Phase12Tuning.redrawDelaysMs())
    }

    @Test
    fun profile_two_uses_one_row_slices_and_an_immediate_redraw() {
        reset()
        Phase12Tuning.setProfile(2)
        assertEquals(1, Phase12Tuning.sliceRows)
        assertArrayEquals(intArrayOf(0), Phase12Tuning.redrawDelaysMs())
    }

    @Test
    fun profile_three_adds_the_second_redraw() {
        reset()
        Phase12Tuning.setProfile(3)
        assertEquals(1, Phase12Tuning.sliceRows)
        assertArrayEquals(intArrayOf(0, 8), Phase12Tuning.redrawDelaysMs())
        assertTrue(Phase12Tuning.describe().contains("redrawMs=0,8"))
    }

    @Test
    fun set_all_true_is_profile_one_and_false_is_off() {
        reset()
        Phase12Tuning.setAll(true)
        assertEquals(1, Phase12Tuning.profile)
        Phase12Tuning.setAll(false)
        assertEquals(0, Phase12Tuning.profile)
    }

    @Test
    fun unknown_profile_changes_nothing() {
        reset()
        Phase12Tuning.setProfile(2)
        assertFalse(Phase12Tuning.configure("9"))
        assertFalse(Phase12Tuning.configure("fast"))
        assertEquals(2, Phase12Tuning.profile)
        assertEquals(1, Phase12Tuning.sliceRows)
        assertTrue(Phase12Tuning.lowLatencyEncoder)
        assertTrue(Phase12Tuning.configure(" 3 "))
        assertEquals(3, Phase12Tuning.profile)
    }
}
