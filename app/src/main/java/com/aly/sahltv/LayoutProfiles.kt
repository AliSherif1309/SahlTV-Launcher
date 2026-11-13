package com.aly.sahltv

import org.json.JSONObject
import android.widget.EditText
import com.aly.sahltv.MainActivity.*

private val layoutFields = listOf("homeTileSize", "homeCombined", "homeShowFavorites", "homeShowRecent", "homeRecentFirst", "dashboardOrder") +
    dashboardKeys.flatMap { listOf("dashboardShow_$it", "dashboardSize_$it") }

/** Named local presets, never altering favorites, installed apps or unrelated preferences. */
internal fun MainActivity.layoutProfilesMenu() {
    val slots = listOf("Default", "Minimal", "Family", "Custom1", "Custom2", "Custom3")
    fun display(slot: String): String = when (slot) {
        "Default" -> t("Default", "الافتراضي")
        "Minimal" -> t("Minimal", "البسيط")
        "Family" -> t("Family", "العائلي")
        else -> prefs.getString("layoutProfileName_$slot", null)?.takeIf { it.isNotBlank() }
            ?: t("Custom ${slot.takeLast(1)}", "مخصص ${slot.takeLast(1)}")
    }
    val actions = slots.flatMap { slot ->
        listOf(t("Apply ", "تطبيق ") + display(slot), t("Save current as ", "حفظ الحالي باسم ") + display(slot))
    }
    dialog().setTitle(t("Layout profiles", "ملفات التخطيط"))
        .setItems(actions.toTypedArray()) { _, choice ->
            val name = slots[choice / 2]
            val key = "layoutProfile_$name"
            if (choice % 2 == 1) {
                val data = JSONObject()
                for (field in layoutFields) {
                    if (!prefs.contains(field)) continue
                    if (field == "homeTileSize" || field == "dashboardOrder" || field.startsWith("dashboardSize_"))
                        data.put(field, prefs.getString(field, ""))
                    else data.put(field, prefs.getBoolean(field, true))
                }
                if (name.startsWith("Custom")) {
                    val entry = EditText(this).apply {
                        setSingleLine(true)
                        setText(display(name))
                        selectAll()
                    }
                    dialog().setTitle(t("Name your layout", "اسم التخطيط"))
                        .setView(entry)
                        .setPositiveButton(t("Save", "حفظ")) { _, _ ->
                            val label = entry.text.toString().trim().take(32)
                            if (label.isEmpty()) {
                                toast(t("Enter a profile name", "أدخل اسم التخطيط"))
                            } else {
                                prefs.edit().putString(key, data.toString())
                                    .putString("layoutProfileName_$name", label).apply()
                                toast(t("Layout saved: $label", "تم حفظ التخطيط: $label"))
                            }
                        }.setNegativeButton(t("Cancel", "إلغاء"), null).show()
                } else {
                    prefs.edit().putString(key, data.toString()).apply()
                    toast(t("Layout saved: $name", "تم حفظ التخطيط: $name"))
                }
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
                if (name.startsWith("Custom") && raw == null) {
                    toast(t("Save a layout to this slot first", "احفظ تخطيطًا في هذا الموضع أولًا"))
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
