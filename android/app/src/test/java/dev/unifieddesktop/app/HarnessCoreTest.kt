package dev.unifieddesktop.app

import org.junit.Assert.*
import org.junit.Test

class HarnessCoreTest {
    @Test fun pairedDescriptionsIdentifyTargetAndEvent() {
        val e = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_A)
        assertEquals("key display=7 action=0 code=29", PairedEventDispatcher.description(7, e))
    }
}
