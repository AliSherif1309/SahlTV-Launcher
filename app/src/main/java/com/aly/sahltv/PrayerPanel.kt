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

// Islamic panel: prayer times and countdown, Ramadan and Sunnah fast cards.  (part of the Sahl TV home screen, split out of MainActivity)

internal fun MainActivity.cardBg(strong: Boolean, focused: Boolean, glow: Boolean = false) = GradientDrawable().apply {
    orientation = GradientDrawable.Orientation.TOP_BOTTOM
    colors = if (focused) intArrayOf(Color.parseColor("#F2183A31"), Color.parseColor("#F2102A24"))
             else if (islamic) intArrayOf(Color.parseColor("#B00E211D"), Color.parseColor("#B8091714"))      // frosted glass: the Kaaba shows through
             else intArrayOf(Color.parseColor("#E8102521"), Color.parseColor("#E80A1A17"))
    cornerRadius = dp(16).toFloat()
    val w = if (focused) dp(3) else if (strong || glow) dp(2) else dp(1)
    setStroke(w, if (focused) Color.parseColor("#F3D58A") else if (strong || glow) GOLD else Color.argb(60, 227, 184, 92))
}

internal fun MainActivity.card(strong: Boolean, onClick: () -> Unit) = LinearLayout(this).apply {
    isFocusable = true; isClickable = true; defaultFocusHighlightEnabled = false
    gravity = Gravity.CENTER_VERTICAL
    background = cardBg(strong, false)
    setPadding(dp(16), dp(8), dp(16), dp(8))
    setOnFocusChangeListener { v, has ->
        v.background = cardBg(strong, has, v.tag == "glow")
        if (has) scroll.smoothScrollTo(0, 0)
    }
    setOnClickListener { onClick() }
}

internal fun MainActivity.iconLabel(kind: Int, text: String) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    addView(GoldIcon(this@iconLabel, kind, GOLD), LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(6) })
    addView(small(text))
}

internal fun MainActivity.small(text: String, color: Int = MUTED) = TextView(this).apply { this.text = text; textSize = 13f; setTextColor(color); maxLines = 1 }

internal fun MainActivity.buildPanel(): LinearLayout {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(12), dp(4), dp(2)); clipChildren = false; clipToPadding = false }

    // row 1: next prayer + countdown, then the five prayers
    prayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
    val hero = card(true) { prayerDialog() }
    val a = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val lbl = iconLabel(GoldIcon.MOSQUE, t("Next prayer", "الصلاة القادمة")); nextLabelTv = lbl.getChildAt(1) as TextView
    a.addView(lbl)
    nextNameTv = TextView(this).apply { textSize = 28f; setTextColor(TEXT); typeface = Fonts.display(true); maxLines = 1; includeFontPadding = false; Fonts.heading(this) }
    a.addView(nextNameTv)
    hero.addView(a, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    hero.addView(View(this).apply { setBackgroundColor(Color.argb(90, 227, 184, 92)) }, LinearLayout.LayoutParams(dp(1), dp(46)).apply { setMargins(dp(12), 0, dp(12), 0) })
    val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    b.addView(iconLabel(GoldIcon.HOURGLASS, t("Time left", "الوقت المتبقي")))
    nextCountTv = TextView(this).apply {
        textSize = 28f; setTextColor(GOLD); setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD)); maxLines = 1
        fontFeatureSettings = "tnum"; textDirection = View.TEXT_DIRECTION_LTR
    }
    b.addView(nextCountTv)
    hero.addView(b, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    prayRow.addView(hero, LinearLayout.LayoutParams(0, dp(84), 0.42f))

    val strip = card(false) { prayerDialog() }.apply { setPadding(dp(6), dp(6), dp(6), dp(6)) }
    val stripIcons = intArrayOf(GoldIcon.FAJR, GoldIcon.SUN, GoldIcon.DHUHR, GoldIcon.ASR, GoldIcon.MAGHRIB, GoldIcon.ISHA)
    for (i in 0 until 6) {
        val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        cell.addView(GoldIcon(this, stripIcons[i], GOLD), LinearLayout.LayoutParams(dp(20), dp(20)).apply { bottomMargin = dp(2) })
        val n = TextView(this).apply { textSize = 13f; setTextColor(MUTED); gravity = Gravity.CENTER; maxLines = 1 }
        val tm = TextView(this).apply { textSize = 16f; setTextColor(TEXT); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; maxLines = 1 }
        cell.addView(n); cell.addView(tm)
        cellBoxes.add(cell); cellNames.add(n); cellTimes.add(tm)
        strip.addView(cell, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
    }
    prayRow.addView(strip, LinearLayout.LayoutParams(0, dp(84), 0.58f).apply { marginStart = dp(12) })
    col.addView(prayRow)

    // row 2: Ramadan / Hijri occasions, and the next Sunnah fast
    val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0); clipChildren = false }
    ramCard = card(false) { islamicDatesDialog() }
    ramCard.addView(GoldIcon(this, GoldIcon.LANTERN, GOLD), LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(14) })
    val rc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    ramTitle = TextView(this).apply { textSize = 18f; setTextColor(TEXT); typeface = Fonts.display(true); maxLines = 1; Fonts.heading(this) }
    ramSub = TextView(this).apply { textSize = 14f; setTextColor(GOLD); maxLines = 1; fontFeatureSettings = "tnum" }
    rc.addView(ramTitle); rc.addView(ramSub)
    ramCard.addView(rc, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    ramCard.minimumHeight = dp(64)
    row2.addView(ramCard, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

    fastCard = card(false) { fastDialog() }
    fastCard.addView(GoldIcon(this, GoldIcon.DATES, GOLD), LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(14) })
    val fc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fastTitle = TextView(this).apply { textSize = 18f; setTextColor(TEXT); typeface = Fonts.display(true); maxLines = 1; Fonts.heading(this) }
    fastSub = TextView(this).apply { textSize = 14f; setTextColor(MUTED); maxLines = 1 }
    fc.addView(fastTitle); fc.addView(fastSub)
    fastCard.addView(fc, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    fastCard.minimumHeight = dp(64)
    row2.addView(fastCard, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
    col.addView(row2)

    // row 3: Ayah of the day (text copied unchanged from the verified Quran text at build time)
    ayahCard = card(false) { openAyah() }.apply { setPadding(dp(18), dp(10), dp(18), dp(10)) }
    val bub = FrameLayout(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(38, 227, 184, 92)) } }
    bub.addView(GoldIcon(this, GoldIcon.BOOK, GOLD), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
    ayahCard.addView(bub, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(16) })
    val ac = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    ac.addView(small(t("Ayah of the day", "آية اليوم"), GOLD))
    ayahTv = TextView(this).apply {
        textSize = 23f; setTextColor(Color.parseColor("#F5EFDF")); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        textDirection = View.TEXT_DIRECTION_RTL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.START
        quranFont?.let { typeface = it }; setLineSpacing(0f, 1.15f)
    }
    ac.addView(ayahTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    ayahRef = small("", MUTED)
    ac.addView(ayahRef)
    ayahCard.addView(ac, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    col.addView(ayahCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })

    // What's new: shown for two days after an update
    newsCard = card(true) { whatsNewDialog() }.apply { setPadding(dp(18), dp(10), dp(18), dp(10)); tag = "glow" }
    val nb = FrameLayout(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(46, 227, 184, 92)) } }
    nb.addView(GoldIcon(this, GoldIcon.STAR, GOLD), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
    newsCard.addView(nb, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
    val nc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    nc.addView(TextView(this).apply { text = t("Updated to Sahl TV ", "تم التحديث إلى سهل TV ") + (whatsNew?.optString("version") ?: ""); textSize = 17f; setTextColor(TEXT); typeface = Fonts.display(true) })
    newsSub = small("", GOLD); nc.addView(newsSub)
    newsCard.addView(nc, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    col.addView(newsCard, 0, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) })

    // Seasonal greeting (Ramadan, Eid, Arafah, Hijri new year, Qatar National Day): Thmanyah Display with its swash
    greetCard = card(true) { greetingMenu() }.apply { setPadding(dp(22), dp(12), dp(22), dp(12)); tag = "glow"; visibility = View.GONE }
    greetIconBox = FrameLayout(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(46, 227, 184, 92)) } }
    greetCard.addView(greetIconBox, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(18) })
    val gc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    greetTitle = TextView(this).apply { textSize = 30f; setTextColor(Color.parseColor("#F6E7C1")); typeface = Fonts.display(true); maxLines = 1; includeFontPadding = false; Fonts.heading(this) }
    greetSub = TextView(this).apply { textSize = 15f; setTextColor(GOLD); typeface = Fonts.text(); maxLines = 1; setPadding(0, dp(4), 0, 0) }
    gc.addView(greetTitle); gc.addView(greetSub)
    greetCard.addView(gc, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    col.addView(greetCard, 0, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) })
    return col
}

internal fun MainActivity.epochAt(day: LocalDate, hours: Double) =
    day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + Math.round(hours * 3_600_000.0)

internal fun MainActivity.fmtLeft(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    if (calm) {
        val m = (s + 59) / 60
        return if (m >= 60) t("${m / 60}h ${m % 60}m", "${m / 60}س ${m % 60}د") else if (m > 0) t("$m min", "$m د") else t("now", "الآن")
    }
    return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
}

internal fun MainActivity.prayerNames(friday: Boolean) = listOf(t("Fajr", "الفجر"), if (friday) t("Jumu'ah", "الجمعة") else t("Dhuhr", "الظهر"),
    t("Asr", "العصر"), t("Maghrib", "المغرب"), t("Isha", "العشاء"))

internal fun MainActivity.timesFor(day: LocalDate): Prayer.Times? {
    val idx = cityIndex
    if (idx !in cities.indices) return null
    val (_, la, lo) = cities[idx]
    val tz = java.time.ZonedDateTime.now(ZoneId.systemDefault()).offset.totalSeconds / 3600.0
    return Prayer.compute(day, la, lo, tz)
}

internal fun MainActivity.weekday(d: LocalDate) = SimpleDateFormat("EEEE", if (ar) Locale.forLanguageTag("ar") else Locale.US)
    .format(Date(epochAt(d, 12.0)))

internal fun MainActivity.shortDate(d: LocalDate) = SimpleDateFormat("EEE d MMM yyyy", if (ar) Locale.forLanguageTag("ar") else Locale.US)
    .format(Date(epochAt(d, 12.0)))

/** Recomputes the whole panel (every 20 s, and whenever a countdown reaches zero). */
internal fun MainActivity.updateIslamic() {
    if (!panelReady) return
    val now = System.currentTimeMillis()
    val today = LocalDate.now()
    val adj = hijriAdj
    val tt = timesFor(today)
    val tm = timesFor(today.plusDays(1))

    // prayers
    if (tt != null && tm != null) {
        prayRow.visibility = View.VISIBLE
        val p5 = prayerNames(today.dayOfWeek == DayOfWeek.FRIDAY)
        val names = listOf(p5[0], t("Sunrise", "الشروق"), p5[1], p5[2], p5[3], p5[4])
        val hours = listOf(tt.fajr, tt.sunrise, tt.dhuhr, tt.asr, tt.maghrib, tt.isha)
        var nextI = hours.indexOfFirst { epochAt(today, it) > now }
        if (nextI < 0) { nextI = 0; nextAt = epochAt(today.plusDays(1), tm.fajr); nextNameTv.text = t("Fajr", "الفجر") }
        else { nextAt = epochAt(today, hours[nextI]); nextNameTv.text = names[nextI] }
        nextLabelTv.text = if (nextI == 1) t("Coming up", "التالي") else t("Next prayer", "الصلاة القادمة")
        for (i in 0 until 6) {
            cellNames[i].text = names[i]
            cellTimes[i].text = digits(Prayer.hm(if (i == 0 && epochAt(today, tt.isha) < now) tm.fajr else hours[i]))
            val on = i == nextI
            cellBoxes[i].background = if (on) GradientDrawable().apply {
                setColor(Color.parseColor("#1C4337")); cornerRadius = dp(12).toFloat(); setStroke(dp(1), GOLD)
            } else null
            cellNames[i].setTextColor(if (on) GOLD else MUTED)
            cellTimes[i].setTextColor(if (on) Color.parseColor("#FFF6E0") else TEXT)
        }
    } else { prayRow.visibility = View.GONE; nextAt = 0 }

    // Ramadan / occasions
    val h = Islamic.hijri(today, adj)
    ramAt = 0; ramLabel = ""
    ramCard.tag = null
    when {
        h.m == 8 -> {
            ramTitle.text = t("Ramadan Kareem  ·  day ${h.d}", "رمضان كريم  ·  اليوم ${h.d}")
            ramCard.tag = "glow"
            if (tt != null && tm != null) {
                when {
                    now < epochAt(today, tt.fajr) -> { ramAt = epochAt(today, tt.fajr); ramLabel = t("Suhoor ends in", "ينتهي السحور بعد") }
                    now < epochAt(today, tt.maghrib) -> { ramAt = epochAt(today, tt.maghrib); ramLabel = t("Iftar in", "الإفطار بعد") }
                    else -> { ramAt = epochAt(today.plusDays(1), tm.fajr); ramLabel = t("Suhoor ends in", "ينتهي السحور بعد") }
                }
            } else ramSub.text = t("May Allah accept your fasting", "تقبّل الله صيامكم")
        }
        h.m == 9 && h.d <= 3 -> { ramTitle.text = t("Eid al-Fitr Mubarak 🎉", "عيد فطر مبارك 🎉"); ramSub.text = t("Taqabbal Allahu minna wa minkum", "تقبّل الله منّا ومنكم"); ramCard.tag = "glow" }
        h.m == 11 && h.d == 9 -> { ramTitle.text = t("Day of Arafah", "يوم عرفة"); ramSub.text = t("The best day for du'a", "خير الدعاء دعاء يوم عرفة"); ramCard.tag = "glow" }
        h.m == 11 && h.d in 10..13 -> { ramTitle.text = t("Eid al-Adha Mubarak 🎉", "عيد أضحى مبارك 🎉"); ramSub.text = t("Taqabbal Allahu minna wa minkum", "تقبّل الله منّا ومنكم"); ramCard.tag = "glow" }
        else -> {
            val key = "$today/$adj"
            if (key != ramCacheKey) { ramCacheKey = key; ramCache = Islamic.firstDate(today, 400) { d -> Islamic.hijri(d, adj).let { it.m == 8 && it.d == 1 } } }
            val start = ramCache
            if (start != null) {
                val days = java.time.temporal.ChronoUnit.DAYS.between(today, start)
                val y = Islamic.hijri(start, adj).y
                ramTitle.text = t("Ramadan $y in $days days", "رمضان $y بعد $days يومًا")
                ramSub.text = t("Expected ${shortDate(start)}  ·  depends on moon sighting", "متوقع ${shortDate(start)}  ·  حسب رؤية الهلال")
                if (days <= 30) ramCard.tag = "glow"
            } else { ramTitle.text = t("Islamic dates", "المناسبات الإسلامية"); ramSub.text = "" }
        }
    }
    if (!ramCard.hasFocus()) ramCard.background = cardBg(false, false, ramCard.tag == "glow")

    // next Sunnah fast (hidden in Ramadan: the Ramadan card covers it)
    fastLine = ""
    if (!fastOn || h.m == 8) { fastCard.visibility = View.GONE }
    else {
        fastCard.visibility = View.VISIBLE
        val beforeFajr = tt != null && now < epochAt(today, tt.fajr)
        val from = if (beforeFajr) today else today.plusDays(1)
        val next = Islamic.firstDate(from, 40) { d -> Islamic.sunnahFast(d, Islamic.hijri(d, adj), ar) != null }
        fastCard.tag = null
        if (next == null) { fastTitle.text = t("No Sunnah fast soon", "لا صيام سنة قريبًا"); fastSub.text = "" }
        else {
            val why = Islamic.sunnahFast(next, Islamic.hijri(next, adj), ar)!!
            val days = java.time.temporal.ChronoUnit.DAYS.between(today, next)
            val nf = timesFor(next)
            when (days) {
                0L -> { fastTitle.text = t("Today: $why", "اليوم: $why"); fastCard.tag = "glow"
                    fastSub.text = if (nf != null) t("Suhoor until Fajr ${Prayer.hm(nf.fajr)}  ·  Iftar ${Prayer.hm(nf.maghrib)}", "السحور حتى الفجر ${Prayer.hm(nf.fajr)}  ·  الإفطار ${Prayer.hm(nf.maghrib)}") else "" }
                1L -> { fastTitle.text = t("Tomorrow: $why", "غدًا: $why"); fastCard.tag = "glow"
                    fastSub.text = if (nf != null) t("Make your intention  ·  Suhoor before Fajr ${Prayer.hm(nf.fajr)}", "انوِ الصيام  ·  السحور قبل الفجر ${Prayer.hm(nf.fajr)}") else t("Make your intention tonight", "انوِ الصيام الليلة") }
                else -> { fastTitle.text = "${weekday(next)}: $why"; fastSub.text = t("Next Sunnah fast  ·  in $days days", "صيام السنة القادم  ·  بعد $days أيام") }
            }
            fastSub.setTextColor(if (days <= 1) GOLD else MUTED)
            if (days <= 1) fastLine = fastTitle.text.toString()
        }
        if (!fastCard.hasFocus()) fastCard.background = cardBg(false, false, fastCard.tag == "glow")
    }
    for (tv in listOf(ramTitle, ramSub, fastTitle, fastSub)) { val d = digits(tv.text.toString()); if (d != tv.text.toString()) tv.text = d }
    // ordinary days: one slim line per card; Ramadan, Eid, Arafah and fasting days: full cards
    slimIslamic(ramCard.tag != "glow" && fastCard.tag != "glow")
    updateAyah(); updateNews(); updateGreeting(); refreshWeather()
    refreshBackground()
    // calm mode: soften the whole home screen from an hour after Isha until Fajr
    val night = if (tt != null) now > epochAt(today, tt.isha) + 3_600_000L || now < epochAt(today, tt.fajr)
                else java.time.LocalTime.now().let { it.hour >= 21 || it.hour < 5 }
    dimView.animate().alpha(if (calm && night) 0.35f else 0f).setDuration(1500).start()
    // night style (all Sahl apps): from Isha until Fajr. The other apps ask Sahl TV, so keep the times of day here.
    if (tt != null) {
        val im = ((tt.isha * 60).toInt() % 1440); val fm = ((tt.fajr * 60).toInt() % 1440)
        if (prefs.getInt("ishaMin", -1) != im || prefs.getInt("fajrMin", -1) != fm) prefs.edit().putInt("ishaMin", im).putInt("fajrMin", fm).apply()
    }
    val nightNow = PrefsProvider.isNight(this)
    if (nightNow != Fonts.night) { Fonts.night = nightNow; if (!isFinishing) handler.post { recreate() } }
    panel.visibility = if (prayRow.visibility == View.GONE && ramCard.visibility == View.GONE && fastCard.visibility == View.GONE) View.GONE else View.VISIBLE
    tickCountdowns()
}

internal fun MainActivity.tickCountdowns() {
    if (!panelReady) return
    val now = System.currentTimeMillis()
    if ((nextAt in 1..now) || (ramAt in 1..now)) { updateClock(); return }
    if (nextAt > 0) {
        val left = nextAt - now
        nextCountTv.text = digits(fmtLeft(left))
    }
    if (ramAt > 0) ramSub.text = digits("$ramLabel  ${fmtLeft(ramAt - now)}")
}

internal fun MainActivity.prayerDialog() {
    val today = LocalDate.now()
    val tt = timesFor(today) ?: run { cityMenu(); return }
    val tm = timesFor(today.plusDays(1))!!
    val n = prayerNames(today.dayOfWeek == DayOfWeek.FRIDAY)
    val msg = StringBuilder()
    msg.append(t("Today", "اليوم")).append("  ·  ").append(shortDate(today)).append("\n\n")
    msg.append("${n[0]}   ${Prayer.hm(tt.fajr)}\n${t("Sunrise", "الشروق")}   ${Prayer.hm(tt.sunrise)}\n${n[1]}   ${Prayer.hm(tt.dhuhr)}\n")
    msg.append("${n[2]}   ${Prayer.hm(tt.asr)}\n${n[3]}   ${Prayer.hm(tt.maghrib)}\n${n[4]}   ${Prayer.hm(tt.isha)}\n\n")
    msg.append(t("Tomorrow's Fajr", "فجر الغد")).append("   ").append(Prayer.hm(tm.fajr)).append("\n\n")
    msg.append(t("Qatar method: Fajr 18°, Asr Shafi'i, Isha 90 min after Maghrib. Compare with the Awqaf timetable.",
                  "طريقة قطر: الفجر 18°، العصر شافعي، العشاء بعد المغرب بـ 90 دقيقة. قارنها بتقويم الأوقاف."))
    val city = if (ar) cityAr(cityIndex) else cities[cityIndex].first
    dialog().setTitle(t("Prayer times  ·  $city", "مواقيت الصلاة  ·  $city")).setMessage(msg.toString())
        .setPositiveButton(t("OK", "حسنًا"), null)
        .setNeutralButton(t("Change city", "تغيير المدينة")) { _, _ -> cityMenu() }.show()
}

internal fun MainActivity.hijriText(d: LocalDate): String {
    val h = Islamic.hijri(d, hijriAdj)
    if (h.m < 0) return ""
    return "${h.d} ${(if (ar) Islamic.monthsAr else Islamic.monthsEn)[h.m]} ${h.y} ${t("AH", "هـ")}"
}

internal fun MainActivity.islamicDatesDialog() {
    val today = LocalDate.now()
    val msg = StringBuilder(t("Today: ", "اليوم: ") + hijriText(today) + "\n\n")
    for ((d, en, a) in Islamic.occasions(today, hijriAdj)) {
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, d)
        msg.append("• ").append(t(en, a)).append("  —  ").append(shortDate(d))
            .append(if (days == 0L) t("  (today)", "  (اليوم)") else t("  (in $days days)", "  (بعد $days يومًا)")).append("\n")
    }
    msg.append("\n").append(t("Dates follow the Umm al-Qura calendar. Months start by moon sighting, so a date can move by a day. If the TV is a day off from Qatar's announcement, use “Adjust Hijri date”.",
        "التواريخ حسب تقويم أم القرى. تبدأ الشهور برؤية الهلال، لذا قد يختلف التاريخ يومًا. إذا اختلف التلفاز عن إعلان قطر استخدم «ضبط التاريخ الهجري»."))
    dialog().setTitle(t("Islamic dates", "المناسبات الإسلامية")).setMessage(msg.toString())
        .setPositiveButton(t("OK", "حسنًا"), null)
        .setNeutralButton(t("Adjust Hijri date", "ضبط التاريخ الهجري")) { _, _ -> hijriAdjMenu() }.show()
}

internal fun MainActivity.hijriAdjMenu() {
    val opts = listOf(-2, -1, 0, 1, 2)
    val today = LocalDate.now()
    val labels = opts.map { o ->
        val h = Islamic.hijri(today, o)
        val s = if (o == 0) t("No change", "بدون تعديل") else if (o > 0) t("+$o day", "+$o يوم") else t("$o day", "$o يوم")
        "$s   →   ${h.d} ${(if (ar) Islamic.monthsAr else Islamic.monthsEn)[h.m.coerceAtLeast(0)]}" + if (o == hijriAdj) "  ✓" else ""
    }
    dialog().setTitle(t("Adjust Hijri date", "ضبط التاريخ الهجري")).setItems(labels.toTypedArray()) { _, w ->
        prefs.edit().putInt("hijriAdj", opts[w]).apply(); updateClock()
    }.show()
}

internal fun MainActivity.fastDialog() {
    val today = LocalDate.now()
    val msg = StringBuilder()
    var d = today
    var count = 0
    repeat(31) {
        val h = Islamic.hijri(d, hijriAdj)
        val why = if (h.m == 8) t("Ramadan", "رمضان") else Islamic.sunnahFast(d, h, ar)
        if (why != null && count < 14) { msg.append("• ").append(shortDate(d)).append("  —  ").append(why).append("\n"); count++ }
        d = d.plusDays(1)
    }
    if (count == 0) msg.append(t("No Sunnah fasts in the next 30 days.", "لا يوجد صيام سنة في الثلاثين يومًا القادمة.")).append("\n")
    msg.append("\n").append(t("Includes Mondays & Thursdays, the White days (13–15), Arafah, Ashura & Tasu'a, the first days of Dhu al-Hijjah and the six days of Shawwal. Eid days and the days of Tashreeq are skipped.",
        "يشمل الاثنين والخميس، والأيام البيض (13–15)، وعرفة، وعاشوراء وتاسوعاء، وعشر ذي الحجة، وست شوال. وتُستثنى أيام العيد وأيام التشريق."))
    dialog().setTitle(t("Upcoming Sunnah fasts", "صيام السنة القادم")).setMessage(msg.toString())
        .setPositiveButton(t("OK", "حسنًا"), null)
        .setNeutralButton(t("Turn reminders off", "إيقاف التذكير")) { _, _ -> prefs.edit().putBoolean("fastOn", false).apply(); updateClock()
            toast(t("Fasting reminders off. Turn on again in ☰ Launcher.", "تم إيقاف تذكير الصيام. يمكن تفعيله من ☰ الواجهة.")) }.show()
}
