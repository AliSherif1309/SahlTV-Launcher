package com.aly.sahltv

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import com.aly.sahltv.MainActivity.*

/** A staged, remote-friendly visual preview. Nothing is persisted until Apply. */
internal fun MainActivity.homeVisualEditor(): android.app.AlertDialog {
    val sizes = arrayOf("compact", "normal", "large")
    var size = homeTileSize
    var combined = homeCombined
    var showFav = homeShowFavorites
    var showRecent = homeShowRecent
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    val preview = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            setColor(Color.rgb(12, 34, 29))
            cornerRadius = dp(12).toFloat()
        }
    }
    root.addView(preview)
    fun label(textValue: String, height: Int = 0): TextView = TextView(this).apply {
        text = textValue
        textSize = 14f
        setTextColor(Color.WHITE)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        if (height > 0) minHeight = dp(height)
    }
    fun drawPreview() {
        preview.removeAllViews()
        preview.addView(label(t("HOME  •  layout preview", "الرئيسية  •  معاينة التخطيط")))
        // Dashboard cards are deliberately shown at their original proportions in this first stage.
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(t("Clock", "الساعة"), t("Prayer", "الصلاة"), t("Islamic", "إسلامي")).forEach {
            val card = label(it, 46).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.rgb(35, 67, 58))
                    cornerRadius = dp(7).toFloat()
                }
            }
            top.addView(card, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
        }
        preview.addView(top)
        val tileCount = when (size) { "large" -> 3; "normal" -> 4; else -> 5 }
        fun appRow(title: String) {
            preview.addView(label(title))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(tileCount) {
                val tile = TextView(this).apply {
                    text = "▣"
                    gravity = Gravity.CENTER
                    textSize = 19f
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(45, 76, 69))
                        cornerRadius = dp(6).toFloat()
                    }
                }
                row.addView(tile, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(5) })
            }
            preview.addView(row)
        }
        if (combined) {
            if (showFav || showRecent) appRow(t("Apps · Favorites first", "التطبيقات · المفضلة أولًا"))
        } else {
            if (showFav) appRow(t("Favorites", "المفضلة"))
            if (showRecent) appRow(t("Recent apps", "المستخدمة مؤخرًا"))
        }
        preview.addView(label(t("App tile size: ", "حجم التطبيقات: ") + size))
    }
    drawPreview()
    fun option(textValue: String, action: () -> Unit) {
        root.addView(label(textValue).apply {
            isFocusable = true
            isClickable = true
            setOnClickListener { action(); drawPreview() }
            fun focusBackground(focused: Boolean) = GradientDrawable().apply {
                setColor(if (focused) Color.rgb(215, 180, 102) else Color.rgb(36, 54, 49))
                cornerRadius = dp(6).toFloat()
            }
            background = focusBackground(false)
            setOnFocusChangeListener { view, focused ->
                view.background = focusBackground(focused)
                (view as TextView).setTextColor(if (focused) Color.BLACK else Color.WHITE)
            }
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(7) })
    }
    option(t("Change tile size", "تغيير حجم التطبيقات")) {
        size = sizes[(sizes.indexOf(size).coerceAtLeast(0) + 1) % sizes.size]
    }
    option(t("Toggle combined / separate rows", "تبديل الصفوف المجمّعة / المنفصلة")) { combined = !combined }
    option(t("Show / hide Favorites", "إظهار / إخفاء المفضلة")) { showFav = !showFav }
    option(t("Show / hide Recent apps", "إظهار / إخفاء التطبيقات الأخيرة")) { showRecent = !showRecent }
    val scrollView = ScrollView(this).apply { addView(root) }
    return dialog().setTitle(t("Visual layout editor", "محرر التخطيط المرئي"))
        .setView(scrollView)
        .setPositiveButton(t("Apply", "تطبيق")) { _, _ ->
            prefs.edit().putString("homeTileSize", size)
                .putBoolean("homeCombined", combined)
                .putBoolean("homeShowFavorites", showFav)
                .putBoolean("homeShowRecent", showRecent).apply()
            homeChanged()
        }
        .setNegativeButton(t("Cancel", "إلغاء"), null)
        .show()
}
