package dev.unifieddesktop.app

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.ComponentName
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
        if (virtual != null) return virtual
        val host = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return null
        val component: ComponentName = host.component ?: return null
        val virtualInfo = BlackBoxCore.getBPackageManager().getActivityInfo(component, 0, 0)
        if (virtualInfo == null) {
            Log.e("Phase1-Launch", "VIRTUAL_LAUNCH_COMPONENT_MISSING package=$packageName component=$component")
            return null
        }
        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .also { Log.w("Phase1-Launch", "VIRTUAL_LAUNCH_FALLBACK package=$packageName component=$component") }
    }
}
