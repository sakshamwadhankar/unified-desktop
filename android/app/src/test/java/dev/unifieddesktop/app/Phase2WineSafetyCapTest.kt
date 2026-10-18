package dev.unifieddesktop.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision logic of the app-side safety cap (bug B-022): a wine run must never
 * grow past 40 processes of our uid or drop MemAvailable below 600 MB before the
 * guard kills the whole tree. (160/400 was a round-4 mistake: the "49 procs"
 * that motivated the raise was the B-022 explorer-swarm pattern, not a normal
 * session. Restored 2026-10-18 round 5 per the manager's correction; normal
 * sessions run ~8-10 procs.)
 */
class Phase2WineSafetyCapTest {
    @Test fun tripsAboveMaxProcs() {
        assertTrue(Phase2Wine.safetyCapTrips(41, 5000))
        assertFalse(Phase2Wine.safetyCapTrips(40, 5000))
    }

    @Test fun tripsBelowMinMb() {
        assertTrue(Phase2Wine.safetyCapTrips(5, 599))
        assertFalse(Phase2Wine.safetyCapTrips(5, 600))
    }
}
