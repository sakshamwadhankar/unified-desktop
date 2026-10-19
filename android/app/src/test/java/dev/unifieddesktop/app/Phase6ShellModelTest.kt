package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase-6 window-manager policy: z-order, focus, clamping, resize limits, hit-tests. */
class Phase6ShellModelTest {

    private fun model() = ShellModel(desktopW = 1920, desktopH = 1080, taskbarH = 60)

    @Test
    fun workAreaStopsAtTheTaskbar() {
        val m = model()
        assertEquals(1920, m.desktopW)
        assertEquals(1020, m.workH)
    }

    @Test
    fun openFocusesAndStacksOnTop() {
        val m = model()
        val a = m.open("A", 800, 600)
        val b = m.open("B", 800, 600)
        assertEquals(listOf(a.id, b.id), m.all().map { it.id })
        assertEquals(b.id, m.focusedId)
        // focusing the lower window raises it
        assertTrue(m.focus(a.id))
        assertEquals(listOf(b.id, a.id), m.all().map { it.id })
        assertEquals(a.id, m.focusedId)
    }

    @Test
    fun openClampsIntoTheWorkArea() {
        val m = model()
        val w = m.open("W", 5000, 5000, x = 9000, y = 9000)
        assertEquals(1920, w.w)
        assertEquals(1020, w.h)
        assertEquals(0, w.x)
        assertEquals(0, w.y)
        assertTrue(w.right <= 1920)
        assertTrue(w.bottom <= m.workH)
    }

    @Test
    fun openRefusesToGoBelowTheMinimumSize() {
        val m = model()
        val w = m.open("W", 10, 10)
        assertEquals(m.minW, w.w)
        assertEquals(m.minH, w.h)
    }

    @Test
    fun closeFallsBackToTheNextTopmostVisible() {
        val m = model()
        val a = m.open("A", 400, 300)
        val b = m.open("B", 400, 300)
        val c = m.open("C", 400, 300)
        assertEquals(c.id, m.focusedId)
        assertTrue(m.close(c.id))
        assertEquals(b.id, m.focusedId)
        assertTrue(m.minimize(b.id))
        assertEquals(a.id, m.focusedId)
        assertTrue(m.close(a.id))
        assertNull("nothing visible is left to focus", m.focusedId)
        assertEquals(1, m.size())
    }

    @Test
    fun minimizeHidesAndRestoreRaises() {
        val m = model()
        val a = m.open("A", 400, 300)
        val b = m.open("B", 400, 300)
        assertTrue(m.minimize(a.id))
        assertTrue(m.byId(a.id)!!.minimized)
        // a minimized window is not hit-tested and not the focus fallback
        assertNull(m.windowAt(a.x + 10, a.y + 10))
        assertEquals(b.id, m.focusedId)
        assertTrue(m.restore(a.id))
        assertFalse(m.byId(a.id)!!.minimized)
        assertEquals(a.id, m.focusedId)
        assertEquals(a.id, m.all().last().id)
    }

    @Test
    fun maximizeAndRestoreKeepTheOldRectangle() {
        val m = model()
        val w = m.open("W", 700, 500, x = 120, y = 90)
        assertTrue(m.toggleMaximize(w.id))
        assertEquals(0, w.x); assertEquals(0, w.y)
        assertEquals(1920, w.w); assertEquals(1020, w.h)
        assertTrue(w.maximized)
        assertTrue(m.toggleMaximize(w.id))
        assertEquals(120, w.x); assertEquals(90, w.y)
        assertEquals(700, w.w); assertEquals(500, w.h)
        assertFalse(w.maximized)
    }

    @Test
    fun moveClampsAtEveryEdgeAndIgnoresMaximized() {
        val m = model()
        val w = m.open("W", 600, 400, x = 100, y = 100)
        m.moveBy(w.id, -5000, -5000)
        assertEquals(0, w.x); assertEquals(0, w.y)
        m.moveBy(w.id, 5000, 5000)
        assertEquals(1920 - 600, w.x)
        assertEquals(m.workH - 400, w.y)
        m.toggleMaximize(w.id)
        m.moveBy(w.id, 100, 100)
        assertEquals(0, w.x); assertEquals(0, w.y)
        assertEquals(1920, w.w)
    }

    @Test
    fun resizeFromTheRightAndBottomGrowsAndStopsAtTheWorkArea() {
        val m = model()
        val w = m.open("W", 600, 400, x = 100, y = 100)
        assertTrue(m.resizeBy(w.id, ShellModel.EDGE_R or ShellModel.EDGE_B, 50, 30))
        assertEquals(650, w.w); assertEquals(430, w.h)
        assertEquals(100, w.x); assertEquals(100, w.y)
        m.resizeBy(w.id, ShellModel.EDGE_R or ShellModel.EDGE_B, 5000, 5000)
        assertEquals(1920 - 100, w.w)
        assertEquals(m.workH - 100, w.h)
    }

    @Test
    fun resizeFromTheLeftAndTopMovesTheOrigin() {
        val m = model()
        val w = m.open("W", 600, 400, x = 300, y = 200)
        m.resizeBy(w.id, ShellModel.EDGE_L or ShellModel.EDGE_T, -100, -50)
        assertEquals(200, w.x); assertEquals(150, w.y)
        assertEquals(700, w.w); assertEquals(450, w.h)
        // dragging past the top-left corner stops at the work-area origin
        m.resizeBy(w.id, ShellModel.EDGE_L or ShellModel.EDGE_T, -9000, -9000)
        assertEquals(0, w.x); assertEquals(0, w.y)
        assertEquals(900, w.w); assertEquals(600, w.h)
    }

    @Test
    fun resizeNeverGoesBelowTheMinimumSize() {
        val m = model()
        val w = m.open("W", 600, 400, x = 300, y = 200)
        m.resizeBy(w.id, ShellModel.EDGE_R or ShellModel.EDGE_B, -9000, -9000)
        assertEquals(m.minW, w.w)
        assertEquals(m.minH, w.h)
        assertEquals(300, w.x); assertEquals(200, w.y)
        // dragging the left edge right past the right edge: the left edge stops at minW
        // and the RIGHT edge stays where it was (that edge is not being dragged)
        val w2 = m.open("V", 600, 400, x = 300, y = 500)
        m.resizeBy(w2.id, ShellModel.EDGE_L, 9000, 0)
        assertEquals(m.minW, w2.w)
        assertEquals(900, w2.right)
        assertEquals(540, w2.x)
    }

    @Test
    fun resizeOfAMaximizedWindowIsIgnored() {
        val m = model()
        val w = m.open("W", 600, 400)
        m.toggleMaximize(w.id)
        m.resizeBy(w.id, ShellModel.EDGE_R, -300, 0)
        assertEquals(1920, w.w)
        assertTrue(w.maximized)
    }

    @Test
    fun edgeHitTestCoversSidesCornersAndTheInside() {
        val m = model()
        val w = m.open("W", 600, 400, x = 100, y = 100)
        assertEquals(0, m.edgesAt(400, 300, w), ) // middle: no handle
        assertEquals(ShellModel.EDGE_L, m.edgesAt(102, 300, w))
        assertEquals(ShellModel.EDGE_R, m.edgesAt(698, 300, w))
        assertEquals(ShellModel.EDGE_T, m.edgesAt(400, 101, w))
        assertEquals(ShellModel.EDGE_B, m.edgesAt(400, 498, w))
        assertEquals(ShellModel.EDGE_L or ShellModel.EDGE_T, m.edgesAt(101, 101, w))
        assertEquals(ShellModel.EDGE_R or ShellModel.EDGE_B, m.edgesAt(699, 499, w))
        assertEquals(ShellModel.EDGE_L or ShellModel.EDGE_B, m.edgesAt(100, 500, w))
        // the handle band is exactly [edge - HANDLE, edge + HANDLE]
        assertEquals(0, m.edgesAt(100 - ShellModel.HANDLE - 1, 300, w))
        assertEquals(ShellModel.EDGE_L, m.edgesAt(100 - ShellModel.HANDLE, 300, w))
    }

    @Test
    fun edgeHitTestIsOffWhileMaximized() {
        val m = model()
        val w = m.open("W", 600, 400, x = 100, y = 100)
        m.toggleMaximize(w.id)
        assertEquals(0, m.edgesAt(1, 1, w))
    }

    @Test
    fun windowAtReturnsTheTopmostWindowOnly() {
        val m = model()
        val a = m.open("A", 600, 400, x = 100, y = 100)
        val b = m.open("B", 600, 400, x = 200, y = 200)
        assertEquals(b.id, m.windowAt(300, 300)!!.id)
        assertEquals(a.id, m.windowAt(150, 150)!!.id)
        assertNull(m.windowAt(1500, 900))
        m.minimize(b.id)
        assertEquals(a.id, m.windowAt(300, 300)!!.id)
    }

    @Test
    fun taskbarListsWindowsInZOrderWithFocusAndMinimizedState() {
        val m = model()
        val a = m.open("A", 400, 300)
        val b = m.open("B", 400, 300)
        var t = m.taskbar()
        assertEquals(listOf(a.id, b.id), t.map { it.id })
        assertFalse(t[0].focused); assertTrue(t[1].focused)
        m.minimize(b.id)
        t = m.taskbar()
        assertTrue(t[1].minimized)
        assertFalse(t[1].focused)
        // minimizing the top window hands the focus to the next visible one (A)
        assertTrue(t[0].focused)
        assertEquals(a.id, m.focusedId)
        m.restore(a.id) // A comes back, and raising it puts it above the minimized B
        val tt = m.taskbar()
        assertEquals(a.id, tt[1].id)
        assertTrue(tt[1].focused)
        assertEquals("A", tt[1].title)
    }

    @Test
    fun unknownIdsAreRefusedNotThrown() {
        val m = model()
        assertFalse(m.focus(77))
        assertFalse(m.close(77))
        assertFalse(m.minimize(77))
        assertFalse(m.moveBy(77, 1, 1))
        assertFalse(m.resizeBy(77, ShellModel.EDGE_R, 1, 1))
        assertFalse(m.toggleMaximize(77))
        assertFalse(m.restore(77))
        assertEquals(0, m.size())
        assertNotNull(m.topmostVisible() == null)
    }

    @Test
    fun threeWindowsOverlapLikeTheGateScreenshot() {
        // The gate screenshot: three windows open, overlapping, the last one focused.
        val m = model()
        val a = m.open("Files", 900, 620)
        val b = m.open("Browser", 900, 620)
        val c = m.open("Settings", 900, 620)
        assertEquals(3, m.size())
        assertEquals(c.id, m.focusedId)
        // each window is fully inside the work area, so nothing is off-screen
        for (w in m.all()) {
            assertTrue("x ${w.x} w ${w.w}", w.x >= 0 && w.right <= m.desktopW)
            assertTrue("y ${w.y} h ${w.h}", w.y >= 0 && w.bottom <= m.workH)
        }
        // click the middle of the bottom window: it becomes the top one
        assertTrue(m.focus(a.id))
        assertEquals(a.id, m.all().last().id)
        assertEquals(a.id, m.windowAt(a.x + 20, a.y + 20)!!.id)
        assertTrue(b.id != m.focusedId)
    }
}
