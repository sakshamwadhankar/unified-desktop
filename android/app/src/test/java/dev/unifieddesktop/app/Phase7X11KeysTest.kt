package dev.unifieddesktop.app

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Item 6: the pure half of the B-062 X11 key path (Android keycode + meta -> X keysym).
 * The other half - keysym -> X keycode/Shift via the server keymap, and the XTEST events -
 * needs a running X server and is proven on the phone only.
 */
class Phase7X11KeysTest {

    private val P = Phase5Protocol

    @Test
    fun printableTextMapsToItsCodePoint() {
        assertEquals('a'.code, X11Keys.keysym(KeyEvent.KEYCODE_A, 0))
        assertEquals('A'.code, X11Keys.keysym(KeyEvent.KEYCODE_A, P.MOD_SHIFT))
        assertEquals('1'.code, X11Keys.keysym(KeyEvent.KEYCODE_1, 0))
        assertEquals('!'.code, X11Keys.keysym(KeyEvent.KEYCODE_1, P.MOD_SHIFT))
        assertEquals(':'.code, X11Keys.keysym(KeyEvent.KEYCODE_SEMICOLON, P.MOD_SHIFT))
        assertEquals('?'.code, X11Keys.keysym(KeyEvent.KEYCODE_SLASH, P.MOD_SHIFT))
        assertEquals('='.code, X11Keys.keysym(KeyEvent.KEYCODE_PLUS, 0))
        assertEquals(' '.code, X11Keys.keysym(KeyEvent.KEYCODE_SPACE, 0))
    }

    @Test
    fun controlKeysUseX11Keysyms() {
        assertEquals(0xFF0D, X11Keys.keysym(KeyEvent.KEYCODE_ENTER, 0))
        assertEquals(0xFF08, X11Keys.keysym(KeyEvent.KEYCODE_DEL, 0))
        assertEquals(0xFFFF, X11Keys.keysym(KeyEvent.KEYCODE_FORWARD_DEL, 0))
        assertEquals(0xFF09, X11Keys.keysym(KeyEvent.KEYCODE_TAB, 0))
        assertEquals(0xFF1B, X11Keys.keysym(KeyEvent.KEYCODE_ESCAPE, 0))
        assertEquals(0xFF51, X11Keys.keysym(KeyEvent.KEYCODE_DPAD_LEFT, 0))
        assertEquals(0xFF52, X11Keys.keysym(KeyEvent.KEYCODE_DPAD_UP, 0))
        assertEquals(0xFF53, X11Keys.keysym(KeyEvent.KEYCODE_DPAD_RIGHT, 0))
        assertEquals(0xFF54, X11Keys.keysym(KeyEvent.KEYCODE_DPAD_DOWN, 0))
    }

    @Test
    fun keysWithoutTextHaveNoKeysym() {
        assertNull(X11Keys.keysym(KeyEvent.KEYCODE_F1, 0))
        assertNull(X11Keys.keysym(KeyEvent.KEYCODE_CTRL_LEFT, 0))
    }
}
