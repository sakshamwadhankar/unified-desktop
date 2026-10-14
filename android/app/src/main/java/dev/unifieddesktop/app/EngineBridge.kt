package dev.unifieddesktop.app

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
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
            val intent: Intent = BlackBoxCore.getBPackageManager()
                .getLaunchIntentForPackage(packageName, 0)
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
}
