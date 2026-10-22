package dev.unifieddesktop.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 11 D-c, phone side: the X11 CLIPBOARD selection <-> Android clipboard.
 *
 * Linux/Wine apps (Notepad++) copy into the X11 CLIPBOARD owned by winex11; Android apps
 * (and the shell's WebViews) use the Android clipboard. This bridge owns a tiny InputOnly
 * window on the Xvfb display, serves the Android text when it owns the selection and polls
 * the selection when another client owns it.
 *
 * Loop guard: the same FNV-1a 64 hash as Phase11Clip. Nothing logs clipboard contents,
 * only a byte length and a SHA-256 prefix, and only our own test markers are exercised.
 */
object Phase11X11Clip {
    private const val TAG = "P11X11CLIP"
    private const val POLL_MS = 350L

    @Volatile private var running = false
    private var thread: Thread? = null
    private val pending = AtomicReference<ByteArray?>(null)

    // Connection + atoms, only touched by the bridge thread.
    private var x: Phase2X11.X11? = null
    private var win = 0
    private var clipboard = 0
    private var utf8 = 0
    private var targets = 0
    private var textAtom = 0
    private var stringAtom = 0
    private var timestampAtom = 0
    private var integerAtom = 0
    private var propAtom = 0
    private var serving: ByteArray? = null
    private var lastHash = 0L
    private var lastLog = 0L
    private var lastOwner = -2
    private var lastTick = 0L

    fun start(ctx: android.content.Context) {
        if (running) return
        running = true
        thread = Thread({ loop(ctx) }, "p11-x11clip").apply {
            isDaemon = true
            start()
        }
        Phase4Log.say(ctx, "$TAG start (X11 CLIPBOARD <-> Android clipboard)")
    }

    fun stop() {
        running = false
        try { x?.close() } catch (_: Throwable) {}
        x = null
        serving = null
        pending.set(null)
    }

    /** Android clipboard changed (already echo-guarded by Phase11Clip). */
    fun onAndroidText(bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > Phase11Clip.MAX_BYTES) return
        pending.set(bytes.copyOf())
    }

    private fun loop(ctx: android.content.Context) {
        while (running) {
            try {
                val c = x ?: connect().also { x = it; phase(ctx, "connected win=$win") }
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastTick > 5000) {
                    lastTick = nowMs
                    phase(ctx, "tick owner=$lastOwner win=$win pending=${pending.get() != null}")
                }
                drainEvents(c)
                val p = pending.getAndSet(null)
                if (p != null) {
                    serving = p
                    // X11 rejects a selection claim whose timestamp is older than the
                    // current owner.  CurrentTime is not sufficient for Wine's clipboard
                    // watcher, so obtain the server timestamp from a real PropertyNotify.
                    val stamp = c.clipPropertyTimestamp(win, propAtom)
                    c.clipSetOwner(clipboard, win, stamp)
                    lastHash = Phase11Clip.hash(p)
                    phase(ctx, "android->x11 len=${p.size} sha=${shaPrefix(p)} timestamp=$stamp")
                }
                // While we own the selection we already know its text; only poll otherwise.
                val owner = c.clipOwner(clipboard)
                if (owner != lastOwner) {
                    lastOwner = owner
                    phase(ctx, "selection owner=$owner win=$win")
                }
                if (owner != win && owner != 0) {
                    var notify = convert(c, utf8, 4000, ctx)
                    if (notify == null) {
                        // Wine answers TARGETS requests even when it is slow to render the
                        // actual conversion; probing it separates "no answer at all" from
                        // "this target is refused".
                        phase(ctx, "no SelectionNotify (UTF8_STRING), probing TARGETS")
                        notify = convert(c, targets, 4000, ctx)
                        if (notify != null) {
                            val bb = ByteBuffer.wrap(notify).order(ByteOrder.LITTLE_ENDIAN)
                            val p = bb.getInt(20)
                            val d = if (p != 0) c.clipGetProperty(win, p) else null
                            phase(ctx, "TARGETS notify prop=$p bytes=${d?.size ?: -1}")
                            notify = null
                        }
                    }
                    if (notify != null) {
                        val bb = ByteBuffer.wrap(notify).order(ByteOrder.LITTLE_ENDIAN)
                        val gotProp = bb.getInt(20)
                        phase(ctx, "notify target=${bb.getInt(16)} prop=$gotProp")
                        if (gotProp != 0) {
                            val data = c.clipGetProperty(win, gotProp)
                            phase(ctx, "property bytes=${data?.size ?: -1}")
                            val b = decode(data)
                            if (b != null && b.isNotEmpty() && Phase11Clip.hash(b) != lastHash) {
                                lastHash = Phase11Clip.hash(b)
                                Phase11Clip.onX11(b)
                                phase(ctx, "x11->android len=${b.size} sha=${shaPrefix(b)}")
                            }
                        }
                    } else {
                        phase(ctx, "no SelectionNotify from owner=$owner")
                    }
                }
            } catch (t: Throwable) {
                if (running) {
                    phase(ctx, "error ${t.javaClass.simpleName}: ${t.message}")
                    try { x?.close() } catch (_: Throwable) {}
                    x = null
                    pause(1000)
                }
            }
            pause(POLL_MS)
        }
    }

    /** ConvertSelection + wait for the matching SelectionNotify; other events are served. */
    private fun convert(c: Phase2X11.X11, target: Int, timeoutMs: Long, ctx: android.content.Context?): ByteArray? {
        c.clipConvert(win, clipboard, target, propAtom)
        var notify: ByteArray? = null
        var logged = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        while (notify == null && System.currentTimeMillis() < deadline) {
            val ev = c.clipRead32(250) ?: continue
            // Bit 7 is set by the server on events delivered through SendEvent
            // (Wine serves SelectionNotify that way), so mask to the event code.
            val type = ev[0].toInt() and 0x7f
            if (type == 31) {
                val bb = ByteBuffer.wrap(ev).order(ByteOrder.LITTLE_ENDIAN)
                if (bb.getInt(20) == propAtom) {
                    notify = ev
                } else {
                    phase(ctx, "notify prop=${bb.getInt(20)} (not ours)")
                }
            } else {
                if (logged < 4) {
                    logged++
                    if (type == 0) {
                        val code = ev[1].toInt() and 0xff
                        val bb = ByteBuffer.wrap(ev).order(ByteOrder.LITTLE_ENDIAN)
                        phase(ctx, "X11 ERROR code=$code value=${bb.getInt(4)} seq=${bb.getShort(2)}")
                    } else {
                        phase(ctx, "packet type=$type raw=${ev.take(8).joinToString("") { "%02x".format(it) }}")
                    }
                }
                handleEvent(c, ev, ctx)
            }
        }
        return notify
    }

    private fun connect(): Phase2X11.X11 {
        val x = Phase2X11.X11.connect()
        clipboard = x.internAtom("CLIPBOARD")
        utf8 = x.internAtom("UTF8_STRING")
        targets = x.internAtom("TARGETS")
        textAtom = x.internAtom("TEXT")
        stringAtom = x.internAtom("STRING")
        timestampAtom = x.internAtom("TIMESTAMP")
        integerAtom = x.internAtom("INTEGER")
        propAtom = x.internAtom("_UD_CLIP")
        win = x.clipCreateWindow()
        x.clipSelectPropertyEvents(win)
        return x
    }

    private fun decode(data: ByteArray?): ByteArray? {
        // GetProperty returns raw bytes for format 8; UTF-8 text passes through as-is.
        return data
    }

    /** Serve selection requests that piled up (only when we own the selection). */
    private fun drainEvents(c: Phase2X11.X11) {
        while (c.clipAvailable() >= 32) {
            val ev = c.clipRead32(50) ?: return
            handleEvent(c, ev, null)
        }
    }

    private fun handleEvent(c: Phase2X11.X11, ev: ByteArray, ctx: android.content.Context?) {
        when (ev[0].toInt() and 0x7f) {
            30 -> { // SelectionRequest
                val bb = ByteBuffer.wrap(ev).order(ByteOrder.LITTLE_ENDIAN)
                val requestor = bb.getInt(12)
                val selection = bb.getInt(16)
                val target = bb.getInt(20)
                val property = bb.getInt(24)
                val text = serving
                val ok = text != null && selection == clipboard
                var propOut = if (ok) property else 0
                var typeOut = utf8
                var fmt = 8
                val payload: ByteArray
                if (!ok) {
                    payload = ByteArray(0)
                } else if (target == targets) {
                    typeOut = 4 // ATOM
                    fmt = 32
                    payload = ByteArray(16)
                    val pb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                    pb.putInt(utf8); pb.putInt(stringAtom); pb.putInt(textAtom); pb.putInt(targets)
                } else if (target == utf8 || target == textAtom) {
                    payload = text!!
                } else if (target == stringAtom) {
                    typeOut = stringAtom
                    payload = String(text!!, Charsets.UTF_8)
                        .map { if (it.code <= 0xFF) it else '?' }.joinToString("").toByteArray(Charsets.ISO_8859_1)
                } else if (target == timestampAtom) {
                    typeOut = integerAtom
                    fmt = 32
                    payload = ByteArray(4)
                } else {
                    propOut = 0
                    payload = ByteArray(0)
                }
                if (propOut != 0) {
                    c.clipChangeProperty(requestor, property, typeOut, fmt, payload)
                    phase(ctx, "served request target=$target len=${payload.size}")
                }
                c.clipSendSelectionNotify(requestor, selection, target, propOut)
            }
            29 -> { // SelectionClear: another client took the selection
                serving = null
            }
            else -> {}
        }
    }

    private fun pause(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { return }
    }

    private fun phase(ctx: android.content.Context?, msg: String) {
        val now = System.currentTimeMillis()
        // Throttle error/noise while still keeping every state change.
        if (msg.startsWith("served") && now - lastLog < 500) return
        lastLog = now
        if (ctx != null) Phase4Log.say(ctx, "$TAG $msg") else println("$TAG $msg")
    }

    private fun shaPrefix(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).take(4)
            .joinToString("") { "%02x".format(it) }
}
