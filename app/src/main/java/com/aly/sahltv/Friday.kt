package com.aly.sahltv

import android.content.Intent
import java.time.DayOfWeek
import java.time.LocalDate

// Friday (Jumu'ah) mode: from Thursday Maghrib until Friday Maghrib, the greeting card follows the day —
// Surah Al-Kahf (OK opens it in Sahl Quran), the Jumu'ah prayer with a countdown, and the last hour before Maghrib.

internal val MainActivity.fridayOn get() = prefs.getBoolean("fridayOn", true)

/** "eve" (Thursday after Maghrib), "day", "jumuah" (90 min before Dhuhr until 30 min after), "hour" (last hour before Maghrib), or null. */
internal fun MainActivity.fridayPhase(): String? {
    if (!fridayOn) return null
    val today = LocalDate.now()
    val now = System.currentTimeMillis()
    val tt = timesFor(today)
    return when (today.dayOfWeek) {
        DayOfWeek.THURSDAY -> if (tt != null && now >= epochAt(today, tt.maghrib)) "eve" else null
        DayOfWeek.FRIDAY -> {
            if (tt == null) return "day"
            val maghrib = epochAt(today, tt.maghrib); val dhuhr = epochAt(today, tt.dhuhr)
            when {
                now >= maghrib -> null
                now >= maghrib - 60 * 60_000L -> "hour"
                now in (dhuhr - 90 * 60_000L) until (dhuhr + 30 * 60_000L) -> "jumuah"
                else -> "day"
            }
        }
        else -> null
    }
}

/** Card key: the Jumu'ah countdown changes every minute, so its minutes are part of the key. */
internal fun MainActivity.fridayKey(phase: String): String {
    if (phase != "jumuah") return "fri_$phase"
    val tt = timesFor(LocalDate.now()) ?: return "fri_jumuah_0"
    val min = ((epochAt(LocalDate.now(), tt.dhuhr) - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
    return "fri_jumuah_$min"
}

internal fun MainActivity.fridayCard(key: String): Triple<String, String, Int> {
    val okKahf = t("press OK to open it", "اضغط OK لفتحها")
    return when {
        key == "fri_eve" -> Triple(t("Friday night", "ليلة الجمعة"), t("Read Surah Al-Kahf  ·  ", "اقرأ سورة الكهف  ·  ") + okKahf, GoldIcon.BOOK)
        key == "fri_hour" -> Triple(t("The hour of response", "ساعة الإجابة"), t("The last hour of Friday  ·  make du'a", "آخر ساعة من يوم الجمعة  ·  أكثِر من الدعاء"), GoldIcon.STAR)
        key.startsWith("fri_jumuah") -> {
            val min = key.substringAfterLast('_').toIntOrNull() ?: 0
            Triple(t("Jumu'ah prayer", "صلاة الجمعة"),
                if (min > 0) digits(t("In $min min  ·  ghusl, perfume, and go early", "بعد $min دقيقة  ·  الغسل والطيب والتبكير إلى المسجد"))
                else t("May Allah accept from us and from you", "تقبّل الله منّا ومنكم"), GoldIcon.MOSQUE)
        }
        else -> Triple(t("Jumu'ah", "يوم الجمعة"), t("Read Surah Al-Kahf  ·  send salawat upon the Prophet ﷺ  ·  ", "اقرأ سورة الكهف  ·  وأكثِر من الصلاة على النبي ﷺ  ·  ") + okKahf, GoldIcon.BOOK)
    }
}

/** OK on the card: on Friday (and Thursday night) it opens Surah Al-Kahf; otherwise the greetings menu. */
internal fun MainActivity.greetClick() {
    val k = greetShown
    if (k != null && k.startsWith("fri_") && !k.startsWith("fri_jumuah") && k != "fri_hour") {
        try {
            startActivity(Intent().setClassName("com.aly.sahlquran", "com.aly.sahlquran.QuranActivity")
                .putExtra("surah", 18).putExtra("ayah", 1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { toast(t("Install Sahl Quran from ⬆ Updates", "ثبّت سهل · القرآن من زر التحديثات")) }
    } else greetingMenu()
}
