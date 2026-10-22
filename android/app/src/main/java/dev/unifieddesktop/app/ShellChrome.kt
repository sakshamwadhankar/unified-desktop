package dev.unifieddesktop.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Phase 13 chrome shared by the app contents (Browser toolbar, Files, App Center).
 *
 * The shell has one dark theme ([Theme]) and one hand-drawn icon set ([DeskIcon]); before
 * this file Files and App Center still used 20 px white text on blue buttons, which the
 * 03:56 review called out. Everything here is desktop pixels at 160 dpi (D9), so a 34 px
 * button is 34 px on the PC too.
 */

internal fun shellRounded(fill: Int, border: Int, radius: Float): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radius
        if (border != 0) setStroke(1, border)
    }

/** A non-interactive desk icon, for search hints and list rows. */
class DeskIconView(
    context: Context,
    private var kind: String,
    private var tint: Int = Theme.TEXT_SECONDARY,
    private val sizePx: Float = 16f,
) : View(context) {
    fun set(kind: String, tint: Int) {
        this.kind = kind
        this.tint = tint
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        DeskIcon.draw(canvas, kind, width / 2f, height / 2f, sizePx, tint)
    }
}

/**
 * A toolbar icon button with the shell's hover and press states. It carries a content
 * description so the tools that inspect the view tree still know what it does.
 */
class DeskIconButton(
    context: Context,
    private val kind: String,
    description: String,
    private val onTap: () -> Unit,
) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private var hover = false

    init {
        contentDescription = description
        isClickable = true
        isFocusable = false
        setOnClickListener { onTap() }
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_HOVER_ENTER -> {
                hover = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                hover = false
                invalidate()
                return true
            }
        }
        return super.onHoverEvent(event)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val lit = hover || isPressed
        if (lit) {
            fill.color = if (isPressed) Theme.SURFACE_HOVER else Theme.alpha(Theme.TEXT, 16)
            canvas.drawRoundRect(1f, 1f, w - 1f, h - 1f, Theme.RADIUS_BUTTON, Theme.RADIUS_BUTTON, fill)
        }
        val tint = when {
            !isEnabled -> Theme.TEXT_DIM
            lit -> Theme.TEXT
            else -> Theme.TEXT_SECONDARY
        }
        DeskIcon.draw(canvas, kind, w / 2f, h / 2f, 15f, tint)
    }
}

internal fun shellField(c: Context, hint: String): EditText = EditText(c).apply {
    this.hint = hint
    setHintTextColor(Theme.TEXT_DIM)
    setTextColor(Theme.TEXT)
    Theme.px(this, Theme.BODY_LARGE)
    setBackground(shellRounded(Theme.SURFACE_SUNKEN, Theme.BORDER, Theme.RADIUS_BUTTON))
    setPadding(10, 0, 10, 0)
    isSingleLine = true
    maxLines = 1
    setSelectAllOnFocus(false)
    includeFontPadding = false
}

/**
 * A compact text button. [primary] is the accent "do it" action, [danger] the destructive
 * one; everything else is a quiet surface button.
 */
internal fun shellButton(
    c: Context,
    text: String,
    primary: Boolean = false,
    danger: Boolean = false,
    onTap: () -> Unit,
): TextView = TextView(c).apply {
    this.text = text
    setTextColor(if (primary) Color.WHITE else if (danger) Theme.DANGER else Theme.TEXT)
    Theme.px(this, Theme.BODY)
    typeface = if (primary) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
    gravity = Gravity.CENTER
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
    setPadding(14, 0, 14, 0)
    background = shellRounded(
        if (primary) Theme.ACCENT else Theme.SURFACE_RAISED,
        if (primary) Theme.ACCENT else Theme.BORDER,
        Theme.RADIUS_BUTTON,
    )
    isClickable = true
    minimumHeight = 32
    setOnClickListener { onTap() }
}

/** A small rounded label: kind, version, rating, installed state. */
internal fun shellChip(
    c: Context,
    text: String,
    tint: Int = Theme.TEXT_SECONDARY,
    fill: Int = Theme.SURFACE_SUNKEN,
): TextView = TextView(c).apply {
    this.text = text
    setTextColor(tint)
    Theme.px(this, Theme.CAPTION)
    setPadding(8, 3, 8, 3)
    background = shellRounded(fill, 0, 9f)
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
}

/** One rounded panel for a card. */
internal fun shellCard(c: Context): LinearLayout = LinearLayout(c).apply {
    orientation = LinearLayout.VERTICAL
    background = shellRounded(Theme.SURFACE_RAISED, Theme.BORDER, Theme.RADIUS_CARD)
    setPadding(12, 10, 12, 10)
}

/** A 1 px hairline that shrinks to the row's own height. */
internal fun shellDivider(c: Context): View = View(c).apply {
    setBackgroundColor(Theme.BORDER)
}

/**
 * Phase 13: the desktop right-click menu. It is shown on the wallpaper only (the shell
 * keeps window context menus for later); the four entries go through the same ShellRoot
 * routes the start menu uses, so they cannot drift.
 */
class DesktopContextMenu(context: Context) : LinearLayout(context) {
    var onPick: ((String) -> Unit)? = null

    private val items = listOf(
        "Files" to "files",
        "App Center" to "appcenter",
        "Change wallpaper" to "wallpaper",
        "Display settings" to "settings",
    )

    init {
        orientation = VERTICAL
        setPadding(4, 4, 4, 4)
        background = shellRounded(Theme.SURFACE_RAISED, Theme.BORDER, Theme.RADIUS_MENU)
        elevation = 24f
        for ((label, id) in items) {
            addView(TextView(context).apply {
                text = label
                setTextColor(Theme.TEXT)
                Theme.px(this, Theme.BODY)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(10, 0, 10, 0)
                isClickable = true
                setOnClickListener {
                    // Hide the menu itself, not this TextView (the `apply` receiver is the row).
                    this@DesktopContextMenu.visibility = View.GONE
                    onPick?.invoke(id)
                }
            }, LayoutParams(LayoutParams.MATCH_PARENT, 32))
        }
    }
}
