package dev.unifieddesktop.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** Shared styling for the shell's app windows (desktop pixels: the display is 240 dpi). */
internal object ShellUi {
    fun label(c: Context, s: String, size: Float = 20f, bold: Boolean = false): TextView =
        TextView(c).apply {
            text = s
            setTextColor(Color.WHITE)
            textSize = size
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(10, 8, 10, 8)
        }

    fun button(c: Context, s: String, onTap: () -> Unit): TextView =
        TextView(c).apply {
            text = s
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(16, 12, 16, 12)
            setBackgroundColor(0xFF3D5AA8.toInt())
            // One line, always: a squeezed button must ellipsize, not wrap over the row
            // (240 dpi in a narrowed Files window "Delete" used to wrap to "Del"/"ete").
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER
            isClickable = true
            setOnClickListener { onTap() }
        }

    fun row(c: Context, s: String, onTap: () -> Unit): TextView =
        TextView(c).apply {
            text = s
            setTextColor(Color.WHITE)
            textSize = 21f
            setPadding(24, 14, 24, 14)
            isClickable = true
            setOnClickListener { onTap() }
        }

    fun column(c: Context, bg: Int): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(bg)
    }
}

/**
 * Files: a list of the app's own `files/desktop/` folder (created on first use), with
 * open-folder, go-up, new-folder, rename and delete (with confirmation). Nothing outside
 * that folder is ever listed or touched.
 */
class FilesView(context: Context) : LinearLayout(context) {

    private val store: File = File(context.filesDir, "desktop").also { if (!it.exists()) it.mkdirs() }
    private var dir: File = store
    private var selected: File? = null

    private val pathLabel = ShellUi.label(context, "", 18f, bold = true)
    private val listBox = ShellUi.column(context, 0xFF171A20.toInt())
    private val status = ShellUi.label(context, "", 17f)

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFF1F2531.toInt())
        val bar = LinearLayout(context).apply { orientation = HORIZONTAL }
        bar.addView(ShellUi.button(context, "Up") { goUp() })
        bar.addView(ShellUi.button(context, "New folder") { newFolder() })
        bar.addView(ShellUi.button(context, "Rename") { renameSelectedPrompt() })
        bar.addView(ShellUi.button(context, "Delete") { deleteSelected() })
        bar.addView(ShellUi.button(context, "Send to PC") {
            val f = selected
            if (f == null) say("select a file first")
            else if (f.isDirectory) say("select a file, not a folder")
            else say(Phase11Files.sendToPc(f))
        })
        // Scrollable, not squeezed: a narrowed window scrolls the toolbar instead of wrapping
        // "Delete" onto two lines (seen at 240 dpi, fixed 2026-10-19).
        val barScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        }
        addView(barScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(pathLabel)
        val scroll = ScrollView(context).apply { addView(listBox) }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(status)
        FilesDispatch.bind(this)
        refresh()
    }

    fun currentDir(): File = dir

    fun refresh() {
        listBox.removeAllViews()
        pathLabel.text = "files/desktop" + dir.absolutePath.removePrefix(store.absolutePath).replace('\\', '/')
        val entries = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        if (entries.isEmpty()) listBox.addView(ShellUi.row(context, "(empty folder)") {})
        for (f in entries) {
            val name = if (f.isDirectory) "\uD83D\uDCC1 ${f.name}" else "\uD83D\uDCC4 ${f.name}  (${f.length()} B)"
            val row = ShellUi.row(context, name) {
                if (f.isDirectory) { dir = f; selected = null; refresh() } else { selected = f; say("selected ${f.name}") }
            }
            row.setBackgroundColor(if (f == selected) 0xFF33436B.toInt() else Color.TRANSPARENT)
            listBox.addView(row)
        }
        status.text = "${entries.size} item(s)"
    }

    private fun say(s: String) {
        status.text = s
        android.util.Log.i("Phase6", "FILES $s in ${dir.name}")
    }

    /** The folder listing as text, for the debug harness (`p6files`). */
    fun listing(): String =
        dir.absolutePath.removePrefix(store.parentFile!!.absolutePath) + " [" +
            (dir.listFiles()?.sortedBy { it.name }?.joinToString(",") { it.name } ?: "") + "]"

    fun goUp() {
        val p = dir.parentFile
        if (p != null && p.absolutePath.startsWith(store.absolutePath)) { dir = p; selected = null; refresh() }
    }

    fun newFolder() {
        var i = 1
        var f = File(dir, "New folder")
        while (f.exists()) { i++; f = File(dir, "New folder $i") }
        if (f.mkdirs()) { selected = null; refresh(); say("created ${f.name}") } else say("could not create folder")
    }

    /** Rename the selected entry (used by the harness too: `p6rename <name>`). */
    fun renameSelected(newName: String): Boolean {
        val s = selected ?: return false
        val target = File(dir, newName)
        val ok = s.renameTo(target)
        if (ok) { selected = target; refresh(); say("renamed to $newName") } else say("rename failed")
        return ok
    }

    fun renameSelectedPrompt() {
        val s = selected ?: run { say("select an item first"); return }
        renameSelected(s.name + "-renamed")
    }

    /** Delete with the confirmation dialog the design asks for. */
    private fun deleteSelected() {
        val s = selected ?: run { say("select an item first"); return }
        android.app.AlertDialog.Builder(context)
            .setTitle("Delete")
            .setMessage("Delete \"${s.name}\" permanently?")
            .setPositiveButton("Delete") { _, _ ->
                val ok = if (s.isDirectory) s.deleteRecursively() else s.delete()
                selected = null
                refresh()
                say(if (ok) "deleted" else "delete failed")
            }
            .setNegativeButton("Cancel") { _, _ -> say("delete cancelled") }
            .show()
    }
}

/** Lets the debug harness act on the live Files window (`p6newfolder`, `p6rename`). */
object FilesDispatch {
    @Volatile private var view: FilesView? = null
    fun bind(v: FilesView?) { view = v }
    fun listing(): String = view?.listing() ?: "(no files window)"
    fun newFolder(): String {
        val v = view ?: return "(no files window)"
        v.newFolder()
        return v.listing()
    }
    fun renameTo(name: String): String {
        val v = view ?: return "(no files window)"
        return "ok=" + v.renameSelected(name) + " " + v.listing()
    }
}

/**
 * Browser: a WebView with an address bar (typed with the PC keyboard), back/forward/reload
 * and a status line. The B-039 probe logs what each keyboard route does, so the working
 * route is a fact in logcat, not a guess.
 *
 * With [codeServer] = true this is the **VS Code window**: it logs in to the running
 * code-server by POSTing the session password (the same flow Phase3CodeActivity used to
 * render the workbench) and opens a workspace through the `?folder=` deep link.
 */
class BrowserView(context: Context, private val codeServer: Boolean = false) :
    LinearLayout(context), ShellTextContent {

    val address: EditText = EditText(context).apply {
        hint = "address (https://...)"
        setText("")
        setTextColor(Color.BLACK)
        setBackgroundColor(0xFFE8E8E8.toInt())
        textSize = 20f
        isFocusable = true
        isFocusableInTouchMode = true
        // B-060: a desktop address bar selects its text when it gets the focus, so typing a
        // new URL replaces the old one instead of appending to it.
        setSelectAllOnFocus(true)
    }

    private val status = ShellUi.label(context, "ready", 17f)

    /** B-082: the desktop Chrome UA, kept out of WebSettings so the fetch thread can use it. */
    private val desktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** Phase 11 D-e: the getUserMedia shim, injected into every main-frame HTML response. */
    private val shimJs: String by lazy {
        context.assets.open("phase11/camera-shim.js").bufferedReader().readText()
    }
    /** True once a page carrying the injected shim has been served on this WebView. */
    @Volatile private var shimServed = false

    /** code-server login state (only used when [codeServer]). */
    private var serverPort = 0
    private var pendingFolder: String? = null

    val web: WebView = @SuppressLint("SetJavaScriptEnabled") KeyWebView(context).apply {
        android.util.Log.i("Phase6", "BROWSER density ctx=" +
            context.resources.displayMetrics.density + " dpi=" +
            context.resources.displayMetrics.densityDpi)
        setBackgroundColor(Color.WHITE)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // The Phase-3 settings that rendered code-server (B-065); the shell Browser was
        // missing media + file-access parity.
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mediaPlaybackRequiresUserGesture = false
        // B-082: Play Store sends an Android WebView to intent://, which this Browser cannot
        // load. A desktop Chrome UA keeps it on https (and gives sites their desktop page).
        if (!codeServer) {
            settings.userAgentString = desktopUa
        }
        isFocusable = true
        isFocusableInTouchMode = true
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                v: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val req = request ?: return null
                if (!req.isForMainFrame || req.method != "GET") return null
                val scheme = req.url.scheme ?: return null
                if (scheme != "http" && scheme != "https") return null
                return try {
                    injectShimIntoMainFrame(req)
                } catch (t: Throwable) {
                    android.util.Log.i("Phase6", "BROWSER shim inject skipped: ${t.javaClass.simpleName}")
                    null
                }
            }

            override fun onPageFinished(v: WebView?, url: String?) {
                status.text = "loaded ${url ?: ""}"
                v?.evaluateJavascript(
                    "(function(){if(window.__udProbe)return 'probe-on';" +
                        "window.__udProbe=1;window.__udKeys=[];" +
                        "document.addEventListener('keydown',function(e){window.__udKeys.push(e.key);});" +
                        "return 'probe-installed';})()", null
                )
                // B-065: finish the login, then open the workspace with the documented deep
                // link. Only once: pendingFolder is cleared before the navigation.
                val folder = pendingFolder
                if (codeServer && folder != null && serverPort > 0 && url?.contains("folder=") != true) {
                    pendingFolder = null
                    val deep = "http://127.0.0.1:$serverPort/?folder=" +
                        android.net.Uri.encode(folder, "/")
                    status.text = "opening folder"
                    android.util.Log.i("Phase6", "VSCODE folder $deep")
                    v?.loadUrl(deep)
                }
            }
        }
    }

    /**
     * Phase 11 D-e: document-start shim injection without androidx.webkit (not in the offline
     * Gradle cache, and dl.google.com is unreachable). The main-frame HTML is fetched here and
     * re-served with the shim as the first script in <head>, so it runs before page scripts;
     * CSP is relaxed for this one response or an inline script would be blocked. Non-HTML,
     * redirects, errors and oversized bodies return null and load normally.
     */
    private fun injectShimIntoMainFrame(req: WebResourceRequest): WebResourceResponse? {
        val url = req.url.toString()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 5000
            readTimeout = 7000
            setRequestProperty("User-Agent", desktopUa)
            setRequestProperty("Accept", req.requestHeaders["Accept"] ?: "text/html,application/xhtml+xml")
            req.requestHeaders["Accept-Language"]?.let { setRequestProperty("Accept-Language", it) }
            CookieManager.getInstance().getCookie(url)?.let { setRequestProperty("Cookie", it) }
        }
        try {
            if (conn.responseCode != 200) return null
            val ctype = conn.contentType ?: ""
            if (!ctype.contains("text/html", ignoreCase = true)) return null
            var body = ByteArray(0)
            conn.inputStream.use { ins ->
                val buf = ByteArray(8192)
                val chunks = ArrayList<ByteArray>()
                var total = 0
                while (total < 3_000_000) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    chunks.add(buf.copyOf(n)); total += n
                }
                body = ByteArray(total)
                var off = 0
                for (c in chunks) { System.arraycopy(c, 0, body, off, c.size); off += c.size }
            }
            if (body.isEmpty()) return null
            val charsetName = Regex("charset=([A-Za-z0-9._-]+)", RegexOption.IGNORE_CASE)
                .find(ctype)?.groupValues?.get(1) ?: "UTF-8"
            val charset = try { java.nio.charset.Charset.forName(charsetName) } catch (_: Throwable) { Charsets.UTF_8 }
            val html = String(body, charset)
            val at = when {
                html.indexOf("<head", 0, true) >= 0 ->
                    html.indexOf('>', html.indexOf("<head", 0, true)) + 1
                html.indexOf("<html", 0, true) >= 0 ->
                    html.indexOf('>', html.indexOf("<html", 0, true)) + 1
                else -> 0
            }
            val injected = html.substring(0, at) +
                "<script>/*__udCamShim*/$shimJs</script>" +
                html.substring(at)
            shimServed = true
            android.util.Log.i("Phase6", "BROWSER shim injected ${url.take(80)} bytes=${body.size}")
            return WebResourceResponse(
                "text/html", charset.name(), 200, "OK",
                mapOf("Content-Security-Policy" to "default-src * 'unsafe-inline' 'unsafe-eval' data: blob:"),
                ByteArrayInputStream(injected.toByteArray(Charsets.UTF_8))
            )
        } finally {
            conn.disconnect()
        }
    }

    /** True when the current page carries the injected shim (camera frames may be pushed). */
    fun camShimActive(): Boolean = udPage == "camtest" || shimServed

    private var history = ArrayList<String>()
    private var pos = -1

    /** Phase 11: the in-APK page currently shown ("camtest", "tone"), or null. */
    var udPage: String? = null
        private set
    /**
     * Added to every page (init). The shim asks for the PC camera and microphone with
     * start()/stop(); reports are counters only (no frame, no audio). Methods are the whole
     * surface: @JavascriptInterface only, so a page cannot reach anything else through it.
     */
    private val udBridge = object {
        @JavascriptInterface
        fun start() {
            android.util.Log.i("Phase11Cam", "P11CAMPAGE start requested")
            Phase5Dispatch.session()?.sendControl("cam-on")
        }

        @JavascriptInterface
        fun stop() {
            android.util.Log.i("Phase11Cam", "P11CAMPAGE stop requested")
            Phase5Dispatch.session()?.sendControl("cam-off")
        }

        @JavascriptInterface
        fun report(json: String) {
            android.util.Log.i("Phase11Cam", "P11CAMSTATS $json")
        }
    }

    /**
     * Load one of our in-APK pages into this WebView. The shim script is inlined into the
     * page (this is our own local page), so no document-start hook or file access is needed.
     */
    fun loadUdPage(name: String): String {
        val page = name.trim().lowercase()
        val asset = when (page) {
            "camtest" -> "phase11/camera-shim.html"
            "tone" -> "phase11/tone.html"
            else -> return "no page [$name]"
        }
        var html = context.assets.open(asset).bufferedReader().readText()
        if (page == "camtest") {
            val js = context.assets.open("phase11/camera-shim.js").bufferedReader().readText()
            html = html.replace("<script src=\"camera-shim.js\"></script>", "<script>\n$js\n</script>")
        }
        udPage = page
        if (pos >= 0 && pos < history.size - 1) history = ArrayList(history.subList(0, pos + 1))
        history.add("ud:$page"); pos = history.size - 1
        address.setText("ud:$page")
        status.text = "loading ud:$page"
        web.loadDataWithBaseURL("https://ud.local/", html, "text/html; charset=utf-8", "UTF-8", null)
        android.util.Log.i("Phase6", "BROWSER ud:$page")
        return "opened ud:$page"
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFF101418.toInt())
        val bar = LinearLayout(context).apply { orientation = HORIZONTAL }
        bar.addView(ShellUi.button(context, "<") { back() })
        bar.addView(ShellUi.button(context, ">") { forward() })
        bar.addView(ShellUi.button(context, "Reload") { web.reload() })
        bar.addView(ShellUi.button(context, "Go") { go() })
        bar.addView(address, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(web, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        // Phase 11 D-e: document-start shim pages reach us through this on every page.
        web.addJavascriptInterface(udBridge, "UdBridge")
        addView(status)
        // Enter from the PC keyboard must navigate, not insert a newline into the address.
        address.setOnKeyListener { _, code, e ->
            if (code == android.view.KeyEvent.KEYCODE_ENTER && e.action == android.view.KeyEvent.ACTION_DOWN) {
                go(); true
            } else false
        }
        // B-075: open the window before code-server is up and it must still work. Start the
        // server (or reuse a healthy one), then load the page; the title shows the wait.
        if (codeServer) {
            status.text = "Starting VS Code..."
            post { ensureCodeServer(defaultCodeFolder()) }
        }
    }

    /** The Phase-3 workspace code-server serves; the window opens this when nothing is given. */
    fun defaultCodeFolder(): String =
        java.io.File(Phase3Native.dir(context), "workspace").absolutePath

    /**
     * B-075: make sure code-server is running, then log in and load the page. A healthy
     * session is reused; otherwise the server is started on a worker thread (the window stays
     * responsive and says "Starting VS Code..."). Waits at most [CodeServerGate.MAX_MS];
     * logs every step.
     */
    fun ensureCodeServer(folder: String = defaultCodeFolder()) {
        status.text = "Starting VS Code..."
        Thread({
            val sf = Phase3Server.sessionFile(context)
            var code = 0
            var port = -1
            if (sf.isFile) {
                sf.useLines { ls ->
                    for (l in ls) if (l.startsWith("port=")) {
                        port = l.substringAfter('=').trim().toIntOrNull() ?: -1
                    }
                }
                if (port > 0) code = Phase3Server.healthz(port).first
            }
            P7Log.say(context, "VSCODE gate session=${sf.isFile} port=$port healthz=$code")
            if (CodeServerGate.needsStart(sf.isFile && port > 0, code)) {
                P7Log.say(context, "VSCODE starting code-server (wait up to ${CodeServerGate.MAX_MS} ms)")
                val s = Phase3Server.start(context, CodeServerGate.MAX_MS)
                if (s == null) {
                    post { status.text = "code-server did not start (see p3.log)" }
                    P7Log.say(context, "VSCODE start FAILED")
                    return@Thread
                }
                P7Log.say(context, "VSCODE start ok port=${s.port} health=${s.startMs} ms")
            }
            post { openCodeServer(folder) }
        }, "p7-cs-gate").start()
    }

    fun go() {
        val typed = address.text.toString().trim()
        if (typed.isEmpty()) return
        // Phase 11: our own pages ship in the APK and are inlined into the page (section 4).
        if (typed.startsWith("ud:")) {
            status.text = loadUdPage(typed.removePrefix("ud:"))
            return
        }
        val url = BrowserUrl.normalize(typed)
        status.text = "loading $url"
        if (pos >= 0 && pos < history.size - 1) history = ArrayList(history.subList(0, pos + 1))
        history.add(url); pos = history.size - 1
        address.setText(url)
        web.loadUrl(url)
        android.util.Log.i("Phase6", "BROWSER go $url")
    }

    /** Phase 8 App Center: open a catalog web app's URL in this window. */
    fun openUrl(url: String) {
        address.setText(url)
        go()
    }

    /**
     * Log in to the running code-server exactly like Phase3CodeActivity did (a POST to
     * `/login` sets the session cookie) and open [folder] through the `?folder=` deep link.
     * B-065: the shell Browser used to navigate with `?password=` in the URL, which never
     * logs a code-server session in.
     */
    fun openCodeServer(folder: String): String {
        val f = Phase3Server.sessionFile(context)
        if (!f.isFile) return "code-server not started (no session file)"
        var port = -1
        var pw = ""
        f.useLines { ls ->
            for (l in ls) {
                when {
                    l.startsWith("port=") -> port = l.substringAfter('=').trim().toIntOrNull() ?: -1
                    l.startsWith("password=") -> pw = l.substringAfter('=').trim()
                }
            }
        }
        if (port <= 0 || pw.isEmpty()) return "bad session file"
        serverPort = port
        pendingFolder = folder
        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        val login = "http://127.0.0.1:$port/login"
        status.text = "logging in to code-server :$port"
        web.postUrl(login, "password=$pw".toByteArray())
        android.util.Log.i("Phase6", "VSCODE postUrl $login folder=$folder")
        return "login :$port folder=$folder"
    }

    fun addressText(): String = address.text.toString()

    fun pageText(): String = status.text.toString()

    override fun contentText(): String = pageText()

    fun back() {
        if (pos > 0) { pos--; web.loadUrl(history[pos]) } else if (web.canGoBack()) web.goBack()
    }

    fun forward() {
        if (pos < history.size - 1) { pos++; web.loadUrl(history[pos]) } else if (web.canGoForward()) web.goForward()
    }

    /**
     * The B-039 probe: three keyboard routes into the page, each logged with its result.
     * Route 1 is a real KeyEvent, route 2 the input connection (what an IME does), route 3
     * the page's own `execCommand('insertText')`. The DOM keydown counter is read back
     * asynchronously into the log, so the route that works is a fact, not a guess.
     */
    fun probeKeyboard(payload: String): String {
        web.requestFocus()
        var handled = 0
        for (ch in payload) {
            val code = charToKeyCode(ch)
            val shift = ch.isUpperCase() || ch in "!@#$%^&*()_+{}|:\"<>?~"
            val meta = if (shift) android.view.KeyEvent.META_SHIFT_ON else 0
            val ev = android.view.KeyEvent(
                android.os.SystemClock.uptimeMillis(), android.os.SystemClock.uptimeMillis(),
                android.view.KeyEvent.ACTION_DOWN, code, 0, meta, -1, 0, 0,
                android.view.InputDevice.SOURCE_KEYBOARD
            )
            if (web.dispatchKeyEvent(ev)) handled++
            val up = android.view.KeyEvent(
                android.os.SystemClock.uptimeMillis(), android.os.SystemClock.uptimeMillis(),
                android.view.KeyEvent.ACTION_UP, code, 0, meta, -1, 0, 0,
                android.view.InputDevice.SOURCE_KEYBOARD
            )
            web.dispatchKeyEvent(up)
        }
        val ic = android.view.inputmethod.BaseInputConnection(web, true)
        ic.commitText(payload, 1)
        web.evaluateJavascript(
            "document.execCommand('insertText',false," + org.json.JSONObject.quote(payload) + ")",
            null
        )
        web.evaluateJavascript("(window.__udKeys||[]).length") { v ->
            android.util.Log.i("Phase6", "B039 probe payload=\"$payload\" handled=$handled keydowns=$v")
        }
        return "dispatch=$handled"
    }

    private var cachedKeys = 0

    /** Keydown events the page recorded (the DOM probe), fetched asynchronously. */
    fun keyCount(): Int {
        web.evaluateJavascript("(window.__udKeys||[]).length") { v ->
            v?.trim()?.trim('"')?.toIntOrNull()?.let { cachedKeys = it }
        }
        return cachedKeys
    }

    /** Last recorded keydown keys, for evidence. */
    fun keysSeen(cb: (String) -> Unit) {
        web.evaluateJavascript("JSON.stringify(window.__udKeys||[])") { v -> cb(v ?: "?") }
    }

    private fun charToKeyCode(ch: Char): Int = when {
        ch in 'a'..'z' -> android.view.KeyEvent.KEYCODE_A + (ch - 'a')
        ch in 'A'..'Z' -> android.view.KeyEvent.KEYCODE_A + (ch - 'A')
        ch in '0'..'9' -> android.view.KeyEvent.KEYCODE_0 + (ch - '0')
        ch == ' ' -> android.view.KeyEvent.KEYCODE_SPACE
        ch == '.' -> android.view.KeyEvent.KEYCODE_PERIOD
        ch == '/' -> android.view.KeyEvent.KEYCODE_SLASH
        ch == ':' -> android.view.KeyEvent.KEYCODE_SEMICOLON
        ch == '-' -> android.view.KeyEvent.KEYCODE_MINUS
        else -> 0
    }
}

/**
 * B-060: the address bar's URL rule, pure so it is unit-tested. A scheme is added only on
 * Go, and only when the text has none (the platform blocks cleartext http for targetSdk 36,
 * so a bare host becomes https). `http://` typed by the user is kept for loopback URLs.
 */
object BrowserUrl {
    fun normalize(typed: String): String {
        val t = typed.trim()
        if (t.isEmpty()) return ""
        return when {
            t.contains("://") -> t
            t.startsWith("about:") || t.startsWith("data:") -> t
            else -> "https://$t"
        }
    }
}

/**
 * B-075's decision, pure so it is unit-tested: reuse a code-server session only when its
 * recorded port answers /healthz with 200; otherwise start one (waiting up to [MAX_MS]).
 */
object CodeServerGate {
    const val POLL_MS = 500L
    const val MAX_MS = 90_000L

    fun needsStart(sessionUsable: Boolean, healthCode: Int): Boolean =
        !sessionUsable || healthCode != 200

    fun expired(startedMs: Long, nowMs: Long, maxMs: Long = MAX_MS): Boolean =
        nowMs - startedMs >= maxMs
}

/**
 * B-039: a WebView that reports window focus. Printable characters go through Chromium's own
 * input connection (the IME route an EditContext accepts); everything else stays a real key
 * event so shortcuts keep working.
 */
class KeyWebView(context: Context) : WebView(context) {

    /** The keycode whose down we sent through the input connection, so its up is swallowed. */
    private var lastCommitted = -1

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        android.util.Log.i("Phase6", "WEBVIEW attached density=" +
            resources.displayMetrics.density + " dpi=" + resources.displayMetrics.densityDpi)
    }

    override fun hasWindowFocus(): Boolean = true

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(true)
        isFocusable = true
        isFocusableInTouchMode = true
        if (!isFocused) requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // B-039 (found 19:12): VS Code 1.141's editor is an EditContext, not a textarea. Raw
        // keydowns reach the page (Ctrl+P opens Quick Open, the New File box takes text), but
        // Monaco's editor never inserts a character from them; the IME route (commitText) is
        // what an EditContext accepts. So printable characters go through the input connection
        // only - never both, or a textarea would get each char twice - while Ctrl/Alt
        // shortcuts, Enter, Backspace, Tab and arrows stay real key events (Ctrl+S, Ctrl+P
        // keep working).
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == lastCommitted) {
            lastCommitted = -1
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && !event.isCtrlPressed && !event.isAltPressed) {
            val ch = Phase5Protocol.keyCodeToChar(event.keyCode, event.metaState)
            if (ch != '\u0000') {
                // Chromium's connection, not a BaseInputConnection: only this one knows the
                // focused DOM editable (or EditContext). The old override returned a plain
                // BaseInputConnection, which cannot reach the page at all.
                val ic = super.onCreateInputConnection(android.view.inputmethod.EditorInfo())
                if (ic != null && ic.commitText(ch.toString(), 1)) {
                    lastCommitted = event.keyCode
                    android.util.Log.i("Phase6", "B039 route=ime ch='$ch' ic=${ic.javaClass.simpleName}")
                    return true
                }
                // B-078: VS Code's editor is an EditContext and exposes no InputConnection on
                // this WebView (ic=null after the editor gets the focus), so PC text died here.
                // Drive the page's own insertText instead - the same operation the IME performs
                // one layer up - one character per PC key press.
                evaluateJavascript(
                    "document.execCommand('insertText',false," +
                        org.json.JSONObject.quote(ch.toString()) + ")",
                    null
                )
                lastCommitted = event.keyCode
                android.util.Log.i("Phase6", "B039 route=js-insert ch='$ch'")
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

/** Settings: link status, the video profile in use, and About with versions. */
class SettingsView(context: Context) : ScrollView(context) {

    private val col = ShellUi.column(context, 0xFF1F2531.toInt())

    init {
        addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        SettingsDispatch.bind(this)
        col.addView(ShellUi.label(context, "Settings", 26f, bold = true))
        col.addView(ShellUi.label(context, "Link: waiting for the receiver's status line", 19f))
        col.addView(ShellUi.label(context, "Video: 1920x1080 @ 60 fps paced (H.264, hardware encoder)", 19f))
        col.addView(ShellUi.label(context, "Input: mouse (real MotionEvents) + keyboard to the focused window", 19f))
        col.addView(ShellUi.label(context, "About", 26f, bold = true))
        col.addView(ShellUi.label(context, "unified-desktop (working codename) - Phase 6 shell", 19f))
        col.addView(ShellUi.label(context, "Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})", 19f))
        col.addView(ShellUi.label(context, "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}", 19f))
        col.addView(ShellUi.label(context, "Shell build: phase6", 19f))
    }

    /** Called by the link with the live numbers, so Settings shows the real profile. */
    fun setLinkStatus(s: String) {
        (col.getChildAt(1) as? TextView)?.text = "Link: $s"
    }

    fun text(): String = (0 until col.childCount).joinToString("\n") { (col.getChildAt(it) as? TextView)?.text ?: "" }
}

/** Keeps the live Settings window reachable for the harness. */
object SettingsDispatch {
    @Volatile private var view: SettingsView? = null
    fun bind(v: SettingsView?) { view = v }
    fun text(): String = view?.text() ?: "(no settings window)"
    fun setStatus(s: String) = view?.setLinkStatus(s) ?: Unit
}

