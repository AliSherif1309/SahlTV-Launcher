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

// Wallpaper: choosing, loading and dimming the background photo.  (part of the Sahl TV home screen, split out of MainActivity)

/** Which photo should show now: "auto" = the Kaaba by day, the Kaaba at night from Maghrib to Fajr. */
internal fun MainActivity.wantedBg(): String {
    val ids = backgrounds.map { it.id }
    if (bgChoice == "drawn" || ids.isEmpty()) return "drawn"
    if (bgChoice != "auto" && bgChoice in ids) return bgChoice
    val tt = timesFor(LocalDate.now())
    val now = System.currentTimeMillis()
    val night = if (tt != null) now >= epochAt(LocalDate.now(), tt.maghrib) || now < epochAt(LocalDate.now(), tt.fajr)
                else java.time.LocalTime.now().let { it.hour >= 18 || it.hour < 5 }
    return if (night && "kaaba_night" in ids) "kaaba_night" else if ("kaaba_day" in ids) "kaaba_day" else ids.first()
}

internal fun MainActivity.makeBackdrop(id: String): android.graphics.drawable.Drawable {
    bgKey = "$id/$bgDim"
    val drawn = IslamicBackdrop(resources.displayMetrics.density, ar)
    return (if (id == "drawn") drawn else PhotoBackdrop(this, "bg_$id.jpg", bgDim, drawn)).also { currentBase = it; currentSeason = null }
}

private var currentBase: android.graphics.drawable.Drawable? = null
private var currentSeason: String? = null

/** Picks the photo (only rebuilt when it changes) and adds or removes the seasonal decoration on top of it. */
internal fun MainActivity.refreshBackground(force: Boolean = false) {
    if (!islamic) return
    val id = wantedBg()
    if (force || "$id/$bgDim" != bgKey) rootFrame.background = makeBackdrop(id)
    val s = season()
    if (s != currentSeason) {
        currentSeason = s
        val base = currentBase ?: return
        rootFrame.background = if (s == null) base else SeasonalBackdrop(base, s, resources.displayMetrics.density, ar)
    }
}

internal fun MainActivity.seasonMenu() {
    val on = prefs.getBoolean("seasonOn", true)
    val keys = listOf("ramadan", "eid", "national")
    val names = listOf(t("Ramadan lanterns", "فوانيس رمضان"), t("Eid stars", "نجوم العيد"), t("Qatar National Day", "اليوم الوطني لقطر"))
    val items = arrayOf(if (on) t("✓ Automatic (on)", "✓ تلقائي (مُفعّل)") else t("Automatic (off)", "تلقائي (متوقف)")) + names.map { t("Preview: ", "معاينة: ") + it }
    dialog().setTitle(t("Seasonal look", "المظهر الموسمي")).setItems(items) { _, w ->
        if (w == 0) { prefs.edit().putBoolean("seasonOn", !on).apply(); seasonPreview = null }
        else { seasonPreview = keys[w - 1]; seasonPreviewUntil = System.currentTimeMillis() + 60_000L
               toast(t("Shown for one minute (Islamic theme)", "تظهر لمدة دقيقة (المظهر الإسلامي)")); scroll.smoothScrollTo(0, 0) }
        refreshBackground()
        handler.postDelayed({ refreshBackground() }, 61_000L)
    }.setNegativeButton(t("Close", "إغلاق"), null).show()
}

internal fun MainActivity.bgName(): String = when (bgChoice) {
    "auto" -> t("Automatic (day / night)", "تلقائي (نهار / ليل)")
    "drawn" -> t("Drawn night sky", "سماء مرسومة")
    else -> backgrounds.firstOrNull { it.id == bgChoice }?.let { if (ar) it.ar else it.en } ?: t("Automatic", "تلقائي")
}

internal fun MainActivity.backgroundMenu() {
    val ids = listOf("auto") + backgrounds.map { it.id } + "drawn"
    val names = ids.map { id -> when (id) {
        "auto" -> t("Automatic — the Kaaba by day, at night after Maghrib", "تلقائي — الكعبة نهارًا، وليلًا بعد المغرب")
        "drawn" -> t("Drawn night sky (no photo)", "سماء ليلية مرسومة (بدون صورة)")
        else -> backgrounds.first { it.id == id }.let { if (ar) it.ar else it.en } } + if (id == bgChoice) "  ✓" else "" }
    dialog().setTitle(t("Background", "الخلفية")).setItems(names.toTypedArray()) { _, w ->
        prefs.edit().putString("bg", ids[w]).apply(); refreshBackground(true)
    }.show()
}

internal fun MainActivity.dimName(l: Int = bgDim) = arrayOf(t("Brightest", "الأسطع"), t("Bright", "ساطع"), t("Balanced", "متوازن"), t("Dim", "خافت"), t("Darkest", "الأغمق"))[l.coerceIn(0, 4)]

internal fun MainActivity.dimMenu() {
    val names = (0..4).map { dimName(it) + (if (it == 2) t("  (recommended)", "  (مُوصى به)") else "") + (if (it == bgDim) "  ✓" else "") }
    dialog().setTitle(t("Photo brightness", "سطوع الصورة")).setItems(names.toTypedArray()) { _, w ->
        prefs.edit().putInt("bgDim", w).apply(); refreshBackground(true)
    }.show()
}

internal fun MainActivity.photoCredits(): String = if (backgrounds.isEmpty()) "" else
    t("Background photos (Wikimedia Commons; cropped, darkened, lower part softened):\n", "صور الخلفية (Wikimedia Commons؛ مقصوصة ومعتّمة وأسفلها مُنعَّم):\n") +
    backgrounds.joinToString("\n") { "• " + (if (ar) it.ar else it.en) + " — " + it.author + ", " + it.licence } + "\n"
