package dev.unifieddesktop.app

/**
 * Phase 12 quality profiles (OPTIMIZATION.md section 2). Pure Kotlin so the choice is
 * testable on the JVM; the service supplies real measurements and applies the result.
 *
 * Order of concern: never pick a profile the phone cannot sustain. A weak encoder or a hot
 * phone wins over the link; USB wins over Wi-Fi when the phone can drive 60 fps.
 */
enum class Phase12Quality(
    val label: String,
    val width: Int,
    val height: Int,
    val maxFps: Int,
    val bitrate: Int,
) {
    LIGHT("light", 1280, 720, 30, 6_000_000),
    STANDARD("standard", 1920, 1080, 30, 14_000_000),
    SMOOTH("smooth", 1920, 1080, 60, 24_000_000),
}

data class Phase12ProfileInput(
    /** The phone has a hardware AVC encoder and enough RAM/cores for a 1080p desktop. */
    val strongPhone: Boolean,
    /** USB cable or USB tethering. */
    val usb: Boolean,
    /** Associated with a 5 GHz (or better) Wi-Fi link. */
    val fiveGhzWifi: Boolean,
    /** Android PowerManager thermal status (0 none ... 6 shutdown). */
    val thermalStatus: Int,
    /** The chosen encoder is a hardware codec (software encoders get the light profile). */
    val hardwareEncoder: Boolean,
)

object Phase12Profiles {
    const val THERMAL_MODERATE = 2
    const val THERMAL_SEVERE = 3

    fun choose(input: Phase12ProfileInput): Phase12Quality {
        // A software encoder cannot carry 1080p in real time; a hot or weak phone must not
        // be pushed there either.
        if (!input.hardwareEncoder) return Phase12Quality.LIGHT
        if (input.thermalStatus >= THERMAL_SEVERE) return Phase12Quality.LIGHT
        if (!input.strongPhone) return Phase12Quality.LIGHT
        if (input.thermalStatus >= THERMAL_MODERATE) return Phase12Quality.STANDARD
        if (!input.usb && !input.fiveGhzWifi) return Phase12Quality.LIGHT
        if (input.usb && input.strongPhone) return Phase12Quality.SMOOTH
        return Phase12Quality.STANDARD
    }
}
