package dev.unifieddesktop.app

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.TextView

/**
 * A trivial, fully in-process activity used to test whether OUR OWN activity can be placed
 * on OUR OWN virtual display (BlackBox out of the picture). If even this cannot render on
 * the private virtual display, the blocker is the Android display-hosting rule, not the
 * virtualization engine.
 */
class SelfTestActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        @Suppress("DEPRECATION")
        val did = if (Build.VERSION.SDK_INT >= 30) display?.displayId else windowManager.defaultDisplay.displayId
        val tv = TextView(this)
        tv.text = "SELF TEST ON DISPLAY $did"
        tv.textSize = 32f
        tv.setBackgroundColor(Color.rgb(0x15, 0x65, 0xc0))
        tv.setTextColor(Color.WHITE)
        setContentView(tv)
        android.util.Log.i("Phase1", "SelfTestActivity onCreate on display=$did")
    }
}
