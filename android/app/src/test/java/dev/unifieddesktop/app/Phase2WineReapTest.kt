package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The B-029 reaper decision (round 5): kill every process of our uid except the
 * app's own process family (their cmdline starts with the package name - the main
 * process and sandboxed children like :black). Wine children (wineboot.exe,
 * services.exe, explorer.exe, ...) and Xvfb never match that prefix, so the one
 * orphan set per boot of round 4 gets reaped at every boot and every `killall`.
 */
class Phase2WineReapTest {
    private val pkg = "dev.unifieddesktop.app"
    private val uid = 10466
    private val me = 18942

    private fun f(pid: Int, uid: Int = this.uid, cmd: String): Phase2Wine.ProcFact =
        Phase2Wine.ProcFact(pid, uid, cmd)

    @Test fun reapsWineChildrenAndXvfb() {
        val procs = listOf(
            f(18942, cmd = pkg),                 // our own pid; also excluded by pid
            f(100, cmd = "wineboot.exe"),
            f(101, cmd = "services.exe"),
            f(102, cmd = "explorer.exe"),
            f(103, cmd = "Xvfb :0 -screen 0 1280x720x24"),
            f(104, cmd = "/data/data/$pkg/files/win86/wine/wine-11.19-amd64/bin/wineserver"),
        )
        assertEquals(listOf(100, 101, 102, 103, 104), Phase2Wine.reapSelection(me, uid, pkg, procs))
    }

    @Test fun keepsTheAppProcessFamily() {
        val procs = listOf(
            f(200, cmd = pkg),
            f(201, cmd = "$pkg:black"),
        )
        assertEquals(emptyList<Int>(), Phase2Wine.reapSelection(me, uid, pkg, procs))
    }

    @Test fun keepsSelfEvenWithForeignCmdline() {
        val procs = listOf(f(me, cmd = "something-else"))
        assertEquals(emptyList<Int>(), Phase2Wine.reapSelection(me, uid, pkg, procs))
    }

    @Test fun neverTouchesOtherUids() {
        val procs = listOf(f(300, uid = 10123, cmd = "wineboot.exe"), f(301, uid = 0, cmd = "init"))
        assertEquals(emptyList<Int>(), Phase2Wine.reapSelection(me, uid, pkg, procs))
    }

    @Test fun emptyCmdlineIsReaped() {
        val procs = listOf(f(400, cmd = ""))
        assertEquals(listOf(400), Phase2Wine.reapSelection(me, uid, pkg, procs))
    }
}
