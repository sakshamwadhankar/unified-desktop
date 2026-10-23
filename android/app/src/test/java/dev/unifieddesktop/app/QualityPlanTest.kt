package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Test

class QualityPlanTest {
    @Test
    fun auto_keeps_the_phase5_defaults() {
        val p = QualityPlan.plan(QualityChoice.AUTO)
        assertEquals(12_000_000, p.bitrate)
        assertEquals(60, p.fps)
        assertEquals("auto", p.label)
    }

    @Test
    fun sharp_spends_more_bits_per_frame() {
        val p = QualityPlan.plan(QualityChoice.SHARP)
        assertEquals(Phase12Quality.STANDARD.bitrate, p.bitrate)
        assertEquals(30, p.fps)
    }

    @Test
    fun smooth_keeps_sixty_fps() {
        val p = QualityPlan.plan(QualityChoice.SMOOTH)
        assertEquals(Phase12Quality.SMOOTH.bitrate, p.bitrate)
        assertEquals(60, p.fps)
    }

    @Test
    fun desktop_size_is_never_part_of_a_plan() {
        // D22: all three choices describe the same fixed 1728x1080 desktop.
        for (q in QualityChoice.values()) {
            val p = QualityPlan.plan(q)
            assert(p.bitrate > 0 && p.fps >= 15)
        }
    }

    @Test
    fun unknown_stored_ids_fall_back_to_auto() {
        assertEquals(QualityChoice.AUTO, QualityChoice.fromId(null))
        assertEquals(QualityChoice.AUTO, QualityChoice.fromId("wat"))
        assertEquals(QualityChoice.SHARP, QualityChoice.fromId("sharp"))
    }

    @Test
    fun the_phone_home_defaults_to_dark() {
        assertEquals(ThemeChoice.DARK, ThemeChoice.fromId(null))
        assertEquals(ThemeChoice.DARK, ThemeChoice.fromId("wat"))
        assertEquals(ThemeChoice.LIGHT, ThemeChoice.fromId("light"))
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.fromId("system"))
    }
}
