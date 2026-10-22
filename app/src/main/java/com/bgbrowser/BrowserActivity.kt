package com.bgbrowser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream

class Tab(val web: WebView) {
    var title = ""
    var url = ""
    var host: String? = null
    var progress = 100
    var desktop = false
    var lazyUrl: String? = null
    var injected = ""
    var script: ScriptHandler? = null
    var tts: NativeTtsBridge? = null
}

abstract class BrowserActivity : Activity() {
    abstract val kind: Int
    protected open val serviceClass: Class<out KeepAliveService> get() = KeepAliveService::class.java

    private val ui = Handler(Looper.getMainLooper())
    private val tabs = ArrayList<Tab>()
    private var cur = -1
    private val tab: Tab? get() = tabs.getOrNull(cur)

    private var paused = false
    private var pendingUrl: String? = null
    private var ttsState = TtsState.UNKNOWN
    private var lastSig = ""
    private var lastUiSig = ""
    private var verifier: WebView? = null
    private var verifying = false
    private val stateBridge = StateBridge()

    private lateinit var rootFrame: FrameLayout
    private lateinit var webHost: FrameLayout
    private lateinit var urlBox: EditText
    private lateinit var lockIcon: ImageView
    private lateinit var reloadBtn: ImageView
    private lateinit var tabsCount: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var chips: LinearLayout
    private lateinit var pill: LinearLayout
    private var chipBar: View? = null
    private lateinit var ppBtn: ImageView
    private lateinit var torOverlay: LinearLayout
    private lateinit var torTitle: TextView
    private lateinit var torDetail: TextView
    private lateinit var torRetry: TextView
    private var sheet: FrameLayout? = null
    private var sheetRefresh: (() -> Unit)? = null

    private val fade = Runnable {
        if (Prefs.bool("pillFade", true)) pill.animate().alpha(0.25f).setDuration(400).start()
    }

    // ───────────────────────── lifecycle ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTheme()
        lastUiSig = themeSig()
        if (kind != K_NORMAL && Build.VERSION.SDK_INT < 28) {
            Toast.makeText(this, "Incognito and Tor need Android 9 or newer", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (kind == K_NORMAL && Prefs.bool("incognitoDefault", false) &&
            !intent.getBooleanExtra("noRedirect", false) && !intent.getBooleanExtra("checklist", false) &&
            Build.VERSION.SDK_INT >= 28
        ) {
            startActivity(Intent(this, IncognitoActivity::class.java))
            finish()
            return
        }
        if (kind != K_NORMAL) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (kind == K_NORMAL && Prefs.bool("clearOnExit", false) && Prefs.bool("sessionOpen", false)) clearWebData()
        if (kind == K_NORMAL) Prefs.put("sessionOpen", "1")

        buildUi()
        applyCookiePolicy()

        SleepTimer.onChange = { ui.post { updateChips() } }
        Bridge.onCommand = { cmd ->
            ui.post {
                when (cmd) {
                    "sleep" -> {
                        // Sleep timer ended: silence every tab, then close the browser.
                        tabs.forEach {
                            it.tts?.shutdown()
                            try { it.web.evaluateJavascript(Js.STOP_ALL, null); it.web.loadUrl("about:blank") } catch (_: Exception) {}
                        }
                        finishAndRemoveTask()
                    }
                    "stop" -> {
                        paused = true
                        tabs.forEach {
                            try { it.tts?.stop() } catch (_: Exception) {}
                            it.web.evaluateJavascript(Js.STOP_ALL, null)
                        }
                        updatePill()
                    }
                    else -> {
                        paused = cmd == "pause"
                        tabs.forEach { it.web.evaluateJavascript(if (paused) Js.PAUSE else Js.RESUME, null) }
                        updatePill()
                    }
                }
            }
        }
        if (kind == K_TOR) initTor()
        startKeepAlive()
        openInitialTabs()
        if (kind == K_NORMAL) {
            TtsHelper.check(this) { st -> ttsState = st; updateChips() }
            if (intent.getBooleanExtra("checklist", false)) {
                ui.postDelayed({ showChecklist() }, 600)
            } else if (!Prefs.bool("firstRunDone", false)) {
                startActivity(Intent(this, WelcomeActivity::class.java))
            } else if (!notifOk()) {
                requestNotif()
            }
        }
        lastSig = settingsSig()
        wake()
        onTorChanged()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("url")?.let { addTab(it, true) }
        if (kind == K_NORMAL && intent.getBooleanExtra("checklist", false)) showChecklist()
    }

    private fun keepRunning() {
        tab?.web?.onResume()
        tab?.web?.resumeTimers()
    }

    override fun onPause() { super.onPause(); keepRunning(); saveTabs() }
    override fun onStop() { super.onStop(); keepRunning() }

    override fun onResume() {
        super.onResume()
        if (kind == K_NORMAL && themeSig() != lastUiSig) {
            Prefs.put("sessionOpen", "0")
            recreate()
            return
        }
        keepRunning()
        applyUiPrefs()
        val sig = settingsSig()
        if (sig != lastSig) {
            lastSig = sig
            applySettingsAll()
        }
        sheetRefresh?.invoke()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (sheet != null) { dismissSheet(); return }
        val t = tab
        when {
            t != null && t.web.canGoBack() -> t.web.goBack()
            tabs.size > 1 -> closeTab(cur)
            else -> moveTaskToBack(true)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        sheetRefresh?.invoke()
    }

    override fun onDestroy() {
        Bridge.onCommand = null
        ui.removeCallbacksAndMessages(null)
        val finishing = isFinishing
        if (finishing) {
            if (kind == K_NORMAL) {
                if (Prefs.bool("clearOnExit", false)) clearWebData()
                Prefs.put("sessionOpen", "0")
            } else {
                clearWebData()
            }
            stopService(Intent(this, serviceClass))
        }
        if (kind == K_TOR) TorManager.stop()
        try { verifier?.destroy() } catch (_: Exception) {}
        tabs.forEach { t ->
            t.tts?.shutdown()
            try { t.web.destroy() } catch (_: Exception) {}
        }
        super.onDestroy()
        if (finishing && kind != K_NORMAL) Process.killProcess(Process.myPid())
    }

    private fun exitApp() {
        finishAndRemoveTask()
    }

    // ───────────────────────── UI ─────────────────────────

    private fun buildUi() {
        rootFrame = FrameLayout(this).apply { setBackgroundColor(C.BG) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val compact = Prefs.bool("compact", false)
        val barTop = Prefs.bool("barTop", false)

        chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips, FrameLayout.LayoutParams(WRAP, MATCH))
        }
        column.addView(chipScroll, LinearLayout.LayoutParams(MATCH, dp(if (compact) 28 else 36)))
        chipBar = chipScroll

        webHost = FrameLayout(this)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            progressTintList = ColorStateList.valueOf(C.GOLD)
            progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        }
        torTitle = label("", 18f, C.TEXT, true).apply { gravity = Gravity.CENTER }
        torDetail = label("", 13f, C.MUTED).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(8), dp(24), dp(16)) }
        torRetry = label("Retry", 15f, C.BG, true).apply {
            gravity = Gravity.CENTER
            background = rounded(C.GOLD, 22)
            setPadding(dp(28), dp(10), dp(28), dp(10))
            visibility = View.GONE
            setOnClickListener { TorManager.restart(applicationContext) }
        }
        torOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(C.BG)
            visibility = View.GONE
            isClickable = true
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_tor)
                setColorFilter(C.PURPLE)
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { bottomMargin = dp(12) }
            })
            addView(torTitle)
            addView(torDetail)
            addView(torRetry, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        val webArea = FrameLayout(this)
        webArea.addView(webHost, FrameLayout.LayoutParams(MATCH, MATCH))
        webArea.addView(progressBar, FrameLayout.LayoutParams(MATCH, dp(3)))
        webArea.addView(torOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        column.addView(webArea, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // bottom bar (one-hand use)
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.BAR)
            setPadding(dp(6), dp(if (compact) 3 else 6), dp(6), dp(if (compact) 3 else 6))
        }
        bottom.addView(iconBtn(R.drawable.ic_back) { goBack() })

        lockIcon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { leftMargin = dp(14); rightMargin = dp(8) }
        }
        urlBox = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setTextColor(C.TEXT)
            setHintTextColor(C.MUTED)
            textSize = 14f
            hint = "Search or type URL"
            background = null
            setPadding(0, 0, dp(12), 0)
            imeOptions = EditorInfo.IME_ACTION_GO or
                (if (kind != K_NORMAL) EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING else 0)
            setSelectAllOnFocus(true)
            layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
            setOnEditorActionListener { v, _, _ ->
                load(v.text.toString())
                clearFocus()
                hideKeyboard()
                true
            }
            setOnFocusChangeListener { _, f ->
                val u = tab?.url ?: ""
                if (f) { setText(u); selectAll() } else setText(display(u))
            }
        }
        val addressPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(C.SURFACE2, 22)
            addView(lockIcon)
            addView(urlBox)
        }
        bottom.addView(addressPill, LinearLayout.LayoutParams(0, dp(if (compact) 38 else 44), 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })

        reloadBtn = iconBtn(R.drawable.ic_reload) {
            val t = tab
            if (t != null) { if (t.progress < 100) t.web.stopLoading() else t.web.reload() }
        }
        bottom.addView(reloadBtn)

        tabsCount = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(C.TEXT)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { setStroke(dp(2), C.TEXT); cornerRadius = dpf(6) }
        }
        val tabsWrap = FrameLayout(this).apply {
            background = ripple(Color.TRANSPARENT, 20)
            setOnClickListener { showTabs() }
            addView(tabsCount, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        }
        bottom.addView(tabsWrap, LinearLayout.LayoutParams(dp(40), dp(40)))
        bottom.addView(iconBtn(R.drawable.ic_menu) { showMenu() })
        column.addView(bottom, if (barTop) 0 else column.childCount, LinearLayout.LayoutParams(MATCH, WRAP))

        // floating control pill
        ppBtn = iconBtn(R.drawable.ic_pause, 44, C.GOLD) { wake(); togglePlay() }
        pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded((C.SURFACE2 and 0x00FFFFFF) or (0xF0 shl 24), 28)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            elevation = dpf(8)
            addView(ppBtn)
            addView(iconBtn(R.drawable.ic_next, 44, C.TEXT) { wake(); nextNow() })
        }

        rootFrame.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        rootFrame.addView(
            pill,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(if (barTop) 24 else if (compact) 64 else 76) }
        )
        setContentView(rootFrame)
        updatePill()
        updateChips()
        updateLock()
        updateTabsCount()
        applyUiPrefs()
    }

    private fun applyUiPrefs() {
        if (::pill.isInitialized) {
            val on = Prefs.bool("showPill", false)
            pill.visibility = if (on) View.VISIBLE else View.GONE
            if (on) wake()
        }
        updateChips()
        if (Prefs.bool("keepScreenOn", false)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(urlBox.windowToken, 0)
    }

    private fun wake() {
        pill.animate().cancel()
        pill.alpha = 1f
        ui.removeCallbacks(fade)
        ui.postDelayed(fade, 3500)
    }

    private fun updatePill() {
        ppBtn.setImageResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
    }

    private fun updateTabsCount() { tabsCount.text = tabs.size.toString() }

    private fun updateLock() {
        val u = tab?.url ?: ""
        when {
            kind == K_TOR -> { lockIcon.setImageResource(R.drawable.ic_tor); lockIcon.setColorFilter(C.PURPLE) }
            kind == K_INCOG -> { lockIcon.setImageResource(R.drawable.ic_incognito); lockIcon.setColorFilter(C.PURPLE) }
            u.startsWith("http://") -> { lockIcon.setImageResource(R.drawable.ic_lock); lockIcon.setColorFilter(C.AMBER) }
            else -> { lockIcon.setImageResource(R.drawable.ic_lock); lockIcon.setColorFilter(C.GREEN) }
        }
    }

    private fun updateChips() {
        if (!::chips.isInitialized) return
        chips.removeAllViews()
        val all = Prefs.bool("chipsAll", false)
        fun add(v: View) {
            chips.addView(v, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })
        }
        if (Bridge.service != null) {
            if (all) add(chip("Running in background", C.GREEN))
        } else if (Prefs.bool("keepAlive", true)) {
            add(chip("Not in background · tap to start", C.AMBER) { startKeepAlive(); ui.postDelayed({ updateChips() }, 800) })
        }

        when (kind) {
            K_TOR -> when {
                TorManager.allowed -> add(chip("Tor verified", C.GREEN))
                TorManager.state == TorManager.S.FAILED -> add(chip("Tor blocked", C.RED))
                else -> add(chip("Tor connecting ${TorManager.progress}%", C.AMBER))
            }
            K_INCOG -> add(chip("Incognito", C.PURPLE))
            else -> if (all) add(chip("Tor off", C.MUTED) { openTor(null) })
        }
        if (SleepTimer.active()) add(chip("Sleep in ${SleepTimer.remainingMinutes()} min", C.GOLD) { showSleep() })
        when (ttsState) {
            TtsState.NO_ENGINE -> add(chip("No TTS engine · tap to install", C.RED) { TtsHelper.install(this) })
            TtsState.NO_DATA -> add(chip("TTS voice data missing · tap to install", C.RED) { TtsHelper.install(this) })
            else -> {}
        }
        chipBar?.visibility = if (Prefs.bool("showChips", true) && chips.childCount > 0) View.VISIBLE else View.GONE
    }

    // ───────────────────────── sheets ─────────────────────────

    private fun showSheet(title: String, fill: (LinearLayout) -> Unit) {
        dismissSheet()
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt())
            isClickable = true
            setOnClickListener { dismissSheet() }
        }
        val r = dpf(20)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(C.SURFACE)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(dp(8), dp(14), dp(8), dp(12))
            isClickable = true
        }
        if (title.isNotEmpty()) {
            panel.addView(label(title, 16f, C.TEXT, true).apply { setPadding(dp(12), 0, dp(12), dp(8)) })
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sv = ScrollView(this).apply { addView(body) }
        panel.addView(sv, LinearLayout.LayoutParams(MATCH, WRAP))
        fill(body)
        scrim.addView(panel, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        rootFrame.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        sheet = scrim
        sheetRefresh = { body.removeAllViews(); fill(body) }
    }

    private fun dismissSheet() {
        sheet?.let { rootFrame.removeView(it) }
        sheet = null
        sheetRefresh = null
    }

    private fun showMenu() = showSheet("") { body ->
        val t = tab
        val hidden = Prefs.list("menuHidden")
        fun on(id: String) = !hidden.contains(id)

        if (on("play") && !Prefs.bool("showPill", false)) {
            tileGrid(body, listOf(
                tile(if (paused) R.drawable.ic_play else R.drawable.ic_pause, if (paused) "Resume" else "Pause", null, C.GOLD) { dismissSheet(); togglePlay() },
                tile(R.drawable.ic_next, "Next") { dismissSheet(); nextNow() }
            ))
        }

        val tiles = ArrayList<View>()
        if (on("newtab")) tiles.add(tile(R.drawable.ic_add, "New tab") { dismissSheet(); newTab() })
        if (kind == K_NORMAL) {
            if (on("incog")) tiles.add(tile(R.drawable.ic_incognito, "Incognito") { dismissSheet(); openIncognito(null) })
            if (on("tor")) tiles.add(tile(R.drawable.ic_tor, "Tor") { dismissSheet(); openTor(null) })
        } else {
            tiles.add(tile(R.drawable.ic_add, "Normal tab") { dismissSheet(); openNormal() })
        }
        if (kind == K_TOR && on("identity")) tiles.add(tile(R.drawable.ic_tor, "New identity", null, C.PURPLE) { dismissSheet(); confirmNewIdentity() })
        if (kind != K_TOR && on("desktop")) {
            val d = t?.desktop == true
            tiles.add(tile(R.drawable.ic_desktop, "Desktop", if (d) "On" else "Off", if (d) C.GOLD else C.TEXT) {
                dismissSheet()
                val x = tab
                if (x != null) { x.desktop = !x.desktop; applySettings(x); x.web.reload() }
            })
        }
        if (on("forward")) tiles.add(tile(R.drawable.ic_forward, "Forward") { dismissSheet(); tab?.web?.let { if (it.canGoForward()) it.goForward() } })
        if (on("copy")) tiles.add(tile(R.drawable.ic_copy, "Copy link") { dismissSheet(); copyLink() })
        if (on("share")) tiles.add(tile(R.drawable.ic_share, "Share") { dismissSheet(); shareLink() })
        if (kind == K_NORMAL && on("history")) tiles.add(tile(R.drawable.ic_history, "History") { showHistory() })
        if (on("sleep")) {
            val a = SleepTimer.active()
            tiles.add(tile(R.drawable.ic_timer, "Sleep", if (a) "${SleepTimer.remainingMinutes()} min" else null, if (a) C.GOLD else C.TEXT) { showSleep() })
        }
        if (on("find")) tiles.add(tile(R.drawable.ic_search, "Find") { dismissSheet(); showFind() })
        if (kind == K_NORMAL && on("bookmark")) {
            val bm = currentUrl()?.let { Bookmarks.has(it) } == true
            tiles.add(tile(R.drawable.ic_star, "Bookmark", if (bm) "Saved" else null, if (bm) C.GOLD else C.TEXT) { toggleBookmark(); sheetRefresh?.invoke() })
        }
        if (kind == K_TOR && on("torinfo")) tiles.add(tile(R.drawable.ic_tor, "Tor info") { dismissSheet(); showTorInfo() })
        if (kind == K_TOR && on("level")) {
            val names = listOf("Standard", "Safer", "Safest")
            tiles.add(tile(R.drawable.ic_lock, "Safety", names[torLevel()], if (torLevel() > 0) C.GOLD else C.TEXT) { cycleTorLevel(); sheetRefresh?.invoke() })
        }
        if (tiles.isNotEmpty()) tileGrid(body, tiles)

        if (kind == K_NORMAL && t?.host != null) {
            val h = t.host!!
            val torOn = Prefs.list("torHosts").contains(h)
            row(body, R.drawable.ic_tor, "Always use Tor for this site", if (torOn) "On for $h" else "Off") {
                dismissSheet()
                val s = Prefs.list("torHosts")
                if (torOn) s.remove(h) else s.add(h)
                Prefs.putList("torHosts", s)
                if (!torOn) openTor(t.url)
            }
        }
        if (kind == K_NORMAL) row(body, R.drawable.ic_star, "Bookmarks") { showBookmarks() }
        row(body, R.drawable.ic_settings, "Settings") { dismissSheet(); startActivity(Intent(this, SettingsActivity::class.java)) }
        row(body, R.drawable.ic_trash, "Clear data", if (kind == K_NORMAL) "Cookies, cache, history" else "Wipe this private session") {
            dismissSheet()
            AlertDialog.Builder(this, dialogTheme())
                .setTitle("Clear data?")
                .setMessage(if (kind == K_NORMAL) "Cookies, cache, site data and history will be deleted." else "Everything in this private session will be wiped.")
                .setPositiveButton("Clear") { _, _ -> clearBrowsingData() }
                .setNegativeButton("Cancel", null).show()
        }
        row(body, R.drawable.ic_exit, "Exit", null, C.RED) { dismissSheet(); exitApp() }
    }

    private fun showTabs() = showSheet("Tabs") { body ->
        for ((i, t) in tabs.withIndex()) {
            val closeBtn = ImageView(this).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(C.MUTED)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                setOnClickListener { closeTab(i); if (sheet != null) sheetRefresh?.invoke() }
            }
            row(body, null, t.title.ifBlank { "New tab" }, display(t.url).ifBlank { "blank" },
                tint = if (i == cur) C.GOLD else C.TEXT, trailing = closeBtn) { dismissSheet(); selectTab(i) }
        }
        row(body, R.drawable.ic_add, "New tab") { dismissSheet(); newTab() }
        if (kind == K_NORMAL) {
            row(body, R.drawable.ic_incognito, "New incognito tab") { dismissSheet(); openIncognito(null) }
            row(body, R.drawable.ic_tor, "New Tor tab") { dismissSheet(); openTor(null) }
        }
    }

    private fun showHistory() = showSheet("History") { body ->
        val items = History.items(60)
        if (items.isEmpty()) row(body, null, "No history yet")
        for (h in items) {
            row(body, null, h.title.ifBlank { h.url }, display(h.url)) {
                dismissSheet()
                tab?.let { loadIn(it, h.url) }
            }
        }
    }

    private fun showChecklist() = showSheet("Setup checklist") { body ->
        fun item(title: String, sub: String, done: Boolean, act: () -> Unit) {
            val mark = ImageView(this).apply {
                setImageResource(R.drawable.ic_check)
                setColorFilter(if (done) C.GREEN else C.SURFACE2)
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            }
            row(body, null, title, sub, trailing = mark, onClick = act)
        }
        item("Notifications", "Media controls in the notification", notifOk()) { requestNotif() }
        item("Unrestricted battery", "Stops Android from freezing the page",
            (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        }
        item("Launch on boot", "Needs “Display over other apps”", Settings.canDrawOverlays(this)) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        }
        item("Tor (optional)", "Tap to open a Tor tab and test it", false) { dismissSheet(); openTor(null) }
        row(body, null, "Done", null, C.GOLD) {
            Prefs.put("firstRunDone", "1")
            dismissSheet()
        }
    }

    private fun notifOk() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun requestNotif() {
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    // ───────────────────────── tabs ─────────────────────────

    private fun openInitialTabs() {
        val extra = intent.getStringExtra("url")
        if (kind == K_NORMAL) {
            val saved = if (Prefs.bool("restoreTabs", true)) Prefs.str("tabs", "").split("\n").filter { it.isNotBlank() } else emptyList()
            for (u in saved) {
                val t = addTab(null, false)
                t.lazyUrl = u
                t.url = u
            }
            if (extra != null) { addTab(extra, true); return }
            if (tabs.isNotEmpty()) { selectTab(Prefs.int("tabIdx", 0).coerceIn(0, tabs.size - 1)); return }
        }
        addTab(extra ?: homeUrl(), true)
    }

    private fun saveTabs() {
        if (kind != K_NORMAL || tabs.isEmpty()) return
        Prefs.put("tabs", tabs.map { if (it.url.isBlank()) (it.lazyUrl ?: "") else it.url }.filter { it.isNotBlank() }.joinToString("\n"))
        Prefs.put("tabIdx", cur)
    }

    private fun addTab(url: String?, select: Boolean): Tab {
        val t = Tab(newWeb())
        t.desktop = kind != K_TOR && Prefs.bool("desktop", false)
        tabs.add(t)
        applySettings(t)
        installScripts(t)
        if (select) selectTab(tabs.size - 1) else updateTabsCount()
        if (url != null) load(url)
        return t
    }

    private fun newTab() {
        if (kind == K_NORMAL && Prefs.bool("incognitoDefault", false)) openIncognito(null) else addTab(homeUrl(), true)
    }

    private fun selectTab(i: Int) {
        if (i !in tabs.indices) return
        cur = i
        val t = tabs[i]
        webHost.removeAllViews()
        (t.web.parent as? ViewGroup)?.removeView(t.web)
        webHost.addView(t.web, FrameLayout.LayoutParams(MATCH, MATCH))
        t.web.requestFocus()
        t.lazyUrl?.let { t.lazyUrl = null; loadIn(t, it) }
        urlBox.setText(display(t.url))
        progressBar.visibility = if (t.progress in 1..99) View.VISIBLE else View.GONE
        reloadBtn.setImageResource(if (t.progress < 100) R.drawable.ic_close else R.drawable.ic_reload)
        updateLock()
        updateTabsCount()
    }

    private fun closeTab(i: Int) {
        if (i !in tabs.indices) return
        val t = tabs.removeAt(i)
        webHost.removeView(t.web)
        try { t.script?.remove() } catch (_: Exception) {}
        t.tts?.shutdown()
        try { t.web.destroy() } catch (_: Exception) {}
        if (tabs.isEmpty()) {
            cur = -1
            if (kind == K_NORMAL) addTab(homeUrl(), true) else finish()
            return
        }
        val n = when {
            i < cur -> cur - 1
            i == cur -> minOf(i, tabs.size - 1)
            else -> cur
        }
        selectTab(n)
    }

    private fun goBack() {
        val t = tab
        if (t != null && t.web.canGoBack()) t.web.goBack() else if (tabs.size > 1) closeTab(cur)
    }

    private fun tabOf(v: WebView?): Tab? = tabs.firstOrNull { it.web === v }

    // ───────────────────────── web ─────────────────────────

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun newWeb(): WebView {
        val w = WebView(this)
        w.setBackgroundColor(C.BG)
        w.isFocusableInTouchMode = true
        if (kind != K_NORMAL) w.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        w.addJavascriptInterface(stateBridge, "BgState")
        w.webViewClient = client
        w.webChromeClient = chrome
        w.setOnTouchListener { _, _ -> wake(); false }
        w.setDownloadListener { url, _, _, _, _ ->
            if (kind == K_NORMAL) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
            } else {
                Toast.makeText(this, "Downloads aren't supported in private tabs", Toast.LENGTH_SHORT).show()
            }
        }
        return w
    }

    private fun httpsOnlyNow() = kind == K_TOR || Prefs.bool("httpsOnly", false)
    private fun canLoad() = kind != K_TOR || TorManager.allowed

    @SuppressLint("SetJavaScriptEnabled")
    private fun applySettings(t: Tab) {
        val s = t.web.settings
        val lvl = torLevel()
        s.javaScriptEnabled = if (kind == K_TOR) lvl < 2 else Prefs.bool("js", true)
        s.domStorageEnabled = !(kind == K_TOR && lvl >= 2)
        s.mediaPlaybackRequiresUserGesture = kind == K_TOR && lvl >= 1
        s.mixedContentMode = if (httpsOnlyNow()) WebSettings.MIXED_CONTENT_NEVER_ALLOW
        else WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.textZoom = if (kind == K_TOR) 100 else Prefs.int("textSize", 100)
        s.setSupportMultipleWindows(false)
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setGeolocationEnabled(false)
        if (kind == K_TOR) s.javaScriptCanOpenWindowsAutomatically = false
        if (kind != K_NORMAL) {
            @Suppress("DEPRECATION")
            s.saveFormData = false
            s.cacheMode = WebSettings.LOAD_NO_CACHE
            s.setSafeBrowsingEnabled(false)
        }
        val ua = Prefs.str("ua", "")
        s.userAgentString = when {
            kind == K_TOR -> torUa()
            ua.isNotBlank() -> ua
            t.desktop -> desktopUa()
            else -> null
        }
        s.useWideViewPort = t.desktop
        s.loadWithOverviewMode = t.desktop
        CookieManager.getInstance().setAcceptThirdPartyCookies(
            t.web, !(kind == K_TOR || Prefs.bool("cookies3p", false))
        )
    }

    private val blockedN = java.util.concurrent.atomic.AtomicInteger(0)

    private fun trackerBlockNow() = if (kind == K_TOR) Prefs.bool("torTrackers", true) else Prefs.bool("blockTrackers", false)

    private val trackParams = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id", "fbclid", "gclid", "dclid",
        "msclkid", "mc_cid", "mc_eid", "igshid", "yclid", "twclid", "ttclid", "li_fat_id", "wbraid", "gbraid",
        "srsltid", "_hsenc", "_hsmi", "mkt_tok", "vero_id", "oly_enc_id", "oly_anon_id"
    )

    /** Removes well-known tracking parameters from a link. Tor tabs do this by default. */
    private fun cleanUrl(raw: String): String {
        val on = if (kind == K_TOR) Prefs.bool("torStrip", true) else Prefs.bool("stripTracking", false)
        if (!on) return raw
        val u = try { Uri.parse(raw) } catch (_: Exception) { return raw }
        val names = try { u.queryParameterNames } catch (_: Exception) { return raw }
        if (names.none { it.lowercase() in trackParams }) return raw
        val b = u.buildUpon().clearQuery()
        for (n in names) {
            if (n.lowercase() !in trackParams) for (x in u.getQueryParameters(n)) b.appendQueryParameter(n, x)
        }
        return b.build().toString()
    }

    private fun torLevel(): Int = if (kind == K_TOR) Prefs.int("torLevel", 0).coerceIn(0, 2) else 0

    /** Same user agent for every Tor tab, with no device model or Android build. Only the Chrome version follows the installed WebView. */
    private fun torUa(): String {
        val d = WebSettings.getDefaultUserAgent(this)
        val v = Regex("Chrome/(\\d+)").find(d)?.groupValues?.get(1) ?: "120"
        return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$v.0.0.0 Mobile Safari/537.36"
    }

    private fun isLocalHost(h: String?): Boolean {
        if (h == null) return true
        val x = h.lowercase().trim('[', ']')
        if (x.isEmpty() || x.all { it.isDigit() } || x.startsWith("0x")) return true
        if (x == "localhost" || x.endsWith(".localhost") || x.endsWith(".local") || x.endsWith(".internal") || x.endsWith(".lan")) return true
        if (x.contains(':') && (x == "::1" || x == "::" || x.startsWith("fe80") || x.startsWith("fc") || x.startsWith("fd") || x.startsWith("::ffff"))) return true
        val m = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)$").find(x) ?: return false
        val a = m.groupValues[1].toInt()
        val b = m.groupValues[2].toInt()
        return a == 0 || a == 10 || a == 127 || (a == 169 && b == 254) || (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) || (a == 100 && b in 64..127)
    }

    private fun desktopUa(): String {
        val d = WebSettings.getDefaultUserAgent(this)
        return d.replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)")
            .replace(" Mobile", "").replace("Version/4.0 ", "")
    }

    private fun applyCookiePolicy() {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(if (kind == K_TOR) Prefs.bool("torCookies", false) else Prefs.bool("cookies", true))
        tabs.forEach { applySettings(it) }
    }

    private fun installScripts(t: Tab) {
        try { t.script?.remove() } catch (_: Exception) {}
        t.script = null
        val sb = StringBuilder(Js.KEEPALIVE)
        if (kind == K_TOR) sb.append('\n').append(Js.NO_WEBRTC).append('\n').append(Js.torHarden(torLevel()))
        val nativeTts = Prefs.bool("nativeTts", false)
        t.web.removeJavascriptInterface("BgTts")
        if (nativeTts) {
            sb.append('\n').append(Js.TTS_SHIM)
            val bridge = t.tts ?: NativeTtsBridge(this) { js -> ui.post { t.web.evaluateJavascript(js, null) } }
            t.tts = bridge
            t.web.addJavascriptInterface(bridge, "BgTts")
        }
        t.injected = sb.toString()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            t.script = WebViewCompat.addDocumentStartJavaScript(t.web, t.injected, setOf("*"))
        }
    }

    private fun settingsSig(): String =
        listOf("js", "desktop", "ua", "textSize", "httpsOnly", "cookies", "cookies3p", "nativeTts",
            "torCookies", "keepAlive", "engine", "torLevel")
            .joinToString("|") { Prefs.str(it, "") }

    private fun applySettingsAll() {
        applyCookiePolicy()
        tabs.forEach { installScripts(it) }
        tab?.web?.reload()
        if (Prefs.bool("keepAlive", true)) startKeepAlive() else stopService(Intent(this, serviceClass))
        ui.postDelayed({ updateChips() }, 600)
    }

    private fun blockedResponse() =
        WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
            val u = r.url
            val scheme = u.scheme ?: return false
            if (scheme == "http" && httpsOnlyNow()) {
                if (r.isForMainFrame) v.loadUrl(u.buildUpon().scheme("https").build().toString())
                return true
            }
            if (scheme != "http" && scheme != "https") {
                if (scheme == "about" || scheme == "data" || scheme == "blob" || scheme == "javascript") return false
                if (kind == K_NORMAL) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                }
                return true
            }
            if (r.isForMainFrame) {
                val cl = cleanUrl(u.toString())
                if (cl != u.toString()) { v.loadUrl(cl); return true }
            }
            if (kind == K_NORMAL && r.isForMainFrame) {
                val h = u.host
                if (h != null && Prefs.list("torHosts").contains(h)) { openTor(u.toString()); return true }
            }
            return false
        }

        override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
            val sc = r.url.scheme ?: return null
            var block = (kind == K_TOR && (!TorManager.allowed || isLocalHost(r.url.host))) ||
                (sc == "http" && httpsOnlyNow())
            if (!block && kind == K_TOR && torLevel() >= 1 && !r.isForMainFrame) {
                val p = r.url.path?.lowercase() ?: ""
                if (p.endsWith(".woff") || p.endsWith(".woff2") || p.endsWith(".ttf") || p.endsWith(".otf") || p.endsWith(".eot")) block = true
            }
            if (!block && !r.isForMainFrame && trackerBlockNow() && Blocklist.isTracker(r.url.host)) {
                blockedN.incrementAndGet()
                block = true
            }
            return if (block) blockedResponse() else null
        }

        override fun onPageStarted(v: WebView, url: String?, f: Bitmap?) {
            val t = tabOf(v) ?: return
            t.url = url ?: ""
            v.evaluateJavascript(t.injected, null)
            if (t === tab) {
                if (!urlBox.hasFocus()) urlBox.setText(display(t.url))
                updateLock()
            }
            val h = hostOf(url)
            if (h != t.host) {
                t.host = h
            }
        }

        override fun onPageFinished(v: WebView, url: String?) {
            val t = tabOf(v) ?: return
            v.evaluateJavascript(t.injected, null)
            if (kind == K_NORMAL && Prefs.bool("saveHistory", true) && url != null) History.add(url, v.title ?: "")
        }
    }

    private val chrome = object : WebChromeClient() {
        override fun onProgressChanged(v: WebView, p: Int) {
            val t = tabOf(v) ?: return
            t.progress = p
            if (t === tab) {
                progressBar.visibility = if (p in 1..99) View.VISIBLE else View.GONE
                progressBar.progress = p
                reloadBtn.setImageResource(if (p < 100) R.drawable.ic_close else R.drawable.ic_reload)
            }
        }

        override fun onReceivedTitle(v: WebView, title: String?) {
            val t = tabOf(v) ?: return
            t.title = title ?: ""
            if (t === tab && kind == K_NORMAL) Bridge.service?.updateMeta(title)
        }

        override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
    }

    // ───────────────────────── navigation ─────────────────────────

    private fun homeUrl(): String =
        if (kind == K_TOR) "https://duckduckgo.com/"
        else Prefs.str("home", "").ifBlank { "https://www.google.com" }

    private fun searchBase(): String {
        val e = if (kind == K_TOR) "ddg" else Prefs.str("engine", "google")
        return when (e) {
            "ddg" -> "https://duckduckgo.com/?q="
            "brave" -> "https://search.brave.com/search?q="
            "startpage" -> "https://www.startpage.com/do/search?q="
            else -> "https://www.google.com/search?q="
        }
    }

    private fun normalize(s: String): String = when {
        s.startsWith("http://") || s.startsWith("https://") -> s
        s.contains(" ") || (!s.contains(".") && !s.contains(":")) -> searchBase() + Uri.encode(s)
        else -> "https://$s"
    }

    private fun load(input: String) {
        val s = input.trim()
        if (s.isEmpty()) return
        val t = tab ?: return
        loadIn(t, normalize(s))
    }

    private fun loadIn(t: Tab, raw: String) {
        var url = raw
        if (httpsOnlyNow() && url.startsWith("http://")) url = "https://" + url.substring(7)
        url = cleanUrl(url)
        if (kind == K_NORMAL) {
            val h = hostOf(url)
            if (h != null && Prefs.list("torHosts").contains(h)) { openTor(url); return }
        }
        if (!canLoad()) { pendingUrl = url; return }
        t.web.loadUrl(url)
    }

    private fun hostOf(url: String?): String? = try { if (url == null) null else Uri.parse(url).host } catch (_: Exception) { null }

    private fun display(u: String): String =
        if (u.startsWith("data:")) "" else u.removePrefix("https://").removePrefix("http://").removeSuffix("/")

    private fun openIncognito(url: String?) {
        if (Build.VERSION.SDK_INT < 28) {
            Toast.makeText(this, "Incognito needs Android 9 or newer", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, IncognitoActivity::class.java).apply { if (url != null) putExtra("url", url) })
    }

    private fun openTor(url: String?) {
        if (Build.VERSION.SDK_INT < 28) {
            Toast.makeText(this, "Tor tabs need Android 9 or newer", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, TorActivity::class.java).apply { if (url != null) putExtra("url", url) })
    }

    private fun openNormal() {
        startActivity(Intent(this, MainActivity::class.java).putExtra("noRedirect", true))
    }

    private fun cycleTorLevel() {
        Prefs.put("torLevel", ((torLevel() + 1) % 3).toString())
        tabs.forEach { applySettings(it); installScripts(it) }
        tab?.web?.reload()
    }

    private fun bytes(n: Long?): String = when {
        n == null -> "?"
        n < 1024 -> "$n B"
        n < 1048576 -> "${n / 1024} KB"
        else -> "${n / 1048576} MB"
    }

    private fun showTorInfo() {
        Thread {
            val ver = TorManager.query("version") ?: "?"
            val circ = TorManager.query("circuit-status") ?: ""
            val rd = TorManager.query("traffic/read")?.trim()?.toLongOrNull()
            val wr = TorManager.query("traffic/written")?.trim()?.toLongOrNull()
            var path = ""
            var open = 0
            for (line in circ.lines()) {
                if (line.contains(" BUILT ") && line.contains("PURPOSE=GENERAL")) {
                    open++
                    if (path.isEmpty()) {
                        val hops = line.trim().split(" ").getOrNull(2) ?: ""
                        path = hops.split(",").joinToString(" → ") { it.substringAfter('~', it) }
                    }
                }
            }
            val msg = "Tor " + ver.trim() +
                "\nExit IP: " + (TorManager.exitIp.ifBlank { "unknown" }) +
                "\nCircuit: " + (path.ifBlank { "none yet" }) +
                "\nOpen circuits: " + open +
                "\nDownloaded: " + bytes(rd) + "   Uploaded: " + bytes(wr) +
                "\nBlocked requests: " + blockedN.get() +
                "\nSecurity level: " + listOf("Standard", "Safer", "Safest")[torLevel()]
            ui.post {
                AlertDialog.Builder(this, dialogTheme())
                    .setTitle("Tor info")
                    .setMessage(msg)
                    .setPositiveButton("OK", null).show()
            }
        }.apply { isDaemon = true; start() }
    }

    private fun showSleep() = showSheet("Sleep timer") { body ->
        if (SleepTimer.active()) {
            row(body, R.drawable.ic_close, "Cancel timer", "${SleepTimer.remainingMinutes()} min left", C.RED) {
                dismissSheet(); SleepTimer.cancel()
                Toast.makeText(this, "Sleep timer off", Toast.LENGTH_SHORT).show()
            }
        }
        val opts = listOf(5, 10, 15, 30, 45, 60, 90, 120)
        tileGrid(body, opts.map { m ->
            tile(R.drawable.ic_timer, if (m >= 60) "${m / 60} h" + (if (m % 60 != 0) " ${m % 60}m" else "") else "$m min") {
                dismissSheet(); startSleep(m)
            }
        })
        row(body, R.drawable.ic_add, "Custom…", "Pick the minutes") { dismissSheet(); askSleepMinutes() }
        body.addView(label("When the timer ends, speech and media are cancelled, the browser closes and the background service stops.", 12f, C.MUTED).apply { setPadding(dp(14), dp(8), dp(14), dp(4)) })
    }

    private fun startSleep(m: Int) {
        SleepTimer.start(this, m)
        Toast.makeText(this, "Stopping in $m min", Toast.LENGTH_SHORT).show()
        updateChips()
    }

    private fun askSleepMinutes() {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            setTextColor(C.TEXT)
            setHintTextColor(C.MUTED)
            hint = "Minutes (1 to 600)"
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("Sleep timer")
            .setView(et)
            .setPositiveButton("Start") { _, _ ->
                val m = et.text.toString().trim().toIntOrNull()
                if (m != null && m in 1..600) startSleep(m)
                else Toast.makeText(this, "Enter 1 to 600", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showFind() {
        val w = tab?.web ?: return
        val et = EditText(this).apply {
            setSingleLine()
            setTextColor(C.TEXT)
            setHintTextColor(C.MUTED)
            hint = "Find in page"
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        var last = ""
        val d = AlertDialog.Builder(this, dialogTheme())
            .setView(et)
            .setPositiveButton("Next", null)
            .setNegativeButton("Close", null)
            .setOnDismissListener { try { w.clearMatches() } catch (_: Exception) {} }
            .create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val q = et.text.toString()
            if (q.isEmpty()) return@setOnClickListener
            if (q != last) { last = q; w.findAllAsync(q) } else w.findNext(true)
        }
    }

    private fun toggleBookmark() {
        val u = currentUrl()
        if (u == null) { Toast.makeText(this, "No page to bookmark", Toast.LENGTH_SHORT).show(); return }
        if (Bookmarks.has(u)) {
            Bookmarks.remove(u)
            Toast.makeText(this, "Bookmark removed", Toast.LENGTH_SHORT).show()
        } else {
            val title = tab?.title ?: ""
            Bookmarks.add(u, title.ifBlank { u })
            Toast.makeText(this, "Bookmarked", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showBookmarks() = showSheet("Bookmarks") { body ->
        val items = Bookmarks.items()
        if (items.isEmpty()) row(body, null, "No bookmarks yet", "Use the star in the menu")
        for (b in items) {
            val del = ImageView(this).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(C.MUTED)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                setOnClickListener { Bookmarks.remove(b.url); sheetRefresh?.invoke() }
            }
            row(body, null, b.title.ifBlank { b.url }, display(b.url), trailing = del) {
                dismissSheet()
                tab?.let { loadIn(it, b.url) }
            }
        }
    }

    private fun currentUrl(): String? = tab?.url?.takeIf { it.startsWith("http") }

    private fun copyLink() {
        val u = currentUrl()
        if (u == null) { Toast.makeText(this, "No page to copy", Toast.LENGTH_SHORT).show(); return }
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("link", u))
        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareLink() {
        val u = currentUrl() ?: return
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, u), null))
        } catch (_: Exception) {}
    }

    private fun togglePlay() {
        val s = Bridge.service
        if (s != null) s.toggle() else Bridge.onCommand?.invoke(if (paused) "resume" else "pause")
    }

    private fun nextNow() {
        val sel = JSONObject.quote(Prefs.str("autoNextSel", ""))
        tab?.web?.evaluateJavascript("window.__bgSel=$sel;window.__bgNext&&window.__bgNext()", null)
    }

    // ───────────────────────── data wiping ─────────────────────────

    private fun clearWebData() {
        try {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
            WebStorage.getInstance().deleteAllData()
        } catch (_: Exception) {}
        tabs.forEach {
            try { it.web.clearCache(true); it.web.clearFormData(); it.web.clearHistory() } catch (_: Exception) {}
        }
    }

    private fun confirmNewIdentity() {
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("New Tor identity?")
            .setMessage("Closes all tabs, wipes cookies and site data, and switches to fresh Tor circuits.")
            .setPositiveButton("New identity") { _, _ -> newIdentity() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun newIdentity() {
        clearWebData()
        while (tabs.size > 1) closeTab(tabs.size - 1)
        TorManager.newIdentity()
        ui.postDelayed({ tab?.let { loadIn(it, homeUrl()) } }, 800)
        Toast.makeText(this, "New identity", Toast.LENGTH_SHORT).show()
    }

    private fun clearBrowsingData() {
        clearWebData()
        if (kind == K_NORMAL) History.clear()
        Toast.makeText(this, "Cleared", Toast.LENGTH_SHORT).show()
    }

    // ───────────────────────── keep-alive ─────────────────────────

    private fun startKeepAlive() {
        if (Prefs.bool("keepAlive", true)) {
            try { ContextCompat.startForegroundService(this, Intent(this, serviceClass)) } catch (_: Exception) {}
        }
    }

    // ───────────────────────── Tor ─────────────────────────

    private fun initTor() {
        TorManager.listener = { ui.post { onTorChanged() } }
        val ok = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (!ok) {
            TorManager.fail("This WebView can't proxy or inject scripts. Update “Android System WebView”.", false)
            return
        }
        TorManager.begin(applicationContext)
    }

    private fun onTorChanged() {
        if (kind != K_TOR || !::torOverlay.isInitialized) { updateChips(); return }
        val s = TorManager.state
        if (s == TorManager.S.READY && !TorManager.verified && !verifying) startVerify()
        val blocked = !TorManager.allowed
        torOverlay.visibility = if (blocked) View.VISIBLE else View.GONE
        torTitle.text = when (s) {
            TorManager.S.FAILED -> "Tor is blocked"
            TorManager.S.READY -> "Verifying Tor…"
            else -> "Connecting to Tor…"
        }
        torDetail.text = TorManager.detail + "\nNothing loads until Tor is verified. There is no fallback to the normal internet."
        torRetry.visibility = if (s == TorManager.S.FAILED) View.VISIBLE else View.GONE
        if (!blocked) {
            pendingUrl?.let { u -> pendingUrl = null; tab?.web?.loadUrl(u) }
        }
        updateChips()
    }

    /** Loads check.torproject.org through the proxy in a hidden WebView and reads IsTor. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun startVerify() {
        verifying = true
        val v = WebView(this)
        v.settings.javaScriptEnabled = true
        v.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        verifier = v
        rootFrame.addView(v, FrameLayout.LayoutParams(1, 1))
        v.alpha = 0f
        v.webViewClient = object : WebViewClient() {
            override fun onPageFinished(w: WebView, url: String?) {
                w.evaluateJavascript("document.body?document.body.innerText:''") { raw ->
                    var isTor = false
                    try {
                        val s = JSONTokener(raw ?: "").nextValue() as? String ?: ""
                        val jo = JSONObject(s)
                        isTor = jo.optBoolean("IsTor", false)
                        TorManager.exitIp = jo.optString("IP", "")
                    } catch (_: Exception) {}
                    finishVerify(isTor, "check.torproject.org says this traffic is NOT coming through Tor")
                }
            }
            override fun onReceivedError(w: WebView, r: WebResourceRequest, e: android.webkit.WebResourceError) {
                if (r.isForMainFrame) finishVerify(false, "Could not reach check.torproject.org through Tor")
            }
        }
        v.loadUrl("https://check.torproject.org/api/ip")
        ui.postDelayed({ if (verifying) finishVerify(false, "Tor check timed out") }, 60000)
    }

    private fun finishVerify(ok: Boolean, msg: String) {
        if (!verifying) return
        verifying = false
        verifier?.let {
            rootFrame.removeView(it)
            try { it.destroy() } catch (_: Exception) {}
        }
        verifier = null
        TorManager.setVerified(ok, msg)
    }
}
