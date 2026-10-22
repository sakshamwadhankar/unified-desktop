package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
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
}
