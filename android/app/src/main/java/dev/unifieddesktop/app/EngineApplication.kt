package dev.unifieddesktop.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.ClientConfiguration
import java.io.File

class EngineApplication : Application() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        BlackBoxCore.get().doAttachBaseContext(base, object : ClientConfiguration() {
            override fun getHostPackageName() = base.packageName
            override fun isEnableLauncherActivity() = false
            override fun requestInstallPackage(file: File, userId: Int) = false
        })
    }
    override fun onCreate() {
        super.onCreate()
        BlackBoxCore.get().doCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityResumed(a: Activity) = Unit
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        })
    }
}
