package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the idempotent decision behind the prefix module symlinks (B-023):
 * only modules missing from the Windows system directory are linked, so a
 * repeated call on a populated prefix writes nothing.
 */
class Phase2WinePrefixBinariesTest {
    @Test fun linksOnlyMissingModules() {
        assertEquals(
            listOf("explorer.exe", "winex11.drv"),
            Phase2Wine.farmPlan(
                listOf("ntdll.dll", "explorer.exe", "winex11.drv"),
                setOf("ntdll.dll", "kernel32.dll")))
    }

    @Test fun nothingToLinkWhenAllPresent() {
        assertEquals(
            emptyList<String>(),
            Phase2Wine.farmPlan(listOf("a.dll", "b.exe"), setOf("a.dll", "b.exe")))
    }
}
