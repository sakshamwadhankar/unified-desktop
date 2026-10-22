package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Test

class Phase12ProfilesTest {
    private fun input(
        strong: Boolean = true,
        usb: Boolean = false,
        five: Boolean = true,
        thermal: Int = 0,
        hw: Boolean = true,
    ) = Phase12ProfileInput(strong, usb, five, thermal, hw)

    @Test
    fun usb_strong_phone_gets_smooth() {
        assertEquals(Phase12Quality.SMOOTH, Phase12Profiles.choose(input(usb = true)))
    }

    @Test
    fun good_wifi_gets_standard() {
        assertEquals(Phase12Quality.STANDARD, Phase12Profiles.choose(input(five = true)))
    }

    @Test
    fun weak_wifi_gets_light() {
        assertEquals(
            Phase12Quality.LIGHT,
            Phase12Profiles.choose(input(usb = false, five = false)),
        )
    }

    @Test
    fun weak_phone_gets_light_even_on_usb() {
        assertEquals(Phase12Quality.LIGHT, Phase12Profiles.choose(input(strong = false, usb = true)))
    }

    @Test
    fun software_encoder_gets_light() {
        assertEquals(Phase12Quality.LIGHT, Phase12Profiles.choose(input(usb = true, hw = false)))
    }

    @Test
    fun moderate_heat_steps_down_one_profile() {
        assertEquals(
            Phase12Quality.STANDARD,
            Phase12Profiles.choose(input(usb = true, thermal = Phase12Profiles.THERMAL_MODERATE)),
        )
    }

    @Test
    fun severe_heat_gets_light() {
        assertEquals(
            Phase12Quality.LIGHT,
            Phase12Profiles.choose(input(usb = true, thermal = Phase12Profiles.THERMAL_SEVERE)),
        )
    }

    @Test
    fun light_fits_a_720p_stream_and_smooth_can_drive_60() {
        assertEquals(1280, Phase12Quality.LIGHT.width)
        assertEquals(720, Phase12Quality.LIGHT.height)
        assertEquals(30, Phase12Quality.LIGHT.maxFps)
        assertEquals(60, Phase12Quality.SMOOTH.maxFps)
        assertEquals(1920, Phase12Quality.SMOOTH.width)
    }
}
