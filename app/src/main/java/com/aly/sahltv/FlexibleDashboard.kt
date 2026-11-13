package com.aly.sahltv

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import java.util.WeakHashMap
import com.aly.sahltv.MainActivity.*

/** Pure, testable layout plan. The prayer strip always remains full-width. */
internal fun flexibleDashboardRows(order: List<String>, widths: Map<String, String>): List<List<String>> {
    val rows = mutableListOf<MutableList<String>>()
    for (key in order.filter { it in dashboardKeys }.distinct()) {
        val half = key != "prayer" && widths[key] == "half"
        if (half && rows.lastOrNull()?.let { it.size == 1 && it[0] != "prayer" && widths[it[0]] == "half" } == true) {
            rows.last().add(key)
        } else {
            rows.add(mutableListOf(key))
        }
    }
    return rows
}

private val appliedPlans = WeakHashMap<LinearLayout, String>()

/**
 * Opt-in only: move existing card views into shared rows instead of recreating them.
 * Their callbacks, dynamic visibility, focusability and content remain intact.
 * No change to the top clock/weather/settings bar, or to app tiles.
 */
internal fun MainActivity.applyFlexibleDashboardCards() {
    val widths = dashboardKeys.associateWith {
        prefs.getString("dashboardWidth_$it", "full") ?: "full"
    }
    val order = dashboardOrder()
    val signature = order.joinToString("|") + ";" + widths.entries.joinToString("|") { it.key + ":" + it.value }
    if (appliedPlans[panel] == signature) return

    val cards = mapOf<String, View>(
        "prayer" to prayRow, "ramadan" to ramCard, "fasting" to fastCard,
        "adhkar" to adhkarCard, "ayah" to ayahCard,
        "news" to newsCard, "greeting" to greetCard
    )
    // First remove children from their original parents; then clear the now-empty
    // original containers. Only flexible mode uses this alternate hierarchy.
    cards.values.forEach { card -> (card.parent as? ViewGroup)?.removeView(card) }
    panel.removeAllViews()
    for (keys in flexibleDashboardRows(order, widths)) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        val two = keys.size == 2
        for ((index, key) in keys.withIndex()) {
            val card = cards[key] ?: continue
            row.addView(card, LinearLayout.LayoutParams(
                if (two) 0 else LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                if (two) 1f else 0f
            ).apply { if (index > 0) marginStart = dp(12) })
        }
        panel.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })
    }
    appliedPlans[panel] = signature
}
