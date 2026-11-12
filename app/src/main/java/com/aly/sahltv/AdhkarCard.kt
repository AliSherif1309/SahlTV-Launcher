package com.aly.sahltv

import android.content.Intent
import android.view.View
import java.time.LocalDate

// Adhkar card: morning adhkar from Fajr until Dhuhr, evening adhkar from Asr until Isha. Hidden once read today.

internal val MainActivity.adhkarOn get() = prefs.getBoolean("adhkarOn", true)

/** "m" (morning), "e" (evening) or null, by today's prayer times. */
internal fun MainActivity.adhkarKind(): String? {
    if (!adhkarOn) return null
    val tt = timesFor(LocalDate.now()) ?: return null
    val today = LocalDate.now(); val now = System.currentTimeMillis()
    return when {
        now >= epochAt(today, tt.fajr) && now < epochAt(today, tt.dhuhr) -> "m"
        now >= epochAt(today, tt.asr) && now < epochAt(today, tt.isha) -> "e"
        else -> null
    }
}

internal fun MainActivity.updateAdhkar() {
    if (!adhkarCardReady) return
    val k = adhkarKind()
    val state = k?.let { prefs.getString(AdhkarActivity.progressKey(it), "") } ?: ""
    if (k == null || state == "done") { if (adhkarCard.visibility != View.GONE) adhkarCard.visibility = View.GONE; return }
    val total = adhkarTotals(this).let { if (k == "m") it.first else it.second }
    val at = state.substringBefore(":", "").toIntOrNull()
    val name = if (k == "m") t("Morning adhkar", "أذكار الصباح") else t("Evening adhkar", "أذكار المساء")
    adhkarTitle.text = if (at != null && at > 0) digits(t("$name  ·  ${at + 1}/$total", "$name  ·  ${at + 1}/$total")) else name
    adhkarCard.visibility = View.VISIBLE
}

private var totals: Pair<Int, Int>? = null
private fun adhkarTotals(a: MainActivity): Pair<Int, Int> = totals ?: (AdhkarActivity.load(a)?.let {
    (it.optJSONArray("m")?.length() ?: 0) to (it.optJSONArray("e")?.length() ?: 0) } ?: (0 to 0)).also { totals = it }

internal fun MainActivity.openAdhkar() {
    val k = adhkarKind() ?: "m"
    startActivity(Intent(this, AdhkarActivity::class.java).putExtra("kind", k))
}
