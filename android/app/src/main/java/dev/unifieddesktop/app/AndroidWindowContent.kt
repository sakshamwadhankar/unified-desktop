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
 * (app virtualization, DECISIONS D3) and launched onto an app-created VirtualDisplay whose
 * output surface is this window's SurfaceView, so the guest's own frames land in the window
 * (manager note 20:06 - no reparenting needed on the phone).
 *
 * Host -> guest input is the open half: an app cannot inject events into another process or
 * display without INJECT_EVENTS (system), and D5 rules out an Accessibility service, so PC
 * keys/clicks are logged and dropped here (SKIPS row "Android window input").
 */
class AndroidWindowContent(context: Context, private val guestPackage: String) :
    FrameLayout(context), ShellRawKeyContent, ShellTextContent {

    private val view = SurfaceView(context)
    private val engine = EngineBridge(context)

    @Volatile private var display: VirtualDisplay? = null
    @Volatile private var started = false
    private var touchLogs = 0
    private var keyLogs = 0

    init {
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) = start(h.surface)
            override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) { }
            override fun surfaceDestroyed(h: SurfaceHolder) = release()
        })
        P7Log.say(context, "P7AND window guest=$guestPackage content built")
    }

    /** Display -> window surface, then import + launch the guest on that display. */
    private fun start(surface: Surface) {
        if (started) return
        started = true
        Thread({
            try {
                val dm = context.getSystemService(DisplayManager::class.java)
                val d = dm.createVirtualDisplay(
                    "p7-android-guest", W, H, 320, surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                )
                display = d
                P7Log.say(context, "P7AND display id=${d.display.displayId} ${W}x$H surface=${surface.isValid}")
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
                val res = engine.launch(guestPackage, d.display.displayId)
                P7Log.say(context, "P7AND launch ok=${res.ok} ${res.message ?: ""}")
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
     * The PC key reached the focused window; without an injection permission it cannot be
     * delivered to the guest `:pN` process. Log the first few so the hop is visible, and say
     * so plainly instead of pretending.
     */
    override fun key(down: Boolean, keyCode: Int, scan: Int, meta: Int): Boolean {
        if (down && keyLogs++ < 8) {
            P7Log.say(
                context,
                "P7AND key code=$keyCode meta=$meta dropped: no host->guest input path (no INJECT_EVENTS)"
            )
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && touchLogs++ < 8) {
            P7Log.say(
                context,
                "P7AND touch x=${event.x.toInt()} y=${event.y.toInt()} dropped: no host->guest input path"
            )
        }
        return true
    }

    override fun contentText(): String = "android:$guestPackage"

    companion object {
        const val W = 960
        const val H = 540
    }
}
