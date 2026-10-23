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
            // B-061: no permanent foreground service (and no "BlackBox Core" notification)
            // just because our process exists. EngineDaemon starts it for a hidden app and
            // stops it with the last one.
            override fun isEnableDaemonService() = false
        })
    }
    override fun onCreate() {
        super.onCreate()
        BlackBoxCore.get().doCreate()
        BlackBoxCore.get().addAppLifecycleCallback(GuestPresentationHost())
        BlackBoxCore.get().addAppLifecycleCallback(GuestSurfacePresentationHost())
        // The *current* process name (the framework property reads back oddly here). The engine
        // runs guest apps in ":pN" and the daemon in ":black"; only the real app process may stop
        // services, and in a guest process BlackBox's hooked Context.stopService throws
        // (ClassCastException: boolean -> int), so the guard must be exact.
        val self = try { java.io.File("/proc/self/cmdline").readText().trim('\u0000') } catch (_: Throwable) { "" }
        val mainProcess = self == packageName
        android.util.Log.i("Phase1-B1", "callback_registered pid=${android.os.Process.myPid()}")
        if (mainProcess) {
            // B-061: an app start that launches no hidden app must not leave the engine daemon
            // (and its notification) running.
            EngineDaemon.ensureIdle(this)
            P7Log.say(this, "APP create pid=${android.os.Process.myPid()} daemonGuests=0")
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityResumed(a: Activity) = AppForeground.resumed()
            override fun onActivityPaused(a: Activity) = AppForeground.paused()
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        })
    }
}
