package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B-088/B-089: only catalog X11 programs are closed before the next catalog launch. */
class Phase8CatalogProcessesTest {

    private fun cmd(vararg argv: String) = argv.joinToString("\u0000")

    @Test
    fun identifiesEveryCatalogProgramByItsExecutable() {
        assertEquals("minesweeper", Phase2Wine.catalogProcessId(
            cmd( "/data/app/libbox64.so", "/wine/lib/wine/x86_64-windows/wine", "winemine.exe")))
        assertEquals("notepadpp", Phase2Wine.catalogProcessId(
            cmd("/data/app/libbox64.so", "/wine/wine", """C:\Program Files\Notepad++\notepad++.exe""")))
        assertEquals("sevenzip", Phase2Wine.catalogProcessId(
            cmd("/data/app/libbox64.so", "/wine/wine", """C:\Program Files\7-Zip\7zFM.exe""")))
        assertEquals("xcalc", Phase2Wine.catalogProcessId(
            cmd("/data/app/libbox64.so", "/rootfs/usr/bin/xcalc", "-fn", "fixed")))
        assertEquals("xeyes", Phase2Wine.catalogProcessId(
            cmd("/data/app/libbox64.so", "/rootfs/usr/bin/xeyes")))
        assertEquals("xmessage", Phase2Wine.catalogProcessId(
            cmd("/data/app/libbox64.so", "/rootfs/usr/bin/xmessage", "-center", "hi")))
    }

    @Test
    fun wineAndSystemProcessesAreNeverCatalogPrograms() {
        assertNull(Phase2Wine.catalogProcessId(cmd("/wine/bin/wineserver", "-p", "3600")))
        assertNull(Phase2Wine.catalogProcessId(cmd("/wine/bin/wineboot.exe", "-i")))
        assertNull(Phase2Wine.catalogProcessId(cmd("/wine/lib/wine/x86_64-windows/explorer.exe")))
        assertNull(Phase2Wine.catalogProcessId(cmd("/system/bin/app_process", "dev.unifieddesktop.app")))
        assertNull(Phase2Wine.catalogProcessId(""))
    }

    @Test
    fun closeSelectionKeepsTheNamedAppAndClosesTheOthers() {
        val winemine = cmd("/box64", "/wine", "winemine.exe")
        val xcalc = cmd("/box64", "/rootfs/usr/bin/xcalc")
        assertTrue(Phase2Wine.shouldCloseCatalogProcess(winemine, "xcalc"))
        assertTrue(Phase2Wine.shouldCloseCatalogProcess(xcalc, "minesweeper"))
        assertFalse(Phase2Wine.shouldCloseCatalogProcess(xcalc, "xcalc"))
        assertTrue(Phase2Wine.shouldCloseCatalogProcess(xcalc, null))
        assertFalse(Phase2Wine.shouldCloseCatalogProcess(cmd("/wine/explorer.exe"), "xcalc"))
    }
}
