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

// Seasonal greeting card (Ramadan, Eid, Arafah, Hijri new year, National Day).  (part of the Sahl TV home screen, split out of MainActivity)

/** The occasion today (by the Hijri date as set in the launcher, and Qatar National Day), or null. */
internal fun MainActivity.occasion(today: LocalDate): String? {
    val h = Islamic.hijri(today, hijriAdj)
    return when {
        today.monthValue == 12 && today.dayOfMonth == 18 -> "national"
        h.m == 8 && h.d >= 20 -> "lastten"
        h.m == 8 -> "ramadan"
        h.m == 9 && h.d <= 3 -> "fitr"
        h.m == 11 && h.d in 1..8 -> "dhulhijja"
        h.m == 11 && h.d == 9 -> "arafah"
        h.m == 11 && h.d in 10..13 -> "adha"
        h.m == 0 && h.d == 1 -> "newyear"
        else -> null
    }
}

/** Title (short, for the swash), line under it, icon. */
internal fun MainActivity.greeting(key: String): Triple<String, String, Int> {
    val hy = Islamic.hijri(LocalDate.now(), hijriAdj).y
    return when (key) {
        "ramadan" -> Triple(t("Ramadan Kareem", "رمضان كريم"), t("May Allah accept your fasting and prayers", "تقبّل الله صيامكم وقيامكم"), GoldIcon.LANTERN)
        "lastten" -> Triple(t("The last ten nights", "العشر الأواخر"), t("O Allah, You pardon and love to pardon, so pardon us", "اللهم إنك عفوٌّ تحب العفو فاعفُ عنّا"), GoldIcon.LANTERN)
        "fitr" -> Triple(t("Eid Mubarak", "عيد مبارك"), t("Eid al-Fitr · May Allah accept from us and from you", "عيد الفطر · تقبّل الله منّا ومنكم"), GoldIcon.CRESCENT)
        "dhulhijja" -> Triple(t("The ten days of Dhul Hijjah", "عشر ذي الحجة"), t("The best days of the year for good deeds", "أفضل أيام الدنيا للعمل الصالح"), GoldIcon.STAR)
        "arafah" -> Triple(t("Day of Arafah", "يوم عرفة"), t("The best supplication is on the Day of Arafah", "خير الدعاء دعاء يوم عرفة"), GoldIcon.MOSQUE)
        "adha" -> Triple(t("Eid al-Adha Mubarak", "عيد أضحى مبارك"), t("May Allah accept from us and from you", "تقبّل الله منّا ومنكم"), GoldIcon.CRESCENT)
        "newyear" -> Triple(t("Happy Hijri New Year", "عام هجري سعيد"), t("$hy AH", "$hy هـ"), GoldIcon.CRESCENT)
        else -> Triple(t("Qatar National Day", "اليوم الوطني"), t("18 December · Qatar", "١٨ ديسمبر · قطر"), GoldIcon.STAR)
    }
}

internal fun MainActivity.updateGreeting() {
    if (!greetCardReady) return
    val preview = greetPreview?.takeIf { System.currentTimeMillis() < greetPreviewUntil }
    val key = preview ?: if (greetOn) occasion(LocalDate.now()) else null
    if (key == null) { greetCard.visibility = View.GONE; greetShown = null; return }
    greetCard.visibility = View.VISIBLE
    if (greetShown == key) return
    greetShown = key
    val (title, sub, icon) = greeting(key)
    greetTitle.text = title; greetSub.text = sub
    greetIconBox.removeAllViews()
    greetIconBox.addView(GoldIcon(this, icon, GOLD), FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
}

internal fun MainActivity.greetingMenu() {
    val keys = listOf("ramadan", "lastten", "fitr", "dhulhijja", "arafah", "adha", "newyear", "national")
    val items = arrayOf((if (greetOn) t("✓ Show on occasions (on)", "✓ الإظهار في المناسبات (مُفعّل)") else t("Show on occasions (off)", "الإظهار في المناسبات (متوقف)"))) +
        keys.map { t("Preview: ", "معاينة: ") + greeting(it).first }.toTypedArray()
    dialog().setTitle(t("Seasonal greetings", "تهاني المناسبات")).setItems(items) { _, w ->
        if (w == 0) { prefs.edit().putBoolean("greetOn", !greetOn).apply(); greetPreview = null }
        else { greetPreview = keys[w - 1]; greetPreviewUntil = System.currentTimeMillis() + 60_000L
            toast(t("Shown for one minute", "تظهر لمدة دقيقة")); scroll.smoothScrollTo(0, 0) }
        greetShown = null; updateGreeting()
    }.setNegativeButton(t("Close", "إغلاق"), null).show()
}
