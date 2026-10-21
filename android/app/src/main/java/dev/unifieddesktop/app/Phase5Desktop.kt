package dev.unifieddesktop.app

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import java.util.concurrent.atomic.AtomicInteger

/**
 * The desktop surface: a VirtualDisplay we own (no MediaProjection), rendered into the
 * SurfaceTexture the GL compositor reads, showing a `Presentation` with the PHASE-6 SHELL
 * (taskbar, start menu, movable windows). The Phase-5 test desktop still exists as the
 * shell's "Test" window, so every Phase-5 measurement keeps working.
 *
 * Plain views on our own display take events by direct dispatch (LESSONS 2 - no
 * INJECT_EVENTS permission, no Accessibility service, D5). The shell sends the PC's pointer
 * to its root as real SOURCE_MOUSE MotionEvents, so normal hit-testing routes them.
 *
 * Every method here runs on the UI thread; the link session posts to it.
 */
class Phase5Desktop(private val context: Context, private val fallbackContext: Context? = null) {
    companion object {
        const val W = Phase5Protocol.DESKTOP_W
        const val H = Phase5Protocol.DESKTOP_H
        const val DPI = 160
        const val TAG = "Phase5"

        /** Key-down flash duration; long enough for the PC's PrintWindow poll to see it. */
        const val FLASH_MS = 120L
    }

    @Volatile var running = false
        private set

    /** The live shell (null until the Presentation is up). */
    var desktop: ShellRoot? = null
        private set

    private var vd: VirtualDisplay? = null
    private var pres: Presentation? = null

    fun start(surface: Surface): Boolean {
        if (running) return true
        if (!createDisplay(surface)) return false
        return showPresentation()
    }

    /**
     * Create the private VirtualDisplay and wait until ColorOS has actually brought it up:
     * adding the Presentation window while the display is still STATE_UNKNOWN fails with
     * `InvalidDisplayException: the specified display can not be found` (phone, 2026-10-19).
     * Safe to call from any thread; the Presentation itself needs the UI thread.
     */
    fun createDisplay(surface: Surface): Boolean {
        return try {
            val dm = context.getSystemService(DisplayManager::class.java)
            // OWN_CONTENT_ONLY only: the proven Phase-1 configuration for this phone
            // (HarnessActivity.createDisplay). Adding VIRTUAL_DISPLAY_FLAG_PUBLIC makes the
            // window add fail with InvalidDisplayException on ColorOS (phone 2026-10-19).
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            val d = dm.createVirtualDisplay("phase5-desktop", W, H, DPI, surface, flags)
            vd = d
            val display = d.display ?: throw RuntimeException("virtual display has no Display")
            // Do not gate on Display.getState(): for an app-created display this reads
            // STATE_UNKNOWN for a while (verified on the phone) even though the system
            // brings it up ~0.5 s later. showPresentation() retries instead.
            android.util.Log.i(TAG, "virtual display id=${display.displayId} state=${display.state}")
            true
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "virtual display failed", t)
            stop()
            false
        }
    }

    /**
     * UI thread: attach the shell to [createDisplay]'s display. Retries because
     * adding the window before ColorOS finishes bringing the display up throws
     * `InvalidDisplayException: the specified display can not be found` (phone 2026-10-19).
     */
    fun showPresentation(): Boolean {
        if (running) return true
        val d = vd ?: return false
        val deadline = SystemClock.uptimeMillis() + 6000
        var last: Throwable? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                val p = try {
                    Presentation(context, d.display)
                } catch (first: Throwable) {
                    val fallback = fallbackContext
                    if (fallback == null) throw first
                    android.util.Log.w(TAG, "service Presentation context failed; trying resumed activity", first)
                    Presentation(fallback, d.display)
                }
                // The shell must render at the desktop's own scale (1.0): building it with the
                // activity context uses the phone's density (up to 480 = 3x), which makes every
                // sp-sized part (taskbar, WebView, code-server) 2-3x too large on a 1920x1080
                // PC-size desktop (owner report + ph-vscode-max1.png, 2026-10-19).
                val config = android.content.res.Configuration(context.resources.configuration)
                config.densityDpi = DPI
                // B-066 round 3: the configuration override fixed the shell's own sp text but the
                // WebView inside it still reported devicePixelRatio=3 (measured on the phone:
                // innerWidth=300 in an 835-px-wide window). A WebView derives its scale from the
                // display it is attached to, so build the shell context from the virtual display
                // (created at DPI) and only then apply the density override.
                val displayContext = context.createDisplayContext(d.display)
                val shellContext = displayContext.createConfigurationContext(config)
                android.util.Log.i(TAG, "shell density=" + shellContext.resources.displayMetrics.density +
                        " dpi=" + shellContext.resources.displayMetrics.densityDpi +
                        " displayDensity=" + displayContext.resources.displayMetrics.density)
                p.setContentView(ShellRoot(shellContext).also {
                    // B-095: the PC draws the only mouse pointer during a PC session.
                    it.setPcPointerVisible(false)
                    desktop = it
                })
                p.setOnDismissListener { running = false }
                p.show()
                pres = p
                running = true
                android.util.Log.i(TAG, "shell presentation shown on display ${d.display.displayId}")
                return true
            } catch (t: Throwable) {
                last = t
                try { Thread.sleep(150) } catch (_: InterruptedException) {}
            }
        }
        android.util.Log.e(TAG, "shell start failed after retries", last)
        stop()
        return false
    }

    fun stop() {
        running = false
        try { pres?.dismiss() } catch (_: Throwable) {}
        pres = null
        desktop = null
        try { vd?.release() } catch (_: Throwable) {}
        vd = null
    }

    /** Replace only the encoder surface; the Presentation and its shell remain intact. */
    fun rebindSurface(surface: Surface): Boolean = try {
        vd?.surface = surface
        true
    } catch (t: Throwable) {
        android.util.Log.e(TAG, "virtual display surface rebind failed", t)
        false
    }

    /** Detach an encoder before its Surface is released during a link loss. */
    fun detachSurface() {
        try { vd?.surface = null } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------ input ops
    // All of these are called on the UI thread by the session.

    /** Hover move (no button held) or drag (button held): the shell decides from its state. */
    fun setCursor(x: Int, y: Int) = desktop?.moveTo(x, y) ?: Unit

    fun pointerButton(button: Int, down: Boolean, x: Int, y: Int) = desktop?.mouseButton(button, down, x, y) ?: Unit

    fun wheel(delta: Int, x: Int, y: Int) = desktop?.wheel(delta, x, y) ?: Unit

    fun key(down: Boolean, keyCode: Int, scan: Int, meta: Int) = desktop?.key(down, keyCode, scan, meta) ?: false

    /** Releases everything the PC still holds; returns (keys released, buttons released). */
    fun releaseAll(): Pair<Int, Int> = desktop?.releaseAll() ?: Pair(0, 0)

    fun inputText(): String = desktop?.focusedText() ?: ""

    fun heldCounts(): Pair<Int, Int> = desktop?.heldCounts() ?: Pair(0, 0)

    /** One line for p4.log: shell state (windows, focus, start menu, input bookkeeping). */
    fun stats(): String = desktop?.stats() ?: "shell=down"

    fun setFlashListener(l: ((Int, Long) -> Unit)?) = desktop?.setFlashListener(l) ?: Unit

    fun shell(): ShellRoot? = desktop
}

/** The test desktop: custom drawing + one real EditText that takes the PC's keys. */
open class TestDesktop(context: Context) : FrameLayout(context) {
    private val canvasView = SurfaceView5(context)

    val edit = EditText(context).apply {
        hint = "type here (PC keyboard)"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        isFocusable = true
        isFocusableInTouchMode = true
        setTextColor(Color.BLACK)
        setBackgroundColor(0xFFE0E0E0.toInt())
        textSize = 22f
    }

    /** Keys/buttons the PC still holds down; the stuck-key check reads these. */
    private val heldKeys = HashSet<Int>()
    private val heldButtons = HashSet<Int>()

    private var keysAllTime = 0
    private var buttonsAllTime = 0
    private var keysReleasedByReleaseAll = 0
    private var buttonsReleasedByReleaseAll = 0

    init {
        setBackgroundColor(0xFF202030.toInt())
        addView(canvasView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(edit, LayoutParams(560, 220).apply { leftMargin = 60; topMargin = 620 })
    }

    fun setCursor(x: Int, y: Int) = canvasView.setCursor(x, y)

    fun pointerButton(button: Int, down: Boolean, x: Int, y: Int) {
        if (down) {
            heldButtons.add(button)
            buttonsAllTime++
            if (button == Phase5Protocol.BTN_LEFT && canvasView.editRect.contains(x, y)) {
                // Click inside the EditText: hand it real focus (B-039 family - focus is
                // what the WebView case lacked), then let the normal touch path run.
                edit.requestFocusFromTouch()
                edit.requestFocus()
                canvasView.dispatchEditTouch(true, x, y)
            }
        } else {
            heldButtons.remove(button)
            if (canvasView.editRect.contains(x, y)) canvasView.dispatchEditTouch(false, x, y)
        }
        canvasView.pointerButton(button, down, x, y)
    }

    fun wheel(delta: Int, x: Int, y: Int) = canvasView.wheel(delta, x, y)

    fun key(down: Boolean, keyCode: Int, scan: Int, meta: Int): Boolean {
        if (keyCode == 0) return false
        if (down) { heldKeys.add(keyCode); keysAllTime++ } else heldKeys.remove(keyCode)
        val now = SystemClock.uptimeMillis()
        val action = if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        // deviceId -1 (VIRTUAL_KEYBOARD) + a real scan code is what `adb input keyevent` uses,
        // the one route that reached a WebView in Phase 3 (BUGS B-039). KeyEvent.obtain is a
        // hidden API (not in the public stubs), the 10-arg constructor is public - use it.
        val ev = KeyEvent(now, now, action, keyCode, 0, meta, -1, scan, 0, android.view.InputDevice.SOURCE_KEYBOARD)
        val handled = try { edit.dispatchKeyEvent(ev) } finally { /* no recycle: not obtain()ed */ }
        if (down) canvasView.flash()
        return handled
    }

    fun releaseAll(): Pair<Int, Int> {
        val k = heldKeys.size
        val b = heldButtons.size
        keysReleasedByReleaseAll += k
        buttonsReleasedByReleaseAll += b
        // A real ACTION_UP for every key the PC still holds: clearing our own bookkeeping
        // alone would leave the views thinking a key (a modifier, say) is still down.
        val now = SystemClock.uptimeMillis()
        for (code in heldKeys.toList()) {
            val up = KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, 0, -1, 0, 0, android.view.InputDevice.SOURCE_KEYBOARD)
            try { edit.dispatchKeyEvent(up) } catch (_: Throwable) {}
        }
        if (heldButtons.isNotEmpty()) canvasView.cancelEditTouch()
        heldKeys.clear()
        heldButtons.clear()
        canvasView.releaseDrag()
        // Empty the text field too? No: the typed text is the item-6 evidence.
        return Pair(k, b)
    }

    fun heldCounts(): Pair<Int, Int> = Pair(heldKeys.size, heldButtons.size)

    /** Latency probe hook: (flash number, System.nanoTime) when a key flash is first drawn. */
    fun setFlashListener(l: ((Int, Long) -> Unit)?) { canvasView.onFlashDrawn = l }

    fun inputText(): String = edit.text.toString()

    /** One line for p4.log: the whole input bookkeeping in one place. */
    fun stats(): String =
        "keysDown=${heldKeys.size} buttonsDown=${heldButtons.size} keysAllTime=$keysAllTime " +
            "buttonsAllTime=$buttonsAllTime keysReleasedByReleaseAll=$keysReleasedByReleaseAll " +
            "buttonsReleasedByReleaseAll=$buttonsReleasedByReleaseAll"
}

/** The pixel-drawing half of the test desktop. */
private class SurfaceView5(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        typeface = Typeface.MONOSPACE
    }
    val editRect = Rect(60, 620, 620, 840)
    private val boxW = 240f
    private val boxH = 180f

    private var boxX = 900f
    private var boxY = 300f
    private var dragButton = -1
    private var boxMoves = 0
    private var keyCount = 0
    private var drawCount = 0
    private var cursorX = 960f
    private var cursorY = 540f
    private var flashUntilMs = 0L

    private val startNs = System.nanoTime()

    init { setWillNotDraw(false) }

    fun setCursor(x: Int, y: Int) {
        cursorX = x.toFloat().coerceIn(0f, (Phase5Desktop.W - 1).toFloat())
        cursorY = y.toFloat().coerceIn(0f, (Phase5Desktop.H - 1).toFloat())
        if (dragButton >= 0) {
            boxX = (cursorX + dragOffX).coerceIn(0f, Phase5Desktop.W - boxW)
            boxY = (cursorY + dragOffY).coerceIn(0f, Phase5Desktop.H - boxH)
            boxMoves++
        }
        invalidate()
    }

    fun pointerButton(button: Int, down: Boolean, x: Int, y: Int) {
        setCursor(x, y)
        if (down) {
            if (button == Phase5Protocol.BTN_LEFT && !editRect.contains(x, y)) {
                dragButton = button
                // grab offset so the box does not jump under the pointer
                dragOffX = boxX - x
                dragOffY = boxY - y
            }
        } else if (button == dragButton) {
            dragButton = -1
        }
        invalidate()
    }

    private var dragOffX = 0f
    private var dragOffY = 0f

    /** The desktop's pointer move already updated the cursor; drag the box if held. */
    fun releaseDrag() { dragButton = -1 }
    /** Forward a click inside the EditText rect as a real tap on the EditText. */
    fun dispatchEditTouch(down: Boolean, x: Int, y: Int) {
        val ev = android.view.MotionEvent.obtain(
            SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
            if (down) android.view.MotionEvent.ACTION_DOWN else android.view.MotionEvent.ACTION_UP,
            x.toFloat(), y.toFloat(), 0
        )
        try { ev.setSource(android.view.InputDevice.SOURCE_MOUSE) } catch (_: Throwable) {}
        try {
            val parent = parent as TestDesktop
            parent.edit.dispatchTouchEvent(ev)
        } finally {
            ev.recycle()
        }
    }

    fun wheel(delta: Int, x: Int, y: Int) {
        setCursor(x, y)
        boxY = (boxY + delta).coerceIn(0f, Phase5Desktop.H - boxH)
        invalidate()
    }

    /** Cancel a touch the PC left down inside the EditText (release-all). */
    fun cancelEditTouch() {
        val t = SystemClock.uptimeMillis()
        val ev = android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try { (parent as TestDesktop).edit.dispatchTouchEvent(ev) } finally { ev.recycle() }
    }

    /** Called when a key flash is first drawn: (flash number, System.nanoTime). */
    var onFlashDrawn: ((Int, Long) -> Unit)? = null
    private var reportedFlash = 0

    fun flash() {
        keyCount++
        flashUntilMs = SystemClock.uptimeMillis() + Phase5Desktop.FLASH_MS
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        drawCount++
        val t = (System.nanoTime() - startNs) / 1_000_000.0

        canvas.drawColor(0xFF202030.toInt())

        // moving elements (fps while something moves)
        val cx = (Phase5Desktop.W / 2.0 + Math.cos(t / 700.0) * (Phase5Desktop.W / 2.0 - 140)).toFloat()
        val cy = (140.0 + Math.sin(t / 300.0) * 60).toFloat()
        paint.color = Color.rgb(255, 90, 90)
        canvas.drawCircle(cx, cy, 52f, paint)
        paint.color = Color.rgb(90, 200, 255)
        canvas.drawCircle(Phase5Desktop.W - cx, (400 + Math.sin(t / 500.0) * 120).toFloat(), 34f, paint)
        val barX = ((t / 8.0) % Phase5Desktop.W).toFloat()
        paint.color = Color.rgb(40, 140, 90)
        canvas.drawRect(barX, 200f, barX + 60f, 420f, paint)

        // flash area (typing latency): white only inside the flash window
        val flashing = SystemClock.uptimeMillis() < flashUntilMs
        if (flashing && reportedFlash != keyCount) {
            reportedFlash = keyCount
            onFlashDrawn?.invoke(keyCount, System.nanoTime())
        }
        paint.color = if (flashing) Color.WHITE else Color.rgb(30, 30, 45)
        canvas.drawRect(60f, 60f, 700f, 260f, paint)
        textPaint.color = if (flashing) Color.BLACK else Color.WHITE
        canvas.drawText(if (flashing) "FLASH #$keyCount" else "flash area", 90f, 175f, textPaint)

        // draggable box
        paint.color = Color.rgb(70, 110, 230)
        canvas.drawRect(boxX, boxY, boxX + boxW, boxY + boxH, paint)
        paint.color = Color.WHITE
        canvas.drawRect(boxX + 6, boxY + 6, boxX + boxW - 6, boxY + boxH - 6, paint)
        paint.color = Color.rgb(70, 110, 230)
        canvas.drawRect(boxX + 12, boxY + 12, boxX + boxW - 12, boxY + boxH - 12, paint)

        // EditText placeholder outline (the real EditText is a sibling view)
        paint.color = Color.rgb(120, 120, 150)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        canvas.drawRect(editRect, paint)
        paint.style = Paint.Style.FILL

        // HUD
        textPaint.color = Color.WHITE
        canvas.drawText("phase5 desktop ${Phase5Desktop.W}x${Phase5Desktop.H} draw=$drawCount t=${t.toInt()}ms", 60f, 1000f, textPaint)
        canvas.drawText(
            "cursor=$cursorX,$cursorY boxMoves=$boxMoves keyFlashes=$keyCount drag=$dragButton",
            60f, 1040f, textPaint
        )

        // cursor
        paint.color = Color.rgb(255, 230, 60)
        canvas.drawCircle(cursorX, cursorY, 6f, paint)
        canvas.drawLine(cursorX, cursorY, cursorX + 22, cursorY + 30, paint)
        canvas.drawLine(cursorX, cursorY, cursorX - 10, cursorY + 30, paint)

        // keep animating while the session is up (fps measurement needs motion)
        if (isShown) postInvalidateOnAnimation()
    }
}
