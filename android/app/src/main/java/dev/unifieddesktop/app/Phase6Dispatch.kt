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
 *   p6:vscode[:folder]         VS Code window: log in to code-server, open the folder
 *   p6:probe:<text>            B-039 keyboard-route probe into the live page
 *   p6:keys                    the page's recorded keydown list (DOM probe)
 *   p6:settings                the Settings window's text
 *   p6:shot[:name]             draw the live desktop surface to filesDir/p6-<name>.png
 */
object Phase6Dispatch {

    private val main = Handler(Looper.getMainLooper())

    fun run(ctx: Context, cmd: String): String {
        P7Log.say(ctx, "P6DISPATCH cmd=$cmd")
        val out = AtomicReference("(no answer)")
        val latch = CountDownLatch(1)
        main.post {
            try {
                out.set(execute(ctx, cmd))
            } catch (t: Throwable) {
                android.util.Log.e("Phase6", "p6 fail cmd=$cmd", t)
                out.set("p6 fail ${t.javaClass.simpleName}: ${t.message?.take(80)}")
            } finally {
                latch.countDown()
            }
        }
        try { latch.await(8, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        P7Log.say(ctx, "P6DISPATCH answer=${out.get()}")
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
            // Phase 8 App Center driver: search/select/install/open/uninstall/exe. The sub-command
            // and its argument are split at the first ':' after "appcenter".
            "appcenter" -> {
                val ac = s.openApp(ShellRoot.App.APPCENTER).content as? AppCenterView
                    ?: return "no app center"
                val sub = arg.substringBefore(':').ifEmpty { "summary" }
                val x = arg.substringAfter(':', "").trim()
                when (sub) {
                    "summary", "open" -> ac.summary()
                    "search" -> ac.setQuery(x)
                    "rows" -> ac.visibleNames()
                    "select" -> ac.selectId(x)
                    "install" -> ac.installSelected()
                    "run" -> ac.openSelected()
                    "uninstall" -> ac.uninstallSelected()
                    "exe" -> ac.runExe(x)
                    else -> "unknown appcenter [$sub]"
                }
            }
            // Per-window geometry, for the real-mouse tests (B-058/B-064b): the harness answers
            // with every window's id/title/rect so a drag can be measured, not eyeballed.
            "rects" -> s.model.all().joinToString(" ") { w ->
                "id=${w.id} title=\"${w.title}\" rect=${w.rect().joinToString(",")} focused=${w.id == s.model.focusedId}"
            }
            // B-083: fixed + real installed-app rows, and the shared launch route by id.
            "menurows" -> s.menuRows()
            "openid" -> s.openCatalogApp(arg.trim())
            // B-086 diagnostics: read the live prefix's Uninstall tree.
            "regquery" -> try {
                val text = Phase2Wine.queryUninstallRegistry(ctx)
                val products = MsiProductCode.parseRegQuery(text)
                "regquery bytes=${text.length} products=${products.size} " +
                    products.joinToString(";") { "${it.code}=${it.displayName}" }
            } catch (t: Throwable) {
                android.util.Log.e("Phase6", "regquery fail", t)
                P7Log.say(ctx, "P6DISPATCH regquery fail ${t.stackTraceToString()}")
                "regquery fail ${t.javaClass.name}: ${t.message}"
            }
            "open" -> {
                val app = when {
                    arg.lowercase() == "files" -> ShellRoot.App.FILES
                    arg.lowercase() == "browser" -> ShellRoot.App.BROWSER
                    arg.lowercase() == "settings" -> ShellRoot.App.SETTINGS
                    arg.lowercase() == "test" -> ShellRoot.App.TEST
                    arg.lowercase() == "x11" -> ShellRoot.App.X11
                    arg.lowercase() == "vscode" -> ShellRoot.App.VSCODE
                    arg.lowercase() == "appcenter" -> ShellRoot.App.APPCENTER
                    arg.lowercase().startsWith("android") -> ShellRoot.App.ANDROID
                    else -> return "unknown app [$arg]"
                }
                // "open:android" or "open:android <package>"; the guest app's package is the
                // argument after the app name.
                val guest = arg.substringAfter(' ', "").trim().ifEmpty { null }
                val f = s.openApp(app, guest)
                "opened ${app.title()} id=${f.win.id} rect=${f.win.rect().joinToString(",")} ${s.stats()}"
            }
            // B-077 proof: force the X11 frame connection to drop and reconnect on the worker.
            "x11reconnect" -> {
                val c = s.x11Content() ?: return "no X11 window"
                c.requestReconnect()
                "x11 reconnect requested"
            }
            // Virtual mouse/keyboard for the second-screen round: injected on the phone's own
            // desktop, so the owner's physical PC mouse and focus are never used. x,y are
            // desktop pixels (1920x1080); key is <android-keycode>,<0|1>[,meta].
            "move" -> {
                val p = arg.split(",")
                if (p.size != 2) return "move needs x,y"
                val x = p[0].trim().toIntOrNull() ?: return "bad x"
                val y = p[1].trim().toIntOrNull() ?: return "bad y"
                s.moveTo(x, y)
                "moved $x,$y ${s.stats()}"
            }
            "tap" -> {
                val p = arg.split(",")
                if (p.size != 2) return "tap needs x,y"
                val x = p[0].trim().toIntOrNull() ?: return "bad x"
                val y = p[1].trim().toIntOrNull() ?: return "bad y"
                s.mouseButton(Phase5Protocol.BTN_LEFT, true, x, y)
                s.mouseButton(Phase5Protocol.BTN_LEFT, false, x, y)
                "tap $x,$y ${s.stats()}"
            }
            "key" -> {
                val p = arg.split(",")
                if (p.size < 2) return "key needs code,down[,meta]"
                val code = p[0].trim().toIntOrNull() ?: return "bad code"
                val down = p[1].trim() == "1" || p[1].trim().equals("true", true)
                val meta = p.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
                val handled = s.key(down, code, 0, meta)
                "key code=$code down=$down handled=$handled ${s.stats()}"
            }
            "menu" -> { s.toggleStartMenu(); "startMenu=${s.startMenuOpen()}" }            "close" -> {
                val id = s.model.focusedId ?: return "nothing focused"
                s.closeWindow(id)
                "closed $id ${s.stats()}"
            }
            "max" -> {
                val id = s.model.focusedId ?: return "nothing focused"
                s.model.toggleMaximize(id)
                // B-063 harness: the model rect alone is not enough; the frame must be laid out
                // again or the window keeps its old on-screen size (found 18:35).
                s.syncViews()
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
            // B-065: open (or re-open) the VS Code window and log in to code-server.
            "vscode" -> {
                val f = s.openApp(ShellRoot.App.VSCODE)
                val b = f.content as BrowserView
                val folder = if (arg.isNotEmpty()) arg else b.defaultCodeFolder()
                // B-075: starts code-server first when it is not running, then loads the page.
                b.ensureCodeServer(folder)
                "vscode starting/loading folder=$folder"
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
            // Run JavaScript in the VS Code/Browser page and log the result. Debug-only, used
            // to find where DOM focus is (Monaco's hidden textarea) when a key event reaches
            // the WebView but no editor accepts it (B-039 trace, 19:0x).
            "js" -> {
                val f = s.openApp(ShellRoot.App.VSCODE)
                val b = f.content as? BrowserView ?: return "window is not a browser"
                b.web.evaluateJavascript(arg) { v -> android.util.Log.i("Phase6", "JS ${v}") }
                "js sent"
            }
            // Fixed JS, no quoting through the device shell: list the page's textareas, then
            // focus Monaco's hidden input so PC keys land in the editor, not in the chat box.
            "jsareas" -> {
                val f = s.openApp(ShellRoot.App.VSCODE)
                val b = f.content as? BrowserView ?: return "window is not a browser"
                b.web.evaluateJavascript(
                    "Array.from(document.querySelectorAll('textarea')).map(function(t){" +
                        "return t.className}).join('|')"
                ) { v -> android.util.Log.i("Phase6", "JSAREAS ${v}") }
                "jsareas sent"
            }
            "jsfocus" -> {
                val f = s.openApp(ShellRoot.App.VSCODE)
                val b = f.content as? BrowserView ?: return "window is not a browser"
                b.web.evaluateJavascript(
                    "(function(){var t=document.querySelectorAll('textarea');var n=0;" +
                        "for(var i=0;i<t.length;i++){var c=t[i].className||'';" +
                        "if(c.indexOf('inputarea')>=0||c.indexOf('monaco')>=0){t[i].focus();n++;}}" +
                        "return n+':'+(document.activeElement.className||document.activeElement.tagName);})()"
                ) { v -> android.util.Log.i("Phase6", "JSFOCUS ${v}") }
                "jsfocus sent"
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
