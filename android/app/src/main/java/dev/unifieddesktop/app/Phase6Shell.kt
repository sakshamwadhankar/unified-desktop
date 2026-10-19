package dev.unifieddesktop.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * The Phase-6 desktop shell, shown inside the Phase-5 `Presentation` on our own private
 * display. Everything here is our own UI: a wallpaper, movable/resizable windows, a taskbar
 * with a start button and one button per window, a start menu, and our own pointer cursor.
 *
 * Input comes from the PC as REAL mouse MotionEvents (SOURCE_MOUSE) dispatched to this root
 * view, so normal Android hit-testing routes clicks/typing to the views that want them; the
 * frame chrome (title bar, resize handles) is handled by [ShellWindowView] itself. The
 * desktop-sized cursor is drawn by [CursorView] on top of everything.
 *
 * Windows live in [model] (`ShellModel`, pure logic, unit-tested); this class only mirrors
 * the model into child views.
 */
class ShellRoot(context: Context) : FrameLayout(context) {

    companion object {
        const val TAG = "Phase6"
        /** Desktop pixels; the display is 1920x1080 at 240 dpi (Phase-5 numbers). */
        const val DESKTOP_W = Phase5Desktop.W
        const val DESKTOP_H = Phase5Desktop.H
        const val TASKBAR_H = 64
        const val TITLE_H = 44
    }

    val model = ShellModel(DESKTOP_W, DESKTOP_H, TASKBAR_H)

    /** Fired whenever the shell's state changes, for the link's stats line. */
    var onChanged: (() -> Unit)? = null

    private val cursorView = CursorView(context)
    private val taskbar = TaskbarView(context)
    private val startMenu = StartMenuView(context)
    private val frames = HashMap<Int, ShellWindowView>()

    private var cursorX = DESKTOP_W / 2
    private var cursorY = DESKTOP_H / 2

    private val heldKeys = HashSet<Int>()
    private val heldButtons = HashSet<Int>()
    private var keysAllTime = 0
    private var keyFlashes = 0
    private var buttonsAllTime = 0
    private var keyAtFocused = 0

    init {
        setBackgroundColor(0xFF10312B.toInt()) // a calm wallpaper
        taskbar.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, TASKBAR_H).apply {
            gravity = android.view.Gravity.BOTTOM
        }
        addView(taskbar)
        addView(startMenu)
        addView(cursorView)
        startMenu.visibility = GONE
        taskbar.onStart = { toggleStartMenu() }
        taskbar.onWindowButton = { id -> onTaskbarWindowClick(id) }
        startMenu.onLaunch = { app -> openApp(app); closeStartMenu() }
    }

    // ------------------------------------------------------------------ windows

    enum class App { FILES, BROWSER, SETTINGS, TEST;
        fun title(): String = when (this) {
            FILES -> "Files"; BROWSER -> "Browser"; SETTINGS -> "Settings"; TEST -> "Test"
        }
    }

    /** Open (or re-focus) an app window. */
    fun openApp(app: App): ShellWindowView {
        val existing = model.all().firstOrNull { it.title == app.title() }
        if (existing != null) {
            model.focus(existing.id)
            syncViews()
            return frames[existing.id]!!
        }
        val win = model.open(app.title(), 900, 620)
        val frame = ShellWindowView(context, win, app)
        frame.onDragBy = { id, dx, dy, edges -> frameDrag(id, dx, dy, edges) }
        frame.onClose = { closeWindow(it) }
        frame.onMinimize = { minimizeWindow(it) }
        frame.onMaximize = { id -> model.toggleMaximize(id); syncViews() }
        if (frame.content is TestWindowContent) {
            testContent = frame.content as TestWindowContent
            flashListener?.let { testContent?.setFlashListener(it) }
        }
        frames[win.id] = frame
        addView(frame, indexOfChild(startMenu)) // under the start menu and the cursor
        syncViews()
        return frame
    }

    private var testContent: TestWindowContent? = null

    /** Latency-probe hook: the Test window's flash reports its draw time (Phase-5 method). */
    private var flashListener: ((Int, Long) -> Unit)? = null

    fun setFlashListener(l: ((Int, Long) -> Unit)?) {
        flashListener = l
        testContent?.setFlashListener(l)
    }

    fun closeWindow(id: Int) {
        val f = frames.remove(id) ?: return
        removeView(f)
        model.close(id)
        syncViews()
    }

    fun minimizeWindow(id: Int) {
        model.minimize(id)
        syncViews()
    }

    private fun onTaskbarWindowClick(id: Int) {
        val win = model.byId(id) ?: return
        if (win.minimized) model.restore(id)
        else if (model.focusedId == id) model.minimize(id) // like a real taskbar: click again to hide
        else model.focus(id)
        syncViews()
    }

    /** Push model state into the views (visibility, rects, taskbar, z-order). */
    private fun syncViews() {
        for (win in model.all()) {
            val f = frames[win.id] ?: continue
            f.visibility = if (win.minimized) View.GONE else View.VISIBLE
            f.layoutParams = LayoutParams(win.w, win.h).apply { leftMargin = win.x; topMargin = win.y }
            f.isFocusedWindow = model.focusedId == win.id
            f.updateTitleBar()
        }
        // z-order: model order is bottom-to-top. bringChildToFront keeps Android's touch
        // targets alive (remove+add would cancel a drag in progress), so raise in that order.
        for (win in model.all()) frames[win.id]?.let { bringChildToFront(it) }
        if (startMenu.visibility == View.VISIBLE) startMenu.bringToFront()
        cursorView.bringToFront()
        taskbar.buttons = model.taskbar()
        taskbar.invalidate()
        requestLayout()
        invalidate()
        onChanged?.invoke()
    }

    // ------------------------------------------------------------------ start menu

    fun toggleStartMenu() {
        if (startMenu.visibility == View.VISIBLE) closeStartMenu() else openStartMenu()
    }

    fun openStartMenu() {
        startMenu.visibility = View.VISIBLE
        // Anchor the menu's bottom edge to the top of the taskbar and let it grow upward:
        // a fixed 300px panel clipped the fourth row at 240 dpi (seen in the first gate capture,
        // 2026-10-19).
        startMenu.layoutParams = LayoutParams(420, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            bottomMargin = TASKBAR_H
        }
        startMenu.bringToFront()
        cursorView.bringToFront()
        startMenu.invalidate()
        onChanged?.invoke()
    }

    fun closeStartMenu() {
        startMenu.visibility = View.GONE
        onChanged?.invoke()
    }

    fun startMenuOpen(): Boolean = startMenu.visibility == View.VISIBLE

    // ------------------------------------------------------------------ pointer

    /**
     * Move the pointer without a button held (hover). Nothing is dispatched as a touch: a
     * hover only moves our cursor and updates the resize-handle feedback.
     */
    fun moveTo(x: Int, y: Int) {
        cursorX = x.coerceIn(0, DESKTOP_W - 1)
        cursorY = y.coerceIn(0, DESKTOP_H - 1)
        cursorView.setCursor(cursorX, cursorY)
        val frame = frameUnder(cursorX, cursorY)
        hoveredEdges = frame?.let { model.edgesAt(cursorX, cursorY, it.win) } ?: 0
        for (f in frames.values) f.setHover(f === frame)
        cursorView.hoverEdges = hoveredEdges
        cursorView.invalidate()
        // With a button held a "move" is a drag: the PC sends moves, not separate drag
        // events, so the shell decides here (Phase-5 wire format unchanged).
        if (heldButtons.isNotEmpty()) dispatchPointer(MotionEvent.ACTION_MOVE, cursorX, cursorY, 1)
    }

    private fun dispatchPointer(action: Int, x: Int, y: Int, buttons: Int): Boolean {
        val ev = MotionEvent.obtain(
            SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0
        )
        ev.setSource(android.view.InputDevice.SOURCE_MOUSE)
        return try { dispatchTouchEvent(ev) } finally { ev.recycle() }
    }
    private var hoveredEdges = 0

    private fun frameUnder(x: Int, y: Int): ShellWindowView? {
        val win = model.windowAt(x, y) ?: return null
        return frames[win.id]
    }

    /**
     * A mouse button went down/up at (x, y). The event is a REAL MotionEvent with
     * SOURCE_MOUSE dispatched to the root, so Android's own hit-testing decides which view
     * (taskbar, start menu, window frame, window content) gets it.
     */
    fun mouseButton(button: Int, down: Boolean, x: Int, y: Int) {
        if (down) {
            heldButtons.add(button)
            buttonsAllTime++
        } else heldButtons.remove(button)
        moveTo(x, y)
        if (button != Phase5Protocol.BTN_LEFT) { invalidate(); return }
        // click-anywhere-focuses: the window under the pointer is raised before its content
        // sees the press (the design rule), except for clicks on the desktop itself.
        if (down) {
            val win = model.windowAt(x, y)
            if (win != null && model.focusedId != win.id) {
                model.focus(win.id)
                syncViews()
            }
        }
        val ev = MotionEvent.obtain(
            SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
            if (down) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0
        )
        val handled = try { dispatchTouchEvent(ev) } finally { ev.recycle() }
        if (!handled && startMenuOpen()) closeStartMenu()
        invalidate()
    }

    /** Pointer moved while a button is held: a real ACTION_MOVE to the same target. */
    fun dragTo(x: Int, y: Int) {
        val cx = x.coerceIn(0, DESKTOP_W - 1)
        val cy = y.coerceIn(0, DESKTOP_H - 1)
        cursorX = cx; cursorY = cy
        cursorView.setCursor(cx, cy)
        dispatchPointer(MotionEvent.ACTION_MOVE, cx, cy, 1)
        invalidate()
    }

    /** Called by a frame while the user drags its title bar or a resize handle. */
    internal fun frameDrag(id: Int, dx: Int, dy: Int, edges: Int) {
        dragCount++
        if (edges == 0) model.moveBy(id, dx, dy) else model.resizeBy(id, edges, dx, dy)
        val win = model.byId(id) ?: return
        frames[id]?.layoutParams = LayoutParams(win.w, win.h).apply { leftMargin = win.x; topMargin = win.y }
        taskbar.invalidate()
        requestLayout()
        invalidate()
        onChanged?.invoke()
    }

    /** Wheel over the desktop: scroll whatever is under the pointer (the shell itself). */
    fun wheel(delta: Int, x: Int, y: Int) {
        moveTo(x, y)
        frameUnder(x, y)?.wheel(delta, x, y)
        invalidate()
    }

    // ------------------------------------------------------------------ keyboard

    /**
     * Key event from the PC. Alt+F4 closes the focused window; everything else goes to the
     * focused window's content view (a real KeyEvent, deviceId -1 + scan code like Phase 5).
     */
    fun key(down: Boolean, keyCode: Int, scan: Int, meta: Int): Boolean {
        if (keyCode == 0) return false
        if (down) { heldKeys.add(keyCode); keysAllTime++ } else heldKeys.remove(keyCode)
        if (down && keyCode == KeyEvent.KEYCODE_F4 && (meta and KeyEvent.META_ALT_ON) != 0) {
            model.focusedId?.let { closeWindow(it) }
            return true
        }
        val frame = model.focusedId?.let { frames[it] } ?: return false
        keyAtFocused++
        if (down) keyFlashes++
        return frame.keyToContent(down, keyCode, scan, meta)
    }

    /** Releases everything the PC still holds; returns (keys, buttons) released. */
    fun releaseAll(): Pair<Int, Int> {
        val k = heldKeys.size
        val b = heldButtons.size
        val now = SystemClock.uptimeMillis()
        for (code in heldKeys.toList()) {
            model.focusedId?.let { id -> frames[id]?.keyToContent(false, code, 0, 0) }
        }
        heldKeys.clear()
        heldButtons.clear()
        for (f in frames.values) f.cancelDrag()
        invalidate()
        return Pair(k, b)
    }

    fun heldCounts(): Pair<Int, Int> = Pair(heldKeys.size, heldButtons.size)

    /** What the `p5text` harness reads: the focused window's content text. */
    fun focusedText(): String =
        model.focusedId?.let { frames[it]?.contentText() } ?: ""

    fun stats(): String =
        "windows=${model.size()} focused=${model.focusedId ?: -1} startMenu=${if (startMenuOpen()) 1 else 0} " +
            "keysDown=${heldKeys.size} buttonsDown=${heldButtons.size} keysAllTime=$keysAllTime " +
            "buttonsAllTime=$buttonsAllTime keyAtFocused=$keyAtFocused keyFlashes=$keyFlashes " +
            "drags=${dragCount}"

    private var dragCount = 0

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        cursorView.setCursor(cursorX, cursorY)
    }
}
