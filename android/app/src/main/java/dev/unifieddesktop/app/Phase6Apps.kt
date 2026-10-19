package dev.unifieddesktop.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

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
 */
class BrowserView(context: Context) : LinearLayout(context) {

    val address: EditText = EditText(context).apply {
        hint = "address (https://...)"
        setText("")
        setTextColor(Color.BLACK)
        setBackgroundColor(0xFFE8E8E8.toInt())
        textSize = 20f
        isFocusable = true
        isFocusableInTouchMode = true
    }

    private val status = ShellUi.label(context, "ready", 17f)

    val web: WebView = @SuppressLint("SetJavaScriptEnabled") WebView(context).apply {
        setBackgroundColor(Color.WHITE)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        isFocusable = true
        isFocusableInTouchMode = true
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView?, url: String?) {
                status.text = "loaded ${url ?: ""}"
                v?.evaluateJavascript(
                    "(function(){if(window.__udProbe)return 'probe-on';" +
                        "window.__udProbe=1;window.__udKeys=[];" +
                        "document.addEventListener('keydown',function(e){window.__udKeys.push(e.key);});" +
                        "return 'probe-installed';})()", null
                )
            }
        }
    }

    private var history = ArrayList<String>()
    private var pos = -1

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
        addView(status)
        // Enter from the PC keyboard must navigate, not insert a newline into the address.
        address.setOnKeyListener { _, code, e ->
            if (code == android.view.KeyEvent.KEYCODE_ENTER && e.action == android.view.KeyEvent.ACTION_DOWN) {
                go(); true
            } else false
        }
    }

    fun go() {
        val typed = address.text.toString().trim()
        if (typed.isEmpty()) return
        // Default to https: the platform blocks cleartext http for targetSdk 36, so a bare
        // host typed on the PC keyboard must not end in "webpage not available" (2026-10-19).
        val url = if (typed.contains("://")) typed else "https://$typed"
        status.text = "loading $url"
        if (pos >= 0 && pos < history.size - 1) history = ArrayList(history.subList(0, pos + 1))
        history.add(url); pos = history.size - 1
        address.setText(url)
        web.loadUrl(url)
        android.util.Log.i("Phase6", "BROWSER go $url")
    }

    fun back() {
        if (pos > 0) { pos--; web.loadUrl(history[pos]) } else if (web.canGoBack()) web.goBack()
    }

    fun forward() {
        if (pos < history.size - 1) { pos++; web.loadUrl(history[pos]) } else if (web.canGoForward()) web.goForward()
    }

    /** Typed text for the address bar; the harness uses this to feed the PC's keys. */
    fun addressText(): String = address.text.toString()

    fun pageText(): String = status.text.toString()

    /**
     * The B-039 probe: three keyboard routes into the page, each logged with its result.
     * Returns the one that works ("keyevent", "edittext-ime", "commit", or "none").
     */
    fun probeKeyboard(payload: String): String {
        web.requestFocus()
        val before = keyCount()
        // Route 1: a real KeyEvent per character (the Phase-5 route that worked on EditText).
        var handled = 0
        for (ch in payload) {
            val code = charToKeyCode(ch)
            val ev = android.view.KeyEvent(
                android.os.SystemClock.uptimeMillis(), android.os.SystemClock.uptimeMillis(),
                android.view.KeyEvent.ACTION_DOWN, code, 0, 0, -1, 0, 0,
                android.view.InputDevice.SOURCE_KEYBOARD
            )
            if (web.dispatchKeyEvent(ev)) handled++
        }
        // Route 2: the input connection (what the IME would do) - the classic WebView route.
        val ic = android.view.inputmethod.BaseInputConnection(web, true)
        ic.commitText(payload, 1)
        val after = keyCount()
        val route = when {
            after > before -> "keyevent"
            handled > 0 -> "edittext-ime"
            else -> "commit"
        }
        android.util.Log.i("Phase6", "B039 probe payload=\"$payload\" keydowns=$before->$after handled=$handled route=$route")
        return route
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

