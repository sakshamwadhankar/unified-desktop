package dev.unifieddesktop.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

object Phase10Onboarding {
    private const val PREFS = "phase10"
    private const val CONFIRMED = "oem_background_confirmed"

    fun isColorOsFamily(): Boolean {
        val text = "${Build.MANUFACTURER} ${Build.BRAND} ${Build.DEVICE}".lowercase()
        return listOf("oppo", "oneplus", "realme", "coloros", "realtme").any { text.contains(it) }
    }

    fun confirmed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CONFIRMED, false)

    fun show(activity: Activity, force: Boolean = false) {
        if (!force && confirmed(activity)) return
        val family = if (isColorOsFamily()) {
            "This phone appears to use the OPPO/OnePlus/ColorOS battery manager. "
        } else {
            "If your phone has battery-management controls, use this setting. "
        }
        val message = family + "Allow background activity so the desktop can keep running " +
            "when the screen is off.\n\n" +
            "Open app settings, then go to:\n" +
            "Apps > ${activity.getString(R.string.app_name)} > Battery usage > Allow background activity\n\n" +
            "This app cannot read whether the setting is enabled. Tap the confirmation only " +
            "after you have checked it."
        AlertDialog.Builder(activity)
            .setTitle("Allow background activity")
            .setMessage(message)
            .setPositiveButton("Open app settings") { _, _ -> openAppSettings(activity) }
            .setNeutralButton("I enabled it") { _, _ ->
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(CONFIRMED, true).apply()
                android.util.Log.i("Phase10", "OEM background activity user-confirmed")
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    fun openAppSettings(context: Context) {
        context.startActivity(
            Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
