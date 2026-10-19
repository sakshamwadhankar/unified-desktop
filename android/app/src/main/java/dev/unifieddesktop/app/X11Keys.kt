package dev.unifieddesktop.app

import android.view.KeyEvent

/**
 * Android keycode + meta -> X11 keysym, pure so the B-062 key path can be unit-tested
 * without an X server. Printable characters use the character's code point (the same char a
 * phone keyboard would produce for that key); the few non-printable keys a text editor needs
 * use their X11 keysym constants.
 *
 * The second half of the key path - keysym -> X keycode + Shift from the server's keymap -
 * lives in [Phase2X11] because it depends on the running server; only the phone has Xvfb.
 */
object X11Keys {
    /** X11 keysym for this key, or null when the key has no text meaning (e.g. F1). */
    fun keysym(keyCode: Int, meta: Int): Int? {
        val ch = Phase5Protocol.keyCodeToChar(keyCode, meta)
        if (ch != '\u0000') return ch.code
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> 0xFF0D // Return
            KeyEvent.KEYCODE_DEL -> 0xFF08 // BackSpace
            KeyEvent.KEYCODE_FORWARD_DEL -> 0xFFFF // Delete
            KeyEvent.KEYCODE_TAB -> 0xFF09
            KeyEvent.KEYCODE_ESCAPE -> 0xFF1B
            KeyEvent.KEYCODE_DPAD_LEFT -> 0xFF51
            KeyEvent.KEYCODE_DPAD_UP -> 0xFF52
            KeyEvent.KEYCODE_DPAD_RIGHT -> 0xFF53
            KeyEvent.KEYCODE_DPAD_DOWN -> 0xFF54
            KeyEvent.KEYCODE_SPACE -> 0x20
            else -> null
        }
    }
}
