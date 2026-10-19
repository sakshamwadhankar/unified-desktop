package dev.unifieddesktop.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/** Content that wants raw PC keys instead of View-level KeyEvents (X11 windows -> XTEST). */
interface ShellRawKeyContent {
    fun key(down: Boolean, keyCode: Int, scan: Int, meta: Int): Boolean
}

/**
 * One shell window showing the whole Xvfb screen with pointer + key input straight to the X
 * server (XTEST), so a **Windows** app (Notepad++ under Wine/Box64) and a **Linux** app (xeyes)
 * running inside that one X server are visible and usable from the PC.
 *
 * Frames: `GetImage` of the 1280x720 root on a worker thread, ~8 fps, posted to an ImageView.
 * Pointer: press/move/release on the content map to Xvfb coordinates and go out as XTEST
 * MotionNotify / Button events. Keys: Android keycode -> unicode char -> X keysym -> XTEST
 * key press/release (with Shift as the keymap demands).
 */
class X11WindowContent(context: Context) :
    FrameLayout(context), ShellMouseContent, ShellRawKeyContent, ShellTextContent {

    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var x11: Phase2X11.X11? = null
    @Volatile private var running = false
    private var frames = 0L
    private var lastGrabMs = 0L

    init {
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (running) return
        running = true
        Thread({ loop() }, "p7-x11-window").start()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    /** Connect once, then grab frames until the window is gone. */
    private fun loop() {
        val x: Phase2X11.X11
        try {
            x = Phase2X11.X11.connect()
            x.initXtest()
            x.initKeyboard()
            x11 = x
            P7Log.say(context, "P7X11 connected")
        } catch (t: Throwable) {
            P7Log.say(context, "P7X11 connect failed: $t")
            running = false
            return
        }
        while (running) {
            val t0 = SystemClock.elapsedRealtime()
            try {
                val bmp = x.getImage()
                frames++
                main.post { image.setImageBitmap(bmp) }
            } catch (t: Throwable) {
                P7Log.say(context, "P7X11 frame failed: $t")
                try { Thread.sleep(250) } catch (_: InterruptedException) { break }
            }
            lastGrabMs = SystemClock.elapsedRealtime() - t0
            val rest = 120 - lastGrabMs
            if (rest > 0) try { Thread.sleep(rest) } catch (_: InterruptedException) { break }
        }
        P7Log.say(context, "P7X11 loop end frames=$frames")
        try { x.close() } catch (_: Throwable) { }
        x11 = null
    }

    // ------------------------------------------------------------------ pointer

    /** Content pixel -> Xvfb pixel (the window shows the whole 1280x720 screen). */
    private fun gx(x: Int): Int = if (width <= 0) x else (x.toLong() * W / width).toInt()
    private fun gy(y: Int): Int = if (height <= 0) y else (y.toLong() * H / height).toInt()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = x11 ?: return false
        val cx = gx(event.x.toInt()).coerceIn(0, W - 1)
        val cy = gy(event.y.toInt()).coerceIn(0, H - 1)
        try {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x.motion(cx, cy); x.button(1, true) }
                MotionEvent.ACTION_MOVE -> x.motion(cx, cy)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { x.motion(cx, cy); x.button(1, false) }
                else -> return false
            }
        } catch (t: Throwable) {
            P7Log.say(context, "P7X11 input failed: $t")
            return false
        }
        return true
    }

    override fun mouseMove(x: Int, y: Int) { }

    override fun mouseButton(button: Int, down: Boolean, x: Int, y: Int) {
        // The shell routes real left-button presses through dispatchTouchEvent (onTouchEvent
        // above); this stays as the ShellMouseContent contract for non-touch dispatch.
    }

    override fun mouseWheel(delta: Int, x: Int, y: Int) {
        val client = x11 ?: return
        try {
            val btn = if (delta < 0) 4 else 5 // X: button 4/5 = wheel up/down
            repeat(3) { client.button(btn, true); client.button(btn, false) }
        } catch (t: Throwable) {
            P7Log.say(context, "P7X11 wheel failed: $t")
        }
    }

    // ------------------------------------------------------------------ keyboard

    /**
     * A PC key -> XTEST key press/release. The char an Android keycode+meta produces is the one
     * Notepad++/xeyes would receive from a phone keyboard, so it maps directly to an X keysym.
     */
    override fun key(down: Boolean, keyCode: Int, scan: Int, meta: Int): Boolean {
        val client = x11 ?: return false
        val ch = charFor(keyCode, meta)
        if (ch == '\u0000') {
            // No printable char (arrows, modifiers, Enter handled below): act on the few keys
            // that matter for a text editor.
            val sym = when (keyCode) {
                KeyEvent.KEYCODE_ENTER -> 0xFF0D
                KeyEvent.KEYCODE_DEL -> 0xFF08
                KeyEvent.KEYCODE_FORWARD_DEL -> 0xFFFF
                KeyEvent.KEYCODE_TAB -> 0xFF09
                KeyEvent.KEYCODE_ESCAPE -> 0xFF1B
                KeyEvent.KEYCODE_DPAD_LEFT -> 0xFF51
                KeyEvent.KEYCODE_DPAD_UP -> 0xFF52
                KeyEvent.KEYCODE_DPAD_RIGHT -> 0xFF53
                KeyEvent.KEYCODE_DPAD_DOWN -> 0xFF54
                KeyEvent.KEYCODE_SPACE -> 0x20
                else -> return false
            }
            return fire(client, sym, down)
        }
        val sym = ch.code
        return fire(client, sym, down)
    }

    private fun fire(client: Phase2X11.X11, keysym: Int, down: Boolean): Boolean {
        val kc = client.keycodeFor(keysym) ?: return false
        return try {
            val evType = if (down) 2 else 3 // KeyPress / KeyRelease
            if (kc.second) client.fakeKey(evType, client.shiftCode)
            client.fakeKey(evType, kc.first)
            true
        } catch (t: Throwable) {
            P7Log.say(context, "P7X11 key failed: $t")
            false
        }
    }

    /** The character a phone keyboard would produce for this keycode+meta, or NUL. */
    private fun charFor(keyCode: Int, meta: Int): Char {
        val ev = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, 0, meta)
        val c = ev.unicodeChar
        return if (c == 0) '\u0000' else c.toChar()
    }

    override fun contentText(): String = ""

    private companion object {
        const val W = 1280
        const val H = 720
    }
}
