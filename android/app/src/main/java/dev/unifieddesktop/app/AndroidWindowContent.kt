package dev.unifieddesktop.app

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout

/**
 * One shell window that runs a hidden Android app: the guest is imported into the engine
 * (app virtualization, DECISIONS D3), the window's SurfaceView surface is set as the output
 * of a VirtualDisplay named [GuestPresentationHost.DISPLAY_NAME], and the guest is launched
 * through the Phase-1 B1 route: [GuestPresentationHost] moves the guest's own decor into a
 * Presentation on that display, so the guest's real frames land in this window.
 *
 * Host -> guest input runs in-process: after the move the decor lives in OUR process, so PC
 * touches/keys are dispatched straight to the reparented view tree (never INJECT_EVENTS,
 * never Accessibility - D5).
 */
class AndroidWindowContent(context: Context, private val guestPackage: String) :
    FrameLayout(context), ShellRawKeyContent, ShellTextContent {

    private val view = SurfaceView(context)
    private val engine = EngineBridge(context)

    @Volatile private var display: VirtualDisplay? = null
    @Volatile private var started = false
    private var displayW = 0
    private var displayH = 0
    private var touchLogs = 0
    private var keyLogs = 0

    init {
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        setBackgroundColor(android.graphics.Color.BLACK)
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { }
            override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) =
                start(h.surface, w, hh)
            override fun surfaceDestroyed(h: SurfaceHolder) = release()
        })
        P7Log.say(context, "P7AND window guest=$guestPackage content built")
    }

    /** Display -> window surface, then import + B1-launch the guest onto that display. */
    private fun start(surface: Surface, w: Int, hh: Int) {
        if (started || w <= 0 || hh <= 0) return
        started = true
        Thread({
            try {
                val dm = context.getSystemService(DisplayManager::class.java)
                val d = dm.createVirtualDisplay(
                    GuestPresentationHost.DISPLAY_NAME, w, hh, 320, surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                )
                display = d
                displayW = w
                displayH = hh
                P7Log.say(context, "P7AND display id=${d.display.displayId} ${w}x$hh surface=${surface.isValid}")
                // Warm the engine daemon first (as the harness guest flow does): the very first
                // installPackageAsUser after a cold start fails with a remote exception.
                val gms = engine.installGms(0)
                P7Log.say(context, "P7AND gms ok=${gms.ok} ${gms.message?.take(60) ?: ""}")
                var imp = engine.importInstalled(guestPackage)
                if (!imp.ok) {
                    Thread.sleep(2500)
                    imp = engine.importInstalled(guestPackage)
                }
                P7Log.say(context, "P7AND import ok=${imp.ok} ${imp.message ?: ""}")
                if (!imp.ok) return@Thread
                // B1: the guest launches normally; GuestPresentationHost moves its decor into a
                // Presentation on the "phase1-owned" display we just created (the one whose
                // output surface is this window's SurfaceView).
                val res = engine.launchPathB1(guestPackage, d.display.displayId)
                P7Log.say(context, "P7AND launch pathB1 ok=${res.ok} ${res.message ?: ""}")
            } catch (t: Throwable) {
                P7Log.say(context, "P7AND fail $t")
            }
        }, "p7-android").start()
    }

    private fun release() {
        display?.release()
        display = null
        started = false
        P7Log.say(context, "P7AND surface destroyed")
    }

    // ------------------------------------------------------------------ input

    /**
     * The PC pointer moved/pressed over this window. The guest decor is in our process, so
     * build a touch event in the guest's display coordinates and dispatch it there
     * (in-process; no injection permission involved).
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val gw = if (displayW > 0) displayW else width
        val gh = if (displayH > 0) displayH else height
        val x = if (width > 0) event.x * gw / width else event.x
        val y = if (height > 0) event.y * gh / height else event.y
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> event.actionMasked
            else -> return false
        }
        val handled = guestInput("T $action ${x.toInt()} ${y.toInt()}")
        if (action == MotionEvent.ACTION_DOWN && touchLogs++ < 8) {
            P7Log.say(
                context,
                "P7AND touch x=${event.x.toInt()} y=${event.y.toInt()} -> guest $x,$y handled=$handled"
            )
        }
        return true
    }

    /** PC key -> the reparented guest decor, in-process. */
    override fun key(down: Boolean, keyCode: Int, scan: Int, meta: Int): Boolean {
        val handled = guestInput("K $keyCode ${if (down) 1 else 0} $meta")
        if (down && keyLogs++ < 8) {
            P7Log.say(context, "P7AND key code=$keyCode meta=$meta handled=$handled")
        }
        return handled
    }

    /**
     * The guest decor lives in the guest process; [GuestPresentationHost] serves input on a
     * same-uid abstract LocalSocket (see its B1_INPUT log). One connection per event, reply
     * "1"/"0" = whether the guest view consumed it.
     */
    private fun guestInput(line: String): Boolean = try {
        android.net.LocalSocket().use { s ->
            s.connect(
                android.net.LocalSocketAddress(
                    GuestPresentationHost.INPUT_SOCKET,
                    android.net.LocalSocketAddress.Namespace.ABSTRACT
                )
            )
            s.outputStream.write((line + "\n").toByteArray())
            s.outputStream.flush()
            val reply = java.io.BufferedReader(
                java.io.InputStreamReader(s.inputStream, "UTF-8")
            ).readLine()
            reply == "1"
        }
    } catch (t: Throwable) {
        if (touchLogs == 0 && keyLogs == 0) {
            P7Log.say(context, "P7AND input bridge failed: $t")
        }
        false
    }

    override fun contentText(): String = "android:$guestPackage"

    companion object {
        const val W = 960
        const val H = 540
    }
}
