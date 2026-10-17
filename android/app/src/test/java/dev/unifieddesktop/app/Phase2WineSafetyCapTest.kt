package dev.unifieddesktop.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision logic of the app-side safety cap (bug B-022): a wine run must never
 * grow past 40 processes of our uid or drop MemAvailable below 600 MB before
 * the guard kills the whole tree.
 */
class Phase2WineSafetyCapTest {
    @Test fun tripsAboveFortyProcesses() {
        assertTrue(Phase2Wine.safetyCapTrips(41, 5000))
        assertFalse(Phase2Wine.safetyCapTrips(40, 5000))
    }

    @Test fun tripsBelowSixHundredMb() {
        assertTrue(Phase2Wine.safetyCapTrips(5, 599))
        assertFalse(Phase2Wine.safetyCapTrips(5, 600))
    }
}
