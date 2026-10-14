package dev.unifieddesktop.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Local JVM unit test: exercises only the pure-Kotlin branch of PairedEventDispatcher.
 * Real MotionEvent/KeyEvent cannot be constructed in a plain unit test (the android.* stubs
 * throw), and the guest-input path is moot anyway (see docs/PHASE1_RESULT.md: a Play app
 * cannot host activities on its own virtual display on Android 16).
 */
class HarnessCoreTest {
    @Test fun unsupportedEventIsDescribedWithTypeAndDisplay() {
        assertEquals(
            "unsupported display=7 type=String",
            PairedEventDispatcher.description(7, "not-an-event")
        )
    }
}
