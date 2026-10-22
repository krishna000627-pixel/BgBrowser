package com.bgbrowser

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** First-launch tour: what the app does, the permissions it needs, and a few choices. */
class WelcomeActivity : Activity() {
    private lateinit var body: LinearLayout
    private lateinit var dots: LinearLayout
    private lateinit var nextBtn: TextView
    private lateinit var skipBtn: TextView
    private var page = 0
    private val last = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTheme()
        page = savedInstanceState?.getInt("page", 0) ?: 0
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
            setPadding(dp(20), dp(28), dp(20), dp(16))
        }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sv = ScrollView(this).apply { addView(body) }
        root.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))

        dots = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10)) }
        root.addView(dots, LinearLayout.LayoutParams(MATCH, WRAP))

        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        skipBtn = label("Skip", 15f, C.MUTED).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { finish() }
        }
        bar.addView(skipBtn)
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        nextBtn = label("Next", 16f, Color.parseColor("#1A1405"), true).apply {
            gravity = Gravity.CENTER
            background = rounded(C.GOLD, 24)
            setPadding(dp(28), dp(12), dp(28), dp(12))
            setOnClickListener { if (page >= last) finish() else { page++; show() } }
        }
        bar.addView(nextBtn)
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(root)
        show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", page)
    }

    override fun onResume() {
        super.onResume()
        if (page == 2 && ::body.isInitialized) show()
    }

    override fun finish() {
        Prefs.put("firstRunDone", "1")
        super.finish()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (page > 0) { page--; show() } else finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        show()
    }

    private fun h1(s: String) = label(s, 28f, C.TEXT, true).apply { setPadding(dp(4), dp(24), dp(4), dp(8)) }
    private fun para(s: String) = label(s, 15f, C.MUTED).apply {
        setPadding(dp(4), dp(0), dp(4), dp(12))
        setLineSpacing(0f, 1.2f)
    }
    private fun card(): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C.SURFACE, 16)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        body.addView(c, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        return c
    }

    private fun show() {
        body.removeAllViews()
        when (page) {
            0 -> {
                body.addView(h1("BG Browser"))
                body.addView(para("Keeps a web page running, and reading aloud, while your screen is off."))
                val c = card()
                row(c, R.drawable.ic_wave, "Background playback", "A media notification keeps the page alive and lets you pause or resume.")
                row(c, R.drawable.ic_timer, "Sleep timer", "Stops the voice, the browser and the background service after a set time.")
                row(c, R.drawable.ic_incognito, "Private tabs and Tor", "Incognito tabs and a Tor mode that never falls back to a direct connection.")
            }
            1 -> {
                body.addView(h1("How it works"))
                body.addView(para("Three steps and you can lock the phone."))
                val c = card()
                row(c, null, "1  Open your site", "Go to the page you read and start its voice.")
                row(c, null, "2  Set a sleep timer", "Optional. In the menu, so it stops by itself when you fall asleep.")
                row(c, null, "3  Lock the screen", "Control playback from the notification.")
            }
            2 -> {
                body.addView(h1("Allow these"))
                body.addView(para("Android may freeze the page without them. You can skip any of them and change it later."))
                val c = card()
                fun item(t: String, sub: String, done: Boolean, act: () -> Unit) {
                    val mark = ImageView(this).apply {
                        setImageResource(R.drawable.ic_check)
                        setColorFilter(if (done) C.GREEN else C.SURFACE2)
                        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
                    }
                    row(c, null, t, sub, trailing = mark, onClick = act)
                }
                item("Notifications", "Media controls in the notification", notifOk()) {
                    if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                }
                item("Unrestricted battery", "Stops Android from freezing the page",
                    (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)) {
                    try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
                }
                item("Launch on boot", "Needs “Display over other apps”", Settings.canDrawOverlays(this)) {
                    try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
                }
            }
            else -> {
                body.addView(h1("Make it yours"))
                body.addView(para("Everything here is also in Settings."))
                val c = card()
                prefSwitch(c, "Show control pill", "Floating play/pause and next buttons. Off keeps the page clean; the same controls are in the menu.", "showPill", false)
                prefSwitch(c, "Show status chips", "Background, Tor and voice status above the page", "showChips", true)
                prefSwitch(c, "Keep screen on", "While the browser is open", "keepScreenOn", false)
            }
        }
        dots.removeAllViews()
        for (i in 0..last) {
            val d = View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (i == page) C.GOLD else C.SURFACE2) }
            }
            dots.addView(d, LinearLayout.LayoutParams(dp(8), dp(8)).apply { leftMargin = dp(4); rightMargin = dp(4) })
        }
        nextBtn.text = if (page >= last) "Start browsing" else "Next"
        skipBtn.visibility = if (page >= last) View.INVISIBLE else View.VISIBLE
    }

    private fun notifOk() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
