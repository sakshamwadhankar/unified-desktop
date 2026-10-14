package dev.unifieddesktop.app

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import android.graphics.Bitmap

object HarnessState {
    var reader: ImageReader? = null
    var display: VirtualDisplay? = null
    var target: Activity? = null
    var lastError: String = "Ready"
    var frameNumber = 0
    fun close() { reader?.close(); reader = null; display?.release(); display = null; target = null }
}

/** Pure conversion used by capture and unit-tested independently of Android display APIs. */
object RgbaFrames {
    fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]; val w = image.width; val h = image.height
        val row = plane.rowStride; val pixel = plane.pixelStride
        val bytes = ByteArray(row * h); plane.buffer.rewind(); plane.buffer.get(bytes)
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * row + x * pixel
            val r = bytes[i].toInt() and 255; val g = bytes[i + 1].toInt() and 255
            val b = bytes[i + 2].toInt() and 255; val a = bytes[i + 3].toInt() and 255
            out[y * w + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}

object PairedEventDispatcher {
    fun description(displayId: Int, event: Any): String = when (event) {
        is MotionEvent -> "motion display=$displayId action=${event.actionMasked} x=${event.x} y=${event.y}"
        is KeyEvent -> "key display=$displayId action=${event.action} code=${event.keyCode}"
        else -> "unsupported display=$displayId type=${event::class.java.simpleName}"
    }
}

class HarnessCallbacks : Application.ActivityLifecycleCallbacks {
    override fun onActivityResumed(a: Activity) { HarnessState.target = a }
    override fun onActivityPaused(a: Activity) { if (HarnessState.target === a) HarnessState.target = null }
    override fun onActivityCreated(a: Activity, b: android.os.Bundle?) {}
    override fun onActivityStarted(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) {}
    override fun onActivityDestroyed(a: Activity) { if (HarnessState.target === a) HarnessState.target = null }
}
