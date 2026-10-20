package dev.unifieddesktop.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/** A content view that wants raw mouse events instead of touch events (the Test window). */
interface ShellMouseContent {
    fun mouseMove(x: Int, y: Int)
    fun mouseButton(button: Int, down: Boolean, x: Int, y: Int)
    fun mouseWheel(delta: Int, x: Int, y: Int)
}

/** Content that can answer the `p5text` harness with its text. */
interface ShellTextContent {
    fun contentText(): String
}

/**
 * One window: a title bar (title, minimize, maximize/restore, close) and a content area that
 * hosts any content view ([ShellRoot.App] builds them). The frame handles the title bar and
 * the resize handles itself; every other press is handed to the content through Android's
 * normal touch dispatch, so real widgets (WebView, EditText, list rows) work as usual.
 *
 * Mouse input arrives as MotionEvents the shell dispatches into the desktop root; Android
 * transforms them into this view's coordinates before [dispatchTouchEvent] runs.
 */
class ShellWindowView(
    context: Context,
    val win: ShellModel.Win,
    val app: ShellRoot.App,
    val guest: String? = null,
) : FrameLayout(context) {

    companion object {
        const val BTN = 34          // title-bar button width, desktop pixels
        const val TITLE_H = ShellRoot.TITLE_H
        const val DEFAULT_GUEST = "com.google.android.apps.tasks"
    }

    enum class Hit { MINIMIZE, MAXIMIZE, CLOSE, TITLE, NONE }

    /** (window id, dx, dy, edges): edges 0 = title-bar move, else the resize handles. */
    var onDragBy: ((Int, Int, Int, Int) -> Unit)? = null
    var onClose: ((Int) -> Unit)? = null
    var onMinimize: ((Int) -> Unit)? = null
    var onMaximize: ((Int) -> Unit)? = null

    var isFocusedWindow = false
        set(v) { field = v; invalidate() }

    private var hovered = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 20f; typeface = Typeface.DEFAULT_BOLD
    }

    val content: View = buildContent(context, app, guest)

    private var captureEdges = -1      // -1 = not capturing; 0 = move; else resize edges
    private var lastX = 0
    private var lastY = 0
    private var downLogs = 0
    private var dragLogs = 0

    init {
        setWillNotDraw(false)
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topMargin = TITLE_H
        })
    }

    private fun buildContent(context: Context, app: ShellRoot.App, guest: String?): View = when (app) {
        ShellRoot.App.APPCENTER -> AppCenterView(context)
        ShellRoot.App.FILES -> FilesView(context)
        ShellRoot.App.BROWSER -> BrowserView(context)
        ShellRoot.App.SETTINGS -> SettingsView(context)
        ShellRoot.App.TEST -> TestWindowContent(context)
        ShellRoot.App.X11 -> X11WindowContent(context)
        ShellRoot.App.VSCODE -> BrowserView(context, codeServer = true)
        ShellRoot.App.ANDROID -> AndroidWindowContent(context, guest ?: DEFAULT_GUEST)
    }

    /** Which title-bar button a window-local x lands on. */
    fun titleBarHit(x: Int): Hit {
        val closeL = win.w - BTN
        val maxL = win.w - 2 * BTN
        val minL = win.w - 3 * BTN
        return when {
            x >= closeL -> Hit.CLOSE
            x >= maxL -> Hit.MAXIMIZE
            x >= minL -> Hit.MINIMIZE
            else -> Hit.TITLE
        }
    }

    fun updateTitleBar() = invalidate()

    fun setHover(h: Boolean) {
        if (hovered != h) { hovered = h; invalidate() }
    }

    fun cancelDrag() { captureEdges = -1 }

    // ------------------------------------------------------------------ input

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // The window's own rectangle in desktop coordinates (the shell uses these for the
        // resize handles, which are the same in model and view space).
        val absX = win.x + event.x.toInt()
        val absY = win.y + event.y.toInt()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val edges = resizeEdges(absX, absY)
                // B-071: log the shell's own hit-test for a real PC drag, so "the title bar did
                // not move the window" can be told apart from "the press never reached it".
                if (downLogs++ < 24) {
                    P7Log.say(context, "P7WIN down id=${win.id} app=$app local=${event.x.toInt()},${event.y.toInt()} " +
                        "abs=$absX,$absY rect=${win.rect().joinToString(",")} edges=$edges " +
                        "titleY=${event.y < TITLE_H} hit=${if (event.y < TITLE_H) titleBarHit(event.x.toInt()) else "CONTENT"}")
                }
                if (edges != 0) {
                    captureEdges = edges; lastX = absX; lastY = absY
                    return true
                }
                if (event.y < TITLE_H) {
                    when (titleBarHit(event.x.toInt())) {
                        Hit.MINIMIZE -> { onMinimize?.invoke(win.id); return true }
                        Hit.MAXIMIZE -> { onMaximize?.invoke(win.id); return true }
                        Hit.CLOSE -> { onClose?.invoke(win.id); return true }
                        Hit.TITLE -> {
                            if (win.maximized) return true
                            captureEdges = 0; lastX = absX; lastY = absY
                            return true
                        }
                        Hit.NONE -> return true
                    }
                }
                // content: normal Android routing (a real tap for widgets)
                return super.dispatchTouchEvent(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (captureEdges >= 0) {
                    val dx = absX - lastX
                    val dy = absY - lastY
                    if (dragLogs++ < 24) {
                        P7Log.say(context, "P7WIN drag id=${win.id} edges=$captureEdges d=$dx,$dy abs=$absX,$absY " +
                            "rect=${win.rect().joinToString(",")}")
                    }
                    if (absX != lastX || absY != lastY) {
                        onDragBy?.invoke(win.id, dx, dy, captureEdges)
                        lastX = absX; lastY = absY
                    }
                    return true
                }
                return super.dispatchTouchEvent(event)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = captureEdges
                captureEdges = -1
                if (was >= 0) return true
                return super.dispatchTouchEvent(event)
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun resizeEdges(absX: Int, absY: Int): Int {
        if (win.maximized) return 0
        // In model space the handles are inside the window rectangle.
        return shellModel()?.edgesAt(absX, absY, win) ?: 0
    }

    /** The shell model, reached through the parent so the hit-test policy lives in one place. */
    private fun shellModel(): ShellModel? = (parent as? ShellRoot)?.model

    /** A key from the PC, delivered to this window's content. */
    fun keyToContent(down: Boolean, keyCode: Int, scan: Int, meta: Int): Boolean {
        val c = content
        if (c is ShellRawKeyContent) return c.key(down, keyCode, scan, meta)
        if (c is TestWindowContent) return c.key(down, keyCode, scan, meta)
        val now = SystemClock.uptimeMillis()
        val ev = KeyEvent(
            now, now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            keyCode, 0, meta, -1, scan, 0, android.view.InputDevice.SOURCE_KEYBOARD
        )
        // Keep whichever widget the mouse focused (the address field the user clicked); only
        // pick a default leaf when nothing inside the window holds focus. Stealing focus on
        // every key sent typed text to the WebView instead of the address bar (seen in the
        // first gate run, 2026-10-19).
        val existing = c.findFocus()
        val target = if (existing != null && existing !== c) existing else (focusTarget(c) ?: c)
        if (!target.isFocused && (target.isFocusable || target.isFocusableInTouchMode)) {
            target.requestFocus()
        }
        return target.dispatchKeyEvent(ev)
    }

    /** The deepest focusable leaf view (a WebView or EditText inside our content views). */
    private fun focusTarget(root: View): View? {
        if (root is ViewGroup) {
            for (i in root.childCount - 1 downTo 0) {
                val child = root.getChildAt(i)
                if (child.visibility != View.VISIBLE) continue
                focusTarget(child)?.let { return it }
            }
        }
        return if (root.isFocusable || root.isFocusableInTouchMode) root else null
    }

    fun wheel(delta: Int, x: Int, y: Int) {
        val c = content
        if (c is ShellMouseContent) { c.mouseWheel(delta, x, y); return }
        // The public API cannot set AXIS_VSCROLL on a synthesized event, so scroll the
        // scrollable view under the pointer directly.
        val target = findScrollable(c) ?: return
        target.scrollBy(0, -delta)
    }

    private fun findScrollable(root: View): View? {
        if (root is android.widget.ScrollView || root is android.webkit.WebView) return root
        if (root is ViewGroup) {
            for (i in root.childCount - 1 downTo 0) {
                findScrollable(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    fun contentText(): String = when (val c = content) {
        is ShellTextContent -> c.contentText()
        is TestWindowContent -> c.contentText()
        else -> ""
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        // B-091: every frame owns an opaque body. Several content views intentionally have
        // transparent gaps; without this the maximized window behind showed through a
        // raised/focused window and it looked as if focus had not raised it.
        paint.color = 0xFF20242C.toInt()
        canvas.drawRect(0f, TITLE_H.toFloat(), w.toFloat(), height.toFloat(), paint)
        paint.color = if (isFocusedWindow) 0xFF2B3A67.toInt() else 0xFF3A3F4B.toInt()
        canvas.drawRect(0f, 0f, w.toFloat(), TITLE_H.toFloat(), paint)
        textPaint.color = Color.WHITE
        textPaint.typeface = if (isFocusedWindow) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        canvas.drawText(titleText(), 12f, TITLE_H - 14f, textPaint)
        val y0 = (TITLE_H - BTN) / 2f
        for (i in 0 until 3) {
            val left = w - (3 - i) * BTN
            paint.color = if (isFocusedWindow) 0xFF3D4E86.toInt() else 0xFF4A4F5B.toInt()
            canvas.drawRect(left + 3f, y0 + 3f, left + BTN - 3f, y0 + BTN - 3f, paint)
            paint.color = Color.WHITE
            paint.strokeWidth = 2.5f
            paint.style = Paint.Style.STROKE
            when (i) {
                0 -> canvas.drawLine(left + 10f, TITLE_H / 2f, left + BTN - 10f, TITLE_H / 2f, paint)
                1 -> if (win.maximized) {
                    canvas.drawRect(left + 13f, y0 + 8f, left + BTN - 6f, y0 + BTN - 8f, paint)
                    canvas.drawRect(left + 7f, y0 + 12f, left + BTN - 12f, y0 + BTN - 5f, paint)
                } else {
                    canvas.drawRect(left + 8f, y0 + 8f, left + BTN - 8f, y0 + BTN - 8f, paint)
                }
                2 -> {
                    canvas.drawLine(left + 10f, y0 + 10f, left + BTN - 10f, y0 + BTN - 10f, paint)
                    canvas.drawLine(left + BTN - 10f, y0 + 10f, left + 10f, y0 + BTN - 10f, paint)
                }
            }
            paint.style = Paint.Style.FILL
        }
        paint.color = if (isFocusedWindow) 0xFF7EA6FF.toInt() else 0xFF20242C.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawRect(1f, 1f, (w - 1).toFloat(), (height - 1).toFloat(), paint)
        paint.style = Paint.Style.FILL
    }

    /** The title bar follows the window model, so an X11 rename (B-087) is visible here. */
    private fun titleText(): String = if (win.maximized) "${win.title} [max]" else win.title
}

/**
 * The Test window's content: the unchanged Phase-5 test desktop (moving shapes, the
 * draggable box, the flash area, the EditText). It takes raw mouse events, exactly like it
 * did as the whole desktop, so every Phase-5 measurement still works on this build.
 */
class TestWindowContent(context: Context) : TestDesktop(context), ShellMouseContent, ShellTextContent {
    override fun mouseMove(x: Int, y: Int) = setCursor(x, y)
    override fun mouseButton(button: Int, down: Boolean, x: Int, y: Int) = pointerButton(button, down, x, y)
    override fun mouseWheel(delta: Int, x: Int, y: Int) = wheel(delta, x, y)
    override fun contentText(): String = inputText()
}
