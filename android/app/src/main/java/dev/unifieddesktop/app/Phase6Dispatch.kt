package dev.unifieddesktop.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Harness bridge for the Phase-6 shell (`adb shell am broadcast -n
 * dev.unifieddesktop.app/.HarnessReceiver --es phase4x p6:<command>`). Debug-only driver:
 * it talks to the live shell on the UI thread and answers with one line, so a run is
 * reproducible from adb alone.
 *
 * Commands:
 *   p6:shell                  one state line (windows, focus, start menu, input counters)
 *   p6:open:<files|browser|settings|test>
 *   p6:menu                    toggle the start menu
 *   p6:close                   close the focused window
 *   p6:max                     maximize/restore the focused window
 *   p6:files                   Files window: what it lists and where it is
 *   p6:newfolder               Files window: create a folder, then list
 *   p6:rename:<name>           Files window: rename the selected entry
 *   p6:url:<url>               Browser: set the address bar and load
 *   p6:probe:<text>            B-039 keyboard-route probe into the live page
 *   p6:keys                    the page's recorded keydown list (DOM probe)
 *   p6:settings                the Settings window's text
 *   p6:shot[:name]             draw the live desktop surface to filesDir/p6-<name>.png
 */
object Phase6Dispatch {

    private val main = Handler(Looper.getMainLooper())

    fun run(ctx: Context, cmd: String): String {
        val out = AtomicReference("(no answer)")
        val latch = CountDownLatch(1)
        main.post {
            try {
                out.set(execute(ctx, cmd))
            } catch (t: Throwable) {
                out.set("p6 fail ${t.javaClass.simpleName}: ${t.message?.take(80)}")
            } finally {
                latch.countDown()
            }
        }
        try { latch.await(8, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        return out.get()
    }

    private fun shell(): ShellRoot? = Phase5Dispatch.session()?.shell()

    private fun execute(ctx: Context, cmd: String): String {
        val s = shell() ?: return "shell not up (no video session)"
        val parts = cmd.split(":", limit = 2)
        val what = parts[0].trim()
        val arg = if (parts.size > 1) parts[1] else ""
        return when (what) {
            "shell" -> s.stats()
            "open" -> {
                val app = when (arg.lowercase()) {
                    "files" -> ShellRoot.App.FILES
                    "browser" -> ShellRoot.App.BROWSER
                    "settings" -> ShellRoot.App.SETTINGS
                    "test" -> ShellRoot.App.TEST
                    "x11" -> ShellRoot.App.X11
                    else -> return "unknown app [$arg]"
                }
                val f = s.openApp(app)
                "opened ${app.title()} id=${f.win.id} rect=${f.win.rect().joinToString(",")} ${s.stats()}"
            }
            "menu" -> { s.toggleStartMenu(); "startMenu=${s.startMenuOpen()}" }
            "close" -> {
                val id = s.model.focusedId ?: return "nothing focused"
                s.closeWindow(id)
                "closed $id ${s.stats()}"
            }
            "max" -> {
                val id = s.model.focusedId ?: return "nothing focused"
                s.model.toggleMaximize(id)
                val w = s.model.byId(id)!!
                "maximized=${w.maximized} rect=${w.rect().joinToString(",")}"
            }
            "files" -> FilesDispatch.listing()
            "newfolder" -> FilesDispatch.newFolder()
            "rename" -> FilesDispatch.renameTo(arg)
            "url" -> {
                val b = browser(s)
                b.address.setText(arg)
                b.go()
                "url=$arg ${b.pageText()}"
            }
            "probe" -> {
                val b = browser(s)
                val route = b.probeKeyboard(arg)
                "probe route=$route payload=\"$arg\""
            }
            "keys" -> {
                val b = browser(s)
                val ref = AtomicReference("(pending)")
                val l = CountDownLatch(1)
                b.keysSeen { v -> ref.set(v); l.countDown() }
                l.await(3, java.util.concurrent.TimeUnit.SECONDS)
                "pageKeys=${ref.get()}"
            }
            "settings" -> SettingsDispatch.text().replace("\n", " | ")
            // Draw the live desktop surface (the real ShellRoot view hierarchy, exactly what the
            // VirtualDisplay composes) into a PNG in app storage. Dev-only evidence path: the
            // emulator's H.264 stream does not decode on the PC yet (BUGS B-057), so the pixels
            // are taken on the phone side with the same views the PC would show.
            "shot" -> {
                val w = if (s.width > 0) s.width else ShellRoot.DESKTOP_W
                val h = if (s.height > 0) s.height else ShellRoot.DESKTOP_H
                val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(bmp)
                c.drawColor(android.graphics.Color.BLACK)
                s.draw(c)
                val name = (if (arg.isEmpty()) "shot" else arg.replace(Regex("[^A-Za-z0-9_-]"), "_"))
                val f = java.io.File(ctx.filesDir, "$name-${System.currentTimeMillis()}.png")
                java.io.FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                "shot=${f.absolutePath} ${w}x${h}"
            }
            else -> "unknown [$what]"
        }
    }

    private fun browser(s: ShellRoot): BrowserView {
        val win = s.model.all().firstOrNull { it.title == "Browser" }
        if (win == null) {
            val f = s.openApp(ShellRoot.App.BROWSER)
            return f.content as BrowserView
        }
        return (s as ShellRoot).openApp(ShellRoot.App.BROWSER).content as BrowserView
    }
}
