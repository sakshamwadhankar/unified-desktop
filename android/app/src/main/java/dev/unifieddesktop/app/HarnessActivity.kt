package dev.unifieddesktop.app

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.*
import android.view.*
import android.widget.*
import java.io.File
import java.io.FileOutputStream

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
        if (HarnessState.display == null) createDisplay()
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

    private fun createDisplay() {
        try {
            HarnessState.close()
            val r = ImageReader.newInstance(1280,720,PixelFormat.RGBA_8888,3)
            val dm = getSystemService(DisplayManager::class.java)
            val d = dm.createVirtualDisplay("phase1-owned",1280,720,320,r.surface,displayFlags)
            HarnessState.reader = r; HarnessState.display = d
            say("Created display id=${d.display.displayId} flags=$displayFlags")
        } catch (t: Throwable) { HarnessState.lastError = t.toString(); say("ERROR creating display: $t") }
    }

    /** Full headless guest flow: display -> import -> launch on our display -> capture. */
    private fun runGuest(pkg: String, caps: Int) {
        if (pkg.isEmpty()) return say("ERROR: no guest package given")
        if (HarnessState.display == null) createDisplay()
        val displayId = currentDisplayId() ?: return say("ERROR: no display")
        say("Guest $pkg: importing...")
        val imp = engine.importInstalled(pkg)
        if (!imp.ok) return say("ERROR import $pkg: ${imp.message}")
        say("Guest $pkg imported; launching on display $displayId")
        val res = engine.launch(pkg, displayId)
        if (!res.ok) { say("ERROR launch $pkg: ${res.message}"); return }
        say("Guest $pkg launch requested on display $displayId")
        // capture a sequence so the guest has time to draw its first frame
        for (n in 1..caps) main.postDelayed({ capture() }, 1000L * n)
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
