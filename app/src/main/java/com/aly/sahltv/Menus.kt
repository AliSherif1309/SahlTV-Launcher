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

// Launcher menus (the ☰ options and their sub-menus).  (part of the Sahl TV home screen, split out of MainActivity)

internal fun MainActivity.dialog() = FontDialog(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

internal fun MainActivity.appMenu(a: App, row: String) {
    val fav = favorites
    val isFav = a.pkg in fav
    val items = ArrayList<Pair<String, () -> Unit>>()
    if (isFav) {
        items.add(t("★  Remove from Favorites", "★  إزالة من المفضلة") to { fav.remove(a.pkg); saveList("fav", fav); render(true) })
        val i = fav.indexOf(a.pkg)
        if (i > 0) items.add((if (ar) "→  تحريك لليمين" else "←  Move left") to { fav.removeAt(i); fav.add(i - 1, a.pkg); saveList("fav", fav); focusRow = "fav"; render(true) })
        if (i < fav.size - 1) items.add((if (ar) "←  تحريك لليسار" else "→  Move right") to { fav.removeAt(i); fav.add(i + 1, a.pkg); saveList("fav", fav); focusRow = "fav"; render(true) })
    } else {
        items.add(t("☆  Add to Favorites", "☆  إضافة إلى المفضلة") to { fav.add(a.pkg); saveList("fav", fav); focusRow = "fav"; render(true) })
    }
    if (a.pkg in locked) items.add(t("🔓  Remove PIN lock", "🔓  إزالة القفل") to {
        askPin(t("Enter PIN to unlock ${a.label}", "أدخل الرمز لفتح قفل ${a.label}")) { val l = locked; l.remove(a.pkg); saveList("locked", l); render(true) }
    }) else items.add(t("🔒  Lock with PIN", "🔒  قفل برمز سري") to {
        val doLock = { val l = locked; l.add(a.pkg); saveList("locked", l); render(true); toast(t("${a.label} now needs the PIN", "${a.label} يحتاج الآن إلى الرمز")) }
        if (pinSet) askPin(t("Enter PIN to lock ${a.label}", "أدخل الرمز لقفل ${a.label}")) { doLock() } else createPin { doLock() }
    })
    items.add(t("🙈  Hide this app", "🙈  إخفاء هذا التطبيق") to {
        guarded {
            val h = hidden; h.add(a.pkg); saveList("hidden", h)
            fav.remove(a.pkg); saveList("fav", fav); render(false)
        }
    })
    items.add(t("ⓘ  App info", "ⓘ  معلومات التطبيق") to { guarded { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}"))) } })
    items.add(t("🗑  Uninstall", "🗑  إلغاء التثبيت") to { guarded { open(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}"))) } })
    dialog().setTitle(a.label)
        .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
        .show()
}

internal fun MainActivity.onOff(b: Boolean) = if (b) t("On", "مُفعّل") else t("Off", "متوقف")

internal fun MainActivity.cityAr(i: Int) = citiesAr.getOrElse(i) { cities[i].first }

internal fun MainActivity.launcherMenu() = showSettings()

/** One place for every setting: used by the Settings screen rows. */
internal fun MainActivity.menuAction(which: Int) {
    if (which in listOf(0, 5, 7)) prefs.edit().putBoolean("reopenSettings", true).apply()
        when (which) {
            0 -> { prefs.edit().putString("lang", if (ar) "en" else "ar").apply(); recreate() }
            1 -> previewsMenu()
            2 -> { prefs.edit().putBoolean("sortUsed", !sortMostUsed).apply(); render(false) }
            3 -> { prefs.edit().putBoolean("clock24", !clock24).apply(); updateClock() }
            4 -> cityMenu()
            5 -> { prefs.edit().putBoolean("islamic", !islamic).apply(); recreate() }
            6 -> islamicMenu()
            7 -> { prefs.edit().putBoolean("calm", !calm).apply(); recreate() }
            8 -> alertMenu()
            9 -> { prefs.edit().putBoolean("autoClean", !autoClean).apply(); toast(t("Auto clean: ", "التنظيف التلقائي: ") + onOff(autoClean)) }
            10 -> pinMenu()
            11 -> guarded { hiddenMenu() }
            12 -> {
                val next = when (saverMin) { 0 -> 3; 3 -> 5; 5 -> 10; else -> 0 }
                prefs.edit().putInt("saverMin", next).apply()
                toast(t("Clock screensaver: ", "شاشة توقف الساعة: ") + if (next == 0) t("Off", "متوقفة") else t("after $next min", "بعد $next دقائق"))
            }
            13 -> nowMenu()
            14 -> backupMenu()
            15 -> remoteMenu()
            16 -> { setUpd(false); Updater.check(this, ar) }
            18 -> if (islamic) backgroundMenu() else toast(t("Choose the Islamic theme first", "اختر المظهر الإسلامي أولًا"))
            19 -> dimMenu()
            20 -> autoUpdMenu()
            21 -> prayerAdjustMenu()
            22 -> { prefs.edit().putBoolean("wxOn", !wxOn).putLong("wxTry", 0L).apply(); updateClock() }
            23 -> { prefs.edit().putBoolean("ayahOn", !ayahOn).apply(); ayahToday = null; updateClock() }
            24 -> textSizeMenu()
            25 -> { prefs.edit().putBoolean("nightStyle", !nightStyle).commit(); Fonts.night = PrefsProvider.isNight(this); recreate() }
            26 -> { prefs.edit().putBoolean("arDigits", !arDigits).apply(); updateClock(); tickCountdowns(); prefs.edit().putLong("wxTry", 0L).apply(); refreshWeather(); render(true) }
            27 -> greetingMenu()
            30 -> com.aly.sahl.common.Family.show(this)
            32 -> { prefs.edit().putBoolean("adhkarOn", !adhkarOn).apply(); updateAdhkar(); toast(t("Adhkar: ", "الأذكار: ") + onOff(adhkarOn)) }
            33 -> { val on = !prefs.getBoolean("prayerPause", false)
                    prefs.edit().putBoolean("prayerPause", on).apply()
                    // it uses the prayer-time banner: turn that on too
                    if (on && Alerts.mode(this) == Alerts.OFF) prefs.edit().putInt("alertMode", Alerts.AT_TIME).apply()
                    Alerts.schedule(this); toast(t("Prayer pause: ", "إيقاف للصلاة: ") + onOff(on)) }
            34 -> seasonMenu()
            35 -> homeSizeMenu()
            36 -> homeArrangementMenu()
            37 -> { prefs.edit().putBoolean("homeShowFavorites", !homeShowFavorites).apply(); homeChanged() }
            38 -> { prefs.edit().putBoolean("homeShowRecent", !homeShowRecent).apply(); homeChanged() }
            39 -> homeResetMenu()
            40 -> homeVisualEditor()
            31 -> { prefs.edit().putBoolean("fridayOn", !fridayOn).apply(); Alerts.schedule(this); greetShown = null; updateGreeting()
                    toast(t("Friday mode: ", "وضع الجمعة: ") + onOff(fridayOn)) }
            29 -> dialog().setTitle(t("Diagnostics", "التشخيص")).setMessage(Diagnostics.report(this, ar))
                .setPositiveButton(t("Free memory now", "حرّر الذاكرة الآن")) { _, _ -> freeMemory() }
                .setNegativeButton(t("Close", "إغلاق"), null).show()
            28 -> { prefs.edit().putBoolean("suhoorOn", !prefs.getBoolean("suhoorOn", true)).apply(); Alerts.schedule(this)
                    toast(t("Suhoor reminder: ", "تذكير السحور: ") + onOff(prefs.getBoolean("suhoorOn", true))) }
            17 -> dialog().setTitle("Sahl TV  ·  سهل")
                .setMessage(t("A fast, ad-free home screen for Android TV.\n\nNo tracking, no background services. Internet is used only for preview pictures, the weather, and checking for updates. Nothing about you is sent anywhere.\n\nPrayer times are calculated on the TV (Qatar method: Fajr 18°, Asr Shafi'i, Isha 90 min after Maghrib). Please compare with the official Awqaf timetable.\n\nTip: hold OK on an app (or press Menu) for options.\n\nFree & open source (MIT).\n" + photoCredits() + "Weather: Open-Meteo.com (CC BY 4.0). Ayah of the day: Tanzil Quran text (CC BY 3.0), Amiri Quran font (SIL OFL).\nContact: alysherif1309@gmail.com",
                              "واجهة رئيسية سريعة وبلا إعلانات لأجهزة Android TV.\n\nبلا تتبّع وبلا خدمات تعمل في الخلفية. يُستخدم الإنترنت فقط لصور المعاينات والطقس والبحث عن تحديثات. لا يُرسل أي شيء عنك.\n\nتُحسب مواقيت الصلاة على التلفاز (طريقة قطر: الفجر 18°، العصر شافعي، العشاء بعد المغرب بـ 90 دقيقة). يُرجى مقارنتها بتقويم الأوقاف الرسمي.\n\nنصيحة: اضغط مطولًا على OK فوق تطبيق (أو زر القائمة) لعرض الخيارات.\n\nمجاني ومفتوح المصدر (MIT).\n" + photoCredits() + "الطقس: Open-Meteo.com (CC BY 4.0). آية اليوم: نص القرآن من مشروع تنزيل (CC BY 3.0) وخط Amiri Quran (SIL OFL).\nللتواصل: alysherif1309@gmail.com"))
                .setPositiveButton(t("OK", "حسنًا"), null).show()
        }
}

internal fun MainActivity.alertName() = when (Alerts.mode(this)) {
    Alerts.OFF -> t("Off", "متوقف"); Alerts.WITH_REMINDER -> t("At prayer time + 10 min before", "عند الأذان + قبله بـ 10 دقائق")
    else -> t("At prayer time", "عند وقت الصلاة") } + if (Alerts.mode(this) != Alerts.OFF && !Alerts.canShow(this)) t("  (needs setup)", "  (يحتاج إعدادًا)") else ""

internal fun MainActivity.alertMenu() {
    val items = arrayOf(t("At prayer time", "عند وقت الصلاة"), t("At prayer time + 10 minutes before", "عند وقت الصلاة + قبله بـ 10 دقائق"),
        t("Off", "إيقاف"), t("Show a test banner now", "عرض تنبيه تجريبي الآن"))
    val msg = if (Alerts.canShow(this)) null else t("One-time setup so the banner can appear over SmartTube and other apps. On your PC run:\n\nadb shell appops set com.aly.sahltv SYSTEM_ALERT_WINDOW allow",
        "إعداد لمرة واحدة ليظهر التنبيه فوق SmartTube والتطبيقات الأخرى. شغّل على الكمبيوتر:\n\nadb shell appops set com.aly.sahltv SYSTEM_ALERT_WINDOW allow")
    val d = dialog().setTitle(t("Prayer-time banner (no sound)", "تنبيه وقت الصلاة (بدون صوت)"))
    if (msg != null) d.setMessage(msg).setPositiveButton(t("Choose…", "اختيار…")) { _, _ -> alertChoices(items) }.setNegativeButton(t("Close", "إغلاق"), null).show()
    else alertChoices(items)
}

internal fun MainActivity.alertChoices(items: Array<String>) {
    dialog().setTitle(t("Prayer-time banner (no sound)", "تنبيه وقت الصلاة (بدون صوت)")).setItems(items) { _, w ->
        when (w) {
            0 -> prefs.edit().putInt("alertMode", Alerts.AT_TIME).apply()
            1 -> prefs.edit().putInt("alertMode", Alerts.WITH_REMINDER).apply()
            2 -> prefs.edit().putInt("alertMode", Alerts.OFF).apply()
            3 -> if (Alerts.canShow(this)) Alerts.show(this, t("It's time for Dhuhr", "حان الآن موعد صلاة الظهر"), t("11:18  ·  This is a test", "11:18  ·  هذا تنبيه تجريبي"), 8000)
                 else toast(t("Run the adb command first", "شغّل أمر adb أولًا"))
        }
        if (w < 3) { Alerts.schedule(this); toast(t("Prayer-time banner: ", "تنبيه وقت الصلاة: ") + alertName()) }
    }.show()
}

internal fun MainActivity.islamicMenu() {
    val items = arrayOf(t("Adjust Hijri date (now: ", "ضبط التاريخ الهجري (حاليًا: ") + hijriText(LocalDate.now()) + ")",
        t("Sunnah fasting reminders: ", "تذكير صيام السنة: ") + onOff(fastOn),
        t("Upcoming Islamic dates", "المناسبات الإسلامية القادمة"))
    dialog().setTitle(t("Hijri date & fasting", "التاريخ الهجري والصيام")).setItems(items) { _, w ->
        when (w) {
            0 -> hijriAdjMenu()
            1 -> { prefs.edit().putBoolean("fastOn", !fastOn).apply(); updateClock(); toast(t("Fasting reminders: ", "تذكير الصيام: ") + onOff(fastOn)) }
            2 -> islamicDatesDialog()
        }
    }.show()
}

internal fun MainActivity.cityMenu() {
    val names = cities.indices.map { if (ar) cityAr(it) else cities[it].first } + t("Turn prayer times off", "إيقاف مواقيت الصلاة")
    dialog().setTitle(t("Prayer times for", "مواقيت الصلاة في")).setItems(names.toTypedArray()) { _, which ->
        prefs.edit().putInt("city", if (which < cities.size) which else -1).apply(); updateClock(); Alerts.schedule(this)
    }.show()
}
