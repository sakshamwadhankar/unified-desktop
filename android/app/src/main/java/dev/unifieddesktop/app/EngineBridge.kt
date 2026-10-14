package dev.unifieddesktop.app

import android.content.Context
import android.content.Intent
import android.app.ActivityOptions
import top.niunaijun.blackbox.BlackBoxCore

data class EngineResult(val ok: Boolean, val message: String? = null)

class EngineBridge(private val context: Context) {
    private val core get() = BlackBoxCore.get()

    fun importInstalled(packageName: String): EngineResult = try {
        context.packageManager.getPackageInfo(packageName, 0)
        val result = core.installPackageAsUser(packageName, 0)
        EngineResult(result.success, result.msg)
    } catch (t: Throwable) { EngineResult(false, t.message ?: t.javaClass.simpleName) }

    fun launch(packageName: String, displayId: Int): EngineResult = try {
        if (!core.isInstalled(packageName, 0)) return EngineResult(false, "package is not imported")
        val intent: Intent = core.getBPackageManager().getLaunchIntentForPackage(packageName, 0)
            ?: return EngineResult(false, "no launch activity")
        val options = ActivityOptions.makeBasic().apply { launchDisplayId = displayId }.toBundle()
        val code = core.getBActivityManager().startActivityAms(0, intent, null, null, null, -1, 0, options)
        EngineResult(code >= 0, if (code >= 0) null else "startActivityAms returned $code")
    } catch (t: Throwable) { EngineResult(false, t.message ?: t.javaClass.simpleName) }
}
