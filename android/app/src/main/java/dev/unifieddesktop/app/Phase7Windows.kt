package dev.unifieddesktop.app

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/** Content that wants raw PC keys instead of View-level KeyEvents (X11 windows -> XTEST). */
interface ShellRawKeyContent {
    fun key(down: Boolean, keyCode: Int, scan: Int, meta: Int): Boolean
}

/**
 * B-074: retry policy for the X11 window's connection, pure so it is unit-tested. The window
 * retries every 500 ms for up to 60 s (Xvfb may still be booting), and reconnects the same
 * way after a later failure (Xvfb restarted).
 */
object X11ConnectPolicy {
    const val STEP_MS = 500L
    const val WINDOW_MS = 60_000L

    fun shouldRetry(startedMs: Long, nowMs: Long, windowMs: Long = WINDOW_MS): Boolean =
        nowMs - startedMs < windowMs

    fun maxAttempts(windowMs: Long = WINDOW_MS, stepMs: Long = STEP_MS): Int =
        (windowMs / stepMs).toInt().coerceAtLeast(1)
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
 *
 * B-074: the window may be opened before Xvfb is listening (the user can open it, and the guest
 * apps start asynchronously). It shows "Starting..." and retries every 500 ms for 60 s; a
 * frame loop that later loses the server reconnects instead of dying for good.
 */
class X11WindowContent(context: Context) :
    FrameLayout(context), ShellMouseContent, ShellRawKeyContent, ShellTextContent {

    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private val status = TextView(context).apply {
        text = "Starting..."
        setTextColor(Color.WHITE)
        textSize = 22f
        gravity = Gravity.CENTER
        setBackgroundColor(0xCC000000.toInt())
    }
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var x11: Phase2X11.X11? = null
    @Volatile private var running = false
    @Volatile private var reconnectRequested = false
    private var frames = 0L
    private var lastGrabMs = 0L

    // B-062: X11 socket I/O must not run on the main thread (NetworkOnMainThreadException).
    // One worker keeps pointer and key events in the order the PC produced them.
    @Volatile private var input: java.util.concurrent.ExecutorService? = null
    private val inputLock = Any()
    // One X11 socket: the frame loop and the input worker both use it, so every request/reply
    // pair must be serialized (interleaved reads desync GetImage vs GetKeyboardMapping).
    private val xio = Any()
    // B-077: every X keycode we pressed and have not released yet. XTEST keys belong to the
    // server's core keyboard, so when this connection dies the server keeps them down and
    // auto-repeats them; the next connection must release them.
    private val pressedKeys = HashSet<Int>()

    init {
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun submit(block: () -> Unit): Boolean {
        val ex = synchronized(inputLock) {
            input ?: java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "p7-x11-input").apply { isDaemon = true }
            }.also { input = it }
        }
        return try {
            ex.execute(block)
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (running) return
        running = true
        Thread({ loop() }, "p7-x11-window").start()
    }

    override fun onDetachedFromWindow() {
        running = false
        synchronized(inputLock) {
            input?.shutdownNow()
            input = null
        }
        super.onDetachedFromWindow()
    }

    /** Connect (retrying), then grab frames until the window is gone or the server dies. */
    private fun loop() {
        var everConnected = false
        while (running) {
            val x = connectWithRetry()
            if (x == null) break
            // B-077: a release that died with the old socket is retried on the fresh one.
            releasePressed(x, "after reconnect")
            everConnected = true
            var framesThisConnection = 0L
            while (running) {
                val t0 = SystemClock.elapsedRealtime()
                try {
                    val bmp = synchronized(xio) { x.getImage() }
                    frames++
                    framesThisConnection++
                    main.post { image.setImageBitmap(bmp) }
                } catch (t: Throwable) {
                    P7Log.say(context, "P7X11 frame failed after $framesThisConnection frames: $t")
                    break
                }
                lastGrabMs = SystemClock.elapsedRealtime() - t0
                if (reconnectRequested) {
                    reconnectRequested = false
                    P7Log.say(context, "P7X11 reconnect requested by harness")
                    break
                }
                val rest = 120 - lastGrabMs
                if (rest > 0) try { Thread.sleep(rest) } catch (_: InterruptedException) { break }
            }
            // B-077: release every key we still hold before the old client goes away.
            releasePressed(x, "before close")
            try { x.close() } catch (_: Throwable) { }
            x11 = null
            if (running) {
                // B-074: Xvfb went away (or restarted). Show the state and try again.
                P7Log.say(context, "P7X11 connection lost, reconnecting")
                main.post { status.text = "Starting..."; status.visibility = View.VISIBLE }
                try { Thread.sleep(X11ConnectPolicy.STEP_MS) } catch (_: InterruptedException) { break }
            }
        }
        P7Log.say(context, "P7X11 loop end frames=$frames connected=$everConnected")
        main.post { if (!running) status.text = "X11 window closed" }
    }

    /**
     * B-074: wait for Xvfb. Retries every [X11ConnectPolicy.STEP_MS] for up to
     * [X11ConnectPolicy.WINDOW_MS]; shows "Starting..." meanwhile. Null only when the window is
     * gone or the time box ran out (then the status says so and stays visible).
     */
    private fun connectWithRetry(): Phase2X11.X11? {
        val started = SystemClock.elapsedRealtime()
        var attempt = 0
        while (running && X11ConnectPolicy.shouldRetry(started, SystemClock.elapsedRealtime())) {
            attempt++
            try {
                val x = Phase2X11.X11.connect()
                x.initXtest()
                x.initKeyboard()
                x11 = x
                P7Log.say(context, "P7X11 connected attempt=$attempt after_ms=${SystemClock.elapsedRealtime() - started}")
                main.post { status.visibility = View.GONE }
                // B-063: side-by-side placement even though there is no window manager. Runs on
                // its own connection; harmless when the guest apps are not up yet.
                try {
                    P7Log.say(context, "P7X11 place ${Phase2X11.placeWindows()}")
                } catch (t: Throwable) {
                    P7Log.say(context, "P7X11 place failed: $t")
                }
                return x
            } catch (t: Throwable) {
                if (attempt == 1 || attempt % 20 == 0) {
                    P7Log.say(context, "P7X11 connect attempt=$attempt failed: $t")
                }
                if (!main.post {
                        status.text = "Starting... (attempt $attempt)"
                        status.visibility = View.VISIBLE
                    }) return null
                try { Thread.sleep(X11ConnectPolicy.STEP_MS) } catch (_: InterruptedException) { return null }
            }
        }
        if (running) {
            P7Log.say(context, "P7X11 gave up after ${X11ConnectPolicy.WINDOW_MS} ms")
            main.post { status.text = "X server did not start (retry by reopening the window)" }
        }
        return null
    }

    // ------------------------------------------------------------------ pointer

    /** Content pixel -> Xvfb pixel (the window shows the whole 1280x720 screen). */
    private fun gx(x: Int): Int = if (width <= 0) x else (x.toLong() * W / width).toInt()
    private fun gy(y: Int): Int = if (height <= 0) y else (y.toLong() * H / height).toInt()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = x11 ?: return false
        val cx = gx(event.x.toInt()).coerceIn(0, W - 1)
        val cy = gy(event.y.toInt()).coerceIn(0, H - 1)
        val action = event.actionMasked
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE &&
            action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL
        ) return false
        return submit {
            try {
                synchronized(xio) {
                    when (action) {
                        MotionEvent.ACTION_DOWN -> { x.motion(cx, cy); x.button(1, true) }
                        MotionEvent.ACTION_MOVE -> x.motion(cx, cy)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { x.motion(cx, cy); x.button(1, false) }
                    }
                }
            } catch (t: Throwable) {
                P7Log.say(context, "P7X11 input failed: $t")
            }
        }
    }

    override fun mouseMove(x: Int, y: Int) { }

    override fun mouseButton(button: Int, down: Boolean, x: Int, y: Int) {
        // The shell routes real left-button presses through dispatchTouchEvent (onTouchEvent
        // above); this stays as the ShellMouseContent contract for non-touch dispatch.
    }

    override fun mouseWheel(delta: Int, x: Int, y: Int) {
        val client = x11 ?: return
        val btn = if (delta < 0) 4 else 5 // X: button 4/5 = wheel up/down
        submit {
            try {
                synchronized(xio) { repeat(3) { client.button(btn, true); client.button(btn, false) } }
            } catch (t: Throwable) {
                P7Log.say(context, "P7X11 wheel failed: $t")
            }
        }
    }

    // ------------------------------------------------------------------ keyboard

    /**
     * A PC key -> XTEST key press/release. The char an Android keycode+meta produces is the one
     * Notepad++/xeyes would receive from a phone keyboard, so it maps directly to an X keysym.
     */
    override fun key(down: Boolean, keyCode: Int, scan: Int, meta: Int): Boolean {
        val client = x11 ?: run {
            if (down) P7Log.say(context, "P7X11 key code=$keyCode dropped: no X11 connection")
            return false
        }
        // Pure mapping (unit-tested in Phase7X11KeysTest): printable char -> its code point,
        // plus the handful of control keys a text editor needs. The server keymap then supplies
        // the X keycode and whether Shift must be held.
        val sym = X11Keys.keysym(keyCode, meta)
        if (sym == null) {
            if (down) P7Log.say(context, "P7X11 key code=$keyCode meta=$meta no keysym")
            return false
        }
        // B-062: XTEST key I/O on the input worker (main thread would throw
        // NetworkOnMainThreadException before the request leaves the app).
        val ctrl = (meta and android.view.KeyEvent.META_CTRL_ON) != 0
        val queued = submit { fire(client, sym, down, ctrl) }
        if (down) {
            P7Log.say(
                context,
                "P7X11 key code=$keyCode meta=$meta char='${charFor(keyCode, meta)}' " +
                    "sym=0x${sym.toString(16)} queued=$queued"
            )
        }
        return queued
    }

    private fun fire(client: Phase2X11.X11, keysym: Int, down: Boolean, ctrl: Boolean): Boolean {
        return try {
            synchronized(xio) {
                val kc = client.keycodeFor(keysym)
                if (kc == null) {
                    if (down) P7Log.say(context, "P7X11 no keycode for sym=0x${keysym.toString(16)}")
                    return false
                }
                val evType = if (down) 2 else 3 // KeyPress / KeyRelease
                // Press Ctrl before the key on the way down, release the key before Ctrl on the
                // way up. The PC sends the modifier both as its own key event (dropped: no
                // keysym) and as meta on the character event, which is what carries it here.
                if (down && ctrl) {
                    client.fakeKey(evType, client.ctrlCode)
                    pressedKeys.add(client.ctrlCode)
                }
                // Press Shift before the key on the way down, release the key before Shift on
                // the way up, exactly like a real keyboard.
                if (down && kc.second) {
                    client.fakeKey(evType, client.shiftCode)
                    pressedKeys.add(client.shiftCode)
                }
                client.fakeKey(evType, kc.first)
                if (down) pressedKeys.add(kc.first) else pressedKeys.remove(kc.first)
                if (!down && kc.second) {
                    client.fakeKey(evType, client.shiftCode)
                    pressedKeys.remove(client.shiftCode)
                }
                if (!down && ctrl) {
                    client.fakeKey(evType, client.ctrlCode)
                    pressedKeys.remove(client.ctrlCode)
                }
                true
            }
        } catch (t: Throwable) {
            P7Log.say(context, "P7X11 key failed: $t")
            false
        }
    }

    /** The character a phone keyboard would produce for this keycode+meta (pure mapping). */
    private fun charFor(keyCode: Int, meta: Int): Char = Phase5Protocol.keyCodeToChar(keyCode, meta)

    /**
     * B-077: send a KeyRelease for every keycode still held, on this X connection. Runs on the
     * frame-loop thread, serialized with the input worker; a key is kept in the set when its
     * release could not be written so the next connection retries it.
     */
    private fun releasePressed(client: Phase2X11.X11?, why: String) {
        if (client == null) return
        try {
            synchronized(xio) {
                if (pressedKeys.isEmpty()) return
                val codes = pressedKeys.toList()
                val failed = ArrayList<Int>()
                for (kc in codes) {
                    try {
                        client.fakeKey(3, kc)
                    } catch (_: Throwable) {
                        failed.add(kc)
                    }
                }
                pressedKeys.clear()
                pressedKeys.addAll(failed)
                P7Log.say(
                    context,
                    "P7X11 release $why sent=${codes.size - failed.size} kept=${failed.size} codes=$codes"
                )
            }
        } catch (t: Throwable) {
            P7Log.say(context, "P7X11 release $why failed: $t")
        }
    }

    override fun contentText(): String = ""

    /** B-077 proof hook: drop this frame connection so the loop reconnects (and releases keys). */
    fun requestReconnect() {
        reconnectRequested = true
    }

    private companion object {
        const val W = 1280
        const val H = 720
    }
}
