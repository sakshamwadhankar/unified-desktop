package dev.unifieddesktop.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
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
    /** Title-bar drag finished at this desktop position: the shell snaps/maximises there. */
    var onDragEnd: ((Int, Int, Int) -> Unit)? = null
    var onClose: ((Int) -> Unit)? = null
    var onMinimize: ((Int) -> Unit)? = null
    var onMaximize: ((Int) -> Unit)? = null

    var isFocusedWindow = false
        set(v) { field = v; invalidate() }

    private var hovered = false
    private var hoverX = -1
    private var hoverY = -1
    private var lastTitleDownMs = 0L
    private var lastTitleX = -1
    private var lastTitleY = -1
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.TEXT; textSize = Theme.BODY_LARGE; typeface = Typeface.DEFAULT
    }
    private val ellipsisPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    val content: View = buildContent(context, app, guest)

    private var captureEdges = -1      // -1 = not capturing; 0 = move; else resize edges
    private var lastX = 0
    private var lastY = 0
    private var downLogs = 0
    private var dragLogs = 0

    init {
        setWillNotDraw(false)
        // D6: rounded window corners, clipped so the hosted WebView/list obeys them too.
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, Theme.RADIUS_WINDOW)
            }
        }
        clipToOutline = true
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

    fun setHover(h: Boolean) = setHover(h, -1, -1)

    fun setHover(h: Boolean, localX: Int, localY: Int) {
        if (hovered != h || hoverX != localX || hoverY != localY) {
            hovered = h
            hoverX = localX
            hoverY = localY
            invalidate()
        }
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
                            val now = SystemClock.uptimeMillis()
                            val lx = event.x.toInt()
                            val ly = event.y.toInt()
                            val doubleClick = now - lastTitleDownMs < 320 &&
                                kotlin.math.abs(lx - lastTitleX) < 12 && kotlin.math.abs(ly - lastTitleY) < 12
                            lastTitleDownMs = now
                            lastTitleX = lx
                            lastTitleY = ly
                            if (doubleClick) {
                                // D6: double-click the title bar to maximise / restore.
                                onMaximize?.invoke(win.id)
                                lastTitleDownMs = 0
                                captureEdges = -1
                                return true
                            }
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
                if (was >= 0) {
                    if (was == 0 && event.actionMasked == MotionEvent.ACTION_UP) {
                        // D6: a title-bar drag that ends on an edge maximises or snaps.
                        onDragEnd?.invoke(win.id, absX, absY)
                    }
                    return true
                }
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
        val h = height
        // Window body: one opaque surface (B-091: a raised window never shows the one behind).
        paint.style = Paint.Style.FILL
        paint.color = Theme.SURFACE
        canvas.drawRect(0f, TITLE_H.toFloat(), w.toFloat(), h.toFloat(), paint)

        // Title bar: raised when focused, quiet when not; a single accent hairline on top.
        paint.color = if (isFocusedWindow) Theme.SURFACE_RAISED else Theme.SURFACE_UNFOCUSED
        canvas.drawRect(0f, 0f, w.toFloat(), TITLE_H.toFloat(), paint)
        if (isFocusedWindow) {
            paint.color = Theme.alpha(Theme.ACCENT, 190)
            canvas.drawRect(0f, 0f, w.toFloat(), 1.5f, paint)
        }

        // App icon + ellipsized title.
        DeskIcon.draw(canvas, app.iconKind(), 18f, TITLE_H / 2f, 15f, app.iconColor())
        textPaint.color = if (isFocusedWindow) Theme.TEXT else Theme.TEXT_SECONDARY
        textPaint.typeface = Typeface.DEFAULT
        textPaint.textSize = Theme.BODY_LARGE
        ellipsisPaint.set(textPaint)
        val avail = (w - 3 * BTN - 46).coerceAtLeast(24).toFloat()
        val label = TextUtils.ellipsize(titleText(), ellipsisPaint, avail, TextUtils.TruncateAt.END).toString()
        canvas.drawText(label, 34f, TITLE_H / 2f + 5f, textPaint)

        // Three hover-aware buttons: minimise, maximise/restore, close.
        if (hoverY in 0..TITLE_H) {
            val hit = titleBarHit(hoverX)
            for (i in 0..2) {
                val left = w - (3 - i) * BTN
                val isHit = when (hit) {
                    Hit.MINIMIZE -> i == 0
                    Hit.MAXIMIZE -> i == 1
                    Hit.CLOSE -> i == 2
                    else -> false
                }
                if (isHit) {
                    paint.color = if (i == 2) Theme.DANGER else Theme.alpha(Theme.TEXT, 22)
                    canvas.drawRoundRect(
                        left + 3f, 4f, (left + BTN - 3).toFloat(), (TITLE_H - 4).toFloat(),
                        Theme.RADIUS_BUTTON, Theme.RADIUS_BUTTON, paint
                    )
                }
            }
        }
        for (i in 0..2) {
            val left = w - (3 - i) * BTN
            val cx = left + BTN / 2f
            val cy = TITLE_H / 2f
            val color = if (i == 2 && hoverY in 0..TITLE_H && titleBarHit(hoverX) == Hit.CLOSE) {
                Color.WHITE
            } else if (isFocusedWindow) Theme.TEXT else Theme.TEXT_SECONDARY
            val kind = when (i) {
                0 -> DeskIcon.MIN
                1 -> if (win.maximized) DeskIcon.RESTORE else DeskIcon.MAX
                else -> DeskIcon.CLOSE
            }
            DeskIcon.draw(canvas, kind, cx, cy, 13f, color)
        }

        // One hairline round the frame, accent-tinted when focused.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = if (isFocusedWindow) Theme.alpha(Theme.ACCENT, 110) else Theme.BORDER
        canvas.drawRoundRect(
            0.5f, 0.5f, (w - 0.5f), (h - 0.5f),
            Theme.RADIUS_WINDOW, Theme.RADIUS_WINDOW, paint
        )
        paint.style = Paint.Style.FILL
    }

    /** The title bar follows the window model, so an X11 rename (B-087) is visible here. */
    private fun titleText(): String = win.title
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
