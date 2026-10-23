package dev.unifieddesktop.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Phase 13: the shell's own vector icon set, drawn from paths on a 24x24 grid. No font and no
 * third-party asset, so there is no licence row and nothing to fetch. Every icon is a stroke
 * drawing (except the few solid ones) so one call paints it at any size in any colour.
 */
object DeskIcon {
    const val GRID = "grid"
    const val FOLDER = "folder"
    const val GLOBE = "globe"
    const val GEAR = "gear"
    const val CODE = "code"
    const val NOTE = "note"
    const val SEARCH = "search"
    const val MIN = "min"
    const val MAX = "max"
    const val RESTORE = "restore"
    const val CLOSE = "close"
    const val LEFT = "left"
    const val RIGHT = "right"
    const val RELOAD = "reload"
    const val PLUS = "plus"
    const val UP = "up"
    const val TRASH = "trash"
    const val SEND = "send"
    const val PENCIL = "pencil"
    const val CHECK = "check"
    const val HOME = "home"
    const val WINDOW = "window"
    const val TERMINAL = "terminal"
    const val POWER = "power"
    const val PHONE = "phone"
    const val DOT = "dot"

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /**
     * Draw [kind] centred at (cx, cy) in a box of [size] px. Coordinates are the 24x24 grid,
     * so a caller never has to know the path.
     */
    fun draw(c: Canvas, kind: String, cx: Float, cy: Float, size: Float, color: Int) {
        val s = size / 24f
        stroke.color = color
        fill.color = color
        stroke.strokeWidth = 2f
        c.save()
        c.translate(cx - 12f * s, cy - 12f * s)
        c.scale(s, s)
        when (kind) {
            GRID -> {
                val r = RectF()
                for (x in floatArrayOf(3f, 13f)) for (y in floatArrayOf(3f, 13f)) {
                    r.set(x, y, x + 8f, y + 8f)
                    c.drawRoundRect(r, 2f, 2f, fill)
                }
            }
            FOLDER -> {
                val p = Path().apply {
                    moveTo(3f, 8f); quadTo(3f, 6f, 5f, 6f); lineTo(9f, 6f)
                    lineTo(11f, 8.5f); lineTo(19f, 8.5f)
                    quadTo(21f, 8.5f, 21f, 10.5f); lineTo(21f, 18f)
                    quadTo(21f, 20f, 19f, 20f); lineTo(5f, 20f)
                    quadTo(3f, 20f, 3f, 18f); close()
                }
                c.drawPath(p, stroke)
            }
            GLOBE -> {
                c.drawCircle(12f, 12f, 8.5f, stroke)
                c.drawLine(3.5f, 12f, 20.5f, 12f, stroke)
                val p = Path().apply {
                    moveTo(12f, 3.5f)
                    cubicTo(7.5f, 6.5f, 7.5f, 17.5f, 12f, 20.5f)
                    cubicTo(16.5f, 17.5f, 16.5f, 6.5f, 12f, 3.5f)
                }
                c.drawPath(p, stroke)
            }
            GEAR -> {
                // Ring + eight short teeth, so it reads as a cog and not a sun.
                c.drawCircle(12f, 12f, 6.8f, stroke)
                c.drawCircle(12f, 12f, 2.7f, stroke)
                for (i in 0 until 8) {
                    val a = Math.toRadians((i * 45).toDouble())
                    val x0 = 12f + 6.8f * Math.cos(a).toFloat()
                    val y0 = 12f + 6.8f * Math.sin(a).toFloat()
                    val x1 = 12f + 9.1f * Math.cos(a).toFloat()
                    val y1 = 12f + 9.1f * Math.sin(a).toFloat()
                    c.drawLine(x0, y0, x1, y1, stroke)
                }
            }
            CODE -> {
                val p = Path().apply {
                    moveTo(8.5f, 6f); lineTo(3.5f, 12f); lineTo(8.5f, 18f)
                    moveTo(15.5f, 6f); lineTo(20.5f, 12f); lineTo(15.5f, 18f)
                }
                c.drawPath(p, stroke)
            }
            NOTE -> {
                c.drawRoundRect(RectF(5f, 3f, 19f, 21f), 2f, 2f, stroke)
                c.drawLine(8f, 8f, 16f, 8f, stroke)
                c.drawLine(8f, 12f, 16f, 12f, stroke)
                c.drawLine(8f, 16f, 13f, 16f, stroke)
            }
            SEARCH -> {
                c.drawCircle(10.5f, 10.5f, 6.3f, stroke)
                c.drawLine(15.1f, 15.1f, 20.5f, 20.5f, stroke)
            }
            MIN -> c.drawLine(6f, 12f, 18f, 12f, stroke)
            MAX -> c.drawRoundRect(RectF(6.5f, 6.5f, 17.5f, 17.5f), 1.5f, 1.5f, stroke)
            RESTORE -> {
                c.drawRoundRect(RectF(9f, 4f, 20f, 15f), 1.5f, 1.5f, stroke)
                c.drawRoundRect(RectF(4f, 9f, 15f, 20f), 1.5f, 1.5f, stroke)
            }
            CLOSE -> {
                c.drawLine(7f, 7f, 17f, 17f, stroke)
                c.drawLine(17f, 7f, 7f, 17f, stroke)
            }
            LEFT -> {
                val p = Path().apply { moveTo(14.5f, 5.5f); lineTo(7.5f, 12f); lineTo(14.5f, 18.5f) }
                c.drawPath(p, stroke)
            }
            RIGHT -> {
                val p = Path().apply { moveTo(9.5f, 5.5f); lineTo(16.5f, 12f); lineTo(9.5f, 18.5f) }
                c.drawPath(p, stroke)
            }
            RELOAD -> {
                val p = Path().apply { addArc(RectF(4f, 4f, 20f, 20f), -50f, 290f) }
                c.drawPath(p, stroke)
                // A short arrow head at the open end of the arc (top right).
                c.drawLine(16.2f, 4.2f, 13.2f, 4.7f, stroke)
                c.drawLine(16.2f, 4.2f, 16.4f, 7.2f, stroke)
            }
            PLUS -> {
                c.drawLine(12f, 5f, 12f, 19f, stroke)
                c.drawLine(5f, 12f, 19f, 12f, stroke)
            }
            UP -> {
                val p = Path().apply { moveTo(5f, 12f); lineTo(12f, 5f); lineTo(19f, 12f) }
                c.drawPath(p, stroke)
                c.drawLine(12f, 5f, 12f, 19f, stroke)
            }
            TRASH -> {
                c.drawLine(4f, 6f, 20f, 6f, stroke)
                c.drawLine(9f, 4f, 15f, 4f, stroke)
                val p = Path().apply {
                    moveTo(6f, 6f); lineTo(7.2f, 20f); lineTo(16.8f, 20f); lineTo(18f, 6f)
                }
                c.drawPath(p, stroke)
                c.drawLine(10f, 10f, 10f, 16f, stroke)
                c.drawLine(14f, 10f, 14f, 16f, stroke)
            }
            SEND -> {
                val p = Path().apply {
                    moveTo(4f, 11f); lineTo(20f, 4f); lineTo(13f, 20f); lineTo(11f, 13f); close()
                }
                c.drawPath(p, stroke)
            }
            PENCIL -> {
                val p = Path().apply {
                    moveTo(4.5f, 19.5f); lineTo(6f, 14.5f); lineTo(16f, 4.5f)
                    lineTo(19.5f, 8f); lineTo(9.5f, 18f); close()
                }
                c.drawPath(p, stroke)
                c.drawLine(14.5f, 6.5f, 17.5f, 9.5f, stroke)
            }
            CHECK -> {
                val p = Path().apply { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) }
                c.drawPath(p, stroke)
            }
            HOME -> {
                val p = Path().apply {
                    moveTo(4f, 11f); lineTo(12f, 4f); lineTo(20f, 11f)
                    moveTo(6f, 10f); lineTo(6f, 20f); lineTo(18f, 20f); lineTo(18f, 10f)
                }
                c.drawPath(p, stroke)
            }
            WINDOW -> {
                c.drawRoundRect(RectF(4f, 5f, 20f, 19f), 2f, 2f, stroke)
                c.drawLine(4f, 9f, 20f, 9f, stroke)
            }
            TERMINAL -> {
                c.drawRoundRect(RectF(3f, 4f, 21f, 20f), 2f, 2f, stroke)
                val p = Path().apply { moveTo(7f, 9f); lineTo(11f, 12f); lineTo(7f, 15f) }
                c.drawPath(p, stroke)
                c.drawLine(13f, 15f, 17f, 15f, stroke)
            }
            POWER -> {
                val p = Path().apply { addArc(RectF(4.5f, 4.5f, 19.5f, 19.5f), -50f, 280f) }
                c.drawPath(p, stroke)
                c.drawLine(12f, 2.5f, 12f, 11f, stroke)
            }
            PHONE -> {
                c.drawRoundRect(RectF(8f, 2f, 16f, 22f), 2f, 2f, stroke)
                c.drawLine(10.5f, 18.5f, 13.5f, 18.5f, stroke)
            }
            DOT -> c.drawCircle(12f, 12f, 5f, fill)
            else -> c.drawCircle(12f, 12f, 7f, stroke)
        }
        c.restore()
    }
}
