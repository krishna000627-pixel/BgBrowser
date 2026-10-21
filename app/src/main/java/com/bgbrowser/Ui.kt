package com.bgbrowser

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

object C {
    var LIGHT = false
    var BG = Color.parseColor("#0B1220")
    var BAR = Color.parseColor("#111A2C")
    var SURFACE = Color.parseColor("#151F33")
    var SURFACE2 = Color.parseColor("#1F2C47")
    var GOLD = Color.parseColor("#F5B83D")
    var TEXT = Color.parseColor("#E8ECF4")
    var MUTED = Color.parseColor("#8A97B0")
    val GREEN = Color.parseColor("#4ADE80")
    val RED = Color.parseColor("#FF6B6B")
    val PURPLE = Color.parseColor("#B388FF")
    val AMBER = Color.parseColor("#FBBF24")

    private fun p(s: String) = Color.parseColor(s)

    /** Reads theme and accent from settings. Call at the start of every activity. */
    fun apply() {
        val th = Prefs.str("theme", "dark")
        LIGHT = th == "light"
        when (th) {
            "light" -> { BG = p("#F2F4F8"); BAR = p("#FFFFFF"); SURFACE = p("#FFFFFF"); SURFACE2 = p("#E4E8F0"); TEXT = p("#111827"); MUTED = p("#5B6577") }
            "amoled" -> { BG = p("#000000"); BAR = p("#000000"); SURFACE = p("#0F0F12"); SURFACE2 = p("#1C1C21"); TEXT = p("#EDEDF2"); MUTED = p("#8B8B97") }
            else -> { BG = p("#0B1220"); BAR = p("#111A2C"); SURFACE = p("#151F33"); SURFACE2 = p("#1F2C47"); TEXT = p("#E8ECF4"); MUTED = p("#8A97B0") }
        }
        val custom = Prefs.str("accentCustom", "").trim()
        GOLD = try {
            if (custom.isNotEmpty()) p(if (custom.startsWith("#")) custom else "#$custom")
            else p("#" + Prefs.str("accent", "F5B83D"))
        } catch (_: Exception) { p("#F5B83D") }
    }
}

val MENU_TILES = listOf(
    "play" to "Playback controls", "newtab" to "New tab", "incog" to "Incognito tab", "tor" to "Tor tab",
    "identity" to "New Tor identity", "desktop" to "Desktop site", "forward" to "Forward",
    "copy" to "Copy link", "share" to "Share link", "history" to "History"
)

fun dialogTheme(): Int =
    if (C.LIGHT) android.R.style.Theme_DeviceDefault_Light_Dialog_Alert else android.R.style.Theme_DeviceDefault_Dialog_Alert

/** Changes in any of these need the main screen to be rebuilt. */
fun themeSig(): String = listOf("theme", "accent", "accentCustom", "barTop", "compact").joinToString("|") { Prefs.str(it, "") }

@Suppress("DEPRECATION")
fun Activity.applyTheme() {
    C.apply()
    window.setBackgroundDrawable(ColorDrawable(C.BG))
    window.statusBarColor = C.BG
    window.navigationBarColor = C.BAR
    var f = 0
    if (C.LIGHT) {
        f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= 26) f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }
    window.decorView.systemUiVisibility = f
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Int): Float = v * resources.displayMetrics.density

fun Context.rounded(color: Int, radiusDp: Int): GradientDrawable =
    GradientDrawable().apply { setColor(color); cornerRadius = dpf(radiusDp) }

fun Context.ripple(color: Int, radiusDp: Int): Drawable =
    RippleDrawable(ColorStateList.valueOf(if (C.LIGHT) 0x22000000 else 0x33FFFFFF), rounded(color, radiusDp), rounded(Color.WHITE, radiusDp))

fun Context.label(s: String, sizeSp: Float = 14f, color: Int = C.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

fun Context.iconBtn(res: Int, sizeDp: Int = 40, tint: Int = C.TEXT, onClick: () -> Unit): ImageView =
    ImageView(this).apply {
        setImageResource(res)
        setColorFilter(tint)
        val p = dp((sizeDp - 24) / 2)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        background = ripple(Color.TRANSPARENT, sizeDp / 2)
        setOnClickListener { onClick() }
    }

fun Context.row(
    parent: LinearLayout,
    icon: Int?,
    title: String,
    sub: String? = null,
    tint: Int = C.TEXT,
    trailing: View? = null,
    onClick: (() -> Unit)? = null
): LinearLayout {
    val r = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        minimumHeight = dp(52)
        if (onClick != null) {
            background = ripple(Color.TRANSPARENT, 12)
            setOnClickListener { onClick() }
        }
    }
    if (icon != null) {
        r.addView(ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(tint)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(16) }
        })
    }
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
    }
    col.addView(label(title, 15f, tint))
    if (sub != null) col.addView(label(sub, 12f, C.MUTED))
    r.addView(col)
    if (trailing != null) r.addView(trailing)
    parent.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
    return r
}

fun Context.chip(text: String, dot: Int, onClick: (() -> Unit)? = null): LinearLayout {
    val c = LinearLayout(this)
    c.orientation = LinearLayout.HORIZONTAL
    c.gravity = Gravity.CENTER_VERTICAL
    c.background = rounded(C.SURFACE2, 14)
    c.setPadding(dp(10), dp(4), dp(10), dp(4))
    if (dot != 0) {
        val d = View(this)
        d.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dot) }
        c.addView(d, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(6) })
    }
    c.addView(label(text, 12f, C.TEXT))
    if (onClick != null) c.setOnClickListener { onClick() }
    return c
}

fun Context.prefSwitch(card: LinearLayout, title: String, sub: String?, key: String, def: Boolean, after: ((Boolean) -> Unit)? = null) {
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    val sw = android.widget.Switch(this).apply {
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

fun Context.tile(icon: Int, title: String, sub: String? = null, tint: Int = C.TEXT, onClick: () -> Unit): LinearLayout {
    val t = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(2), dp(10), dp(2), dp(10))
        background = ripple(C.SURFACE2, 14)
        setOnClickListener { onClick() }
    }
    t.addView(ImageView(this).apply {
        setImageResource(icon)
        setColorFilter(tint)
        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
    })
    t.addView(label(title, 12f, C.TEXT).apply { gravity = Gravity.CENTER; maxLines = 1; setPadding(0, dp(6), 0, 0) })
    if (sub != null) t.addView(label(sub, 11f, C.MUTED).apply { gravity = Gravity.CENTER; maxLines = 1 })
    return t
}

fun Context.tileGrid(parent: LinearLayout, tiles: List<View>, perRow: Int = 4) {
    var i = 0
    while (i < tiles.size) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        for (j in 0 until perRow) {
            val v = tiles.getOrNull(i + j)
            val lp = LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(4); rightMargin = dp(4) }
            r.addView(v ?: View(this), lp)
        }
        parent.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
        i += perRow
    }
}
