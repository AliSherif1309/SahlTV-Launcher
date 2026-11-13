package com.aly.sahltv

import org.json.JSONObject
import com.aly.sahltv.MainActivity.*

private val layoutFields = listOf("homeTileSize", "homeCombined", "homeShowFavorites", "homeShowRecent", "dashboardOrder") +
    dashboardKeys.flatMap { listOf("dashboardShow_$it", "dashboardSize_$it") }

/** Named local presets, never altering favorites, installed apps or unrelated preferences. */
internal fun MainActivity.layoutProfilesMenu() {
    val names = listOf("Default", "Minimal", "Family")
    val actions = names.flatMap { name ->
        listOf(t("Apply $name", "تطبيق $name"), t("Save current as $name", "حفظ الحالي باسم $name"))
    }
    dialog().setTitle(t("Layout profiles", "ملفات التخطيط"))
        .setItems(actions.toTypedArray()) { _, choice ->
            val name = names[choice / 2]
            val key = "layoutProfile_$name"
            if (choice % 2 == 1) {
                val data = JSONObject()
                for (field in layoutFields) {
                    if (!prefs.contains(field)) continue
                    if (field == "homeTileSize" || field == "dashboardOrder" || field.startsWith("dashboardSize_"))
                        data.put(field, prefs.getString(field, ""))
                    else data.put(field, prefs.getBoolean(field, true))
                }
                prefs.edit().putString(key, data.toString()).apply()
                toast(t("Layout saved: $name", "تم حفظ التخطيط: $name"))
            } else {
                val raw = prefs.getString(key, null)
                val data = try { if (raw != null) JSONObject(raw) else when (name) {
                    "Minimal" -> JSONObject().put("homeTileSize", "compact").put("homeCombined", true)
                        .put("homeShowFavorites", true).put("homeShowRecent", false)
                        .put("dashboardShow_news", false).put("dashboardShow_greeting", false)
                        .put("dashboardShow_ayah", false)
                    "Family" -> JSONObject().put("homeTileSize", "large").put("homeCombined", false)
                        .put("homeShowFavorites", true).put("homeShowRecent", true)
                    else -> JSONObject()
                } } catch (_: Exception) {
                    toast(t("Invalid saved profile", "ملف تخطيط غير صالح"))
                    return@setItems
                }
                val edit = prefs.edit()
                for (field in layoutFields) {
                    if (!data.has(field)) edit.remove(field)
                    else if (field == "homeTileSize" || field == "dashboardOrder" || field.startsWith("dashboardSize_"))
                        edit.putString(field, data.optString(field))
                    else edit.putBoolean(field, data.optBoolean(field, true))
                }
                edit.apply()
                recreate()
            }
        }
        .setNegativeButton(t("Cancel", "إلغاء"), null).show()
}
