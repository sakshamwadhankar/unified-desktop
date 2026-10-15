package dev.unifieddesktop.app

import android.app.*
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.*
import android.view.*
import android.widget.*
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Phase 1 test harness. Besides the manual control buttons it supports a fully headless,
 * adb-driven guest run so no phone screen touch is needed:
 *
 *   adb shell am start -n dev.unifieddesktop.app/.HarnessActivity \
 *     --es guest com.oneplus.calculator --ei caps 6
 *
 * It creates a private virtual display we own, imports the named Play-installed app into
 * the engine, launches it onto that display, and writes PNG frames to filesDir/phase1/.
 */
class HarnessActivity : Activity() {
    private lateinit var status: TextView
    private val main = Handler(Looper.getMainLooper())
    private val engine by lazy { EngineBridge(this) }
    private var smokePresentation: Presentation? = null
    private var smokeStartedNs = 0L
    private var smokeFrames = 0
    private var smokeFirstFrameNs = 0L
    private var pathB1 = false
    private var pathB2 = false
    private var injectInput = false
    private var measureGuest = false
    private var guestMeasureStartNs = 0L
    private var displayName = "phase1-owned"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        (application as Application).registerActivityLifecycleCallbacks(HarnessCallbacks())
        buildUi()
        maybeRunGuestFromIntent(intent)
    }

    override fun onNewIntent(i: Intent?) { super.onNewIntent(i); maybeRunGuestFromIntent(i) }

    private fun buildUi() {
        val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL; box.setPadding(24,24,24,24)
        status = TextView(this); status.text = "Phase 1 harness"; box.addView(status)
        val row = LinearLayout(this)
        button(row,"Create display") { createDisplay() }; button(row,"Release") { HarnessState.close(); say("Released") }
        button(row,"Capture PNG") { capture() }; box.addView(row)
        val edit = EditText(this); edit.hint = "guest package id"; box.addView(edit)
        val row2 = LinearLayout(this)
        button(row2,"Import+Launch guest") { runGuest(edit.text.toString().trim(), 6) }; box.addView(row2)
        setContentView(box)
    }

    private fun button(parent: LinearLayout, text: String, f: () -> Unit) { Button(this).also { it.text=text; it.setOnClickListener{f()}; parent.addView(it) } }
    private fun say(s: String) { status.text = s; android.util.Log.i("Phase1", s) }

    private var displayFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY

    private fun maybeRunGuestFromIntent(i: Intent?) {
        if (i != null && i.hasExtra("dflags")) displayFlags = i.getIntExtra("dflags", displayFlags)
        pathB1 = i?.getBooleanExtra("b1", false) == true
        pathB2 = i?.getBooleanExtra("b2", false) == true
        injectInput = i?.getBooleanExtra("input", false) == true
        measureGuest = i?.getBooleanExtra("measure", false) == true
        displayName = if (pathB2) "phase1-owned-b2" else "phase1-owned"
        if (i?.getBooleanExtra("smoke", false) == true) {
            main.postDelayed({ runSmoke(i.getIntExtra("duration", 10)) }, 500)
            return
        }
        if (i?.getBooleanExtra("selftest", false) == true) {
            main.postDelayed({ runSelfTest(i.getIntExtra("caps", 4)) }, 800)
            return
        }
        val pkg = i?.getStringExtra("guest") ?: return
        val caps = i.getIntExtra("caps", 6)
        main.postDelayed({ runGuest(pkg, caps) }, 1500)
    }

    /** Launch OUR OWN activity on OUR OWN virtual display; capture. Isolates the display rule. */
    private fun runSelfTest(caps: Int) {
        if (HarnessState.display == null) createDisplay(showSmoke = false)
        val displayId = currentDisplayId() ?: return say("ERROR: no display")
        try {
            val opts = ActivityOptions.makeBasic()
            opts.launchDisplayId = displayId
            startActivity(
                Intent(this, SelfTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                opts.toBundle()
            )
            say("Self-test activity launch requested on display $displayId")
        } catch (t: Throwable) { say("ERROR self-test launch: $t") }
        for (n in 1..caps) main.postDelayed({ capture() }, 1000L * n)
    }

    private fun currentDisplayId(): Int? = HarnessState.display?.display?.displayId

    private fun createDisplay(showSmoke: Boolean = true) {
        try {
            HarnessState.close()
            smokePresentation?.dismiss()
            smokePresentation = null
            smokeFrames = 0
            smokeFirstFrameNs = 0L
            val r = ImageReader.newInstance(
                1280, 720, PixelFormat.RGBA_8888, 3,
                HardwareBuffer.USAGE_CPU_READ_OFTEN
            )
            // Smoke owns the ImageReader listener. Guest runs use capture() as the sole
            // consumer; otherwise the listener drains frames before capture() can prove them.
            if (showSmoke || measureGuest) r.setOnImageAvailableListener({ reader -> consumeFrame(reader) }, main)
            val dm = getSystemService(DisplayManager::class.java)
            val d = dm.createVirtualDisplay(displayName,1280,720,320,r.surface,displayFlags)
            HarnessState.reader = r; HarnessState.display = d
            if (showSmoke) {
                smokePresentation = SmokePresentation(this, d.display).also { it.show() }
            }
            say("Created display id=${d.display.displayId} flags=$displayFlags")
        } catch (t: Throwable) { HarnessState.lastError = t.toString(); say("ERROR creating display: $t") }
    }

    private fun runSmoke(seconds: Int) {
        smokeStartedNs = System.nanoTime()
        createDisplay(showSmoke = true)
        val durationMs = seconds.coerceIn(3, 60) * 1000L
        main.postDelayed({
            smokePresentation?.dismiss()
            smokePresentation = null
            val elapsed = (System.nanoTime() - smokeStartedNs) / 1_000_000_000.0
            val fps = if (elapsed > 0) smokeFrames / elapsed else 0.0
            val first = if (smokeFirstFrameNs == 0L) "never" else
                ((smokeFirstFrameNs - smokeStartedNs) / 1_000_000.0).roundToInt().toString()
            say("SMOKE_DONE frames=$smokeFrames elapsed_s=${"%.3f".format(elapsed)} fps=${"%.2f".format(fps)} first_frame_ms=$first")
        }, durationMs)
    }

    private fun consumeFrame(reader: ImageReader) {
        val image = try { reader.acquireLatestImage() } catch (t: Throwable) {
            say("ERROR ImageReader acquire: $t")
            return
        } ?: return
        image.use {
            smokeFrames++
            if (smokeFirstFrameNs == 0L) smokeFirstFrameNs = System.nanoTime()
            // These PNGs remain in app-private filesDir; they are not repository evidence.
            if (smokeFrames <= 3 || smokeFrames % 30 == 0) {
                try {
                    val dir = File(filesDir, "phase1").also { it.mkdirs() }
                    val f = File(dir, "smoke-frame-$smokeFrames.png")
                    FileOutputStream(f).use { out ->
                        RgbaFrames.toBitmap(image).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                    }
                } catch (t: Throwable) {
                    say("ERROR smoke PNG frame=$smokeFrames: $t")
                }
            }
        }
    }

    /** Full headless guest flow: display -> import -> launch on our display -> capture. */
    private fun runGuest(pkg: String, caps: Int) {
        if (pkg.isEmpty()) return say("ERROR: no guest package given")
        if (HarnessState.display == null) createDisplay(showSmoke = false)
        val displayId = currentDisplayId() ?: return say("ERROR: no display")
        say("Guest $pkg: importing...")
        val imp = engine.importInstalled(pkg)
        if (!imp.ok) return say("ERROR import $pkg: ${imp.message}")
        say("Guest $pkg imported; launching on display $displayId")
        guestMeasureStartNs = System.nanoTime()
        val res = if (pathB1 || pathB2) engine.launchPathB1(pkg, displayId, injectInput) else engine.launch(pkg, displayId)
        if (!res.ok) { say("ERROR launch $pkg: ${res.message}"); return }
        say("Guest $pkg launch requested on display $displayId")
        // capture a sequence so the guest has time to draw its first frame
        if (measureGuest) {
            main.postDelayed({
                val elapsed = (System.nanoTime() - guestMeasureStartNs) / 1_000_000_000.0
                val fps = if (elapsed > 0) smokeFrames / elapsed else 0.0
                val first = if (smokeFirstFrameNs == 0L) "never" else
                    ((smokeFirstFrameNs - guestMeasureStartNs) / 1_000_000.0).roundToInt().toString()
                say("GUEST_MEASURE_DONE frames=$smokeFrames elapsed_s=${"%.3f".format(elapsed)} fps=${"%.2f".format(fps)} first_frame_ms=$first")
            }, caps.coerceIn(5, 60) * 1000L)
        } else {
            for (n in 1..caps) main.postDelayed({ capture() }, 1000L * n)
        }
    }

    private fun capture() {
        val r = HarnessState.reader ?: return say("ERROR: no reader")
        val i = r.acquireLatestImage() ?: return say("ERROR: no frame yet")
        i.use {
            try {
                val dir = File(filesDir,"phase1"); dir.mkdirs()
                val f = File(dir,"frame-${HarnessState.frameNumber++}.png")
                FileOutputStream(f).use { RgbaFrames.toBitmap(i).compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                say("Captured ${f.absolutePath}")
            } catch (t: Throwable) { say("ERROR capture: $t") }
        }
    }
}

/** A real application window on the app-owned private display. */
private class SmokePresentation(context: android.content.Context, display: Display) : Presentation(context, display) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(SmokeView(context))
    }
}

private class SmokeView(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var counter = 0
    private val tick = object : Runnable {
        override fun run() {
            counter++
            invalidate()
            postDelayed(this, 100L)
        }
    }

    init {
        setBackgroundColor(Color.rgb(0x20, 0x32, 0x55))
        post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val phase = (counter % 120) / 120f
        paint.color = Color.rgb(
            (0x18 + 0x50 * phase).roundToInt(),
            (0x70 + 0x60 * (1 - phase)).roundToInt(),
            0xd0
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.color = Color.WHITE
        paint.textSize = 54f
        paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("ImageReader smoke test", 48f, 120f, paint)
        paint.textSize = 40f
        canvas.drawText("presentation frame $counter", 48f, 190f, paint)
        canvas.drawText("private display", 48f, 250f, paint)
    }
}
