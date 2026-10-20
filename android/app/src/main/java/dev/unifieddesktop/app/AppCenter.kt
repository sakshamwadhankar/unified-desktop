package dev.unifieddesktop.app

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * The Phase-8 App Center: a search box, the catalog's apps, and the picked version (kind +
 * rating) for the selected app. Actions are real: builtin/web apps open their shell window,
 * Android apps are imported into the guest engine, Notepad++ installs from its pinned winget
 * manifest (URL + SHA-256, the Phase-2 pin), and "Open any .exe" runs a user-chosen file as
 * data under Wine. A version rated "Not yet" is shown with its reason and never installed.
 *
 * The view is pure UI over [Catalog] (the picker is unit-tested) and talks to the shell by
 * walking up to [ShellRoot]; it has no dependency on the link or the harness.
 */
class AppCenterView(context: Context) : LinearLayout(context) {

    private val apps: List<CatalogApp> = try {
        CatalogLoader.load(context)
    } catch (t: Throwable) {
        P7Log.say(context, "APPCTR catalog fail ${t.javaClass.simpleName}: ${t.message}")
        emptyList()
    }

    private val search = EditText(context).apply {
        hint = "Search apps (name or alias)"
        setTextColor(Color.BLACK)
        setBackgroundColor(0xFFE8E8E8.toInt())
        textSize = 20f
        setPadding(14, 8, 14, 8)
        isSingleLine = true
        isFocusable = true
        isFocusableInTouchMode = true
    }
    private val listCol = LinearLayout(context).apply { orientation = VERTICAL }
    private val detail = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 17f
        setPadding(10, 6, 10, 6)
        setBackgroundColor(0xFF1E2430.toInt())
    }
    private val status = TextView(context).apply {
        setTextColor(0xFF9AE6B4.toInt())
        textSize = 16f
        setPadding(10, 4, 10, 4)
    }
    private val exePath = EditText(context).apply {
        hint = "/sdcard/Download/program.exe"
        setTextColor(Color.BLACK)
        setBackgroundColor(0xFFE8E8E8.toInt())
        textSize = 18f
        setPadding(12, 6, 12, 6)
        isSingleLine = true
    }

    private var selected: CatalogApp? = null
    private var picked: CatalogVersion? = null
    private val engine by lazy { EngineBridge(context) }
    private val installBtn = ShellUi.button(context, "Install") { installSelected() }

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFF141821.toInt())
        setPadding(16, 6, 16, 6)

        addView(ShellUi.label(context, "App Center", 24f, bold = true))
        addView(search, LayoutParams(LayoutParams.MATCH_PARENT, 48))
        addView(ScrollView(context).apply {
            addView(listCol)
        }, LayoutParams(LayoutParams.MATCH_PARENT, 200))
        addView(detail, LayoutParams(LayoutParams.MATCH_PARENT, 118))
        addView(actionRow(), LayoutParams(LayoutParams.MATCH_PARENT, 52))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(exePath, LayoutParams(0, 46, 1f))
            addView(ShellUi.button(context, "Open any .exe") { runExe(exePath.text.toString()) })
        }, LayoutParams(LayoutParams.MATCH_PARENT, 48))
        addView(status)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh(s?.toString().orEmpty())
        })
        P7Log.say(context, "APPCTR loaded apps=${apps.size} catalog=${apps.joinToString(",") { it.id }}")
        refresh("")
        selected = apps.firstOrNull()
        renderDetail()
    }

    private fun actionRow(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        addView(installBtn)
        addView(ShellUi.button(context, "Open") { openSelected() })
        addView(ShellUi.button(context, "Uninstall") { uninstallSelected() })
        gravity = Gravity.START
    }

    // ------------------------------------------------------------------ list

    private fun refresh(query: String) {
        listCol.removeAllViews()
        val rows = Catalog.search(apps, query)
        for (app in rows) {
            val best = Catalog.pick(app)
            val label = buildString {
                append(app.name)
                if (best != null) append("   ·  ${best.kind} ${best.version}  ·  ${best.rating}")
                else append("   ·  no version")
            }
            val row = TextView(context).apply {
                text = label
                textSize = 18f
                setPadding(12, 6, 12, 6)
                isClickable = true
                setTextColor(if (best != null && Catalog.isUsable(best)) Color.WHITE else 0xFF9AA0AA.toInt())
                setOnClickListener {
                    selected = app
                    picked = best
                    renderDetail()
                    P7Log.say(context, "APPCTR select id=${app.id} picked=${best?.kind}/${best?.version}/${best?.rating}")
                }
            }
            listCol.addView(row)
        }
        if (rows.isEmpty()) listCol.addView(ShellUi.label(context, "no match", 18f))
        if (selected != null && rows.none { it.id == selected?.id }) {
            // keep the detail of the selected app even when the filter hides its row
        }
        status.text = "${rows.size} of ${apps.size} apps"
        P7Log.say(context, "APPCTR search q=\"$query\" rows=${rows.size}")
    }

    private fun renderDetail() {
        val app = selected ?: run { detail.text = "No app selected"; return }
        val v = picked ?: Catalog.pick(app)
        picked = v
        if (v == null) {
            detail.text = "${app.name}\nno versions in the catalog"
            return
        }
        val usable = Catalog.isUsable(v)
        val installer = installerState(v)
        val have = Installed.version(context, app.id)
        installBtn.text = if (v.kind == "windows" || v.kind == "linux") {
            Winget.actionLabel(v.version, have)
        } else {
            "Install"
        }
        detail.text = buildString {
            append(app.name).append('\n')
            append("version ").append(v.version).append("  ·  ").append(v.kind)
                .append("  ·  ").append(v.rating).append('\n')
            append("installer: ").append(installer).append('\n')
            if (have.isNotEmpty()) append("installed: ").append(have).append('\n')
            if (!usable) append("Not yet: ").append(v.note.ifEmpty { v.rating }).append('\n')
            else if (v.note.isNotEmpty()) append(v.note).append('\n')
            append("fetch: ").append(v.fetch.ifEmpty { "-" })
        }
    }

    private fun installerState(v: CatalogVersion): String = when {
        v.kind == "builtin" || v.kind == "web" -> "none needed"
        v.kind == "android" && v.packageName.isNotEmpty() -> "Android import if installed, else Play page"
        v.kind == "windows" && v.source == "winget" && v.sha256.isNotEmpty() ->
            "winget: ${v.note.removePrefix("winget: ").ifEmpty { v.version }} (manifest URL + SHA-256)"
        v.kind == "linux" && v.source == "deb" && v.sha256.isNotEmpty() ->
            "Debian .deb (URL + SHA-256 -> rootfs)"
        v.kind == "linux" -> "shipped with the app"
        else -> "installer not available yet"
    }

    // ------------------------------------------------------------------ actions

    /** Select by id (harness + tests); returns the detail summary. */
    fun selectId(id: String): String {
        val app = apps.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: return "no app [$id]"
        selected = app
        picked = Catalog.pick(app)
        renderDetail()
        return summary()
    }

    /** One line for the harness: name/version/kind/rating/installer. */
    fun summary(): String {
        val app = selected ?: return "(none)"
        val v = picked ?: Catalog.pick(app) ?: return "${app.name}: no version"
        return "${app.name} | ${v.version} | ${v.kind} | ${v.rating} | installer=${installerState(v)} | usable=${Catalog.isUsable(v)}"
    }

    fun visibleNames(): String = Catalog.search(apps, search.text.toString()).joinToString(",") { it.name }

    fun setQuery(q: String): String {
        search.setText(q)
        search.setSelection(search.text.length)
        return "${Catalog.search(apps, q).size} rows: ${visibleNames()}"
    }

    /** Install the selected app. Fast kinds answer now; downloads/imports run on a thread. */
    fun installSelected(): String {
        val app = selected ?: return "no app selected"
        val v = picked ?: Catalog.pick(app) ?: return "no version"
        if (!Catalog.isUsable(v)) return "Not yet: ${v.note.ifEmpty { v.rating }}"
        return when (v.kind) {
            "builtin", "web" -> {
                say("install ${app.id}: ${v.kind} needs no install")
                "nothing to install (${v.kind})"
            }
            "android" -> {
                if (v.packageName.isEmpty()) return "installer not available yet: no package name"
                val onPhone = try {
                    context.packageManager.getPackageInfo(v.packageName, 0); true
                } catch (t: Throwable) {
                    false
                }
                if (!onPhone) {
                    // B-092: the same missing-package policy as Open. One Play URL, no APK
                    // download and no guest window; the install tap/sign-in are the owner's.
                    val s = shell() ?: return "no shell"
                    val url = AndroidOpen.playUrl(OpenRoute.of(app, v))
                    val w = s.openApp(ShellRoot.App.BROWSER)
                    (w.content as? BrowserView)?.openUrl(url)
                    val msg = "${app.name} is not installed; opened its Google Play page."
                    status.text = msg
                    say("install ${app.id}: not on the phone, opened Play page $url")
                    return msg
                }
                say("install ${app.id}: import ${v.packageName}")
                Thread {
                    val r = engine.importInstalled(v.packageName)
                    if (r.ok) {
                        Installed.record(context, app.id, v.version)
                        // B-090: a window left from the previous import points at a dead
                        // display; close it so the next Open builds a fresh one.
                        post { shell()?.closeGuestWindow(v.packageName) }
                    }
                    post {
                        status.text = "install ${v.packageName}: ${if (r.ok) "ok" else "failed"} ${r.message ?: ""}"
                        shell()?.refreshInstalledRows()
                    }
                    P7Log.say(context, "APPCTR install pkg=${v.packageName} ok=${r.ok} msg=${r.message}")
                }.start()
                "importing ${v.packageName}..."
            }
            "windows" -> {
                if (v.source != "winget" || v.sha256.isEmpty() || !v.fetch.startsWith("https://")) {
                    "installer not available yet: no pinned winget manifest"
                } else {
                    say("install ${app.id}: winget manifest ${v.fetch} sha=${v.sha256.take(12)}...")
                    Thread {
                        val r = try {
                            Installers.installWinget(context, app.id, app.name, v) { msg ->
                                post { status.text = msg }
                            }
                        } catch (t: Throwable) {
                            android.util.Log.e("Phase6", "APPCTR install ${app.id} fail", t)
                            P7Log.say(context, "APPCTR install ${app.id} fail ${t.javaClass.simpleName}: ${t.message}")
                            "failed: ${t.message}"
                        }
                        post {
                            status.text = "install ${app.name}: $r"
                            shell()?.refreshInstalledRows()
                            if (selected?.id == app.id) renderDetail()
                        }
                        P7Log.say(context, "APPCTR install ${app.id} -> $r")
                    }.start()
                    "winget: ${v.note.removePrefix("winget: ").ifEmpty { v.version }}"
                }
            }
            "linux" -> {
                if (v.source == "deb" && v.sha256.isNotEmpty() && v.fetch.startsWith("https://")) {
                    say("install ${app.id}: deb ${v.fetch} sha=${v.sha256.take(12)}...")
                    Thread {
                        val r = try {
                            Installers.installDeb(context, app.id, v) { msg ->
                                post { status.text = msg }
                            }
                        } catch (t: Throwable) {
                            P7Log.say(context, "APPCTR deb ${app.id} fail ${t.javaClass.simpleName}: ${t.message}")
                            "failed: ${t.message}"
                        }
                        post { status.text = "install ${app.name}: $r" }
                        P7Log.say(context, "APPCTR install ${app.id} -> $r")
                    }.start()
                    "downloading + unpacking ${v.version}..."
                } else {
                    "shipped with the app (no download)"
                }
            }
            else -> "installer not available yet"
        }
    }

    /**
     * Open the selected app through [ShellRoot.openCatalogApp], the same route the start
     * menu's installed rows use (B-083), so both can never drift apart.
     */
    fun openSelected(): String {
        val app = selected ?: return "no app selected"
        val v = picked ?: return "no version"
        if (!Catalog.isUsable(v)) return "Not yet: ${v.note.ifEmpty { v.rating }}"
        val s = shell() ?: return "no shell"
        val r = s.openCatalogApp(app.id)
        status.text = r
        say("open ${app.id}: $r")
        return r
    }

    fun uninstallSelected(): String {
        val app = selected ?: return "no app selected"
        val v = picked ?: Catalog.pick(app) ?: return "no version"
        return when (v.kind) {
            "android" -> {
                val pkg = v.packageName.ifEmpty { app.packageName }
                if (pkg.isEmpty()) return "no package name"
                val r = engine.uninstallGuest(pkg)
                if (r.ok) {
                    Installed.clear(context, app.id)
                    // B-090: do not leave the old TextureView/display for the next import.
                    shell()?.closeGuestWindow(pkg)
                    shell()?.refreshInstalledRows()
                    renderDetail()
                }
                say("uninstall ${app.id} pkg=$pkg ok=${r.ok} msg=${r.message}")
                "uninstall $pkg: ${if (r.ok) "ok" else "failed"} ${r.message ?: ""}"
            }
            "windows" -> {
                val rec = Installed.read(context)[app.id]
                Installers.uninstallRefusal(rec)?.let { return it }
                say("uninstall ${app.id} productCode=${rec!!.productCode}")
                // B-088: close the program (and its X11 frame) before msiexec runs; a live
                // client kept its window and content alive after a successful uninstall.
                Phase2Wine.closeCatalogApps(context)
                shell()?.closeX11Window()
                Thread {
                    val r = try {
                        Installers.uninstallWinget(context, app.id) { msg ->
                            post { status.text = msg }
                        }
                    } catch (t: Throwable) {
                        P7Log.say(context, "APPCTR uninstall ${app.id} fail ${t.javaClass.simpleName}: ${t.message}")
                        "failed: ${t.message}"
                    }
                    post {
                        status.text = "uninstall ${app.name}: $r"
                        shell()?.refreshInstalledRows()
                        if (selected?.id == app.id) renderDetail()
                    }
                    P7Log.say(context, "APPCTR uninstall ${app.id} -> $r")
                }.start()
                "uninstalling ${app.name}..."
            }
            "builtin" -> "cannot uninstall: built into the app"
            "web" -> "nothing to uninstall (web)"
            else -> "cannot uninstall: the Linux rootfs copy is shared, not per-app"
        }
    }

    /** "Open any .exe": run a user-chosen file as data under Wine. */
    fun runExe(path: String): String {
        val p = path.trim()
        if (p.isEmpty()) return "enter a path first"
        val f = File(p)
        if (!f.isFile) return "no such file: $p"
        say("open exe $p bytes=${f.length()}")
        status.text = "starting $p (Wine)..."
        // B-093: openExe reuses the one X11 frame, so rename it here; otherwise the previous
        // catalog app's title stays above the new program. UI thread, before Wine starts.
        val title = X11Title.exeTitle(f.name)
        shell()?.openApp(ShellRoot.App.X11, title = title)
        say("exe frame title \"$title\"")
        Thread {
            try {
                // boot() reaps every process of ours, Xvfb included, so a running desktop would
                // be torn down and left reconnecting (B-084). Only boot when Wine is down.
                if (!Phase2X11.xvfbRunning() || !Phase2Wine.wineserverAlive(context)) {
                    Phase2Wine.boot(context, null)
                }
                val ok = Phase2Wine.openExe(context, p)
                post { status.text = if (ok) "started $p" else "failed to start $p (see p7.log)" }
            } catch (t: Throwable) {
                post { status.text = "exe failed: ${t.message}" }
                P7Log.say(context, "APPCTR exe fail ${t.javaClass.simpleName}: ${t.message}")
            }
        }.start()
        return "starting $p ..."
    }

    private fun say(what: String) = P7Log.say(context, "APPCTR $what")

    private fun shell(): ShellRoot? {
        var p: android.view.ViewParent? = parent
        while (p != null) {
            if (p is ShellRoot) return p
            p = p.parent
        }
        return null
    }
}
