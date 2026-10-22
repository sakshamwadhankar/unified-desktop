package dev.unifieddesktop.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View

/**
 * Phase 13 (UX_AUDIT D1): the desktop wallpaper. Six calm, code-generated gradients - no
 * image, no licence, no green (the owner's complaint). The chosen one is kept in the shell's
 * preferences so it survives a reconnect, and Settings shows the same six as swatches.
 */
object Wallpaper {
    class Style(val name: String, val top: Int, val bottom: Int, val glow: Int)

    val styles = listOf(
        Style("Aurora", 0xFF1B2140.toInt(), 0xFF0B0F1A.toInt(), 0xFF4C8DFF.toInt()),
        Style("Midnight", 0xFF101A33.toInt(), 0xFF06080F.toInt(), 0xFF2F6FE4.toInt()),
        Style("Violet", 0xFF2A1B40.toInt(), 0xFF0B0812.toInt(), 0xFF8B5CF6.toInt()),
        Style("Ocean", 0xFF0E2436.toInt(), 0xFF05090E.toInt(), 0xFF22B8CF.toInt()),
        Style("Ember", 0xFF2A1A18.toInt(), 0xFF0B0808.toInt(), 0xFFF97316.toInt()),
        Style("Graphite", 0xFF23272E.toInt(), 0xFF090B0F.toInt(), 0xFFA7B0BE.toInt()),
    )

    private const val PREFS = "shell"
    private const val KEY = "wallpaper"

    fun load(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0).coerceIn(0, styles.size - 1)

    fun save(ctx: Context, index: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY, index.coerceIn(0, styles.size - 1)).apply()
    }

    fun name(index: Int): String = styles[index.coerceIn(0, styles.size - 1)].name

    /** Paint the wallpaper into a [w] x [h] rectangle. Cheap enough for the rare redraw. */
    fun draw(c: Canvas, w: Float, h: Float, index: Int) {
        val st = styles[index.coerceIn(0, styles.size - 1)]
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, w, h, st.top, st.bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        // A soft light near the top left: the "calm depth" the audit asks for.
        p.shader = RadialGradient(
            w * 0.28f, h * 0.12f, w * 0.62f,
            intArrayOf(Theme.alpha(st.glow, 46), Theme.alpha(st.glow, 14), 0x00000000),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w, h, p)
        // And a second, fainter glow low right so the picture does not fall flat.
        p.shader = RadialGradient(
            w * 0.88f, h * 0.92f, w * 0.55f,
            intArrayOf(Theme.alpha(st.glow, 20), 0x00000000),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w, h, p)
    }
}

/** The full-screen wallpaper layer, below every window. */
class WallpaperView(context: Context) : View(context) {
    var index: Int = Wallpaper.load(context)
        set(v) { field = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        Wallpaper.draw(canvas, width.toFloat(), height.toFloat(), index)
    }
}
