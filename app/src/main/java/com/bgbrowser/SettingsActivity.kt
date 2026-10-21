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
    private lateinit var ttsStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sv = ScrollView(this).apply { setBackgroundColor(C.BG) }
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(40))
        }
        sv.addView(col)
        setContentView(sv)

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(iconBtn(R.drawable.ic_back) { finish() })
        top.addView(label("Settings", 22f, C.TEXT, true).apply { setPadding(dp(8), 0, 0, 0) })
        col.addView(top)
        build()
    }

    override fun onResume() {
        super.onResume()
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

    private fun dlg() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

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
            trackTintList = ColorStateList(states, intArrayOf(0x66F5B83D, 0x44FFFFFF))
        }
        sw.setOnCheckedChangeListener { _, c ->
            Prefs.put(key, if (c) "1" else "0")
            after?.invoke(c)
        }
        row(card, null, title, sub, trailing = sw) { sw.toggle() }
    }

    private fun choiceRow(card: LinearLayout, title: String, key: String, labels: List<String>, values: List<String>, def: String) {
        val valueView = label("", 13f, C.GOLD)
        fun refresh() {
            val i = values.indexOf(Prefs.str(key, def))
            valueView.text = labels.getOrElse(if (i < 0) values.indexOf(def) else i) { "" }
        }
        refresh()
        row(card, null, title, null, trailing = valueView) {
            dlg().setTitle(title)
                .setItems(labels.toTypedArray<CharSequence>()) { _, i -> Prefs.put(key, values[i]); refresh() }
                .show()
        }
    }

    private fun textRow(card: LinearLayout, title: String, key: String, placeholder: String, def: String) {
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
                .setPositiveButton("Save") { _, _ -> Prefs.put(key, et.text.toString().trim()); refresh() }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun buttonRow(card: LinearLayout, title: String, sub: String?, tint: Int = C.TEXT, onClick: () -> Unit) {
        row(card, null, title, sub, tint) { onClick() }
    }

    private fun note(card: LinearLayout, text: String) {
        card.addView(label(text, 12f, C.MUTED).apply { setPadding(dp(12), dp(8), dp(12), dp(10)) })
    }

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

    // ───────── sections ─────────

    private fun build() {
        var c = section("Browsing")
        choiceRow(c, "Search engine", "engine", listOf("Google", "DuckDuckGo", "Brave", "Startpage"),
            listOf("google", "ddg", "brave", "startpage"), "google")
        textRow(c, "Home page", "home", "https://www.google.com", "")
        switchRow(c, "JavaScript", "Turning this off breaks most sites", "js", true)
        switchRow(c, "Desktop site", "Default for new tabs", "desktop", false)
        textRow(c, "User agent", "ua", "Default", "")
        choiceRow(c, "Text size", "textSize", listOf("80%", "90%", "100%", "115%", "130%", "150%"),
            listOf("80", "90", "100", "115", "130", "150"), "100")
        switchRow(c, "HTTPS-only", "Upgrade http links, block insecure requests", "httpsOnly", false)

        c = section("Cookies")
        switchRow(c, "Accept cookies", null, "cookies", true)
        switchRow(c, "Block third-party cookies", null, "cookies3p", false)
        switchRow(c, "Clear cookies on exit", "Also wipes site data after a crash or swipe-away", "clearOnExit", false)
        buttonRow(c, "Clear cookies now", "Cookies and site storage", C.RED) { clearCookies(); toast("Cookies cleared") }

        c = section("Privacy")
        switchRow(c, "New tabs in incognito", "Opening the app also starts in incognito", "incognitoDefault", false)
        switchRow(c, "Save history", null, "saveHistory", true)
        buttonRow(c, "Clear history", null, C.RED) { History.clear(); toast("History cleared") }
        buttonRow(c, "Clear cache", null, C.RED) { clearCache(); toast("Cache cleared") }
        note(c, "Incognito and Tor tabs run in their own process with separate storage. It is wiped when you close them and again at next start. Screenshots are blocked in them.")

        c = section("Auto mode")
        choiceRow(c, "Default mode", "autoMode", listOf("Off", "Scroll", "Swipe"), listOf("0", "1", "2"), "0")
        choiceRow(c, "Default speed", "autoSpeed", listOf("1 · slow", "2 · medium", "3 · fast"), listOf("1", "2", "3"), "2")
        switchRow(c, "Remember per site", "Mode and speed are saved for each site", "rememberSite", true)
        switchRow(c, "Click “next” at page end", null, "autoNext", true)
        textRow(c, "Custom “next” selector", "autoNextSel", "CSS selector, e.g. a.next-chapter", "")
        buttonRow(c, "Forget remembered sites", null) { Prefs.removePrefix("site."); toast("Done") }

        c = section("Background")
        switchRow(c, "Keep running in background", "Foreground service with fake media session", "keepAlive", true)
        switchRow(c, "Wake lock", null, "wakeLock", true)
        switchRow(c, "Wi-Fi lock", null, "wifiLock", true)
        val batteryOk = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        buttonRow(c, "Unrestricted battery", if (batteryOk) "Allowed" else "Tap to allow") {
            try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
        }
        switchRow(c, "Launch on boot", "Needs “Display over other apps”", "bootLaunch", true)
        buttonRow(c, "Display over other apps", if (Settings.canDrawOverlays(this)) "Allowed" else "Tap to allow") {
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
        }
        buttonRow(c, "Setup checklist", null) {
            startActivity(Intent(this, MainActivity::class.java).putExtra("checklist", true).putExtra("noRedirect", true))
        }
        note(c, "Some phone makers also need autostart / unrestricted battery set by hand in system settings.")

        c = section("Text-to-speech")
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

        c = section("Tor")
        switchRow(c, "Allow session cookies in Tor tabs", "Off = no cookies at all. On = logins work until you close the tab.", "torCookies", false)
        val n = Prefs.list("torHosts").size
        buttonRow(c, "Always-Tor sites", if (n == 0) "None" else "$n saved. Tap to clear") {
            Prefs.put("torHosts", ""); toast("Cleared"); recreate()
        }
        buttonRow(c, "Open a Tor tab", "Starts Tor and verifies it with check.torproject.org") {
            if (Build.VERSION.SDK_INT >= 28) startActivity(Intent(this, TorActivity::class.java)) else toast("Needs Android 9+")
        }
        note(c, "Tor tabs hide your IP and your ISP's view of the sites you visit. Nothing loads until Tor is verified, and WebRTC is off. This is still weaker than Tor Browser against fingerprinting, and logging in to accounts can identify you.")

        c = section("About")
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        row(c, null, "BG Browser", "Version $ver · $packageName")
        note(c, "Swiping the app out of recents kills the page. Auto-next is a text guess, so use the custom selector if a site needs it. Swipe mode only works with the screen on; scroll mode works with it off. Audio over Tor can stutter.")
    }
}
