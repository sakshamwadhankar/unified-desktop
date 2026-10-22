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

    private val search = shellField(context, "Search apps by name or alias")
    private val listCol = LinearLayout(context).apply { orientation = VERTICAL }
    private val detailTitle = TextView(context).apply {
        setTextColor(Theme.TEXT)
        Theme.px(this, Theme.TITLE)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val detailBody = TextView(context).apply {
        setTextColor(Theme.TEXT_SECONDARY)
        Theme.px(this, Theme.SMALL)
        maxLines = 4
        ellipsize = android.text.TextUtils.TruncateAt.END
        setLineSpacing(2f, 1f)
    }
    private val status = TextView(context).apply {
        setTextColor(Theme.TEXT_DIM)
        Theme.px(this, Theme.CAPTION)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(2, 2, 2, 2)
    }
    private val exePath = shellField(context, "Path to a .exe under /sdcard/Download")

    private var selected: CatalogApp? = null
    private var picked: CatalogVersion? = null
    private val engine by lazy { EngineBridge(context) }
    private val installBtn = shellButton(context, "Install", primary = true) { installSelected() }
    private val openBtn = shellButton(context, "Open") { openSelected() }
    private val uninstallBtn = shellButton(context, "Uninstall", danger = true) { uninstallSelected() }
    private val catalogTab = shellChip(context, "Catalog", Theme.TEXT, Theme.alpha(Theme.ACCENT, 50))
    private val installedTab = shellChip(context, "Installed", Theme.TEXT_DIM, Theme.SURFACE_SUNKEN)
    private var tab = 0

    init {
        orientation = VERTICAL
        setBackgroundColor(Theme.SURFACE)
        setPadding(14, 8, 14, 8)

        addView(TextView(context).apply {
            text = "App Center"
            setTextColor(Theme.TEXT)
            Theme.px(this, Theme.HEADING)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        addView(TextView(context).apply {
            text = "Windows, Linux and Android apps"
            setTextColor(Theme.TEXT_DIM)
            Theme.px(this, Theme.CAPTION)
        })
        addView(search, LayoutParams(LayoutParams.MATCH_PARENT, 34).apply { topMargin = 6 })
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(catalogTab, LayoutParams(LayoutParams.WRAP_CONTENT, 24))
            addView(installedTab, LayoutParams(LayoutParams.WRAP_CONTENT, 24).apply { marginStart = 6 })
            catalogTab.setOnClickListener { setTab(0) }
            installedTab.setOnClickListener { setTab(1) }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 6 })
        addView(ScrollView(context).apply {
            isFillViewport = true
            addView(listCol)
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = 6 })
        addView(shellCard(context).apply {
            addView(detailTitle)
            addView(detailBody, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = 2
            })
        }, LayoutParams(LayoutParams.MATCH_PARENT, 100).apply { topMargin = 6 })
        addView(actionRow(), LayoutParams(LayoutParams.MATCH_PARENT, 34).apply { topMargin = 6 })
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(exePath, LayoutParams(0, 28, 1f))
            addView(shellButton(context, "Open .exe") { runExe(exePath.text.toString()) }, LayoutParams(100, 28).apply {
                marginStart = 6
            })
        }, LayoutParams(LayoutParams.MATCH_PARENT, 30).apply { topMargin = 6 })
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, 18).apply { topMargin = 2 })

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh(s?.toString().orEmpty())
        })
        styleTab()
        P7Log.say(context, "APPCTR loaded apps=${apps.size} catalog=${apps.joinToString(",") { it.id }}")
        selected = apps.firstOrNull()
        refresh("")
        renderDetail()
    }

    private fun setTab(t: Int) {
        tab = t
        styleTab()
        refresh(search.text.toString())
    }

    /** The two chips are the only "tabs"; the selected one is accent-tinted. */
    private fun styleTab() {
        catalogTab.setTextColor(if (tab == 0) Theme.TEXT else Theme.TEXT_DIM)
        catalogTab.background = shellRounded(
            if (tab == 0) Theme.alpha(Theme.ACCENT, 55) else Theme.SURFACE_SUNKEN, 0, 9f,
        )
        installedTab.setTextColor(if (tab == 1) Theme.TEXT else Theme.TEXT_DIM)
        installedTab.background = shellRounded(
            if (tab == 1) Theme.alpha(Theme.ACCENT, 55) else Theme.SURFACE_SUNKEN, 0, 9f,
        )
    }

    private fun actionRow(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(installBtn, LayoutParams(96, 32))
        addView(openBtn, LayoutParams(70, 32).apply { marginStart = 6 })
        addView(uninstallBtn, LayoutParams(96, 32).apply { marginStart = 6 })
    }

    // ------------------------------------------------------------------ list

    private fun refresh(query: String) {
        listCol.removeAllViews()
        if (tab == 1) {
            refreshInstalled()
            return
        }
        val rows = Catalog.search(apps, query)
        for (app in rows) {
            listCol.addView(appCard(app), LayoutParams(LayoutParams.MATCH_PARENT, 54).apply {
                bottomMargin = 6
            })
        }
        if (rows.isEmpty()) listCol.addView(emptyLabel("No apps match \"$query\""))
        status.text = "${rows.size} of ${apps.size} apps"
        P7Log.say(context, "APPCTR search q=\"$query\" rows=${rows.size}")
    }

    /** One catalog card: icon tile, name, kind/version, and the rating or installed badge. */
    private fun appCard(app: CatalogApp): LinearLayout {
        val best = Catalog.pick(app)
        val have = Installed.version(context, app.id)
        val card = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = if (selected?.id == app.id) {
                shellRounded(Theme.alpha(Theme.ACCENT, 30), Theme.ACCENT, Theme.RADIUS_CARD)
            } else {
                shellRounded(Theme.SURFACE_RAISED, Theme.BORDER, Theme.RADIUS_CARD)
            }
            setPadding(10, 8, 10, 8)
            isClickable = true
            setOnClickListener {
                selected = app
                picked = Catalog.pick(app)
                renderDetail()
                refresh(search.text.toString())
                P7Log.say(context, "APPCTR select id=${app.id} picked=${best?.kind}/${best?.version}/${best?.rating}")
            }
        }
        val kind = when {
            app.id == "vscode" -> DeskIcon.CODE
            app.id == "files" || app.id == "browser" || app.id == "settings" || app.id == "appcenter" -> DeskIcon.WINDOW
            app.versions.any { it.kind == "android" } -> DeskIcon.PHONE
            app.versions.any { it.kind == "linux" } -> DeskIcon.TERMINAL
            app.versions.any { it.kind == "windows" } -> DeskIcon.GRID
            else -> DeskIcon.GLOBE
        }
        val tint = when (kind) {
            DeskIcon.PHONE -> Theme.SUCCESS
            DeskIcon.TERMINAL -> Theme.WARNING
            DeskIcon.WINDOW -> Theme.ACCENT
            else -> Theme.ACCENT
        }
        card.addView(AppIconView(context, kind, tint, true), LayoutParams(40, 40))
        val text = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(TextView(context).apply {
                text = app.name
                setTextColor(Theme.TEXT)
                Theme.px(this, Theme.BODY_LARGE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val meta = when {
                have.isNotEmpty() -> "$have installed"
                best == null -> "no version"
                else -> "${best.kind} · ${best.version}"
            }
            addView(TextView(context).apply {
                text = meta
                setTextColor(Theme.TEXT_DIM)
                Theme.px(this, Theme.CAPTION)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        card.addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 10 })
        val chip = when {
            have.isNotEmpty() -> shellChip(context, "Installed", Theme.SUCCESS, Theme.alpha(Theme.SUCCESS, 28))
            best == null -> shellChip(context, "Not yet", Theme.WARNING, Theme.alpha(Theme.WARNING, 28))
            Catalog.isUsable(best) -> shellChip(
                context, best.rating,
                if (best.rating == Catalog.SLOW) Theme.WARNING else Theme.SUCCESS,
                Theme.alpha(if (best.rating == Catalog.SLOW) Theme.WARNING else Theme.SUCCESS, 24),
            )
            else -> shellChip(context, best.rating, Theme.DANGER, Theme.alpha(Theme.DANGER, 24))
        }
        card.addView(chip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = 6
        })
        return card
    }

    /** The Installed tab: only apps this phone has a record for, newest catalog order. */
    private fun refreshInstalled() {
        val all = Installed.read(context)
        val list = apps.filter { all.containsKey(it.id) }
        for (app in list) {
            listCol.addView(appCard(app), LayoutParams(LayoutParams.MATCH_PARENT, 54).apply {
                bottomMargin = 6
            })
        }
        if (list.isEmpty()) listCol.addView(emptyLabel("No apps installed yet — pick one from the Catalog tab"))
        status.text = "${list.size} installed"
        P7Log.say(context, "APPCTR installed tab rows=${list.size}")
    }

    private fun emptyLabel(s: String): TextView = TextView(context).apply {
        text = s
        setTextColor(Theme.TEXT_DIM)
        Theme.px(this, Theme.SMALL)
        setPadding(6, 18, 6, 18)
    }

    private fun renderDetail() {
        val app = selected ?: run {
            detailTitle.text = "No app selected"
            detailBody.text = ""
            return
        }
        val v = picked ?: Catalog.pick(app)
        picked = v
        if (v == null) {
            detailTitle.text = app.name
            detailBody.text = "No versions in the catalog"
            installBtn.isEnabled = false
            installBtn.alpha = 0.45f
            openBtn.isEnabled = false
            openBtn.alpha = 0.45f
            uninstallBtn.visibility = View.GONE
            return
        }
        val usable = Catalog.isUsable(v)
        val installer = installerState(v)
        val have = Installed.version(context, app.id)
        val installable = v.kind == "windows" || v.kind == "linux" || v.kind == "android"
        installBtn.text = when {
            v.kind == "windows" || v.kind == "linux" -> Winget.actionLabel(v.version, have)
            v.kind == "android" -> if (have.isNotEmpty()) "Installed" else "Install"
            else -> "Included"
        }
        installBtn.isEnabled = usable && installable && have.isEmpty()
        installBtn.alpha = if (installBtn.isEnabled) 1f else 0.45f
        openBtn.isEnabled = usable
        openBtn.alpha = if (usable) 1f else 0.45f
        uninstallBtn.visibility = if (have.isNotEmpty()) View.VISIBLE else View.GONE
        detailTitle.text = app.name
        detailBody.text = buildString {
            append(v.version).append(" · ").append(v.kind).append(" · ").append(v.rating).append('\n')
            append(installer)
            if (have.isNotEmpty()) append("\nInstalled: ").append(have)
            if (!usable) append("\nNot yet: ").append(v.note.ifEmpty { v.rating })
            else if (v.note.isNotEmpty()) append('\n').append(v.note)
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
                // B-084: never boot over a live desktop. ensureLivePrefix reuses a live
                // prefix and is serialized with the Phase 12 warm-up (D21), so a click while
                // the warm-up is still booting waits for it instead of starting a second boot.
                if (!Phase2Wine.ensureLivePrefix(context, "open exe")) {
                    say("open exe: Wine prefix not ready")
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
