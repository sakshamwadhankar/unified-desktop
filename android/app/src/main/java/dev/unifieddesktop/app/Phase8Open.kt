package dev.unifieddesktop.app

/**
 * B-087: what an X11 shell window is called. It names the app that was asked for, or the
 * neutral desktop name when the window was opened without one.
 */
object X11Title {
    const val BASE = "X11 desktop"

    fun title(appName: String?): String =
        if (appName.isNullOrBlank()) BASE else "$BASE — $appName"

    /**
     * B-093: "Open any .exe" reuses the X11 frame, so it must rename it too. The program's
     * base name is used ("Minesweeper.exe" -> "X11 desktop — Minesweeper"); a name that is
     * only an extension falls back to the neutral desktop name.
     */
    fun exeTitle(fileName: String): String {
        val stem = fileName.substringBeforeLast('.', fileName).trim()
        return title(stem.ifEmpty { null })
    }
}

/**
 * One launch decision. Pure data, so the App Center and the start menu use the same route
 * (B-083) and the route itself is unit-tested without Android.
 */
data class OpenTarget(
    val action: String,
    val title: String,
    val url: String = "",
    val guest: String = "",
    val catalogId: String = "",
)

object OpenRoute {

    /** The window/URL decision for [app] at [v]; "none" when the catalog has no opener. */
    fun of(app: CatalogApp, v: CatalogVersion): OpenTarget {
        val guest = v.packageName.ifEmpty { app.packageName }
        return when (app.open) {
            "files" -> OpenTarget("files", "Files")
            "browser" -> OpenTarget("browser", "Browser", url = v.fetch)
            "settings" -> OpenTarget("settings", "Settings")
            "vscode" -> OpenTarget("vscode", "VS Code")
            "x11" -> OpenTarget("x11", X11Title.title(app.name), catalogId = app.id)
            "android" -> OpenTarget(
                "android", "Android app", url = v.fetch, guest = guest, catalogId = app.id,
            )
            else -> OpenTarget("none", app.name)
        }
    }
}

/**
 * B-092: an Android catalog package that is not on the phone must never get a guest window.
 * Its only honest target is the official Play page in the Browser; the owner installs it.
 */
object AndroidOpen {
    private const val PLAY = "https://play.google.com/store/apps/details?id="

    fun playUrl(t: OpenTarget): String =
        if (t.url.startsWith("https://play.google.com/")) t.url else PLAY + t.guest

    fun browserFallback(t: OpenTarget): OpenTarget =
        t.copy(action = "browser", title = "Browser", url = playUrl(t))
}

/** One installed app that the start menu offers. */
data class MenuRow(val appId: String, val label: String, val kind: String, val version: String)

/**
 * B-083: the start menu's installed-app section, derived only from real install/import
 * records. An uninstall clears the record, so the row disappears in the same change.
 */
object InstalledMenu {
    fun rows(installed: Map<String, InstalledApp>, apps: List<CatalogApp>): List<MenuRow> =
        apps.mapNotNull { app ->
            val rec = installed[app.id] ?: return@mapNotNull null
            if (rec.isEmpty()) return@mapNotNull null
            MenuRow(app.id, app.name, app.open, rec.version)
        }
}
