package com.aly.sahltv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Round glass button with a gold line icon (top bar). The name shows under it while it is selected,
 * or for a few seconds when it reports something ("Cleaning…"). A gold dot marks "something new".
 */
class IconBtn(ctx: Context, kind: Int, private var name: CharSequence, private val gold: Int, private val dark: Int, onClick: () -> Unit) : LinearLayout(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val icon = GoldIcon(ctx, kind, gold)
    private val circle = FrameLayout(ctx)
    private val dot = View(ctx)
    private val label = TextView(ctx)
    private val h = Handler(Looper.getMainLooper())
    private val hideLabel = Runnable { if (!hasFocus()) label.animate().alpha(0f).setDuration(250).withEndAction { label.text = name }.start() else label.text = name }

    private fun bg(focused: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (focused) gold else Color.parseColor("#990B1E1A"))
        setStroke(dp(1), if (focused) gold else Color.argb(110, 227, 184, 92))
    }

    /** Name under the button; setting it shows it briefly (used for status messages). */
    var text: CharSequence
        get() = label.text
        set(v) { label.text = v; label.animate().alpha(1f).setDuration(200).start(); h.removeCallbacks(hideLabel); h.postDelayed(hideLabel, 3500) }

    init {
        orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        isFocusable = true; isClickable = true; defaultFocusHighlightEnabled = false
        contentDescription = name
        circle.background = bg(false)
        circle.addView(icon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(gold); setStroke(dp(2), Color.parseColor("#0B1E1A")) }
        dot.visibility = View.GONE
        circle.addView(dot, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.TOP or Gravity.END))
        addView(circle, LayoutParams(dp(52), dp(52)))
        label.text = name; label.textSize = 12f; label.setTextColor(Color.parseColor("#EEF3F1")); label.maxLines = 1
        label.gravity = Gravity.CENTER; label.alpha = 0f; label.setPadding(0, dp(4), 0, 0)
        label.setShadowLayer(6f, 0f, 1f, Color.BLACK)
        addView(label, LayoutParams(dp(110), LayoutParams.WRAP_CONTENT))
        setOnFocusChangeListener { _, has ->
            circle.background = bg(has); icon.color = if (has) dark else gold
            if (has) { h.removeCallbacks(hideLabel); label.animate().alpha(1f).setDuration(180).start() }
            else label.animate().alpha(0f).setDuration(180).withEndAction { label.text = name }.start()
        }
        setOnClickListener { onClick() }
    }

    fun setName(n: CharSequence) { name = n; contentDescription = n; if (hasFocus()) label.text = n else label.text = n }
    fun setBadge(on: Boolean) { dot.visibility = if (on) View.VISIBLE else View.GONE }
}
