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

// Top bar: clock, dates, weather slot and the round quick buttons.  (part of the Sahl TV home screen, split out of MainActivity)

internal fun MainActivity.buildTopBar(): View {
    val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    // one line only: the clock shrinks a little instead of wrapping (the column is shared with the weather and buttons)
    clockTv = TextView(this).apply { textSize = 56f; setTextColor(Color.WHITE); typeface = Fonts.get(2); includeFontPadding = false
        setShadowLayer(14f, 0f, 2f, Color.argb(220, 0, 0, 0)); fontFeatureSettings = "tnum"; maxLines = 1
        setAutoSizeTextTypeUniformWithConfiguration(34, 56, 2, android.util.TypedValue.COMPLEX_UNIT_SP) }
    dateTv = TextView(this).apply { textSize = 17f; setTextColor(Color.parseColor("#E6EEEA")); setShadowLayer(8f, 0f, 1f, Color.BLACK); setPadding(0, dp(2), 0, 0)
        maxLines = 1; setAutoSizeTextTypeUniformWithConfiguration(11, 17, 1, android.util.TypedValue.COMPLEX_UNIT_SP) }
    hijriTv = TextView(this).apply { textSize = 18f; setTextColor(GOLD); typeface = Fonts.display(false); setPadding(0, dp(1), 0, 0); setShadowLayer(8f, 0f, 1f, Color.BLACK)
        maxLines = 1; setAutoSizeTextTypeUniformWithConfiguration(11, 18, 1, android.util.TypedValue.COMPLEX_UNIT_SP) }
    prayerTv = TextView(this).apply { textSize = 15f; setTextColor(ACCENT); visibility = View.GONE }
    val hRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    hRow.addView(GoldIcon(this, GoldIcon.CRESCENT, GOLD), LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) })
    hRow.addView(hijriTv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    left.addView(clockTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)); left.addView(dateTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)); left.addView(hRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    bar.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    bar.addView(buildWeather(), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(20) })

    val dark = Color.parseColor("#0B1E1A")
    fun gap() = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(6) }
    updBtn = IconBtn(this, GoldIcon.UPDATE, t("Updates", "التحديثات"), GOLD, dark) { setUpd(false); Updater.check(this, ar) }
    bar.addView(updBtn, gap())
    memBtn = IconBtn(this, GoldIcon.BROOM, t("Free memory", "تحرير الذاكرة"), GOLD, dark) { freeMemory() }
    bar.addView(memBtn, gap())
    settingsBtn = IconBtn(this, GoldIcon.GEAR, t("TV settings", "إعدادات التلفاز"), GOLD, dark) {
        if (lockSettings) askPin(t("TV settings are locked", "إعدادات التلفاز مقفلة")) { open(Intent(Settings.ACTION_SETTINGS)) } else open(Intent(Settings.ACTION_SETTINGS))
    }
    bar.addView(settingsBtn, gap())
    bar.addView(IconBtn(this, GoldIcon.SLIDERS, t("Settings", "الإعدادات"), GOLD, dark) { launcherMenu() }, gap())
    return bar
}

internal fun MainActivity.pill(text: String, onClick: () -> Unit) = TextView(this).apply {
    this.text = text; textSize = 14f; setTextColor(TEXT)
    setPadding(dp(16), dp(9), dp(16), dp(9))
    isFocusable = true; isClickable = true
    defaultFocusHighlightEnabled = false
    val normal = GradientDrawable().apply { setColor(Color.parseColor("#1AFFFFFF")); cornerRadius = dp(20).toFloat() }
    val focused = GradientDrawable().apply { setColor(FOCUS); cornerRadius = dp(20).toFloat() }
    background = normal
    setOnFocusChangeListener { v, has ->
        v.background = if (has) focused else normal
        (v as TextView).setTextColor(if (has) Color.parseColor("#0E1A24") else TEXT)
    }
    setOnClickListener { onClick() }
}

/** [full] = false only sets the time and date (cheap), for the very first frame; the rest follows right after. */
internal fun MainActivity.updateClock(full: Boolean = true) {
    val now = Date()
    clockTv.text = digits(SimpleDateFormat(if (clock24) "HH:mm" else "h:mm a", if (ar) Locale.forLanguageTag("ar") else Locale.US).format(now))
    dateTv.text = digits(SimpleDateFormat("EEEE d MMMM yyyy", if (ar) Locale.forLanguageTag("ar") else Locale.US).format(now))
    if (!full) return
    hijriTv.text = digits(hijriText(LocalDate.now()))
    updatePrayer()
    updateIslamic()
}

internal fun MainActivity.updatePrayer() {
    val idx = cityIndex
    if (idx < 0 || idx >= cities.size) { prayerTv.visibility = View.GONE; return }
    val (city, la, lo) = cities[idx]
    val zone = ZoneId.systemDefault()
    val nowZ = java.time.ZonedDateTime.now(zone)
    val tz = nowZ.offset.totalSeconds / 3600.0
    val nowH = nowZ.hour + nowZ.minute / 60.0 + nowZ.second / 3600.0
    val today = nowZ.toLocalDate()
    val t = Prayer.compute(today, la, lo, tz)
    val friday = today.dayOfWeek == DayOfWeek.FRIDAY
    val list = listOf(t("Fajr", "الفجر") to t.fajr, (if (friday) t("Jumu'ah", "الجمعة") else t("Dhuhr", "الظهر")) to t.dhuhr,
        t("Asr", "العصر") to t.asr, t("Maghrib", "المغرب") to t.maghrib, t("Isha", "العشاء") to t.isha)
    var next = list.firstOrNull { it.second > nowH }
    var hoursLeft = next?.let { it.second - nowH }
    if (next == null) {   // after Isha: tomorrow's Fajr
        val tm = Prayer.compute(today.plusDays(1), la, lo, tz)
        next = t("Fajr", "الفجر") to tm.fajr
        hoursLeft = tm.fajr + 24.0 - nowH
    }
    val mins = Math.round(hoursLeft!! * 60).toInt()
    val left = if (mins >= 60) t("${mins / 60} h ${mins % 60} min", "${mins / 60} س ${mins % 60} د") else t("$mins min", "$mins دقيقة")
    prayerTv.text = t("🕌  Next: ${next.first} ${Prayer.hm(next.second)}  ·  in $left  ·  $city",
                      "🕌  الصلاة القادمة: ${next.first} ${Prayer.hm(next.second)}  ·  بعد $left  ·  ${cityAr(idx)}")
}
