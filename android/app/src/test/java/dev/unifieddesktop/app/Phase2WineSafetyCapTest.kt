package dev.unifieddesktop.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision logic of the app-side safety cap (bug B-022): a wine run must never
 * grow past 160 processes of our uid or drop MemAvailable below 400 MB before
 * the guard kills the whole tree. (Was 40/600 until 2026-10-18: a real Notepad++
 * launch legitimately hit 49 procs / 1.2 GB available and got killed mid-init.)
 */
class Phase2WineSafetyCapTest {
    @Test fun tripsAboveMaxProcs() {
        assertTrue(Phase2Wine.safetyCapTrips(161, 5000))
        assertFalse(Phase2Wine.safetyCapTrips(160, 5000))
    }

    @Test fun tripsBelowMinMb() {
        assertTrue(Phase2Wine.safetyCapTrips(5, 399))
        assertFalse(Phase2Wine.safetyCapTrips(5, 400))
    }
}
