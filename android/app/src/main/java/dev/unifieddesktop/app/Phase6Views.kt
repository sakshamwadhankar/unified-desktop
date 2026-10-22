package dev.unifieddesktop.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The icon + tint for a shell app, shared by the taskbar, the start menu and title bars. */
internal fun ShellRoot.App.iconKind(): String = when (this) {
    ShellRoot.App.APPCENTER -> DeskIcon.GRID
    ShellRoot.App.FILES -> DeskIcon.FOLDER
    ShellRoot.App.BROWSER -> DeskIcon.GLOBE
    ShellRoot.App.SETTINGS -> DeskIcon.GEAR
    ShellRoot.App.VSCODE -> DeskIcon.CODE
    ShellRoot.App.X11 -> DeskIcon.NOTE
    ShellRoot.App.ANDROID -> DeskIcon.PHONE
    ShellRoot.App.TEST -> DeskIcon.WINDOW
}

internal fun ShellRoot.App.iconColor(): Int = when (this) {
    ShellRoot.App.APPCENTER -> 0xFF4C8DFF.toInt()
    ShellRoot.App.FILES -> 0xFFE3A008.toInt()
    ShellRoot.App.BROWSER -> 0xFF3AA0FF.toInt()
    ShellRoot.App.SETTINGS -> 0xFF9BA6B8.toInt()
    ShellRoot.App.VSCODE -> 0xFF4C8DFF.toInt()
    ShellRoot.App.X11 -> 0xFF7FA6FF.toInt()
    ShellRoot.App.ANDROID -> 0xFFA78BFA.toInt()
    ShellRoot.App.TEST -> 0xFF6B7484.toInt()
}

/** A rounded icon tile, used in the start menu and in window title bars. */
class AppIconView(context: Context, var kind: String, var color: Int, private val tile: Boolean = true) :
    View(context) {

    init { setWillNotDraw(false) }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        if (tile) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Theme.alpha(color, 38) }
            val r = s * 0.5f
            canvas.drawRoundRect(RectF(cx - r, cy - r, cx + r, cy + r), r * 0.28f, r * 0.28f, p)
        }
        DeskIcon.draw(canvas, kind, cx, cy, s * if (tile) 0.52f else 0.9f, color)
    }
}

/**
 * The taskbar (UX_AUDIT D4, Phase 13): a start icon, one icon+title button per open window
 * (focused / minimized states, hover), the phone's battery, and a clock with the date. Drawn
 * in one view with its own hit-testing, so it costs one redraw a second and nothing per
 * frame. It is always the topmost chrome: [ShellRoot.syncViews] raises it above the windows.
 */
class TaskbarView(context: Context) : View(context) {

    companion object {
        const val START_W = 52
        const val BTN_W = 204
        const val BTN_GAP = 6
        const val CLOCK_W = 92
    }

    var onStart: (() -> Unit)? = null
    var onWindowButton: ((Int) -> Unit)? = null
    var buttons: List<ShellModel.TaskButton> = emptyList()
    var iconFor: ((Int) -> Pair<String, Int>)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.TEXT
        textSize = Theme.BODY
        typeface = Typeface.DEFAULT
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.TEXT_SECONDARY
        textSize = Theme.CAPTION
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.TEXT
        textSize = Theme.BODY
    }
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())

    private var hoverX = -1
    private var downAt = -1
    private var pressedAt = -1
    private var battery = -1

    init {
        setWillNotDraw(false)
        setBackgroundColor(Theme.SURFACE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(ticker, 1000)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 1000)
        }
    }

    /** Hover (the PC has no hover MotionEvents; the shell forwards the pointer position). */
    fun hover(x: Int, y: Int) {
        val inside = y >= 0 && y < height
        val nx = if (inside) x else -1
        if (nx != hoverX) { hoverX = nx; invalidate() }
    }

    private fun startHit(x: Int): Boolean = x in 0 until START_W

    private fun buttonRect(i: Int): RectF {
        val l = START_W + 8 + i * (BTN_W + BTN_GAP)
        return RectF(l.toFloat(), 8f, (l + BTN_W).toFloat(), (height - 8).toFloat())
    }

    private fun buttonAt(x: Int): Int {
        if (x < START_W) return -1
        for (i in buttons.indices) if (x >= buttonRect(i).left && x < buttonRect(i).right) return i
        return -1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = event.x.toInt()
                pressedAt = if (startHit(downAt)) -2 else buttonAt(downAt)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val x = event.x.toInt()
                if (kotlin.math.abs(x - downAt) < 14) {
                    if (startHit(x)) onStart?.invoke()
                    else buttonAt(x).let { i -> if (i >= 0) onWindowButton?.invoke(buttons[i].id) }
                }
                downAt = -1
                pressedAt = -1
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                downAt = -1; pressedAt = -1; invalidate(); return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun readBattery() {
        try {
            val bm = context.getSystemService(BatteryManager::class.java)
            val v = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (v in 0..100) battery = v
        } catch (_: Throwable) {
            // A battery reading is decoration; never let it break the taskbar.
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        readBattery()

        // Top border: one hairline so the bar reads as a surface, not a hole in the picture.
        paint.color = Theme.BORDER
        canvas.drawRect(0f, 0f, w.toFloat(), 1f, paint)

        // Start button: the four-square mark, no word.
        val hoverStart = hoverX in 0 until START_W
        if (pressedAt == -2 || hoverStart) {
            paint.color = if (pressedAt == -2) Theme.SURFACE_HOVER else Theme.SURFACE_RAISED
            canvas.drawRoundRect(RectF(8f, 8f, 44f, h - 8f), 9f, 9f, paint)
        }
        DeskIcon.draw(canvas, DeskIcon.GRID, 26f, h / 2f, 20f, Theme.ACCENT)

        // One button per window, in model order (bottom to top).
        for ((i, b) in buttons.withIndex()) {
            val r = buttonRect(i)
            val hover = hoverX >= r.left && hoverX < r.right
            if (b.focused || hover || pressedAt == i) {
                paint.color = when {
                    pressedAt == i -> Theme.SURFACE_HOVER
                    b.focused -> Theme.SURFACE_RAISED
                    else -> Theme.alpha(Theme.SURFACE_HOVER, 150)
                }
                canvas.drawRoundRect(r, Theme.RADIUS_TILE, Theme.RADIUS_TILE, paint)
            }
            if (b.focused) {
                paint.color = Theme.ACCENT
                canvas.drawRoundRect(
                    RectF(r.left + 10f, r.bottom - 2f, r.right - 10f, r.bottom), 1f, 1f, paint
                )
            }
            val (kind, color) = iconFor?.invoke(b.id) ?: (DeskIcon.WINDOW to Theme.ACCENT)
            DeskIcon.draw(canvas, kind, r.left + 18f, h / 2f, 16f, if (b.minimized) Theme.alpha(color, 140) else color)
            titlePaint.color = if (b.minimized) Theme.TEXT_DIM else Theme.TEXT
            val max = (r.width() - 44f).toInt().coerceAtLeast(20)
            val label = TextUtils.ellipsize(b.title, titlePaint, max.toFloat(), TextUtils.TruncateAt.END).toString()
            canvas.drawText(label, r.left + 32f, h / 2f + 5f, titlePaint)
            if (b.minimized) {
                paint.color = Theme.alpha(Theme.TEXT_DIM, 120)
                canvas.drawCircle(r.right - 12f, h / 2f, 2.5f, paint)
            }
        }

        // Right side: battery, then the clock and the date.
        val right = w - 14f
        val batteryLeft = right - 58f
        drawBattery(canvas, batteryLeft, h / 2f - 7f)
        small.color = Theme.TEXT_SECONDARY
        val date = dateFmt.format(Date())
        val dateW = small.measureText(date)
        canvas.drawText(date, batteryLeft - 14f - dateW, h / 2f + 5f, small)
        text.color = Theme.TEXT
        text.textSize = Theme.BODY_LARGE
        val time = timeFmt.format(Date())
        val timeW = text.measureText(time)
        canvas.drawText(time, batteryLeft - 14f - dateW - 14f - timeW, h / 2f + 5f, text)

        // The bar's own bottom hairline.
        paint.color = Theme.alpha(Theme.BORDER, 120)
        canvas.drawRect(0f, h - 1f, w.toFloat(), h.toFloat(), paint)
    }

    private fun drawBattery(canvas: Canvas, left: Float, top: Float) {
        val level = if (battery in 0..100) battery else 60
        paint.color = Theme.TEXT_SECONDARY
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        canvas.drawRoundRect(RectF(left, top, left + 30f, top + 14f), 3f, 3f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(RectF(left + 30f, top + 4f, left + 33f, top + 10f), 1f, 1f, paint)
        paint.color = when {
            level <= 15 -> Theme.DANGER
            level <= 30 -> Theme.WARNING
            else -> Theme.SUCCESS
        }
        val fill = (26f * level / 100f).coerceAtLeast(2f)
        canvas.drawRoundRect(RectF(left + 2f, top + 2f, left + 2f + fill, top + 12f), 2f, 2f, paint)
        small.color = Theme.TEXT_SECONDARY
        small.textSize = 10f
        canvas.drawText("${level}%", left + 38f, top + 11f, small)
    }
}

/**
 * The start menu (UX_AUDIT D3): a search box that filters as you type, the pinned apps as an
 * icon grid, and an "All apps" list with real names and icons. No Test, no versions, no
 * developer words. Esc or a click outside closes it; Enter opens the first match.
 */
class StartMenuView(context: Context) : LinearLayout(context) {

    companion object {
        /** The pinned apps, in the order the grid shows them. */
        val PINNED = listOf(
            ShellRoot.App.FILES,
            ShellRoot.App.BROWSER,
            ShellRoot.App.APPCENTER,
            ShellRoot.App.SETTINGS,
        )

        /** Steps shown under "All apps" that are always available. */
        val BUILTIN = listOf(ShellRoot.App.VSCODE)
    }

    var onLaunch: ((ShellRoot.App) -> Unit)? = null
    var onLaunchInstalled: ((String) -> Unit)? = null

    private class Entry(
        val label: String,
        val app: ShellRoot.App?,
        val catalogId: String?,
        val kind: String,
        val color: Int,
    )

    private val search = EditText(context).apply {
        hint = "Search apps"
        setHintTextColor(Theme.TEXT_DIM)
        setTextColor(Theme.TEXT)
        Theme.px(this, Theme.BODY_LARGE)
        setBackground(rounded(Theme.SURFACE_SUNKEN, Theme.BORDER, Theme.RADIUS_BUTTON))
        setPadding(30, 0, 12, 0)
        isSingleLine = true
        maxLines = 1
        isFocusable = true
        isFocusableInTouchMode = true
        setSelectAllOnFocus(false)
    }

    private val pinnedRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val allCol = LinearLayout(context).apply { orientation = VERTICAL }
    private val allScroll = ScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = true
        addView(allCol)
    }
    private val installed = ArrayList<MenuRow>()
    private var shown: List<Entry> = emptyList()

    init {
        orientation = VERTICAL
        setPadding(12, 12, 12, 10)
        background = rounded(Theme.SURFACE_RAISED, Theme.BORDER, Theme.RADIUS_MENU)
        elevation = 24f
        addView(search, LayoutParams(LayoutParams.MATCH_PARENT, 40))
        addView(pinnedRow, LayoutParams(LayoutParams.MATCH_PARENT, 88).apply { topMargin = 10 })
        addView(sectionLabel("All apps"), LayoutParams(LayoutParams.WRAP_CONTENT, 24).apply { topMargin = 8 })
        addView(allScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = 2 })

        for (app in PINNED) {
            val tile = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { launch(app) }
                addView(AppIconView(context, app.iconKind(), app.iconColor()), LayoutParams(40, 40))
                addView(TextView(context).apply {
                    text = app.title()
                    setTextColor(Theme.TEXT)
                    Theme.px(this, Theme.SMALL)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(2, 5, 2, 0)
                }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            }
            pinnedRow.addView(tile, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = rebuild(s?.toString().orEmpty())
        })
        rebuild("")
        isClickable = true
    }

    /** Replace the installed-app rows (kept separate from the pinned built-ins). */
    fun setInstalledRows(rows: List<MenuRow>) {
        installed.clear()
        installed.addAll(rows)
        rebuild(search.text?.toString().orEmpty())
    }

    /**
     * Manager 03:56: the panel must size itself to its content (search + pinned + rows) so
     * it never grows past the work area. One 40 px row per app; the caller caps it at 70 %
     * of the work area and the list scrolls inside.
     */
    fun preferredHeight(): Int = 204 + shown.size * 40

    /** Harness proof: the fixed rows plus the real installed rows, without Test. */
    fun rowsText(): String =
        (listOf("Programs") + PINNED.map { it.title() } + BUILTIN.map { it.title() } +
            installed.map { it.label }).joinToString(" | ")

    /** The PC keyboard while the menu is open: type to filter, Enter opens the first match. */
    fun handleKey(down: Boolean, keyCode: Int, scan: Int, meta: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) return false
        if (down && (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            shown.firstOrNull()?.let { open(it) }
            return true
        }
        val now = SystemClock.uptimeMillis()
        val ev = KeyEvent(
            now, now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            keyCode, 0, meta, -1, scan, 0, android.view.InputDevice.SOURCE_KEYBOARD
        )
        return search.dispatchKeyEvent(ev)
    }

    fun focusSearch() {
        search.requestFocus()
        search.setSelection(search.text?.length ?: 0)
    }

    fun clearSearch() {
        search.setText("")
    }

    private fun sectionLabel(s: String): TextView = TextView(context).apply {
        text = s
        setTextColor(Theme.TEXT_DIM)
        Theme.px(this, Theme.CAPTION)
        setPadding(6, 4, 6, 4)
    }

    private fun rebuild(query: String) {
        val q = query.trim().lowercase()
        val entries = ArrayList<Entry>()
        for (app in PINNED) entries.add(Entry(app.title(), app, null, app.iconKind(), app.iconColor()))
        for (app in BUILTIN) entries.add(Entry(app.title(), app, null, app.iconKind(), app.iconColor()))
        for (r in installed) {
            val kind = when (r.kind.lowercase()) {
                "windows" -> DeskIcon.NOTE
                "linux" -> DeskIcon.TERMINAL
                "android" -> DeskIcon.PHONE
                "web" -> DeskIcon.GLOBE
                else -> DeskIcon.WINDOW
            }
            val color = when (r.kind.lowercase()) {
                "windows" -> 0xFF7FA6FF.toInt()
                "linux" -> 0xFFE3A008.toInt()
                "android" -> 0xFFA78BFA.toInt()
                "web" -> 0xFF3AA0FF.toInt()
                else -> Theme.ACCENT
            }
            entries.add(Entry(r.label, null, r.appId, kind, color))
        }
        val matches = if (q.isEmpty()) entries else entries.filter { it.label.lowercase().contains(q) }
        // Pinned grid: show it only when the user is not searching; the all-apps list always
        // holds every match, so a search has exactly one answer list.
        pinnedRow.visibility = if (q.isEmpty()) View.VISIBLE else View.GONE
        shown = matches
        allCol.removeAllViews()
        for (e in matches) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8, 0, 8, 0)
                isClickable = true
                setOnClickListener { open(e) }
                addView(AppIconView(context, e.kind, e.color), LayoutParams(26, 26))
                addView(TextView(context).apply {
                    text = e.label
                    setTextColor(Theme.TEXT)
                    Theme.px(this, Theme.BODY_LARGE)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(10, 0, 0, 0)
                }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
            allCol.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, 40))
        }
        if (matches.isEmpty()) {
            allCol.addView(TextView(context).apply {
                text = "No apps match \"$query\""
                setTextColor(Theme.TEXT_DIM)
                Theme.px(this, Theme.BODY)
                setPadding(10, 12, 10, 12)
            })
        }
    }

    private fun launch(app: ShellRoot.App) {
        onLaunch?.invoke(app)
    }

    private fun open(e: Entry) {
        val app = e.app
        if (app != null) onLaunch?.invoke(app) else e.catalogId?.let { onLaunchInstalled?.invoke(it) }
    }

    private fun rounded(fill: Int, border: Int, radius: Float): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius
            setStroke(1, border)
        }
}

/** Our own pointer: a light arrow with a dark edge, drawn above every window. */
class CursorView(context: Context) : View(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF10141B.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
        strokeJoin = Paint.Join.ROUND
    }
    private var x = 0f
    private var y = 0f

    /** Resize-handle feedback: drawn as a small double arrow when over an edge/corner. */
    var hoverEdges = 0
        set(v) { field = v; invalidate() }

    fun setCursor(nx: Int, ny: Int) {
        x = nx.toFloat(); y = ny.toFloat()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val p = Path().apply {
            moveTo(x, y)
            lineTo(x, y + 19f)
            lineTo(x + 5.2f, y + 14.4f)
            lineTo(x + 8.6f, y + 22f)
            lineTo(x + 11.4f, y + 20.8f)
            lineTo(x + 8.2f, y + 13.4f)
            lineTo(x + 14.4f, y + 13.2f)
            close()
        }
        canvas.drawPath(p, fill)
        canvas.drawPath(p, edge)
        if (hoverEdges != 0) {
            edge.color = Theme.SUCCESS
            edge.strokeWidth = 2f
            if (hoverEdges and (ShellModel.EDGE_L or ShellModel.EDGE_R) != 0) {
                canvas.drawLine(x - 26, y, x - 10, y, edge)
                canvas.drawLine(x + 10, y, x + 26, y, edge)
            }
            if (hoverEdges and (ShellModel.EDGE_T or ShellModel.EDGE_B) != 0) {
                canvas.drawLine(x, y - 26, x, y - 10, edge)
                canvas.drawLine(x, y + 10, x, y + 26, edge)
            }
            edge.strokeWidth = 1.6f
        }
    }
}
