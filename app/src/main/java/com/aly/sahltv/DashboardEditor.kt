package com.aly.sahltv

import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.view.Gravity
import android.view.View
import com.aly.sahltv.MainActivity.*

/** Staged dashboard settings; all edits are committed together on Apply. */
internal fun MainActivity.dashboardEditor() {
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
    val enabled = dashboardKeys.associateWith { prefs.getBoolean("dashboardShow_$it", true) }.toMutableMap()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }
    fun redraw() {
        root.removeAllViews()
        root.addView(TextView(this).apply {
            text = t("Select a card to show/hide it. Use arrows to change its order. Changes are saved only when you Apply.",
                "اختر بطاقة لإظهارها أو إخفائها، واستخدم الأسهم لتغيير ترتيبها. تُحفظ التغييرات عند الضغط على تطبيق فقط.")
            setTextColor(TEXT); textSize = 15f; setPadding(dp(8), dp(8), dp(8), dp(14))
        })
        order.forEachIndexed { index, key ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            fun control(textValue: String, action: () -> Unit): TextView = TextView(this).apply {
                text = textValue
                textSize = 17f
                setTextColor(TEXT)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isFocusable = true; isClickable = true
                background = cardBg(false, false)
                setOnFocusChangeListener { v, focused -> v.background = cardBg(false, focused) }
                setOnClickListener { action(); redraw() }
            }
            row.addView(control((if (enabled[key] == true) "☑ " else "☐ ") + names[key]) {
                enabled[key] = enabled[key] != true
            }, LinearLayout.LayoutParams(0, dp(54), 1f))
            row.addView(control("↑") {
                if (index > 0) { order.removeAt(index); order.add(index - 1, key) }
            })
            row.addView(control("↓") {
                if (index < order.lastIndex) { order.removeAt(index); order.add(index + 1, key) }
            })
            root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
    }
    redraw()
    dialog().setTitle(t("Upper dashboard cards", "بطاقات اللوحة العلوية"))
        .setView(ScrollView(this).apply { addView(root) })
        .setPositiveButton(t("Apply", "تطبيق")) { _, _ ->
            val edit = prefs.edit().putString("dashboardOrder", order.joinToString("|"))
            enabled.forEach { (key, on) -> edit.putBoolean("dashboardShow_$key", on) }
            edit.apply()
            // Rebuild to restore contextual visibility before applying the custom selection.
            recreate()
        }
        .setNegativeButton(t("Cancel", "إلغاء"), null)
        .show()
}
