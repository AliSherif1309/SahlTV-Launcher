package com.aly.sahlfiles

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** The Sahl look (same colours as the Sahl TV launcher): deep green, soft white text, gold focus. Calm on the eyes. */
object C {
    val BG_TOP = Color.parseColor("#0E1A24")
    val BG_BOTTOM = Color.parseColor("#10261F")
    val PANEL = Color.parseColor("#13241F")
    val CARD = Color.parseColor("#1AFFFFFF")
    val TEXT = Color.parseColor("#EEF3F1")
    val MUTED = Color.parseColor("#8FA39C")
    val GOLD = Color.parseColor("#E3B85C")
    val DARK = Color.parseColor("#0B1E1A")
}

val isAr: Boolean get() = Locale.getDefault().language == "ar"
fun t(en: String, ar: String) = if (isAr) ar else en

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float) = v * resources.displayMetrics.density

fun Context.rounded(color: Int, radiusDp: Int, strokeDp: Int = 0, stroke: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = dpf(radiusDp.toFloat())
    if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
}

fun Context.backdrop() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(C.BG_TOP, C.BG_BOTTOM))

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun Context.label(text: CharSequence, size: Float, color: Int = C.TEXT, bold: Boolean = false) = TextView(this).apply {
    this.text = text; textSize = size; setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
}

/** Rounded button: glass when idle, gold with dark text when selected with the remote. */
fun Context.pill(text: String, onClick: () -> Unit) = TextView(this).apply {
    this.text = text; textSize = 16f; setTextColor(C.TEXT); gravity = Gravity.CENTER
    setPadding(dp(18), dp(10), dp(18), dp(10))
    isFocusable = true; isClickable = true; defaultFocusHighlightEnabled = false
    val idle = rounded(C.CARD, 22, 1, Color.argb(90, 227, 184, 92))
    val on = rounded(C.GOLD, 22)
    background = idle
    setOnFocusChangeListener { _, has ->
        background = if (has) on else idle; setTextColor(if (has) C.DARK else C.TEXT)
        animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(120).start()
    }
    setOnClickListener { onClick() }
}

/** Gold ring + gentle grow for a focusable card. The parent must not clip (clipChildren = false) and should leave ~8 dp around it. */
fun View.goldFocus(ring: View, scale: Float = 1.06f, extra: ((Boolean) -> Unit)? = null) {
    ring.visibility = View.INVISIBLE
    setOnFocusChangeListener { v, has ->
        ring.visibility = if (has) View.VISIBLE else View.INVISIBLE
        v.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f)
            .translationZ(if (has) context.dpf(8f) else 0f).setDuration(140).start()
        extra?.invoke(has)
    }
}

fun Context.ring(radiusDp: Int) = View(this).apply { background = rounded(Color.TRANSPARENT, radiusDp, 3, C.GOLD) }

/** Calm dialogs that work with the remote (▲ ▼ OK Back). */
object Dlg {
    fun base(a: Activity, title: String?, body: View, wide: Boolean = false): Dialog {
        val d = Dialog(a)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = a.rounded(C.PANEL, 18, 1, Color.argb(120, 227, 184, 92))
            setPadding(a.dp(20), a.dp(16), a.dp(20), a.dp(16))
            layoutDirection = if (isAr) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            clipChildren = false; clipToPadding = false
        }
        if (title != null) panel.addView(a.label(title, 21f, C.GOLD, true).apply { typeface = Fonts.display(true); setPadding(a.dp(4), 0, a.dp(4), a.dp(10)) })
        panel.addView(body)
        d.setContentView(panel)
        Fonts.watch(d.window)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(a.dp(if (wide) 620 else 460), ViewGroup.LayoutParams.WRAP_CONTENT)
        return d
    }

    fun row(a: Context, text: String, onClick: () -> Unit) = TextView(a).apply {
        this.text = text; textSize = 17f; setTextColor(C.TEXT)
        setPadding(a.dp(14), a.dp(11), a.dp(14), a.dp(11))
        isFocusable = true; isClickable = true; defaultFocusHighlightEnabled = false
        val on = a.rounded(C.GOLD, 10)
        setOnFocusChangeListener { _, has -> background = if (has) on else null; setTextColor(if (has) C.DARK else C.TEXT) }
        setOnClickListener { onClick() }
    }

    /** A list of choices. Picking one closes the dialog, then runs it. */
    fun menu(a: Activity, title: String?, items: List<Pair<String, () -> Unit>>, focus: Int = 0, onClose: (() -> Unit)? = null) {
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        lateinit var d: Dialog
        items.forEach { (name, run) -> list.addView(row(a, name) { d.dismiss(); run() }) }
        val sc = ScrollView(a).apply { isVerticalScrollBarEnabled = false; addView(list) }
        d = base(a, title, sc)
        d.setOnDismissListener { onClose?.invoke() }
        d.setOnShowListener { list.getChildAt(focus.coerceIn(0, maxOf(0, items.size - 1)))?.requestFocus() }
        d.show()
    }

    fun confirm(a: Activity, title: String, msg: String, yes: String, onYes: () -> Unit) {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        box.addView(a.label(msg, 17f, C.TEXT).apply { typeface = Fonts.text(); setLineSpacing(0f, 1.2f); setPadding(a.dp(4), 0, a.dp(4), a.dp(14)) })
        lateinit var d: Dialog
        val btns = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; clipChildren = false }
        val no = a.pill(t("Cancel", "إلغاء")) { d.dismiss() }
        btns.addView(no)
        btns.addView(a.pill(yes) { d.dismiss(); onYes() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = a.dp(12) })
        box.addView(btns)
        d = base(a, title, box)
        d.setOnShowListener { no.requestFocus() }      // the safe choice is selected first
        d.show()
    }

    fun message(a: Activity, title: String, msg: CharSequence) {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        box.addView(a.label(msg, 17f, C.TEXT).apply { typeface = Fonts.text(); setLineSpacing(0f, 1.2f); setPadding(a.dp(4), 0, a.dp(4), a.dp(14)) })
        lateinit var d: Dialog
        val ok = a.pill(t("OK", "حسنًا")) { d.dismiss() }
        box.addView(ok, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
        d = base(a, title, box)
        d.setOnShowListener { ok.requestFocus() }
        d.show()
    }

    fun input(a: Activity, title: String, initial: String, numeric: Boolean, onOk: (String) -> Unit) {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val et = EditText(a).apply {
            setText(initial); setSelection(initial.length); textSize = 20f; setTextColor(C.TEXT); setHintTextColor(C.MUTED)
            background = a.rounded(C.CARD, 10, 1, C.GOLD); setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10))
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE; isSingleLine = true
        }
        box.addView(et)
        lateinit var d: Dialog
        val go = { val s = et.text.toString().trim(); d.dismiss(); if (s.isNotEmpty()) onOk(s) }
        et.setOnEditorActionListener { _, id, ev -> if (id == EditorInfo.IME_ACTION_DONE || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { go(); true } else false }
        val btns = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, a.dp(14), 0, 0); clipChildren = false }
        btns.addView(a.pill(t("Cancel", "إلغاء")) { d.dismiss() })
        btns.addView(a.pill(t("OK", "موافق")) { go() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = a.dp(12) })
        box.addView(btns)
        d = base(a, title, box)
        d.setOnShowListener { et.requestFocus() }
        d.show()
    }
}

/** A focusable frame with rounded corners, a gold ring on top, and gentle grow on focus. */
fun Context.card(radiusDp: Int, color: Int): Pair<FrameLayout, View> {
    val f = FrameLayout(this).apply {
        isFocusable = true; isClickable = true; defaultFocusHighlightEnabled = false
        background = rounded(color, radiusDp); clipToOutline = true
    }
    val r = ring(radiusDp)
    return f to r
}
