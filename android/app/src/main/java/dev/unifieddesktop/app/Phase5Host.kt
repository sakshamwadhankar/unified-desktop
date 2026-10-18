package dev.unifieddesktop.app

import android.app.Activity
import java.lang.ref.WeakReference

/**
 * Phase-5 host bridge. A `Presentation` (plain views) needs a real UI Context to be created
 * (`HarnessActivity`/`Phase1` pattern, LESSONS 2), so the foreground Activity registers
 * itself here while it is resumed. The link service asks for it when a session turns on the
 * video surface; with no resumed Activity the desktop cannot be shown and the service says
 * so in p4.log instead of pretending.
 */
object Phase5Host {
    @Volatile private var current: WeakReference<Activity>? = null

    fun attach(a: Activity) { current = WeakReference(a) }

    fun detach(a: Activity) {
        val c = current?.get()
        if (c === a) current = null
    }

    fun activity(): Activity? = current?.get()
}
