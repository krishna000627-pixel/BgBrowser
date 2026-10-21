package com.bgbrowser

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

object C {
    val BG = Color.parseColor("#0B1220")
    val BAR = Color.parseColor("#111A2C")
    val SURFACE = Color.parseColor("#151F33")
    val SURFACE2 = Color.parseColor("#1F2C47")
    val GOLD = Color.parseColor("#F5B83D")
    val TEXT = Color.parseColor("#E8ECF4")
    val MUTED = Color.parseColor("#8A97B0")
    val GREEN = Color.parseColor("#4ADE80")
    val RED = Color.parseColor("#FF6B6B")
    val PURPLE = Color.parseColor("#B388FF")
    val AMBER = Color.parseColor("#FBBF24")
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Int): Float = v * resources.displayMetrics.density

fun Context.rounded(color: Int, radiusDp: Int): GradientDrawable =
    GradientDrawable().apply { setColor(color); cornerRadius = dpf(radiusDp) }

fun Context.ripple(color: Int, radiusDp: Int): Drawable =
    RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(color, radiusDp), rounded(Color.WHITE, radiusDp))

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
