package dev.unifieddesktop.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 13 P1-P6: the phone's real home screen. One connection card with five clear states,
 * a first-run checklist, trusted PCs, Settings and a running notification with Stop.
 *
 * The system dark/light mode picks the palette; the whole page is edge-to-edge with insets
 * applied by hand because this app ships no AndroidX/Material dependency.
 */
class HomeActivity : Activity() {

    private lateinit var ui: PhoneUi
    private lateinit var content: LinearLayout
    private lateinit var homePage: LinearLayout
    private lateinit var settingsPage: LinearLayout

    private lateinit var dot: View
    private lateinit var stateLabel: TextView
    private lateinit var stateTitle: TextView
    private lateinit var stateDetail: TextView
    private lateinit var codeView: TextView
    private lateinit var stepsBox: LinearLayout
    private lateinit var pairRow: LinearLayout
    private lateinit var primaryBtn: TextView
    private lateinit var secondaryBtn: TextView

    private lateinit var homeSetup: SetupViews
    private lateinit var settingsSetup: SetupViews
    private lateinit var soundValue: TextView
    private lateinit var trustedBox: LinearLayout
    private var trustedSig = ""

    private lateinit var qualityRow: LinearLayout
    private lateinit var qualityDetail: TextView
    private val qualityChips = LinkedHashMap<QualityChoice, TextView>()
    private var quality = QualityChoice.AUTO

    private val main = Handler(Looper.getMainLooper())
    private var showingSettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = PhoneUi(this, ThemePref.isDark(this))
        edgeToEdge()
        build()
        maybeAskNotifications()
        showHome()
    }

    // ------------------------------------------------------------------ window

    private fun edgeToEdge() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                (if (ui.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
        }
        window.decorView.setBackgroundColor(ui.bg)
    }

    /** Light/dark status and navigation icons; needs the decor view, so call after setContentView. */
    private fun applyBarAppearance() {
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = window.insetsController ?: return
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            bars.setSystemBarsAppearance(if (ui.dark) 0 else mask, mask)
        }
    }

    private fun build() {
        val root = FrameLayout(this)
        root.setBackgroundColor(ui.bg)
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.clipToPadding = false
        root.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(40))
        }
        val screenW = resources.displayMetrics.widthPixels
        val maxW = ui.dp(620)
        val w = minOf(screenW - ui.dp(24), maxW).coerceAtLeast(ui.dp(280))
        scroll.addView(content, FrameLayout.LayoutParams(
            w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ))
        homePage = buildHomePage()
        settingsPage = buildSettingsPage()
        content.addView(homePage)
        content.addView(settingsPage)

        root.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                top = bars.top
                bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            content.setPadding(ui.dp(24), ui.dp(24) + top, ui.dp(24), ui.dp(40) + bottom)
            insets
        }
        setContentView(root)
        applyBarAppearance()
        refreshConnection()
        refreshSetup(homeSetup)
        refreshSetup(settingsSetup)
        refreshSound()
        refreshQuality()
    }

    // ------------------------------------------------------------------ home

    private fun buildHomePage(): LinearLayout {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(DeskIconView(this, DeskIcon.PHONE, ui.accent, ui.dpf(22f)), LinearLayout.LayoutParams(
            ui.dp(22), ui.dp(22),
        ).apply { marginEnd = ui.dp(10) })
        header.addView(label(getString(R.string.app_name), 20f, ui.text, medium = true))
        header.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(pill("Settings") { showSettings() })
        page.addView(header)

        page.addView(label("Your phone.\nOn the big screen.", 36f, ui.text, bold = true).apply {
            setLineSpacing(ui.dpf(2f), 1f)
            setPadding(0, ui.dp(26), 0, ui.dp(10))
        })
        page.addView(label(
            "Use Windows, Linux and Android apps on a PC's screen, keyboard and mouse.",
            16f, ui.secondary,
        ).apply { setLineSpacing(ui.dpf(3f), 1f) })

        val card = card()
        val status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dot = View(this)
        status.addView(dot, LinearLayout.LayoutParams(ui.dp(9), ui.dp(9)).apply { marginEnd = ui.dp(8) })
        stateLabel = label("NOT CONNECTED", 11f, ui.secondary, medium = true).apply {
            letterSpacing = 0.12f
        }
        status.addView(stateLabel)
        card.addView(status)
        stateTitle = label("Connect this phone to a PC", 24f, ui.text, bold = true).apply {
            setPadding(0, ui.dp(12), 0, ui.dp(4))
        }
        card.addView(stateTitle)
        stateDetail = label("", 15f, ui.secondary).apply {
            setLineSpacing(ui.dpf(3f), 1f)
        }
        card.addView(stateDetail)

        codeView = label("000000", 40f, ui.text, bold = true).apply {
            letterSpacing = 0.18f
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(18), 0, ui.dp(6))
            visibility = View.GONE
        }
        card.addView(codeView)

        stepsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ui.dp(10), 0, 0)
        }
        card.addView(stepsBox)
        step("1", "Install the PC app on the computer.")
        step("2", "Plug in a USB cable, or join the same Wi-Fi.")
        step("3", "Confirm the six-digit code on both screens.")

        pairRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(0, ui.dp(14), 0, 0)
        }
        pairRow.addView(primaryButton("Yes, same code") {
            startService(Intent(this@HomeActivity, Phase4LinkService::class.java)
                .setAction(Phase4LinkService.ACTION_PAIR_YES))
        }, LinearLayout.LayoutParams(0, ui.dp(52), 1f).apply { marginEnd = ui.dp(10) })
        pairRow.addView(outlineButton("No") {
            startService(Intent(this@HomeActivity, Phase4LinkService::class.java)
                .setAction(Phase4LinkService.ACTION_PAIR_NO))
        }, LinearLayout.LayoutParams(0, ui.dp(52), 1f))
        card.addView(pairRow)

        primaryBtn = primaryButton("Start desktop") { startLink() }
        card.addView(primaryBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(52),
        ).apply { topMargin = ui.dp(18) })
        secondaryBtn = outlineButton("Try again") { restartLink() }
        secondaryBtn.visibility = View.GONE
        card.addView(secondaryBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(48),
        ).apply { topMargin = ui.dp(10) })
        page.addView(card, cardParams())

        homeSetup = buildSetupCard(compact = true)
        page.addView(homeSetup.card, cardParams())

        page.addView(label(
            "The desktop runs on this phone. Nothing is sent anywhere except to the PC you trust.",
            12f, ui.dim,
        ).apply { setLineSpacing(ui.dpf(2f), 1f); setPadding(ui.dp(4), ui.dp(8), ui.dp(4), 0) })
        return page
    }

    private fun step(number: String, text: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, ui.dp(5), 0, ui.dp(5))
        }
        val n = label(number, 12f, ui.accent, medium = true).apply {
            gravity = Gravity.CENTER
            background = ui.rounded(ui.accentSoft, ui.dpf(11f))
        }
        row.addView(n, LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)).apply { marginEnd = ui.dp(10) })
        row.addView(label(text, 14f, ui.secondary).apply { setLineSpacing(ui.dpf(2f), 1f) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        stepsBox.addView(row)
    }

    // ------------------------------------------------------------------ connection

    private fun refreshConnection() {
        val running = Phase4LinkService.isRunning()
        val live = Phase4LinkService.liveCount()
        val code = Phase4LinkService.pairingCode()
        val failure = Phase4LinkService.lastFailure()
        val since = Phase4LinkService.connectedSinceMs()

        stepsBox.visibility = View.GONE
        pairRow.visibility = View.GONE
        codeView.visibility = View.GONE
        primaryBtn.visibility = View.VISIBLE
        secondaryBtn.visibility = View.GONE
        primaryBtn.setOnClickListener { startLink() }

        when {
            !running && failure != null -> {
                paintStatus(ui.danger, "PROBLEM")
                stateTitle.text = "Could not connect"
                stateDetail.text = failure
                primaryBtn.text = "Try again"
                primaryBtn.setOnClickListener { restartLink() }
            }
            !running -> {
                paintStatus(ui.dim, "NOT CONNECTED")
                stateTitle.text = "Connect this phone to a PC"
                stateDetail.text = "Tap Start desktop, then open the PC app. The PC shows the phone's desktop."
                stepsBox.visibility = View.VISIBLE
                primaryBtn.text = "Start desktop"
            }
            code != null -> {
                paintStatus(ui.accent, "PAIRING")
                stateTitle.text = "Does your PC show this code?"
                stateDetail.text = "Only say yes if the same six digits are on the PC screen."
                codeView.text = code
                codeView.visibility = View.VISIBLE
                pairRow.visibility = View.VISIBLE
                primaryBtn.visibility = View.GONE
            }
            live > 0 -> {
                paintStatus(ui.success, "CONNECTED")
                stateTitle.text = "Your PC"
                val link = LinkInfo.kind(this)
                val time = connectedFor(since)
                stateDetail.text = "$link  ·  Connected $time"
                primaryBtn.text = "Stop desktop"
                primaryBtn.setOnClickListener { stopLink() }
            }
            else -> {
                paintStatus(ui.warning, "LOOKING FOR YOUR PC")
                stateTitle.text = "Waiting for your PC"
                stateDetail.text = "Keep the PC app open. The six-digit code will appear here."
                primaryBtn.text = "Stop"
                primaryBtn.setOnClickListener { stopLink() }
            }
        }
    }

    private fun paintStatus(color: Int, text: String) {
        dot.background = ui.rounded(color, ui.dpf(5f))
        stateLabel.text = text
        stateLabel.setTextColor(color)
    }

    private fun connectedFor(since: Long): String {
        if (since <= 0) return "just now"
        val secs = ((SystemClock.elapsedRealtime() - since) / 1000).coerceAtLeast(0)
        return if (secs < 60) "${secs}s" else "${secs / 60}m ${secs % 60}s"
    }

    private fun startLink() {
        try {
            startForegroundService(
                Intent(this, Phase4LinkService::class.java).setAction(Phase4LinkService.ACTION_START)
            )
            main.postDelayed({ refreshConnection() }, 250)
        } catch (t: Throwable) {
            toast("Could not start the desktop. Try again.")
        }
    }

    private fun stopLink() {
        try {
            startService(
                Intent(this, Phase4LinkService::class.java).setAction(Phase4LinkService.ACTION_STOP)
            )
            main.postDelayed({ refreshConnection() }, 250)
        } catch (t: Throwable) {
            toast("Could not stop the desktop.")
        }
    }

    private fun restartLink() {
        try {
            startService(
                Intent(this, Phase4LinkService::class.java).setAction(Phase4LinkService.ACTION_STOP)
            )
        } catch (_: Throwable) {}
        main.postDelayed({ startLink() }, 500)
    }

    // ------------------------------------------------------------------ setup

    private class SetupRowView(
        val root: LinearLayout,
        val tick: DeskIconView,
        val status: TextView,
    )

    private class SetupViews(
        val card: LinearLayout,
        val progress: TextView,
        val rows: List<SetupRowView>,
    )

    private fun buildSetupCard(compact: Boolean): SetupViews {
        val card = card()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(label("Finish setting up", 18f, ui.text, medium = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val progress = label("", 12f, ui.dim)
        head.addView(progress)
        card.addView(head)
        card.addView(label(
            "Three small things make the desktop reliable.",
            13f, ui.secondary,
        ).apply { setPadding(0, ui.dp(4), 0, ui.dp(8)) })

        val rows = ArrayList<SetupRowView>()
        rows.add(setupRow(card, "Notifications", "So the desktop can tell you it is running.") {
            if (!notificationsGranted()) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            }
        })
        rows.add(setupRow(card, "Background activity", "So the PC stays connected with the screen off.") {
            Phase10Onboarding.show(this, force = true)
        })
        rows.add(setupRow(card, "Sound to the PC", "Android asks once per session before sound is sent.") {
            requestSound()
        })
        return SetupViews(card, progress, rows)
    }

    private fun setupRow(
        parent: LinearLayout,
        title: String,
        why: String,
        onClick: () -> Unit,
    ): SetupRowView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(10), 0, ui.dp(10))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val tick = DeskIconView(this, DeskIcon.DOT, ui.dim, ui.dpf(18f))
        root.addView(tick, LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)).apply { marginEnd = ui.dp(10) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(title, 15f, ui.text, medium = true))
        col.addView(label(why, 12f, ui.dim).apply { setLineSpacing(ui.dpf(1f), 1f) })
        root.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val status = label("", 12f, ui.secondary, medium = true)
        root.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = ui.dp(8) })
        parent.addView(root)
        return SetupRowView(root, tick, status)
    }

    private fun refreshSetup(v: SetupViews) {
        if (!::homeSetup.isInitialized) return
        val items = listOf(
            notificationsGranted(),
            Phase10Onboarding.confirmed(this),
            Phase11Audio.isActive(),
        )
        for (i in v.rows.indices) {
            val done = items[i]
            v.rows[i].tick.set(if (done) DeskIcon.CHECK else DeskIcon.DOT, if (done) ui.success else ui.dim)
            v.rows[i].status.text = if (done) "Done" else "Set up"
            v.rows[i].status.setTextColor(if (done) ui.success else ui.accent)
        }
        val done = items.count { it }
        v.progress.text = "$done of 3"
        if (v === homeSetup) v.card.visibility = if (done == 3) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ settings

    private fun buildSettingsPage(): LinearLayout {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(label("Settings", 30f, ui.text, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(pill("Done") { showHome() })
        page.addView(header)
        page.addView(label("Everything the desktop needs, in one place.", 14f, ui.secondary)
            .apply { setPadding(0, ui.dp(6), 0, ui.dp(18)) })

        page.addView(section("APPEARANCE"))
        val appearanceCard = card()
        appearanceCard.addView(label("Theme", 18f, ui.text, medium = true))
        val themeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ui.dp(12), 0, 0)
        }
        val chosenTheme = ThemePref.get(this)
        for (t in ThemeChoice.values()) {
            val chip = TextView(this).apply {
                text = t.label
                setTextColor(if (t == chosenTheme) ui.onAccent else ui.secondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                background = ui.rounded(
                    if (t == chosenTheme) ui.accent else ui.raised,
                    ui.dpf(12f),
                )
                isClickable = true
                setOnClickListener {
                    if (ThemePref.get(this@HomeActivity) != t) {
                        ThemePref.set(this@HomeActivity, t)
                        recreate()
                    }
                }
            }
            themeRow.addView(chip, LinearLayout.LayoutParams(0, ui.dp(44), 1f).apply {
                marginEnd = if (t == ThemeChoice.SYSTEM) 0 else ui.dp(8)
            })
        }
        appearanceCard.addView(themeRow)
        page.addView(appearanceCard, cardParams())

        page.addView(section("PICTURE QUALITY"))
        val qCard = card()
        qCard.addView(label("Quality", 18f, ui.text, medium = true))
        qualityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ui.dp(12), 0, ui.dp(4))
        }
        for (q in QualityChoice.values()) {
            val chip = TextView(this).apply {
                text = q.label
                setTextColor(ui.secondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { pickQuality(q) }
            }
            qualityChips[q] = chip
            qualityRow.addView(chip, LinearLayout.LayoutParams(0, ui.dp(44), 1f).apply {
                marginEnd = if (q == QualityChoice.SMOOTH) 0 else ui.dp(8)
            })
        }
        qCard.addView(qualityRow)
        qualityDetail = label("", 12f, ui.secondary).apply {
            setLineSpacing(ui.dpf(2f), 1f)
            setPadding(0, ui.dp(8), 0, 0)
        }
        qCard.addView(qualityDetail)
        page.addView(qCard, cardParams())

        page.addView(section("SOUND"))
        val soundCard = card()
        val soundRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { toggleSound() }
        }
        val soundCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        soundCol.addView(label("Sound to the PC", 16f, ui.text, medium = true))
        soundCol.addView(label("Android asks for consent each session.", 12f, ui.dim))
        soundRow.addView(soundCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        soundValue = label("Off", 13f, ui.secondary, medium = true).apply {
            gravity = Gravity.CENTER
            background = ui.rounded(ui.raised, ui.dpf(14f))
            setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6))
        }
        soundRow.addView(soundValue)
        soundCard.addView(soundRow)
        page.addView(soundCard, cardParams())

        page.addView(section("FIRST-RUN SETUP"))
        settingsSetup = buildSetupCard(compact = false)
        page.addView(settingsSetup.card, cardParams())

        page.addView(section("TRUSTED PCS"))
        val tCard = card()
        trustedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tCard.addView(trustedBox)
        page.addView(tCard, cardParams())

        page.addView(section("ABOUT"))
        val aCard = card()
        aCard.addView(infoRow("Version", appVersion()))
        aCard.addView(infoRow("Licences", "View") {
            AlertDialog.Builder(this)
                .setTitle("Licences")
                .setMessage(
                    "XZ for Java (public-domain-equivalent) is the only third-party library " +
                        "compiled into this app.\n\nThe Windows and Linux runtimes (Wine, Box64, " +
                        "Xvfb and their libraries) are downloaded as data and keep their own " +
                        "licences, listed in the project's THIRD_PARTY.md.",
                )
                .setPositiveButton("Close", null)
                .show()
        })
        aCard.addView(infoRow("Privacy", "View") {
            AlertDialog.Builder(this)
                .setTitle("Privacy")
                .setMessage(
                    "The desktop runs on this phone. Your screen, keyboard, sound and the files " +
                        "you choose are sent only to the PC you pair with. Nothing is sent to us " +
                        "and nothing is recorded to storage.",
                )
                .setPositiveButton("Close", null)
                .show()
        })
        page.addView(aCard, cardParams())
        return page
    }

    private fun refreshQuality() {
        quality = QualityPref.get(this)
        for ((q, chip) in qualityChips) {
            val on = q == quality
            chip.setTextColor(if (on) ui.onAccent else ui.secondary)
            chip.background = ui.rounded(if (on) ui.accent else ui.raised, ui.dpf(12f))
        }
        qualityDetail.text = quality.detail + " Used when the desktop starts."
    }

    private fun pickQuality(q: QualityChoice) {
        quality = q
        QualityPref.set(this, q)
        refreshQuality()
    }

    private fun refreshSound() {
        val on = Phase11Audio.isActive()
        soundValue.text = if (on) "On" else "Off"
        soundValue.setTextColor(if (on) ui.success else ui.secondary)
        soundValue.background = ui.rounded(if (on) ui.successSoft else ui.raised, ui.dpf(14f))
    }

    private fun toggleSound() {
        if (Phase11Audio.isActive()) {
            Phase11Audio.stopCapture("user turned sound off")
            refreshSound()
        } else {
            requestSound()
        }
    }

    private fun requestSound() {
        if (!Phase4LinkService.isRunning()) {
            toast("Start the desktop first, then turn on sound.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Sound to the PC")
            .setMessage(
                "Android will ask to record this phone's screen. This app captures only its " +
                    "own desktop sound, and only while sound is on. No microphone and no other " +
                    "app's audio can be captured.",
            )
            .setPositiveButton("Continue") { _, _ -> askAudioPermission() }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun askAudioPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), Phase11Audio.REQ_RECORD_AUDIO)
            return
        }
        val mgr = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mgr.createScreenCaptureIntent(), Phase11Audio.REQ_CONSENT)
    }

    private fun refreshTrusted(force: Boolean = false) {
        if (!::trustedBox.isInitialized) return
        val rows = Phase4Crypto.trustedList(this)
        val sig = rows.joinToString("|") { it.first + it.third }
        if (!force && sig == trustedSig) return
        trustedSig = sig
        trustedBox.removeAllViews()
        if (rows.isEmpty()) {
            trustedBox.addView(label(
                "No trusted PCs yet. Connect a PC and confirm the six-digit code.",
                13f, ui.secondary,
            ).apply { setLineSpacing(ui.dpf(2f), 1f) })
            return
        }
        for ((fpr, label, added) in rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, ui.dp(8), 0, ui.dp(8))
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(label(if (label.isBlank()) "Your PC" else label, 15f, ui.text, medium = true))
            val date = try {
                SimpleDateFormat("d MMM yyyy", Locale.getDefault())
                    .format(Date(added.toLongOrNull()?.times(1000) ?: 0L))
            } catch (_: Throwable) {
                ""
            }
            col.addView(label("${fpr.take(12)}…  ·  $date", 12f, ui.dim))
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(smallOutline("Forget") {
                Phase4Crypto.trustedForget(this, fpr.take(16))
                try {
                    startService(
                        Intent(this, Phase4LinkService::class.java)
                            .setAction(Phase4LinkService.ACTION_FORGET)
                            .putExtra("fpr", fpr),
                    )
                } catch (_: Throwable) {}
                refreshTrusted(force = true)
            })
            trustedBox.addView(row)
        }
    }

    private fun showSettings() {
        showingSettings = true
        homePage.visibility = View.GONE
        settingsPage.visibility = View.VISIBLE
        refreshSetup(settingsSetup)
        refreshQuality()
        refreshSound()
        refreshTrusted(force = true)
        (content.parent as? ScrollView)?.scrollTo(0, 0)
    }

    private fun showHome() {
        showingSettings = false
        settingsPage.visibility = View.GONE
        homePage.visibility = View.VISIBLE
        (content.parent as? ScrollView)?.scrollTo(0, 0)
        refreshConnection()
        refreshSetup(homeSetup)
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        refreshConnection()
        refreshSetup(homeSetup)
        refreshSetup(settingsSetup)
        refreshSound()
        refreshQuality()
        if (showingSettings) refreshTrusted(force = true)
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, 500)
    }

    override fun onPause() {
        main.removeCallbacks(ticker)
        super.onPause()
    }

    private val ticker = object : Runnable {
        override fun run() {
            refreshConnection()
            refreshSetup(homeSetup)
            refreshSetup(settingsSetup)
            refreshSound()
            main.postDelayed(this, 500)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        val granted = results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED
        when (code) {
            REQ_NOTIFICATIONS -> refreshSetup(homeSetup)
            Phase11Audio.REQ_RECORD_AUDIO -> if (granted) askAudioPermission() else refreshSound()
        }
    }

    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code == Phase11Audio.REQ_CONSENT) {
            Phase4LinkService.audioConsentReady(result, data)
            main.postDelayed({ refreshSound() }, 400)
            if (result != RESULT_OK) toast("Sound was not turned on.")
        }
    }

    // ------------------------------------------------------------------ small bits

    private fun notificationsGranted() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun maybeAskNotifications() {
        if (notificationsGranted()) return
        val prefs = getSharedPreferences("home", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1"
    } catch (_: Throwable) {
        "0.1"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = ui.rounded(ui.surface, ui.dpf(20f), if (ui.dark) 0 else ui.border)
        setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(18))
    }

    private fun cardParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = ui.dp(16) }

    private fun section(s: String): TextView = label(s, 11f, ui.dim, medium = true).apply {
        letterSpacing = 0.12f
        setPadding(ui.dp(4), ui.dp(4), 0, ui.dp(8))
    }

    private fun label(
        s: String,
        sp: Float,
        color: Int,
        bold: Boolean = false,
        medium: Boolean = false,
    ): TextView = TextView(this).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = when {
            bold -> Typeface.create("sans-serif", Typeface.BOLD)
            medium -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.create("sans-serif", Typeface.NORMAL)
        }
        includeFontPadding = false
    }

    private fun primaryButton(s: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = s
        setTextColor(ui.onAccent)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        background = ui.rounded(ui.accent, ui.dpf(14f))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun outlineButton(s: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = s
        setTextColor(ui.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        background = ui.rounded(0, ui.dpf(14f), ui.border)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun smallOutline(s: String, onClick: () -> Unit): TextView = outlineButton(s, onClick).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
    }

    private fun pill(s: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = s
        setTextColor(ui.accent)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(ui.dp(14), ui.dp(7), ui.dp(14), ui.dp(7))
        background = ui.rounded(0, ui.dpf(16f), ui.border)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun infoRow(key: String, value: String, onTap: (() -> Unit)? = null): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(9), 0, ui.dp(9))
            if (onTap != null) {
                isClickable = true
                setOnClickListener { onTap() }
            }
        }
        row.addView(label(key, 15f, ui.text),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label(value, 14f, if (onTap != null) ui.accent else ui.secondary, medium = onTap != null))
        return row
    }

    companion object {
        private const val REQ_NOTIFICATIONS = 40
    }
}

/**
 * Phone palette: the UX_AUDIT section-2 tokens. Light is the phone-only theme; the desktop
 * shell keeps its own fixed dark Theme.
 */
private class PhoneUi(context: Context, val dark: Boolean) {
    private val density = context.resources.displayMetrics.density

    val bg = if (dark) 0xFF0B0E14.toInt() else 0xFFF6F7F9.toInt()
    val surface = if (dark) 0xFF141922.toInt() else 0xFFFFFFFF.toInt()
    val raised = if (dark) 0xFF1C2230.toInt() else 0xFFF0F2F5.toInt()
    val border = if (dark) 0xFF2A3140.toInt() else 0xFFE1E5EB.toInt()
    val text = if (dark) 0xFFE8ECF2.toInt() else 0xFF11151C.toInt()
    val secondary = if (dark) 0xFFA7B0BE.toInt() else 0xFF5B6472.toInt()
    val dim = if (dark) 0xFF6B7484.toInt() else 0xFF8A93A0.toInt()
    val accent = if (dark) 0xFF4C8DFF.toInt() else 0xFF2F6FE4.toInt()
    val accentSoft = if (dark) 0x334C8DFF else 0x1A2F6FE4
    val onAccent = 0xFFFFFFFF.toInt()
    val success = if (dark) 0xFF3FB950.toInt() else 0xFF1A7F37.toInt()
    val successSoft = if (dark) 0x333FB950 else 0x1A1A7F37
    val warning = if (dark) 0xFFD29922.toInt() else 0xFF9A6700.toInt()
    val danger = if (dark) 0xFFF85149.toInt() else 0xFFCF222E.toInt()

    fun dp(v: Int): Int = (v * density).toInt()
    fun dpf(v: Float): Float = v * density

    fun rounded(fill: Int, radiusPx: Float, border: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radiusPx
            if (border != 0) setStroke(dp(1), border)
        }
}
