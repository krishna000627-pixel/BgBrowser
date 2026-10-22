package com.bgbrowser

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    private lateinit var col: LinearLayout
    private var testTts: TextToSpeech? = null
    private var page = "home"
    private var shownSig = ""
    private lateinit var ttsStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTheme()
        shownSig = themeSig()
        page = intent.getStringExtra("page") ?: "home"
        val sv = ScrollView(this).apply { setBackgroundColor(C.BG) }
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(40))
        }
        sv.addView(col)
        setContentView(sv)

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(iconBtn(R.drawable.ic_back) { finish() })
        top.addView(label(pageTitle(), 22f, C.TEXT, true).apply { setPadding(dp(8), 0, 0, 0) })
        col.addView(top)
        build()
    }

    override fun onResume() {
        super.onResume()
        if (themeSig() != shownSig) { recreate(); return }
        refreshTts()
    }

    override fun onDestroy() {
        try { testTts?.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7) {
            if (resultCode == TextToSpeech.Engine.CHECK_VOICE_DATA_PASS) {
                Toast.makeText(this, "Voice data is installed", Toast.LENGTH_SHORT).show()
            } else {
                dlg().setTitle("Voice data missing")
                    .setMessage("Install voice data for text-to-speech?")
                    .setPositiveButton("Install") { _, _ -> TtsHelper.install(this) }
                    .setNegativeButton("Later", null).show()
            }
            refreshTts()
        }
    }

    private fun refreshTts() {
        if (!::ttsStatus.isInitialized) return
        ttsStatus.text = "Checking…"
        TtsHelper.check(this) { st ->
            ttsStatus.text = when (st) {
                TtsState.OK -> "Ready"
                TtsState.NO_ENGINE -> "No engine"
                TtsState.NO_DATA -> "No voice data"
                else -> "Unknown"
            }
            ttsStatus.setTextColor(if (st == TtsState.OK) C.GREEN else C.RED)
        }
    }

    private fun dlg() = AlertDialog.Builder(this, dialogTheme())

    // ───────── helpers ─────────

    private fun section(title: String): LinearLayout {
        col.addView(label(title.uppercase(), 12f, C.GOLD, true).apply {
            setPadding(dp(4), dp(22), 0, dp(8))
            letterSpacing = 0.08f
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C.SURFACE, 16)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        col.addView(card, LinearLayout.LayoutParams(MATCH, WRAP))
        return card
    }

    private fun switchRow(card: LinearLayout, title: String, sub: String?, key: String, def: Boolean, after: ((Boolean) -> Unit)? = null) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val sw = Switch(this).apply {
            isChecked = Prefs.bool(key, def)
            thumbTintList = ColorStateList(states, intArrayOf(C.GOLD, C.MUTED))
            trackTintList = ColorStateList(states, intArrayOf((C.GOLD and 0x00FFFFFF) or 0x66000000, if (C.LIGHT) 0x33000000 else 0x44FFFFFF))
        }
        sw.setOnCheckedChangeListener { _, c ->
            Prefs.put(key, if (c) "1" else "0")
            after?.invoke(c)
        }
        row(card, null, title, sub, trailing = sw) { sw.toggle() }
    }

    private fun choiceRow(card: LinearLayout, title: String, key: String, labels: List<String>, values: List<String>, def: String, after: (() -> Unit)? = null) {
        val valueView = label("", 13f, C.GOLD)
        fun refresh() {
            val i = values.indexOf(Prefs.str(key, def))
            valueView.text = labels.getOrElse(if (i < 0) values.indexOf(def) else i) { "" }
        }
        refresh()
        row(card, null, title, null, trailing = valueView) {
            dlg().setTitle(title)
                .setItems(labels.toTypedArray<CharSequence>()) { _, i -> Prefs.put(key, values[i]); refresh(); after?.invoke() }
                .show()
        }
    }

    private fun textRow(card: LinearLayout, title: String, key: String, placeholder: String, def: String, after: (() -> Unit)? = null) {
        val v = label("", 13f, C.MUTED).apply {
            maxWidth = dp(170)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        fun refresh() {
            val s = Prefs.str(key, def)
            v.text = if (s.isBlank()) placeholder else s
        }
        refresh()
        row(card, null, title, null, trailing = v) {
            val et = EditText(this).apply {
                setText(Prefs.str(key, def))
                setTextColor(C.TEXT)
                setHintTextColor(C.MUTED)
                hint = placeholder
                setSingleLine()
                setPadding(dp(20), dp(12), dp(20), dp(12))
            }
            dlg().setTitle(title).setView(et)
                .setPositiveButton("Save") { _, _ -> Prefs.put(key, et.text.toString().trim()); refresh(); after?.invoke() }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun buttonRow(card: LinearLayout, title: String, sub: String?, tint: Int = C.TEXT, onClick: () -> Unit) {
        row(card, null, title, sub, tint) { onClick() }
    }

    private fun note(card: LinearLayout, text: String) {
        card.addView(label(text, 12f, C.MUTED).apply { setPadding(dp(12), dp(8), dp(12), dp(10)) })
    }

    private fun pageTitle() = when (page) {
        "appearance" -> "Appearance"
        "browsing" -> "Browsing"
        "controls" -> "Controls & display"
        "privacy" -> "Privacy & cookies"
        "background" -> "Background"
        "tts" -> "Text-to-speech"
        "tor" -> "Tor"
        "about" -> "About"
        else -> "Settings"
    }

    private fun open(p: String) = startActivity(Intent(this, SettingsActivity::class.java).putExtra("page", p))

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun clearCookies() {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
        WebStorage.getInstance().deleteAllData()
    }

    private fun clearCache() {
        try { val w = WebView(applicationContext); w.clearCache(true); w.destroy() } catch (_: Exception) {}
    }

    // ───────── pages ─────────

    private fun build() {
        when (page) {
            "appearance" -> buildAppearance()
            "browsing" -> buildBrowsing()
            "controls" -> buildControls()
            "privacy" -> buildPrivacy()
            "background" -> buildBackground()
            "tts" -> buildTts()
            "tor" -> buildTor()
            "about" -> buildAbout()
            else -> buildHome()
        }
    }

    private fun buildHome() {
        val c = section("Categories")
        fun item(icon: Int, title: String, sub: String, p: String) {
            row(c, icon, title, sub, trailing = label("›", 22f, C.MUTED)) { open(p) }
        }
        item(R.drawable.ic_settings, "Appearance", "Theme, accent color, layout, menu, status chips", "appearance")
        item(R.drawable.ic_desktop, "Browsing", "Search engine, home page, desktop site, text size", "browsing")
        item(R.drawable.ic_play, "Controls & display", "Floating pill, status chips, screen, tabs", "controls")
        item(R.drawable.ic_lock, "Privacy & cookies", "Cookies, history, incognito, cache", "privacy")
        item(R.drawable.ic_pause, "Background", "Keep-alive, battery, boot, disconnect", "background")
        item(R.drawable.ic_wave, "Text-to-speech", "Voice data, test, native bridge", "tts")
        item(R.drawable.ic_tor, "Tor", "Security level, cookies, always-Tor sites", "tor")
        item(R.drawable.ic_settings, "About", "Version, help, reset", "about")
    }

    private fun buildAppearance() {
        var c = section("Theme")
        choiceRow(c, "Theme", "theme", listOf("Dark", "AMOLED black", "Light"), listOf("dark", "amoled", "light"), "dark") { recreate() }
        choiceRow(c, "Accent color", "accent",
            listOf("Gold", "Blue", "Green", "Purple", "Red", "Teal", "Pink", "Orange"),
            listOf("F5B83D", "5AA9FF", "4ADE80", "B388FF", "FF6B6B", "2DD4BF", "F472B6", "FB923C"), "F5B83D") { recreate() }
        textRow(c, "Custom accent", "accentCustom", "#RRGGBB (empty = preset above)", "") { recreate() }
        note(c, "Private tabs pick up theme changes the next time you open them.")

        c = section("Layout")
        switchRow(c, "Address bar at top", "Off keeps it at the bottom for one-hand use", "barTop", false)
        switchRow(c, "Compact bars", "Thinner address bar and status strip", "compact", false)

        c = section("Menu")
        buttonRow(c, "Menu items", "Choose which shortcuts show in the menu") { pickMenuItems() }

        c = section("Status chips")
        switchRow(c, "Show status chips", "The small strip above the page", "showChips", true)
        switchRow(c, "Show all chips", "Off = only warnings, Tor status and Auto when it is on", "chipsAll", false)
    }

    private fun pickMenuItems() {
        val hidden = Prefs.list("menuHidden")
        val ids = MENU_TILES.map { it.first }
        val names = MENU_TILES.map { it.second }
        val checked = BooleanArray(ids.size) { !hidden.contains(ids[it]) }
        dlg().setTitle("Menu items")
            .setMultiChoiceItems(names.toTypedArray<CharSequence>(), checked) { _, i, b -> checked[i] = b }
            .setPositiveButton("Save") { _, _ ->
                val off = HashSet<String>()
                for (i in ids.indices) if (!checked[i]) off.add(ids[i])
                Prefs.putList("menuHidden", off)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun buildBrowsing() {
        val c = section("Browsing")
        choiceRow(c, "Search engine", "engine", listOf("Google", "DuckDuckGo", "Brave", "Startpage"),
            listOf("google", "ddg", "brave", "startpage"), "google")
        textRow(c, "Home page", "home", "https://www.google.com", "")
        switchRow(c, "JavaScript", "Turning this off breaks most sites", "js", true)
        switchRow(c, "Desktop site", "Default for new tabs", "desktop", false)
        textRow(c, "User agent", "ua", "Default", "")
        choiceRow(c, "Text size", "textSize", listOf("80%", "90%", "100%", "115%", "130%", "150%"),
            listOf("80", "90", "100", "115", "130", "150"), "100")
        switchRow(c, "HTTPS-only", "Upgrade http links, block insecure requests", "httpsOnly", false)
    }

    private fun buildControls() {
        var c = section("Floating pill")
        switchRow(c, "Show control pill", "Play/pause and next on top of the page. Off by default.", "showPill", false)
        switchRow(c, "Fade when idle", "Dim the pill a few seconds after you touch the page", "pillFade", true)
        note(c, "With the pill off, the same controls are at the top of the menu.")

        c = section("Display")
        switchRow(c, "Keep screen on", "Stops the screen from turning off while the browser is open", "keepScreenOn", false)

        c = section("Next page button")
        textRow(c, "Custom selector", "autoNextSel", "CSS selector, e.g. a.next-chapter", "")
        note(c, "Used by “Next” in the menu. Empty = find a “next” link or button by its text.")

        c = section("Tabs")
        switchRow(c, "Restore tabs on start", "Reopen the tabs you had open last time", "restoreTabs", true)
    }

    private fun buildPrivacy() {
        var c = section("Cookies")
        switchRow(c, "Accept cookies", null, "cookies", true)
        switchRow(c, "Block third-party cookies", null, "cookies3p", false)
        switchRow(c, "Clear cookies on exit", "Also wipes site data after a crash or swipe-away", "clearOnExit", false)
        buttonRow(c, "Clear cookies now", "Cookies and site storage", C.RED) { clearCookies(); toast("Cookies cleared") }

        c = section("Privacy")
        switchRow(c, "New tabs in incognito", "Opening the app also starts in incognito", "incognitoDefault", false)
        switchRow(c, "Block trackers and ads", "Built-in list of common tracking domains", "blockTrackers", false)
        switchRow(c, "Strip tracking parameters", "Removes utm_, fbclid, gclid and similar from links", "stripTracking", false)
        switchRow(c, "Save history", null, "saveHistory", true)
        buttonRow(c, "Clear history", null, C.RED) { History.clear(); toast("History cleared") }
        buttonRow(c, "Clear cache", null, C.RED) { clearCache(); toast("Cache cleared") }
        note(c, "Incognito and Tor tabs run in their own process with separate storage. It is wiped when you close them and again at next start. Screenshots are blocked in them.")
    }

    private fun buildBackground() {
        val c = section("Background")
        switchRow(c, "Keep running in background", "Foreground service with fake media session", "keepAlive", true)
        switchRow(c, "Wake lock", null, "wakeLock", true)
        switchRow(c, "Wi-Fi lock", null, "wifiLock", true)
        val batteryOk = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        buttonRow(c, "Unrestricted battery", if (batteryOk) "Allowed" else "Tap to allow") {
            try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
        }
        choiceRow(c, "When headphones or Bluetooth disconnect", "onDisconnect",
            listOf("Stop speech", "Pause", "Do nothing"), listOf("stop", "pause", "none"), "stop")
        note(c, "“Stop speech” cancels the page's voice, so it does not carry on from the phone speaker. Use the sleep timer in the menu to stop everything after a set time.")
        switchRow(c, "Launch on boot", "Needs “Display over other apps”", "bootLaunch", true)
        buttonRow(c, "Display over other apps", if (Settings.canDrawOverlays(this)) "Allowed" else "Tap to allow") {
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
        }
        buttonRow(c, "Setup checklist", null) {
            startActivity(Intent(this, MainActivity::class.java).putExtra("checklist", true).putExtra("noRedirect", true))
        }
        note(c, "Some phone makers also need autostart / unrestricted battery set by hand in system settings.")
    }

    private fun buildTts() {
        val c = section("Text-to-speech")
        ttsStatus = label("…", 13f, C.MUTED)
        row(c, null, "Engine and voice data", null, trailing = ttsStatus) { refreshTts() }
        buttonRow(c, "Check voice data", "Asks the TTS engine") {
            try { startActivityForResult(Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA), 7) }
            catch (_: Exception) { toast("No TTS engine found"); refreshTts() }
        }
        buttonRow(c, "Install voice data", null) { TtsHelper.install(this) }
        buttonRow(c, "Test voice", null) {
            try { testTts?.shutdown() } catch (_: Exception) {}
            var t: TextToSpeech? = null
            t = TextToSpeech(applicationContext) { st ->
                if (st == TextToSpeech.SUCCESS) t?.speak("Text to speech is working.", TextToSpeech.QUEUE_FLUSH, null, "test")
                else toast("TTS engine not available")
            }
            testTts = t
        }
        buttonRow(c, "System TTS settings", null) {
            try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (_: Exception) { toast("Not available") }
        }
        switchRow(c, "Native TTS bridge", "Experimental. Replaces the page's speechSynthesis with Android TextToSpeech. Reloads open tabs.", "nativeTts", false)
    }

    private fun buildTor() {
        var c = section("Protection")
        choiceRow(c, "Security level", "torLevel", listOf("Standard", "Safer", "Safest"), listOf("0", "1", "2"), "0")
        note(c, "Standard: fingerprint hardening only. Safer: also removes WebGL and WebAssembly, blocks autoplay and rounds timers more. Safest: JavaScript and site storage off. Most sites look plain, but it removes the biggest attack surface. Open Tor tabs pick the change up when you return to them.")
        switchRow(c, "Block trackers and ads", "A built-in list of common tracking domains. Also saves Tor bandwidth.", "torTrackers", true)
        switchRow(c, "Strip tracking parameters", "Removes utm_, fbclid, gclid and similar from links", "torStrip", true)
        switchRow(c, "Separate circuit per site", "Sites you visit cannot be linked at the exit relay. A bit slower. Applies the next time Tor starts.", "torIsolate", true)
        note(c, "Always on in Tor tabs: no referrers on links and forms, no push or service workers, same user agent for everyone, UTC time zone, rounded screen size, canvas noise, WebRTC off, no location, no localhost or LAN access, HTTPS only, nothing loads until Tor is verified. Use “New Tor identity” in a Tor tab's menu to wipe everything.")

        c = section("Tor")
        switchRow(c, "Allow session cookies in Tor tabs", "Off = no cookies at all. On = logins work until you close the tab.", "torCookies", false)
        val n = Prefs.list("torHosts").size
        buttonRow(c, "Always-Tor sites", if (n == 0) "None" else "$n saved. Tap to clear") {
            Prefs.put("torHosts", ""); toast("Cleared"); recreate()
        }
        buttonRow(c, "Open a Tor tab", "Starts Tor and verifies it with check.torproject.org") {
            if (Build.VERSION.SDK_INT >= 28) startActivity(Intent(this, TorActivity::class.java)) else toast("Needs Android 9+")
        }
        note(c, "Tor tabs hide your IP and your ISP's view of the sites you visit. This is still weaker than Tor Browser. Android WebView cannot isolate cookies and caches per site, hide your system language or fonts, or trim referrers, and it has no .onion support. For serious anonymity use Tor Browser. Logging in to accounts can identify you.")
    }

    @Suppress("DEPRECATION")
    private fun versionInfo(): String = try {
        val p = packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
        "${p.versionName} ($code)"
    } catch (_: Exception) { "?" }

    private fun buildAbout() {
        val ver = versionInfo()
        var c = section("BG Browser")
        row(c, null, "Version", ver)
        row(c, null, "Package", packageName)
        note(c, "A browser that keeps a page running in the background, mainly sites that read text aloud. The page never registers a media session, so the app does it for the page.")

        c = section("How to use")
        note(c, "1. Open your reading site and start its voice.\n2. Set a sleep timer from the menu if you will fall asleep.\n3. Lock the screen. Use the notification to pause or resume.\n4. Allow notifications and unrestricted battery so Android does not freeze the page.")

        c = section("Help")
        buttonRow(c, "Welcome tour", "Show the first-launch screens again") {
            startActivity(Intent(this, WelcomeActivity::class.java))
        }
        buttonRow(c, "Setup checklist", "Notifications, battery, boot") {
            startActivity(Intent(this, MainActivity::class.java).putExtra("checklist", true).putExtra("noRedirect", true))
        }
        buttonRow(c, "Project on GitHub", "krishna000627-pixel/bgbrowser") {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/krishna000627-pixel/bgbrowser"))) }
            catch (_: Exception) { toast("No browser found") }
        }
        buttonRow(c, "Copy version info", null) {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("BG Browser",
                "BG Browser $ver, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}"))
            toast("Copied")
        }
        buttonRow(c, "Reset all settings", "Also clears saved tabs and per-site memory", C.RED) {
            dlg().setTitle("Reset all settings?")
                .setMessage("Every setting returns to its default. Cookies and history are not touched.")
                .setPositiveButton("Reset") { _, _ -> Prefs.removePrefix(""); toast("Settings reset"); recreate() }
                .setNegativeButton("Cancel", null).show()
        }

        c = section("Good to know")
        note(c, "Swiping the app out of recents kills the page. Pause relies on the page's own speech pause, so use the sleep timer or “Stop speech” when a site ignores it. Audio over Tor can stutter. Some phone makers need manual battery and autostart settings.")

        c = section("Credits")
        note(c, "Tor support uses the Guardian Project tor-android library (BSD 3-clause).")
    }
}
