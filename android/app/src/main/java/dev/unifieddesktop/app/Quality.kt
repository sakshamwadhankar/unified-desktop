package dev.unifieddesktop.app

import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/**
 * Phase 13: the user's Quality choice (docs/UX_AUDIT.md). The desktop/VirtualDisplay size is
 * fixed for a session (D22); the choice only changes the encoder's bitrate and frame rate.
 * `Auto` keeps the Phase-5 defaults; the governor still steps fps/bitrate down when the phone
 * gets hot. Sharp spends more bits per frame at 30 fps; Smooth keeps 60 fps.
 */
enum class QualityChoice(val id: String, val label: String, val detail: String) {
    AUTO("auto", "Auto", "Balanced. The app steps down when the phone is hot."),
    SHARP("sharp", "Sharp", "More detail per frame, 30 fps."),
    SMOOTH("smooth", "Smooth", "60 fps for motion and dragging."),
    ;

    companion object {
        fun fromId(id: String?): QualityChoice =
            values().firstOrNull { it.id == id } ?: AUTO
    }
}

/** One encoder plan: the desktop size is never part of it (D22). */
data class EncodePlan(val bitrate: Int, val fps: Int, val label: String)

/**
 * Pure mapping, unit-tested on the JVM. The constants are repeated rather than read from
 * Phase5Encoder so this class loads without the Android media stack.
 */
object QualityPlan {
    const val DEFAULT_BITRATE = 12_000_000
    const val DEFAULT_FPS = 60

    fun plan(choice: QualityChoice): EncodePlan = when (choice) {
        QualityChoice.AUTO -> EncodePlan(DEFAULT_BITRATE, DEFAULT_FPS, choice.id)
        QualityChoice.SHARP -> EncodePlan(
            Phase12Quality.STANDARD.bitrate,
            Phase12Quality.STANDARD.maxFps,
            choice.id,
        )
        QualityChoice.SMOOTH -> EncodePlan(
            Phase12Quality.SMOOTH.bitrate,
            Phase12Quality.SMOOTH.maxFps,
            choice.id,
        )
    }
}

/** The Quality preference shared by the desktop Settings and the phone home Settings. */
internal object QualityPref {
    private const val FILE = "desktop-settings"
    private const val KEY = "quality"

    fun get(c: Context): QualityChoice =
        QualityChoice.fromId(
            c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null),
        )

    fun set(c: Context, q: QualityChoice) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, q.id).apply()
    }

    /** The plan the next encoder uses; re-read on every encoder build (restart/next session). */
    fun plan(c: Context): EncodePlan = QualityPlan.plan(get(c))
}

/**
 * Phase 13: the phone home's palette. Dark is the default (the owner called the old all-white
 * screen cheap), Light is the same layout in daylight, System follows the phone.
 */
enum class ThemeChoice(val id: String, val label: String) {
    DARK("dark", "Dark"),
    LIGHT("light", "Light"),
    SYSTEM("system", "System"),
    ;

    companion object {
        fun fromId(id: String?): ThemeChoice = values().firstOrNull { it.id == id } ?: DARK
    }
}

internal object ThemePref {
    private const val FILE = "phone-home"
    private const val KEY = "appearance"

    fun get(c: Context): ThemeChoice = ThemeChoice.fromId(
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null),
    )

    fun set(c: Context, t: ThemeChoice) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, t.id).apply()
    }

    fun isDark(c: Context): Boolean = when (get(c)) {
        ThemeChoice.DARK -> true
        ThemeChoice.LIGHT -> false
        ThemeChoice.SYSTEM ->
            (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }
}

/** What the PC link runs over, from the phone's active network (or an AOA accessory). */
internal object LinkInfo {
    fun kind(c: Context): String {
        Phase4LinkService.linkLabel()?.let { return it }
        if (Phase4LinkService.accessoryOpen()) return "USB"
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return "Unknown"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Not connected"
        return when {
            Build.VERSION.SDK_INT >= 31 &&
                caps.hasTransport(NetworkCapabilities.TRANSPORT_USB) -> "USB"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "USB tethering"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            else -> "Connected"
        }
    }
}
