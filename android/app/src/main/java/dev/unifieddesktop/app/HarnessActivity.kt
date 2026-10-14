package dev.unifieddesktop.app

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.*
import android.view.*
import android.widget.*
import java.io.File
import java.io.FileOutputStream

class HarnessActivity : Activity() {
    private lateinit var status: TextView
    private val main = Handler(Looper.getMainLooper())
    override fun onCreate(b: Bundle?) { super.onCreate(b); (application as Application).registerActivityLifecycleCallbacks(HarnessCallbacks()); buildUi() }
    private fun buildUi() {
        val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL; box.setPadding(24,24,24,24)
        status = TextView(this); status.text = "Phase 1 harness — control screen (not guest proof)"; box.addView(status)
        val row = LinearLayout(this)
        button(row,"Create display") { createDisplay() }; button(row,"Release") { HarnessState.close(); say("Released") }
        button(row,"Launch control") { launchControl() }; button(row,"Capture PNG") { capture() }; box.addView(row)
        val inputs = LinearLayout(this); button(inputs,"Tap") { dispatchTap() }; button(inputs,"Key A") { dispatchKey() }; box.addView(inputs)
        val edit = EditText(this); edit.hint = "Host control text field (dispatcher validation only)"; box.addView(edit)
        setContentView(box)
    }
    private fun button(parent: LinearLayout, text: String, f: () -> Unit) { Button(this).also { it.text=text; it.setOnClickListener{f()}; parent.addView(it) } }
    private fun say(s: String) { status.text = s }
    private fun createDisplay() { try {
        HarnessState.close(); val r=ImageReader.newInstance(1280,720,PixelFormat.RGBA_8888,3)
        val dm=getSystemService(DisplayManager::class.java); val d=dm.createVirtualDisplay("phase1-owned-control",1280,720,1,r.surface,DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
        HarnessState.reader=r; HarnessState.display=d; say("Created owned 1280x720 RGBA display id=${d.display.displayId}; control only")
    } catch(t: Throwable) { HarnessState.lastError=t.toString(); say("ERROR creating display: ${t.message}") } }
    private fun launchControl() { val d=HarnessState.display ?: return say("ERROR: create display first"); try { val opts=ActivityOptions.makeBasic(); opts.launchDisplayId=d.display.displayId; startActivity(Intent(this, HarnessActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),opts.toBundle()); say("Host control launch requested on display ${d.display.displayId}; not guest proof") } catch(t:Throwable){say("ERROR launch: ${t.message}")} }
    private fun capture() { val r=HarnessState.reader ?: return say("ERROR: no reader"); val i=r.acquireLatestImage() ?: return say("ERROR: no frame available yet"); i.use { try { val dir=File(filesDir,"phase1");dir.mkdirs(); val f=File(dir,"frame-${HarnessState.frameNumber++}.png"); FileOutputStream(f).use{RgbaFrames.toBitmap(i).compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}; say("Captured ${f.absolutePath}") } catch(t:Throwable){say("ERROR capture: ${t.message}")} } }
    private fun dispatchTap(){
        val d=HarnessState.display?:return say("ERROR: no display")
        val downTime=SystemClock.uptimeMillis()
        val down=MotionEvent.obtain(downTime,downTime,MotionEvent.ACTION_DOWN,640f,360f,0)
        var handled=false
        try { handled=PairedEventDispatcher.dispatch(d.display.displayId,down) }
        finally {
            down.recycle()
            val up=MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,640f,360f,0)
            try { PairedEventDispatcher.dispatch(d.display.displayId,up) } finally { up.recycle() }
        }
        say("Paired tap delivered=$handled; control-only until guest verified")
    }
    private fun dispatchKey(){
        val d=HarnessState.display?:return say("ERROR: no display")
        val time=SystemClock.uptimeMillis()
        var handled=false
        try { handled=PairedEventDispatcher.dispatch(d.display.displayId,KeyEvent(time,time,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_A,0)) }
        finally { PairedEventDispatcher.dispatch(d.display.displayId,KeyEvent(time,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_A,0)) }
        say("Paired key A delivered=$handled; control-only until guest verified")
    }
}
