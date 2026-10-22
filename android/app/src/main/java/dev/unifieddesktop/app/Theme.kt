package dev.unifieddesktop.app

import android.graphics.Typeface
import android.widget.TextView
import android.util.TypedValue

/**
 * Phase 13 design tokens (docs/UX_AUDIT.md section 2): one palette and one scale for the
 * desktop shell. Sizes are **desktop pixels**: the shell runs on a 1920x1080 virtual display
 * at 160 dpi, so 1 dp = 1 px = the Windows 100 % size the audit asks for (D9).
 *
 * Nothing in the shell should carry a colour literal; add a token here instead.
 */
object Theme {
    // Surfaces (dark is the desktop's only theme; the phone app has its own light palette).
    const val BG = 0xFF0B0E14.toInt()
    const val SURFACE = 0xFF141922.toInt()
    const val SURFACE_RAISED = 0xFF1C2230.toInt()
    const val SURFACE_HOVER = 0xFF242C3C.toInt()
    const val SURFACE_SUNKEN = 0xFF10151D.toInt()
    const val SURFACE_UNFOCUSED = 0xFF171C26.toInt()
    const val BORDER = 0xFF2A3140.toInt()
    const val BORDER_SOFT = 0x33FFFFFF

    // Text.
    const val TEXT = 0xFFE8ECF2.toInt()
    const val TEXT_SECONDARY = 0xFFA7B0BE.toInt()
    const val TEXT_DIM = 0xFF6B7484.toInt()

    // One accent plus the three states.
    const val ACCENT = 0xFF4C8DFF.toInt()
    const val ACCENT_PRESSED = 0xFF2F6FE4.toInt()
    const val SUCCESS = 0xFF3FB950.toInt()
    const val WARNING = 0xFFD29922.toInt()
    const val DANGER = 0xFFF85149.toInt()

    // Type scale (px), tuned for a 1920-wide desktop seen 1:1 on a laptop (D9).
    const val CAPTION = 12f
    const val SMALL = 13f
    const val BODY = 14f
    const val BODY_LARGE = 15f
    const val TITLE = 16f
    const val HEADING = 20f
    const val DISPLAY = 26f

    const val REGULAR = Typeface.NORMAL
    const val MEDIUM = Typeface.NORMAL // Roboto has no medium face in the system font
    const val BOLD = Typeface.BOLD

    // Spacing, corners, chrome.
    const val GAP = 8
    const val PAD = 12
    const val PAD_LARGE = 16
    const val RADIUS_WINDOW = 10f
    const val RADIUS_MENU = 12f
    const val RADIUS_CARD = 10f
    const val RADIUS_BUTTON = 6f
    const val RADIUS_TILE = 8f
    const val TITLE_H = 36
    const val TASKBAR_H = 56

    /** Translucent version of a token, for hover washes and glows. */
    fun alpha(color: Int, a: Int): Int =
        (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    /** Set a TextView's size in desktop pixels, never in scaled pixels. */
    fun px(view: TextView, size: Float) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
    }
}
