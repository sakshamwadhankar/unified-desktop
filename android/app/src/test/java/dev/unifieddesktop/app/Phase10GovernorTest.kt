package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase10GovernorTest {
    private fun input(
        lowMemory: Boolean = false,
        trim: Int = 0,
        availableKb: Long = 2_000_000L,
        thermal: Int = 0,
        visible: Boolean = true,
    ) = Phase10GovernorInput(lowMemory, trim, availableKb, thermal, visible)

    @Test
    fun pressureStepsInPhoneFirstOrder() {
        val p = input(lowMemory = true)
        val d1 = Phase10Governor.next(Phase10GovernorStage.NORMAL, p)
        val d2 = Phase10Governor.next(d1.next, p)
        val d3 = Phase10Governor.next(d2.next, p)
        val d4 = Phase10Governor.next(d3.next, p)
        val d5 = Phase10Governor.next(d4.next, p)

        assertTrue(d1.action is Phase10GovernorAction.StopHiddenWindows)
        assertEquals(30, (d2.action as Phase10GovernorAction.SetFps).fps)
        assertEquals(15, (d3.action as Phase10GovernorAction.SetFps).fps)
        assertTrue(d4.action is Phase10GovernorAction.LowerResolution)
        assertTrue(d5.action is Phase10GovernorAction.WarnPc)
    }

    @Test
    fun trimMemoryAndThermalAreIndependentTriggers() {
        assertEquals("trim=10", Phase10Governor.pressure(input(trim = 10)))
        assertEquals("thermal=2", Phase10Governor.pressure(input(thermal = 2)))
        assertEquals("memAvailableKb=255999", Phase10Governor.pressure(input(availableKb = 255_999)))
        assertEquals("memAvailableKb=0", Phase10Governor.pressure(input(availableKb = 0)))
        assertEquals(null, Phase10Governor.pressure(input(availableKb = 262_144)))
    }

    @Test
    fun healthyInputDoesNotChangeStageOrAct() {
        val d = Phase10Governor.next(Phase10GovernorStage.FPS_15, input())
        assertEquals(Phase10GovernorStage.FPS_15, d.next)
        assertTrue(d.action is Phase10GovernorAction.None)
    }
}
