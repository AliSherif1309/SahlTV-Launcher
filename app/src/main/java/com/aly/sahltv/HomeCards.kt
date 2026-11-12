package com.aly.sahltv

import com.aly.sahl.common.Fonts
import com.aly.sahl.common.FontDialog
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import com.aly.sahltv.MainActivity.*

// Home cards: Ayah of the day, weather and Qatar dates, what's new.  (part of the Sahl TV home screen, split out of MainActivity)

internal fun MainActivity.arDigits(n: Int) = n.toString().map { "٠١٢٣٤٥٦٧٨٩"[it - '0'] }.joinToString("")

internal fun MainActivity.updateAyah() {
    val list = ayat
    if (!ayahOn || list == null || list.length() == 0) { ayahCard.visibility = View.GONE; return }
    ayahCard.visibility = View.VISIBLE
    val o = list.getJSONObject((LocalDate.now().toEpochDay() % list.length()).toInt())
    if (ayahToday?.toString() == o.toString()) return
    ayahToday = o
    ayahTv.text = o.getString("text") + " ﴿" + arDigits(o.getInt("a")) + "﴾"
    val nameAr = o.optString("ar"); val nameEn = o.optString("en")
    ayahRef.text = if (ar) "سورة ${nameAr.ifEmpty { arDigits(o.getInt("s")) }} · الآية ${arDigits(o.getInt("a"))}  ·  اضغط للقراءة"
                   else "Surah ${nameEn.ifEmpty { o.getInt("s").toString() }} ${o.getInt("s")}:${o.getInt("a")}  ·  press OK to read"
}

internal fun MainActivity.openAyah() {
    val o = ayahToday ?: return
    try {
        startActivity(Intent().setClassName("com.aly.sahlquran", "com.aly.sahlquran.QuranActivity")
            .putExtra("surah", o.getInt("s")).putExtra("ayah", o.getInt("a")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) { toast(t("Install Sahl Quran from ⬆ Updates to read the whole surah", "ثبّت سهل · القرآن من زر التحديثات لقراءة السورة كاملة")) }
}

internal fun MainActivity.updateNews() {
    val o = whatsNew
    val mine = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
    if (o == null || o.optString("version") != mine || prefs.getString("wnSeen", "") == mine) { newsCard.visibility = View.GONE; return }
    val first = prefs.getLong("wnFirst_$mine", 0L).let { if (it == 0L) System.currentTimeMillis().also { n -> prefs.edit().putLong("wnFirst_$mine", n).apply() } else it }
    if (System.currentTimeMillis() - first > 48 * 3_600_000L) { newsCard.visibility = View.GONE; return }
    val items = o.optJSONArray(if (ar) "ar" else "en")
    newsSub.text = (if (items != null && items.length() > 0) items.getString(0) else "") + t("  ·  press OK for all", "  ·  اضغط لعرض الكل")
    newsCard.visibility = View.VISIBLE
}

internal fun MainActivity.whatsNewDialog() {
    val o = whatsNew ?: return
    val items = o.optJSONArray(if (ar) "ar" else "en")
    val msg = StringBuilder()
    if (items != null) for (i in 0 until items.length()) msg.append("• ").append(items.getString(i)).append("\n\n")
    dialog().setTitle(t("What's new in ", "الجديد في ") + o.optString("version")).setMessage(msg.toString().trim())
        .setPositiveButton(t("Got it", "تم")) { _, _ -> prefs.edit().putString("wnSeen", o.optString("version")).apply(); newsCard.visibility = View.GONE }.show()
}

internal fun MainActivity.buildWeather(): View {
    wxBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    wxIcon = GoldIcon(this, GoldIcon.SUN, GOLD)
    wxBox.addView(wxIcon, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) })
    val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    wxTemp = TextView(this).apply { textSize = 28f; setTextColor(Color.WHITE); setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); includeFontPadding = false; setShadowLayer(10f, 0f, 1f, Color.BLACK) }
    wxLine = TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#DCE6E2")); setShadowLayer(6f, 0f, 1f, Color.BLACK); maxLines = 1 }
    qaLine = TextView(this).apply { textSize = 13f; setTextColor(GOLD); setShadowLayer(6f, 0f, 1f, Color.BLACK); maxLines = 1 }
    c.addView(wxTemp); c.addView(wxLine); c.addView(qaLine)
    wxBox.addView(c)
    return wxBox
}

internal fun MainActivity.wxCity(): Pair<Double, Double> = cities.getOrElse(cityIndex) { cities[0] }.let { it.second to it.third }

internal fun MainActivity.refreshWeather() {
    if (!wxBoxReady) return
    if (!wxOn) { wxBox.visibility = View.GONE; return }
    wxBox.visibility = View.VISIBLE
    showWeather()
    val now = System.currentTimeMillis()
    if (now - prefs.getLong("wxTry", 0L) < 45 * 60_000L) return
    prefs.edit().putLong("wxTry", now).apply()
    val (la, lo) = wxCity()
    worker.execute {
        try {
            val u = java.net.URL("https://api.open-meteo.com/v1/forecast?latitude=$la&longitude=$lo&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1")
            val c = u.openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            val body = c.inputStream.bufferedReader().use { it.readText() }
            org.json.JSONObject(body)            // valid?
            prefs.edit().putString("wx", body).putLong("wxAt", System.currentTimeMillis()).apply()
            handler.post { showWeather() }
        } catch (_: Exception) {}
    }
}

internal fun MainActivity.showWeather() {
    val o = try { org.json.JSONObject(prefs.getString("wx", "") ?: "") } catch (_: Exception) { null }
    val fresh = System.currentTimeMillis() - prefs.getLong("wxAt", 0L) < 6 * 3_600_000L
    if (o != null && fresh) {
        val cur = o.getJSONObject("current"); val day = o.getJSONObject("daily")
        val code = cur.getInt("weather_code"); val isDay = cur.optInt("is_day", 1) == 1
        wxTemp.text = digits("${Math.round(cur.getDouble("temperature_2m"))}°")
        wxIcon.kind = when (code) { 0, 1 -> if (isDay) GoldIcon.SUN else GoldIcon.CRESCENT; 2, 3, 45, 48 -> GoldIcon.CLOUD; in 51..67, in 80..82, in 95..99 -> GoldIcon.RAIN; else -> GoldIcon.CLOUD }
        val desc = when (code) { 0 -> t("Clear", "صافٍ"); 1, 2 -> t("Partly cloudy", "غائم جزئيًا"); 3 -> t("Cloudy", "غائم"); 45, 48 -> t("Fog", "ضباب")
            in 51..67, in 80..82 -> t("Rain", "أمطار"); in 95..99 -> t("Thunderstorm", "عاصفة رعدية"); else -> "" }
        val hi = Math.round(day.getJSONArray("temperature_2m_max").getDouble(0)); val lo = Math.round(day.getJSONArray("temperature_2m_min").getDouble(0))
        wxLine.text = digits((if (desc.isNotEmpty()) "$desc  ·  " else "") + t("H $hi°  L $lo°", "العظمى $hi°  الصغرى $lo°"))
        wxTemp.visibility = View.VISIBLE; wxLine.visibility = View.VISIBLE; wxIcon.visibility = View.VISIBLE
    } else { wxTemp.visibility = View.GONE; wxLine.visibility = View.GONE; wxIcon.visibility = View.GONE }
    qaLine.text = digits(qatarNext())
}

/** Next Qatar occasion: National Day (18 Dec), National Sport Day (2nd Tuesday of February), the two Eids. Worked out on the TV. */
internal fun MainActivity.qatarNext(): String {
    val today = LocalDate.now()
    val key = "$today/$hijriAdj/$ar"
    if (key == qaKey) return qaText
    val c = ArrayList<Pair<LocalDate, String>>()
    for (y in listOf(today.year, today.year + 1)) {
        c.add(LocalDate.of(y, 12, 18) to t("Qatar National Day", "اليوم الوطني لقطر"))
        var f = LocalDate.of(y, 2, 1); while (f.dayOfWeek != DayOfWeek.TUESDAY) f = f.plusDays(1)
        c.add(f.plusDays(7) to t("National Sport Day", "اليوم الرياضي للدولة"))
    }
    Islamic.firstDate(today, 400) { d -> Islamic.hijri(d, hijriAdj).let { it.m == 9 && it.d == 1 } }?.let { c.add(it to t("Eid al-Fitr", "عيد الفطر")) }
    Islamic.firstDate(today, 400) { d -> Islamic.hijri(d, hijriAdj).let { it.m == 11 && it.d == 10 } }?.let { c.add(it to t("Eid al-Adha", "عيد الأضحى")) }
    val next = c.filter { !it.first.isBefore(today) }.minByOrNull { it.first }
    qaText = if (next == null) "" else {
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, next.first)
        when (days) { 0L -> t("Today: ${next.second} 🎉", "اليوم: ${next.second} 🎉"); 1L -> t("Tomorrow: ${next.second}", "غدًا: ${next.second}")
            else -> t("${next.second} in $days days", "${next.second} بعد $days يومًا") }
    }
    qaKey = key
    return qaText
}

internal fun MainActivity.prayerAdjustMenu() {
    val tt = timesFor(LocalDate.now()) ?: run { cityMenu(); return }
    val names = listOf(t("Fajr", "الفجر"), t("Sunrise", "الشروق"), t("Dhuhr", "الظهر"), t("Asr", "العصر"), t("Maghrib (Isha follows +90 min)", "المغرب (العشاء بعده بـ 90 دقيقة)"))
    val times = listOf(tt.fajr, tt.sunrise, tt.dhuhr, tt.asr, tt.maghrib)
    val items = names.indices.map { "${names[it]}   ${Prayer.hm(times[it])}" + (if (Prayer.user[it] != 0) "   (${if (Prayer.user[it] > 0) "+" else ""}${Prayer.user[it]} ${t("min", "د")})" else "") } +
        t("Reset to the Qatar calendar", "إعادة إلى تقويم قطر")
    dialog().setTitle(t("Adjust prayer times (today)", "ضبط مواقيت الصلاة (اليوم)")).setItems(items.toTypedArray()) { _, w ->
        if (w == names.size) { Prayer.user = IntArray(5); Prayer.save(this); updateClock(); Alerts.schedule(this); return@setItems }
        val opts = (-5..5).toList()
        val labels = opts.map { (if (it > 0) "+$it" else "$it") + " " + t("min", "د") + (if (it == 0) t("  (Qatar calendar)", "  (تقويم قطر)") else "") + (if (it == Prayer.user[w]) "  ✓" else "") }
        dialog().setTitle(names[w]).setItems(labels.toTypedArray()) { _, k ->
            Prayer.user[w] = opts[k]; Prayer.save(this); updateClock(); Alerts.schedule(this); prayerAdjustMenu()
        }.show()
    }.show()
}
