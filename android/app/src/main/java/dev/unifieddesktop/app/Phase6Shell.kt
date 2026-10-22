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

    /**
     * Harness-only typing probe (Phase 12 round 2). The Phase-5 latency probe reports the
     * moment the key "flash" is really drawn; in shell mode only the Test window's own flash
     * area did that, so typing in the Browser, VS Code or Notepad++ produced no LAT line at
     * all. This 28x28 px square at the bottom-right of the desktop flashes white on every
     * key-down while it is armed (`p6:probe on`), which gives the probe the same draw
     * timestamp for a key typed into ANY focused window. It is GONE unless armed, so the
     * normal UI is unchanged.
     */
    private val probeChip = object : View(context) {
        var armed = false
        private var untilMs = 0L
        private var count = 0
        private var reported = 0
        var onDrawn: ((Int, Long) -> Unit)? = null

        fun arm(on: Boolean) {
            armed = on
            visibility = if (on) VISIBLE else GONE
            invalidate()
        }

        fun flash() {
            if (!armed) return
            count++
            untilMs = SystemClock.uptimeMillis() + 120
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            val flashing = SystemClock.uptimeMillis() < untilMs
            c.drawColor(if (flashing) Color.WHITE else Color.rgb(45, 45, 60))
            if (flashing && reported != count) {
                reported = count
                onDrawn?.invoke(count, System.nanoTime())
            }
        }
    }.apply {
        visibility = GONE
        layoutParams = FrameLayout.LayoutParams(28, 28).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = 18
            bottomMargin = TASKBAR_H + 18
        }
    }

    private var cursorX = DESKTOP_W / 2
    private var cursorY = DESKTOP_H / 2

    private val heldKeys = HashSet<Int>()
    private val heldButtons = HashSet<Int>()
    private var pcPointerVisible = true
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
        addView(probeChip)
        addView(cursorView)
        startMenu.visibility = GONE
        taskbar.onStart = { toggleStartMenu() }
        taskbar.onWindowButton = { id -> onTaskbarWindowClick(id) }
        startMenu.onLaunch = { app -> openApp(app); closeStartMenu() }
        startMenu.onLaunchInstalled = { id -> openCatalogApp(id); closeStartMenu() }
    }

    // ------------------------------------------------------------------ windows

    enum class App { APPCENTER, FILES, BROWSER, SETTINGS, TEST, X11, VSCODE, ANDROID;
        fun title(): String = when (this) {
            APPCENTER -> "App Center"
            FILES -> "Files"; BROWSER -> "Browser"; SETTINGS -> "Settings"; TEST -> "Test"
            X11 -> X11Title.BASE
            VSCODE -> "VS Code"
            ANDROID -> "Android app"
        }
    }

    /** The live App Center content while its window exists (the Phase-8 harness drives it). */
    var appCenter: AppCenterView? = null
        private set

    /**
     * Open (or re-focus) an app window. [guest] names the hidden Android app for ANDROID;
     * [title] names the app inside an X11 window (B-087), reusing the one X11 window.
     */
    fun openApp(app: App, guest: String? = null, title: String? = null): ShellWindowView {
        // B-090: the Android window hosts exactly one guest. Opening a different package
        // must not reuse the old TextureView/display, and an uninstall must close it so the
        // next import launches against a fresh display instead of a black stale surface.
        if (app == App.ANDROID) {
            frames.values.firstOrNull {
                it.app == App.ANDROID && !it.guest.equals(guest, ignoreCase = true)
            }?.let { closeWindow(it.win.id) }
        }
        val existing = frames.values.firstOrNull { it.app == app }
        if (existing != null) {
            if (app == App.X11) {
                val t = title?.takeIf { it.isNotBlank() } ?: X11Title.BASE
                model.rename(existing.win.id, t)
            }
            model.focus(existing.win.id)
            syncViews()
            return existing
        }
        val initialTitle = if (app == App.X11) {
            title?.takeIf { it.isNotBlank() } ?: X11Title.BASE
        } else {
            app.title()
        }
        val win = model.open(initialTitle, 900, 620)
        val frame = ShellWindowView(context, win, app, guest)
        frame.onDragBy = { id, dx, dy, edges -> frameDrag(id, dx, dy, edges) }
        frame.onClose = { closeWindow(it) }
        frame.onMinimize = { minimizeWindow(it) }
        frame.onMaximize = { id -> model.toggleMaximize(id); syncViews() }
        if (frame.content is TestWindowContent) {
            testContent = frame.content as TestWindowContent
            flashListener?.let { testContent?.setFlashListener(it) }
        }
        if (frame.content is AppCenterView) appCenter = frame.content as AppCenterView
        frames[win.id] = frame
        addView(frame, indexOfChild(startMenu)) // under the start menu and the cursor
        syncViews()
        return frame
    }

    private var testContent: TestWindowContent? = null

    /** The live X11 window content, if that window is open (B-077 reconnect proof). */
    fun x11Content(): X11WindowContent? =
        frames.values.firstNotNullOfOrNull { it.content as? X11WindowContent }

    /** Phase 11 D-e: the live Browser WebView. The injected shim checks for itself, so
     *  pushing into a page without it is a no-op (`window.udCam && ...`). */
    fun cameraWeb(): android.webkit.WebView? =
        frames.values.firstNotNullOfOrNull { (it.content as? BrowserView)?.web }

    /** The catalog, read once; the start menu and the App Center share these entries. */
    private val catalog: List<CatalogApp> by lazy {
        try {
            CatalogLoader.load(context)
        } catch (t: Throwable) {
            P7Log.say(context, "APPCTR catalog fail ${t.javaClass.simpleName}: ${t.message}")
            emptyList()
        }
    }

    /** B-083: the one launch path for an installed/catalog app (start menu and App Center). */
    private fun packageInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Throwable) {
        false
    }

    fun openCatalogApp(id: String): String {
        val app = catalog.firstOrNull { it.id.equals(id, ignoreCase = true) }
            ?: return "no catalog app [$id]"
        val v = Catalog.pick(app) ?: return "no version for ${app.name}"
        if (!Catalog.isUsable(v)) return "Not yet: ${v.note.ifEmpty { v.rating }}"
        return applyTarget(OpenRoute.of(app, v))
    }

    private fun applyTarget(t: OpenTarget): String = when (t.action) {
        "files" -> { openApp(App.FILES); "opened Files" }
        "browser" -> {
            val w = openApp(App.BROWSER)
            (w.content as? BrowserView)?.openUrl(t.url)
            "opened Browser ${t.url}"
        }
        "settings" -> { openApp(App.SETTINGS); "opened Settings" }
        "vscode" -> { openApp(App.VSCODE); "opened VS Code" }
        "x11" -> {
            openApp(App.X11, title = t.title)
            Thread { Phase2Wine.openCatalogApp(context, t.catalogId) }.start()
            "opened ${t.title}"
        }
        "android" -> {
            if (t.guest.isEmpty()) "no package name"
            else if (!packageInstalled(t.guest)) {
                // B-092: no guest window for a package that is not on the phone. The Browser
                // Play page is the only truthful target; Install/Open share this policy.
                val fallback = AndroidOpen.browserFallback(t)
                val w = openApp(App.BROWSER)
                (w.content as? BrowserView)?.openUrl(fallback.url)
                val name = catalog.firstOrNull {
                    it.id.equals(t.catalogId, ignoreCase = true)
                }?.name ?: t.guest
                "$name is not installed; opened its Google Play page."
            } else {
                openApp(App.ANDROID, t.guest)
                "opening Android ${t.guest}"
            }
        }
        else -> "no window for [${t.title}]"
    }

    /** Latency-probe hook: the Test window's flash reports its draw time (Phase-5 method). */
    private var flashListener: ((Int, Long) -> Unit)? = null

    fun setFlashListener(l: ((Int, Long) -> Unit)?) {
        flashListener = l
        testContent?.setFlashListener(l)
        probeChip.onDrawn = l
    }

    /** Harness: arm/disarm the typing-probe square (see [probeChip]). */
    fun probeArm(on: Boolean): String {
        probeChip.arm(on)
        return "probe=${if (on) "on" else "off"} chip=bottom-right-28px"
    }

    fun closeWindow(id: Int) {
        val f = frames.remove(id) ?: return
        if (f.content is AppCenterView) appCenter = null
        removeView(f)
        model.close(id)
        syncViews()
    }

    /** Close the X11 frame (its content reconnects when the next X11 app opens). */
    fun closeX11Window(): Boolean {
        val f = frames.values.firstOrNull { it.app == App.X11 } ?: return false
        closeWindow(f.win.id)
        return true
    }

    /** B-090: close the Android window showing [pkg] (after its import is removed/replaced). */
    fun closeGuestWindow(pkg: String): Boolean {
        val f = frames.values.firstOrNull {
            it.app == App.ANDROID && it.guest.equals(pkg, ignoreCase = true)
        } ?: return false
        closeWindow(f.win.id)
        return true
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
    fun syncViews() {
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

    /** Rebuild the installed section from the records (after an install or uninstall). */
    fun refreshInstalledRows() {
        startMenu.setInstalledRows(InstalledMenu.rows(Installed.read(context), catalog))
    }

    /** Harness: every start-menu row, fixed entries first. */
    fun menuRows(): String {
        refreshInstalledRows()
        return startMenu.rowsText()
    }

    fun openStartMenu() {
        refreshInstalledRows()
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

    /** Governor step one: ensure minimized windows are detached from the render path. */
    fun stopRenderingHiddenWindows(): Int {
        val hidden = model.all().count { it.minimized }
        if (hidden > 0) syncViews()
        return hidden
    }

    fun startMenuOpen(): Boolean = startMenu.visibility == View.VISIBLE

    // ------------------------------------------------------------------ pointer

    /**
     * B-095: while a PC session is connected, the PC draws the one real arrow over the video.
     * Keep tracking the pointer for hover and clicks, but do not paint a second one on the
     * phone's own desktop surface.
     */
    fun setPcPointerVisible(visible: Boolean) {
        pcPointerVisible = visible
        cursorView.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun pcPointerIsVisible(): Boolean = pcPointerVisible

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
        if (buttonLogs++ < 30) {
            P7Log.say(context, "P7SHELL button=$button down=$down at=$x,$y " +
                "under=${model.windowAt(x, y)?.title ?: "desktop"} focus=${model.focusedId ?: -1}")
        }
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
        if (down) {
            keyFlashes++
            probeChip.flash()
        }
        val handled = frame.keyToContent(down, keyCode, scan, meta)
        if (down) P7Log.say(context, "P6SHELL key code=$keyCode meta=$meta focused=${frame.win.title} handled=$handled")
        return handled
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
    private var buttonLogs = 0

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        cursorView.setCursor(cursorX, cursorY)
    }
}
