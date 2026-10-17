package dev.unifieddesktop.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the byte pattern the prefix self-heal keys on (bug B-021).
 *
 * `src/test/resources/poisoned_system.reg` is the real 340-byte system.reg
 * captured on the phone on 2026-10-17: its [System\CurrentControlSet] link was
 * saved with an EMPTY target (`"SymbolicLinkValue"=hex(0):`), which made every
 * wine boot recurse in kernelbase's create_key until the stack overflowed.
 * The healthy hive saves the same value as `hex(6):` + the UTF-16LE target and
 * must never match the heal marker.
 */
class Phase2WineHealTest {
    private val poisoned: String by lazy {
        javaClass.getResourceAsStream("/poisoned_system.reg")!!
            .readBytes().toString(Charsets.ISO_8859_1)
    }

    @Test fun poisonedHiveMatchesTheHealMarker() {
        assertTrue(poisoned.contains("CurrentControlSet"))
        assertTrue(poisoned.contains(Phase2Wine.POISON_LINK))
    }

    @Test fun healthyHiveDoesNotMatchTheHealMarker() {
        val healthy = "\"SymbolicLinkValue\"=hex(6):5c,00,52,00,65,00,67,00,69,00,73,00," +
            "74,00,72,00,79,00,5c,00,4d,00,61,00,63,00,68,00,69,00,6e,00,65,00"
        assertFalse(healthy.contains(Phase2Wine.POISON_LINK))
    }
}
