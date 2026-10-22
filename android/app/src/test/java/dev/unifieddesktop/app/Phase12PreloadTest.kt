package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase12PreloadTest {
    @Test
    fun parse_mode_is_explicit_and_defaults_to_off() {
        assertEquals(3, Phase12Preload.parseMode("1"))
        assertEquals(3, Phase12Preload.parseMode("all"))
        assertEquals(1, Phase12Preload.parseMode("wine"))
        assertEquals(1, Phase12Preload.parseMode("Notepad++"))
        assertEquals(2, Phase12Preload.parseMode("code"))
        assertEquals(2, Phase12Preload.parseMode("VS Code"))
        assertEquals(0, Phase12Preload.parseMode("0"))
        assertEquals(0, Phase12Preload.parseMode("off"))
        assertEquals(0, Phase12Preload.parseMode(""))
        assertEquals(0, Phase12Preload.parseMode("yes"))
    }

    @Test
    fun shipped_default_warms_wine_only() {
        // DECISIONS D21: the Wine warm-up is on for every user; code-server stays off.
        assertTrue(Phase12Preload.wineEnabled())
        assertFalse(Phase12Preload.codeEnabled())
        assertTrue(Phase12Preload.describe(), Phase12Preload.describe().contains("wine=true"))
    }

    @Test
    fun warm_only_when_windows_use_is_plausible() {
        // Manager 03:12: no 192 MB for a user who never runs Windows programs.
        val now = 1_700_000_000_000L
        val week = 7L * 24 * 3600 * 1000
        assertTrue(Phase12Preload.wineWarmWanted(now, 1, 0))                    // installed on disk
        assertTrue(Phase12Preload.wineWarmWanted(now, 2, now - 8 * week))      // installed wins
        assertTrue(Phase12Preload.wineWarmWanted(now, 0, now - 6 * 24 * 3600 * 1000L))
        assertTrue(Phase12Preload.wineWarmWanted(now, 0, now - week))          // exactly 7 days
        assertFalse(Phase12Preload.wineWarmWanted(now, 0, now - week - 1))     // just over 7 days
        assertFalse(Phase12Preload.wineWarmWanted(now, 0, 0))                  // never opened
    }
}
