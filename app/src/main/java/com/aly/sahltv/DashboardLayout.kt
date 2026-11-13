package com.aly.sahltv

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.aly.sahltv.MainActivity.*

/** Optional dashboard arrangement; absent preferences preserve the original home. */
internal val dashboardKeys = listOf("prayer", "ramadan", "fasting", "adhkar", "ayah", "news", "greeting")

internal fun MainActivity.dashboardOrder(): List<String> {
    val stored = (prefs.getString("dashboardOrder", "") ?: "").split("|")
    return stored.filter { it in dashboardKeys }.distinct() + dashboardKeys.filter { it !in stored }
}

internal fun MainActivity.applyDashboardCardPreferences() {
    if (!panelReady) return
    val views = mapOf(
        "prayer" to prayRow, "ramadan" to ramCard, "fasting" to fastCard,
        "adhkar" to adhkarCard, "ayah" to ayahCard,
        "news" to newsCard, "greeting" to greetCard
    )
    // Preserve contextual visibility (e.g. no fasting card during Ramadan).
    // This only suppresses cards the user explicitly disabled.
    for ((key, view) in views) {
        if (!prefs.getBoolean("dashboardShow_$key", true)) view.visibility = View.GONE
    }
    for ((key, view) in views) {
        val size = prefs.getString("dashboardSize_$key", "normal")
        val height = when (size) { "compact" -> 48; "large" -> 110; else -> 0 }
        if (height > 0 && key != "prayer") view.minimumHeight = dp(height)
        if (key == "prayer") {
            val params = view.layoutParams
            if (params != null) {
                // Prayer row height is governed by its children; avoid clipping it.
                view.minimumHeight = dp(if (size == "large") 110 else 84)
            }
        }
    }
    if (!prefs.contains("dashboardOrder")) return
    val order = dashboardOrder()
    // Reorder within existing parent containers only. Moving cards between
    // containers would break their layout parameters and contextual behavior.
    for (parent in listOf(panel, ramCard.parent as? LinearLayout)) {
        if (parent == null) continue
        val entries = order.mapNotNull { key ->
            val v = views[key] ?: return@mapNotNull null
            if (v.parent === parent) key to v else null
        }
        if (entries.size < 2) continue
        val original = entries.map { it.second }
        val params = original.associateWith { it.layoutParams }
        val insertAt = original.map { parent.indexOfChild(it) }.filter { it >= 0 }.minOrNull() ?: 0
        original.forEach { parent.removeView(it) }
        entries.forEachIndexed { index, (_, v) ->
            parent.addView(v, (insertAt + index).coerceAtMost(parent.childCount), params[v])
        }
    }
}
