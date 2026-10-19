package dev.unifieddesktop.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The taskbar: a start button, one button per open window (showing which one has the focus
 * and which are minimized) and a clock. Buttons are drawn, not child views, so the taskbar
 * is one view with its own hit-testing - cheap to redraw and easy to reason about.
 */
class TaskbarView(context: Context) : View(context) {

    companion object {
        const val START_W = 150
        const val BTN_W = 240
        const val CLOCK_W = 130
    }

    var onStart: (() -> Unit)? = null
    var onWindowButton: ((Int) -> Unit)? = null
    var buttons: List<ShellModel.TaskButton> = emptyList()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 20f; typeface = Typeface.DEFAULT
    }
    private val clockFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var downAt = -1

    init {
        setWillNotDraw(false)
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

    /** Index of the window button at [x], or -1. */
    private fun buttonAt(x: Int): Int {
        if (x < START_W) return -1
        val i = (x - START_W) / BTN_W
        return if (i < buttons.size) i else -1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = event.x.toInt()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val x = event.x.toInt()
                if (kotlin.math.abs(x - downAt) < 12) {
                    if (x < START_W) onStart?.invoke()
                    else {
                        val i = buttonAt(x)
                        if (i >= 0) onWindowButton?.invoke(buttons[i].id)
                    }
                }
                downAt = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        paint.color = 0xFF1B1F27.toInt()
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.color = 0xFF2E3542.toInt()
        canvas.drawRect(0f, 0f, START_W.toFloat(), h.toFloat(), paint)
        text.color = Color.WHITE
        text.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("Start", 22f, h / 2f + 7f, text)
        text.typeface = Typeface.DEFAULT
        for ((i, b) in buttons.withIndex()) {
            val x = START_W + i * BTN_W
            paint.color = if (b.focused) 0xFF3D5AA8.toInt() else 0xFF252A34.toInt()
            canvas.drawRect(
                (x + 4).toFloat(), 6f, (x + BTN_W - 4).toFloat(), (h - 6).toFloat(), paint
            )
            paint.color = if (b.minimized) 0xFF6B7280.toInt() else 0xFF9AE6B4.toInt()
            canvas.drawRect((x + 4).toFloat(), (h - 9).toFloat(), (x + BTN_W - 4).toFloat(), (h - 6).toFloat(), paint)
            text.color = if (b.minimized) 0xFFB0B6C0.toInt() else Color.WHITE
            val label = (if (b.minimized) "\u25BC " else "") + b.title
            canvas.drawText(label, (x + 16).toFloat(), h / 2f + 7f, text)
        }
        text.color = 0xFFE6E6E6.toInt()
        val c = clockFmt.format(Date())
        val cw = text.measureText(c)
        canvas.drawText(c, (w - cw - 18).toFloat(), h / 2f + 7f, text)
    }
}

/**
 * The start menu: Files, Browser, Settings and Test (the Phase-5 test desktop, kept
 * reachable so the Phase-5 measurements still work on this build).
 */
class StartMenuView(context: Context) : LinearLayout(context) {

    var onLaunch: ((ShellRoot.App) -> Unit)? = null

    private val title = TextView(context).apply {
        text = "Programs"
        setTextColor(Color.WHITE)
        setBackgroundColor(0xFF2B3A67.toInt())
        textSize = 18f
        setPadding(18, 12, 18, 12)
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFF20242C.toInt())
        addView(title)
        for (app in ShellRoot.App.values()) {
            val row = TextView(context).apply {
                text = app.title()
                setTextColor(Color.WHITE)
                textSize = 22f
                setPadding(28, 18, 18, 18)
                isClickable = true
                setOnClickListener { onLaunch?.invoke(app) }
            }
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        isClickable = true
    }
}

/** Our own pointer: a desktop-sized arrow, drawn on top of everything. */
class CursorView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 230, 60) }
    private val tip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
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
        val p = android.graphics.Path()
        p.moveTo(x, y)
        p.lineTo(x + 6, y + 6)
        p.lineTo(x + 12, y + 12)
        p.lineTo(x + 6, y + 14)
        p.lineTo(x + 8, y + 22)
        p.lineTo(x + 3, y + 24)
        p.lineTo(x + 1, y + 16)
        p.lineTo(x - 4, y + 20)
        canvas.drawPath(p, tip)
        paint.color = Color.rgb(255, 230, 60)
        canvas.drawCircle(x, y, 4f, paint)
        if (hoverEdges != 0) {
            paint.color = Color.rgb(120, 220, 160)
            if (hoverEdges and (ShellModel.EDGE_L or ShellModel.EDGE_R) != 0) {
                canvas.drawLine(x - 26, y, x - 10, y, paint)
                canvas.drawLine(x + 10, y, x + 26, y, paint)
            }
            if (hoverEdges and (ShellModel.EDGE_T or ShellModel.EDGE_B) != 0) {
                canvas.drawLine(x, y - 26, x, y - 10, paint)
                canvas.drawLine(x, y + 10, x, y + 26, paint)
            }
        }
    }
}
