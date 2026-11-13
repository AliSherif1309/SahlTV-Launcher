package com.aly.sahltv

import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.view.Gravity
import android.view.View
import com.aly.sahltv.MainActivity.*

/** Staged dashboard settings; all edits are committed together on Apply. */
internal fun MainActivity.dashboardEditor(): android.app.AlertDialog {
    val names = mapOf(
        "prayer" to t("Prayer times", "مواقيت الصلاة"),
        "ramadan" to t("Ramadan / occasions", "رمضان والمناسبات"),
        "fasting" to t("Sunnah fasting", "صيام السنة"),
        "adhkar" to t("Adhkar", "الأذكار"),
        "ayah" to t("Ayah of the day", "آية اليوم"),
        "news" to t("What's new", "ما الجديد"),
        "greeting" to t("Seasonal greeting", "تهنئة المناسبات")
    )
    val order = dashboardOrder().toMutableList()
    var flexible = prefs.getBoolean("dashboardFlexible", false)
    val widths = dashboardKeys.associateWith { prefs.getString("dashboardWidth_$it", "full") ?: "full" }.toMutableMap()
    val enabled = dashboardKeys.associateWith { prefs.getBoolean("dashboardShow_$it", true) }.toMutableMap()
    val sizes = dashboardKeys.associateWith { prefs.getString("dashboardSize_$it", "normal") ?: "normal" }.toMutableMap()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }
    var focusKey: String? = null
    var focusControl = 0
    fun redraw() {
        root.removeAllViews()
        var restoreFocus: View? = null
        root.addView(TextView(this).apply {
            text = t("Select a card to show/hide it. S/M/L changes height; arrows reorder. Optional flexible mode unlocks cross-group moves and full/half widths. Apply to save.",
                "اختر بطاقة لإظهارها أو إخفائها، واختر S/M/L لضبط ارتفاعها والأسهم لترتيبها. تُحفظ التغييرات عند تطبيق فقط.")
            setTextColor(TEXT); textSize = 15f; setPadding(dp(8), dp(8), dp(8), dp(14))
        })
        root.addView(TextView(this).apply {
            text = if (flexible) t("☑ Flexible cards · full/half widths", "☑ بطاقات مرنة · عرض كامل أو نصف")
                else t("☐ Original dashboard layout (unchanged)", "☐ التخطيط الأصلي للبطاقات (بلا تغيير)")
            setTextColor(TEXT)
            textSize = 16f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isFocusable = true
            isClickable = true
            background = cardBg(false, false)
            setOnFocusChangeListener { v, focused -> v.background = cardBg(false, focused) }
            setOnClickListener { flexible = !flexible; redraw() }
        })
        if (flexible) {
            root.addView(TextView(this).apply {
                text = t("Preview · card order and widths", "معاينة · ترتيب البطاقات وعرضها")
                setTextColor(GOLD); textSize = 14f
                setPadding(dp(8), dp(8), dp(8), dp(6))
            })
            val previewRows = flexibleDashboardRows(order, widths)
            for (keys in previewRows) {
                val previewRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (key in keys) {
                    previewRow.addView(TextView(this).apply {
                        text = (if (enabled[key] == true) "" else "◌ ") + (names[key] ?: key)
                        textSize = 12f
                        setTextColor(TEXT)
                        gravity = Gravity.CENTER
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                        background = cardBg(false, false)
                        alpha = if (enabled[key] == true) 1f else 0.45f
                    }, LinearLayout.LayoutParams(0, dp(43), 1f).apply { marginEnd = dp(6); bottomMargin = dp(5) })
                }
                root.addView(previewRow)
            }
        }
        order.forEachIndexed { index, key ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            var controlIndex = 0
            fun control(textValue: String, action: () -> Unit): TextView = TextView(this).apply {
                val myIndex = controlIndex++
                if (focusKey == key && focusControl == myIndex) restoreFocus = this
                text = textValue
                textSize = 17f
                setTextColor(TEXT)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isFocusable = true; isClickable = true
                background = cardBg(false, false)
                setOnFocusChangeListener { v, focused -> v.background = cardBg(false, focused) }
                setOnClickListener { focusKey = key; focusControl = myIndex; action(); redraw() }
            }
            row.addView(control((if (enabled[key] == true) "☑ " else "☐ ") + names[key]) {
                enabled[key] = enabled[key] != true
            }, LinearLayout.LayoutParams(0, dp(54), 1f))
            row.addView(control(when (sizes[key]) { "compact" -> "S"; "large" -> "L"; else -> "M" }) {
                sizes[key] = when (sizes[key]) { "compact" -> "normal"; "normal" -> "large"; else -> "compact" }
            })
            row.addView(control("↑") {
                if (index > 0) { order.removeAt(index); order.add(index - 1, key) }
            })
            row.addView(control("↓") {
                if (index < order.lastIndex) { order.removeAt(index); order.add(index + 1, key) }
            })
            if (key != "prayer") {
                row.addView(control(if (widths[key] == "half") t("½ width", "نصف") else t("Full", "كامل")) {
                    widths[key] = if (widths[key] == "half") "full" else "half"
                })
            }
            root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        restoreFocus?.requestFocus()
    }
    redraw()
    return dialog().setTitle(t("Upper dashboard cards", "بطاقات اللوحة العلوية"))
        .setView(ScrollView(this).apply { addView(root) })
        .setPositiveButton(t("Apply", "تطبيق")) { _, _ ->
            val edit = prefs.edit().putString("dashboardOrder", order.joinToString("|"))
            enabled.forEach { (key, on) -> edit.putBoolean("dashboardShow_$key", on) }
            sizes.forEach { (key, size) -> edit.putString("dashboardSize_$key", size) }
            widths.forEach { (key, width) -> edit.putString("dashboardWidth_$key", width) }
            edit.putBoolean("dashboardFlexible", flexible)
            edit.apply()
            // Rebuild to restore contextual visibility before applying the custom selection.
            recreate()
        }
        .setNegativeButton(t("Cancel", "إلغاء"), null)
        .show()
}

