package dev.unifieddesktop.app

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore

data class EngineResult(val ok: Boolean, val message: String? = null)

/**
 * Thin wrapper over BlackBoxCore for the Phase 1 proof: import a Play-installed app into
 * the engine and launch it onto a display we own. Static BlackBoxCore members are reached
 * through the class, not the instance.
 */
class EngineBridge(private val context: Context) {
    private val core get() = BlackBoxCore.get()

    fun importInstalled(packageName: String): EngineResult {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            val result = core.installPackageAsUser(packageName, 0)
            EngineResult(result.success, result.msg)
        } catch (t: Throwable) {
            EngineResult(false, t.message ?: t.javaClass.simpleName)
        }
    }

    fun installGms(userId: Int): EngineResult {
        return try {
            val supported = core.isSupportGms()
            Log.i("Phase1-GMS", "GMS_SUPPORT supported=$supported")
            if (!supported) {
                return EngineResult(false, "engine host package manager does not expose $GMS_PACKAGE")
            }
            val packages = listOf(
                "com.google.android.gms",
                "com.google.android.gsf",
                "com.android.vending",
                "com.google.android.gsf.login",
                "com.google.android.backuptransport",
                "com.google.android.backup",
                "com.google.android.configupdater",
                "com.google.android.syncadapters.contacts",
                "com.google.android.feedback",
                "com.google.android.onetimeinitializer",
                "com.google.android.partnersetup",
                "com.google.android.setupwizard",
                "com.google.android.syncadapters.calendar",
                "com.google.android.play.games",
                "com.google.android.wearable.app",
                "com.google.android.wearable.app.cn"
            )
            val packageResults = mutableListOf<String>()
            var allSuccess = true
            for (packageName in packages) {
                val hostInfo = try {
                    val info = context.packageManager.getApplicationInfo(packageName, 0)
                    "present source=${info.sourceDir} splits=${info.splitSourceDirs?.size ?: 0}"
                } catch (t: Throwable) {
                    "missing:${t.javaClass.simpleName}"
                }
                val before = core.isInstalled(packageName, userId)
                val result = if (before || !hostInfo.startsWith("present")) {
                    null
                } else {
                    core.installPackageAsUser(packageName, userId)
                }
                val after = core.isInstalled(packageName, userId)
                val success = result?.success ?: true
                val postconditionSuccess = before || !hostInfo.startsWith("present") || (success && after)
                if (!postconditionSuccess) allSuccess = false
                val detail = "package=$packageName host=$hostInfo before=$before " +
                    "installSuccess=${result?.success ?: "skipped"} installMsg=${result?.msg} " +
                    "after=$after postconditionSuccess=$postconditionSuccess"
                packageResults += detail
                Log.i("Phase1-GMS", "GMS_PACKAGE $detail")
            }
            val installed = core.isInstallGms(userId)
            val traces = core.hasGmsTraces(userId)
            val details = "success=$allSuccess installedGms=$installed traces=$traces " +
                packageResults.joinToString(" | ")
            Log.i("Phase1-GMS", "GMS_INSTALL $details")
            EngineResult(allSuccess, details)
        } catch (t: Throwable) {
            Log.e("Phase1-GMS", "GMS_INSTALL_EXCEPTION", t)
            EngineResult(false, "${t.javaClass.name}: ${t.message}")
        }
    }

    fun describePackage(packageName: String): String {
        return try {
            val virtual = BlackBoxCore.getBPackageManager().getPackageInfo(
                packageName,
                PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS,
                0
            )
            val host = context.packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS
            )
            val virtualActivities = virtual?.activities?.joinToString(";") { activity ->
                "${activity.name}:alias=${activity.targetActivity}"
            } ?: "null"
            val hostActivities = host.activities?.joinToString(";") { activity ->
                "${activity.name}:alias=${activity.targetActivity}"
            } ?: "null"
            val splits = host.applicationInfo?.splitNames?.joinToString(",") ?: "none"
            val result = "hostSplits=$splits hostActivities=$hostActivities virtualActivities=$virtualActivities"
            Log.i("Phase1-Launch", "PACKAGE_INFO package=$packageName $result")
            if (packageName == "com.android.chrome") {
                val selected = virtual?.activities?.filter {
                    it.name.contains("ChromeTabbedActivity") ||
                        it.name.contains("chrome.Main") ||
                        it.targetActivity?.contains("ChromeTabbedActivity") == true
                }?.joinToString(";") { activity ->
                    "${activity.name}:alias=${activity.targetActivity}"
                } ?: "none"
                Log.i("Phase1-Launch", "CHROME_VIRTUAL_SELECTED $selected")
            }
            result
        } catch (t: Throwable) {
            val result = "${t.javaClass.name}: ${t.message}"
            Log.e("Phase1-Launch", "PACKAGE_INFO_EXCEPTION package=$packageName $result", t)
            result
        }
    }

    fun launch(packageName: String, displayId: Int): EngineResult {
        return try {
            if (!core.isInstalled(packageName, 0)) {
                return EngineResult(false, "package is not imported")
            }
            val intent: Intent = launchIntent(packageName)
                ?: return EngineResult(false, "no launch activity")
            val options = ActivityOptions.makeBasic()
            options.launchDisplayId = displayId
            val code = BlackBoxCore.getBActivityManager()
                .startActivityAms(0, intent, null, null, null, -1, 0, options.toBundle())
            EngineResult(code >= 0, if (code >= 0) null else "startActivityAms returned $code")
        } catch (t: Throwable) {
            EngineResult(false, t.message ?: t.javaClass.simpleName)
        }
    }

    /** B1: launch normally, then the guest lifecycle callback moves its decor to a Presentation. */
    fun launchPathB1(packageName: String, displayId: Int, injectInput: Boolean = false): EngineResult {
        return try {
            if (!core.isInstalled(packageName, 0)) {
                return EngineResult(false, "package is not imported")
            }
            val intent: Intent = launchIntent(packageName)
                ?: return EngineResult(false, "no launch activity")
            if (injectInput) intent.putExtra("phase1_inject_input", true)
            val code = BlackBoxCore.getBActivityManager()
                .startActivityAms(0, intent, null, null, null, -1, 0, null)
            EngineResult(code >= 0, if (code >= 0) null else "startActivityAms returned $code")
        } catch (t: Throwable) {
            EngineResult(false, t.message ?: t.javaClass.simpleName)
        }
    }

    /** Prefer the virtual resolver; use the host's resolved component only as an explicit
     * component fallback. It still enters BlackBox's activity manager and never starts the
     * host activity directly. This covers packages whose launcher filter is missing from the
     * virtual package record (for example a split-installed browser). */
    private fun launchIntent(packageName: String): Intent? {
        val virtual = BlackBoxCore.getBPackageManager().getLaunchIntentForPackage(packageName, 0)
        if (virtual != null) {
            Log.i("Phase1-Launch", "VIRTUAL_LAUNCH_COMPONENT package=$packageName component=${virtual.component}")
            return virtual
        }
        val host = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return null
        val component: ComponentName = host.component ?: return null
        val hostInfo = try {
            context.packageManager.getActivityInfo(component, PackageManager.MATCH_ALL)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val candidates = linkedSetOf<ComponentName>()
        candidates += component
        hostInfo?.targetActivity?.let { target ->
            val targetName = when {
                target.startsWith(".") -> packageName + target
                target.indexOf('.') < 0 -> "$packageName.$target"
                else -> target
            }
            candidates += ComponentName(packageName, targetName)
            Log.i("Phase1-Launch", "HOST_LAUNCH_ALIAS package=$packageName alias=$component target=${candidates.last()}")
        }
        for (candidate in candidates) {
            val virtualInfo: ActivityInfo? = try {
                BlackBoxCore.getBPackageManager().getActivityInfo(candidate, 0, 0)
            } catch (_: Throwable) {
                null
            }
            if (virtualInfo != null) {
                return Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(candidate)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .also { Log.w("Phase1-Launch", "VIRTUAL_LAUNCH_FALLBACK package=$packageName component=$candidate") }
            }
        }
        Log.e("Phase1-Launch", "VIRTUAL_LAUNCH_COMPONENT_MISSING package=$packageName candidates=$candidates")
        return null
    }

    private companion object {
        const val GMS_PACKAGE = "com.google.android.gms"
    }
}
