package com.aly.sahltv

import com.aly.sahltv.MainActivity.*

/** User-controlled home layout. Islamic dashboard dimensions are never changed here. */
internal val MainActivity.homeTileSize: String
    get() = prefs.getString("homeTileSize", "compact") ?: "compact"

internal val MainActivity.homeCombined: Boolean
    get() = prefs.getBoolean("homeCombined", false)

internal val MainActivity.homeRecentFirst: Boolean
    get() = prefs.getBoolean("homeRecentFirst", false)

internal val MainActivity.homeShowFavorites: Boolean
    get() = prefs.getBoolean("homeShowFavorites", true)

internal val MainActivity.homeShowRecent: Boolean
    get() = prefs.getBoolean("homeShowRecent", true)

internal fun MainActivity.homeSizeName() = when (homeTileSize) {
    "normal" -> t("Normal", "عادي")
    "large" -> t("Large", "كبير")
    else -> t("Compact", "مدمج")
}

internal fun MainActivity.homeLayoutName() =
    if (homeCombined) t("Combined", "صف واحد") else t("Separate", "صفان")

/** Preserve current selection and scroll position where possible on live updates. */
internal fun MainActivity.homeChanged() {
    render(keepFocus = true)
    fillSettings()
}

internal fun MainActivity.homeSizeMenu() {
    val sizes = arrayOf("compact", "normal", "large")
    val names = arrayOf(t("Compact · 130 × 73", "مدمج · ١٣٠ × ٧٣"),
        t("Normal · 160 × 90", "عادي · ١٦٠ × ٩٠"),
        t("Large · 200 × 112", "كبير · ٢٠٠ × ١١٢"))
    dialog().setTitle(t("App tile size", "حجم مربعات التطبيقات"))
        .setSingleChoiceItems(names, sizes.indexOf(homeTileSize).coerceAtLeast(0)) { d, which ->
            d.dismiss()
            prefs.edit().putString("homeTileSize", sizes[which]).apply()
            recreate()
        }.setNegativeButton(t("Cancel", "إلغاء"), null).show()
}

internal fun MainActivity.homeArrangementMenu() {
    val names = arrayOf(t("Separate Favorites and Recent rows", "صف منفصل للمفضلة والمستخدمة مؤخرًا"),
        t("Combined row · Favorites first", "صف واحد · المفضلة أولًا"))
    dialog().setTitle(t("App row layout", "ترتيب صفوف التطبيقات"))
        .setSingleChoiceItems(names, if (homeCombined) 1 else 0) { d, which ->
            d.dismiss()
            prefs.edit().putBoolean("homeCombined", which == 1).apply()
            homeChanged()
        }.setNegativeButton(t("Cancel", "إلغاء"), null).show()
}

internal fun MainActivity.homeResetMenu() {
    dialog().setTitle(t("Reset Home layout?", "إعادة ترتيب الشاشة الرئيسية؟"))
        .setMessage(t("Restores compact tiles, separate Favorites and Recent rows, Favorites first, and both rows visible. Your favorites and Islamic cards will not change.",
            "يعيد المربعات المدمجة والصفوف المنفصلة وإظهار المفضلة والمستخدمة مؤخرًا. لن تتغير تطبيقاتك المفضلة أو البطاقات الإسلامية."))
        .setPositiveButton(t("Reset", "إعادة الضبط")) { _, _ ->
            prefs.edit().remove("homeTileSize").remove("homeCombined")
                .remove("homeShowFavorites").remove("homeShowRecent").remove("homeRecentFirst")
                .remove("dashboardOrder").apply()
            prefs.edit().apply {
                for (key in dashboardKeys) { remove("dashboardShow_$key"); remove("dashboardSize_$key") }
            }.apply()
            homeChanged()
        }.setNegativeButton(t("Cancel", "إلغاء"), null).show()
}
