package dev.unifieddesktop.app

/**
 * The Phase-6 desktop shell's window manager: pure logic, no Android types, so the whole
 * policy (z-order, focus, clamping, resize limits, edge hit-testing, taskbar state) is
 * unit-testable (Phase6ShellModelTest).
 *
 * Coordinates are desktop pixels: the surface is [desktopW] x [desktopH]; the bottom
 * [taskbarH] pixels are the taskbar and never part of the work area.
 *
 * Z-order is list order: index 0 is the bottom window, the last entry is the top one. The
 * view layer mirrors that with child order, so "raise" is one list move.
 */
class ShellModel(
    val desktopW: Int,
    val desktopH: Int,
    val taskbarH: Int,
    val minW: Int = 360,
    val minH: Int = 240,
) {
    companion object {
        const val EDGE_L = 1
        const val EDGE_T = 2
        const val EDGE_R = 4
        const val EDGE_B = 8
        /** Grab band for resize handles, desktop pixels. */
        const val HANDLE = 10
    }

    /** One window. [restore] holds the normal rectangle while maximized. */
    class Win(
        val id: Int,
        val title: String,
        var x: Int,
        var y: Int,
        var w: Int,
        var h: Int,
        var minimized: Boolean = false,
        var maximized: Boolean = false,
        var restore: IntArray? = null,
    ) {
        val right get() = x + w
        val bottom get() = y + h
        fun rect(): IntArray = intArrayOf(x, y, w, h)
    }

    /** Work area height: everything above the taskbar. */
    val workH get() = (desktopH - taskbarH).coerceAtLeast(1)

    private val wins = ArrayList<Win>()
    private var nextId = 1

    /** The focused window id, or null when the desktop itself has focus. */
    var focusedId: Int? = null
        private set

    fun all(): List<Win> = wins
    fun size(): Int = wins.size
    fun byId(id: Int): Win? = wins.firstOrNull { it.id == id }
    fun focused(): Win? = focusedId?.let { byId(it) }

    /**
     * Open a new window on top and focus it. [x]/[y] are clamped into the work area; a
     * cascade offset keeps stacked windows from covering each other exactly.
     */
    fun open(title: String, w: Int, h: Int, x: Int? = null, y: Int? = null): Win {
        val n = wins.size
        val cw = w.coerceIn(minW, desktopW)
        val ch = h.coerceIn(minH, workH)
        val cx = (x ?: (60 + n * 36)).coerceIn(0, (desktopW - cw).coerceAtLeast(0))
        val cy = (y ?: (48 + n * 32)).coerceIn(0, (workH - ch).coerceAtLeast(0))
        val win = Win(nextId++, title, cx, cy, cw, ch)
        wins.add(win)
        focusedId = win.id
        return win
    }

    /** Raise + focus. Returns false for an unknown id. */
    fun focus(id: Int): Boolean {
        val i = wins.indexOfFirst { it.id == id }
        if (i < 0) return false
        val win = wins.removeAt(i)
        win.minimized = false
        wins.add(win)
        focusedId = win.id
        return true
    }

    /** Close a window; focus falls to the next topmost visible window, else nowhere. */
    fun close(id: Int): Boolean {
        val i = wins.indexOfFirst { it.id == id }
        if (i < 0) return false
        wins.removeAt(i)
        if (focusedId == id) focusedId = topmostVisible()?.id
        return true
    }

    /** Minimize a window; focus falls to the next topmost visible window, else nowhere. */
    fun minimize(id: Int): Boolean {
        val win = byId(id) ?: return false
        if (win.minimized) return true
        win.minimized = true
        if (focusedId == id) focusedId = topmostVisible()?.id
        return true
    }

    /** Un-minimize and focus (a taskbar click). */
    fun restore(id: Int): Boolean {
        val win = byId(id) ?: return false
        win.minimized = false
        return focus(id)
    }

    /** Maximize to the work area, or go back to the stored rectangle. */
    fun toggleMaximize(id: Int): Boolean {
        val win = byId(id) ?: return false
        if (win.maximized) {
            val r = win.restore
            if (r != null) {
                win.x = r[0]; win.y = r[1]; win.w = r[2]; win.h = r[3]
            }
            win.maximized = false
            win.restore = null
        } else {
            win.restore = win.rect()
            win.x = 0; win.y = 0; win.w = desktopW; win.h = workH
            win.maximized = true
        }
        // A maximized window is by definition on top and focused.
        return focus(id)
    }

    /** Drag by the title bar: move by (dx, dy), clamped so the window stays usable. */
    fun moveBy(id: Int, dx: Int, dy: Int): Boolean {
        val win = byId(id) ?: return false
        if (win.maximized) return true // maximized windows do not move
        win.x = (win.x + dx).coerceIn(0, (desktopW - win.w).coerceAtLeast(0))
        win.y = (win.y + dy).coerceIn(0, (workH - win.h).coerceAtLeast(0))
        return true
    }

    /**
     * Resize by dragging the given edge/corner combination of [edges]. The dragged edge
     * follows the pointer, the opposite one stays; the minimum size wins, and the whole
     * window stays inside the work area.
     */
    fun resizeBy(id: Int, edges: Int, dx: Int, dy: Int): Boolean {
        val win = byId(id) ?: return false
        if (win.maximized) return true
        if (edges == 0) return true
        var l = win.x
        var t = win.y
        var r = win.right
        var b = win.bottom
        if (edges and EDGE_L != 0) l += dx
        if (edges and EDGE_T != 0) t += dy
        if (edges and EDGE_R != 0) r += dx
        if (edges and EDGE_B != 0) b += dy
        // Keep the fixed edges inside the work area.
        if (edges and EDGE_L != 0) l = l.coerceIn(0, desktopW - minW)
        if (edges and EDGE_R != 0) r = r.coerceIn(minW, desktopW)
        if (edges and EDGE_T != 0) t = t.coerceIn(0, workH - minH)
        if (edges and EDGE_B != 0) b = b.coerceIn(minH, workH)
        // Minimum size, anchored on the edge that is not being dragged.
        if (r - l < minW) {
            if (edges and EDGE_L != 0) l = r - minW else r = l + minW
        }
        if (b - t < minH) {
            if (edges and EDGE_T != 0) t = b - minH else b = t + minH
        }
        // Final safety clamp (the min-size fix-up can push an edge out by a few pixels).
        l = l.coerceIn(0, desktopW - minW)
        r = r.coerceIn(l + minW, desktopW)
        t = t.coerceIn(0, workH - minH)
        b = b.coerceIn(t + minH, workH)
        win.x = l; win.y = t; win.w = r - l; win.h = b - t
        return true
    }

    /** The visible topmost window (the one a "focus" falls back to). */
    fun topmostVisible(): Win? = wins.lastOrNull { !it.minimized }

    /**
     * The window under (x, y), topmost first, or null for the desktop. Minimized windows
     * are not hit. Used to focus a window on click.
     */
    fun windowAt(x: Int, y: Int): Win? =
        wins.lastOrNull { !it.minimized && x >= it.x && x < it.right && y >= it.y && y < it.bottom }

    /** Edge/corner flags at (x, y) for [win]; 0 = not on a resize handle (or maximized). */
    fun edgesAt(x: Int, y: Int, win: Win, handle: Int = HANDLE): Int {
        if (win.maximized) return 0
        if (x < win.x - handle || x > win.right + handle) return 0
        if (y < win.y - handle || y > win.bottom + handle) return 0
        var e = 0
        if (x <= win.x + handle) e = e or EDGE_L
        if (x >= win.right - handle) e = e or EDGE_R
        if (y <= win.y + handle) e = e or EDGE_T
        if (y >= win.bottom - handle) e = e or EDGE_B
        return e
    }

    /** One taskbar button's state, in the order the taskbar shows them (bottom to top). */
    class TaskButton(val id: Int, val title: String, val focused: Boolean, val minimized: Boolean)

    /** Windows bottom-to-top, as the taskbar lists them. */
    fun taskbar(): List<TaskButton> =
        wins.map { TaskButton(it.id, it.title, it.id == focusedId, it.minimized) }
}
