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

// Comfort settings: text size, numbers (123 / ١٢٣), night style.  (part of the Sahl TV home screen, split out of MainActivity)

internal fun MainActivity.digits(s: String): String = if (!arDigits) s.map { if (it in '٠'..'٩') '0' + (it - '٠') else it }.joinToString("")
    else s.map { if (it in '0'..'9') '٠' + (it - '0') else it }.joinToString("")

internal fun MainActivity.textSizeName() = when { textScale >= 1.19f -> t("Extra large", "كبير جدًا"); textScale >= 1.09f -> t("Large", "كبير"); else -> t("Normal", "عادي") }

internal fun MainActivity.textSizeMenu() {
    val sizes = floatArrayOf(1f, 1.1f, 1.2f)
    val names = arrayOf(t("Normal", "عادي"), t("Large", "كبير"), t("Extra large", "كبير جدًا"))
    dialog().setTitle(t("Text size", "حجم الخط"))
        .setSingleChoiceItems(names, sizes.indexOfFirst { kotlin.math.abs(it - textScale) < 0.01f }.coerceAtLeast(0)) { d, w ->
            d.dismiss()
            prefs.edit().putFloat("textScale", sizes[w]).commit()
            Fonts.scale = sizes[w]
            toast(t("Text size: ", "حجم الخط: ") + names[w] + t("  ·  other Sahl apps use it next time they open", "  ·  تطبيقات سهل الأخرى تستخدمه عند فتحها"))
            recreate()
        }.setNegativeButton(t("Cancel", "إلغاء"), null).show()
}

internal fun MainActivity.settingsSections(): List<Pair<String, List<SRow>>> {
    val city = if (cityIndex in cities.indices) (if (ar) cityAr(cityIndex) else cities[cityIndex].first) else t("Off", "متوقف")
    val alert = when (Alerts.mode(this)) { Alerts.OFF -> t("Off", "متوقف"); Alerts.WITH_REMINDER -> t("On + 10 min before", "مُفعّل + قبله بـ 10 د"); else -> t("On", "مُفعّل") } +
        if (Alerts.mode(this) != Alerts.OFF && !Alerts.canShow(this)) t(" · needs setup", " · يحتاج إعدادًا") else ""
    return listOf(
        t("Prayer & Islamic", "الصلاة والمناسبات") to listOf(
            SRow(GoldIcon.MOSQUE, t("Prayer times city", "مدينة مواقيت الصلاة"), t("Times are calculated on the TV with the Qatar method", "تُحسب المواقيت على التلفاز بطريقة قطر"), city, 4),
            SRow(GoldIcon.CLOCK, t("Adjust prayer times", "ضبط مواقيت الصلاة"), t("Matches the Qatar Calendar House; fine-tune any prayer by a few minutes", "مطابقة لدار التقويم القطري؛ عدّل أي صلاة بدقائق"),
                if (Prayer.user.any { it != 0 }) t("Adjusted", "معدّلة") else t("Qatar calendar", "تقويم قطر"), 21),
            SRow(GoldIcon.BELL, t("Prayer-time banner", "تنبيه وقت الصلاة"), t("A quiet card at each prayer time, even over videos. No sound", "بطاقة هادئة عند كل صلاة حتى فوق الفيديو، بدون صوت"), alert, 8),
            SRow(GoldIcon.HOURGLASS, t("Suhoor reminder", "تذكير السحور"), t("A quiet banner 30 minutes before Fajr on fasting days (Ramadan and Sunnah fasts), even over videos", "تنبيه هادئ قبل الفجر بـ 30 دقيقة في أيام الصيام (رمضان وصيام السنة)، حتى فوق الفيديو"),
                onOff(prefs.getBoolean("suhoorOn", true)) + if (prefs.getBoolean("suhoorOn", true) && !Alerts.canShow(this)) t(" · needs setup", " · يحتاج إعدادًا") else "", 28),
            SRow(GoldIcon.CRESCENT, t("Hijri date & fasting", "التاريخ الهجري والصيام"), t("Adjust the Hijri date, Sunnah fast reminders, Islamic dates", "ضبط التاريخ الهجري، تذكير صيام السنة، المناسبات"), hijriText(LocalDate.now()), 6)),
        t("Look & comfort", "المظهر والراحة") to listOf(
            SRow(GoldIcon.GLOBE, t("Language", "اللغة"), t("Language of the home screen and menus", "لغة الشاشة الرئيسية والقوائم"), if (ar) "العربية" else "English", 0),
            SRow(GoldIcon.STAR, t("Theme", "المظهر"), t("Islamic: Kaaba photo with gold details. Classic: plain dark", "إسلامي: صورة الكعبة بلمسات ذهبية. كلاسيكي: داكن بسيط"), if (islamic) t("Islamic", "إسلامي") else t("Classic", "كلاسيكي"), 5),
            SRow(GoldIcon.IMAGE, t("Background", "الخلفية"), t("The Kaaba, the Prophet's Mosque, Al-Aqsa — or automatic day / night", "الكعبة أو المسجد النبوي أو الأقصى — أو تلقائي نهار / ليل"), if (islamic) bgName() else t("Classic theme", "المظهر الكلاسيكي"), 18),
            SRow(GoldIcon.SUN, t("Photo brightness", "سطوع الصورة"), t("Darker is calmer and easier to read", "الأغمق أهدأ وأسهل للقراءة"), dimName(), 19),
            SRow(GoldIcon.EYE, t("Calm mode", "الوضع الهادئ"), t("Gentle for little eyes: minute countdown, soft focus, dims at night", "لطيف لعيون الصغار: عدّ بالدقائق، تركيز هادئ، تعتيم ليلًا"), onOff(calm), 7),
            SRow(GoldIcon.MOSQUE, t("Friday mode", "وضع الجمعة"), t("From Thursday Maghrib: Surah Al-Kahf (OK opens it), Jumu'ah countdown and reminder, the last hour before Maghrib", "من مغرب الخميس: سورة الكهف (OK لفتحها)، العدّ لصلاة الجمعة والتذكير، وآخر ساعة قبل المغرب"), onOff(fridayOn), 31),
            SRow(GoldIcon.STAR, t("Seasonal greetings", "تهاني المناسبات"), t("Ramadan, Eid, Arafah, Hijri new year and Qatar National Day, on the home screen", "رمضان والعيدين وعرفة ورأس السنة الهجرية واليوم الوطني، على الشاشة الرئيسية"), onOff(greetOn), 27),
            SRow(GoldIcon.CRESCENT, t("Night style", "النمط الليلي"), t("From Isha until Fajr: warmer, softer colours and the reading font, in all Sahl apps", "من العشاء حتى الفجر: ألوان أدفأ وأهدأ وخط القراءة، في كل تطبيقات سهل"), onOff(nightStyle), 25),
            SRow(GoldIcon.CLOCK, t("Numbers", "الأرقام"), t("Clock, prayer times and countdown", "الساعة ومواقيت الصلاة والعدّ التنازلي"), if (arDigits) "١٢٣" else "123", 26),
            SRow(GoldIcon.EYE, t("Text size", "حجم الخط"), t("For all Sahl apps: Sahl TV, Quran, PDF and Files", "لكل تطبيقات سهل: سهل TV والقرآن وPDF والملفات"), textSizeName(), 24),
            SRow(GoldIcon.CLOCK, t("Clock", "الساعة"), t("Show time as 24-hour or 12-hour", "عرض الوقت بنظام 24 أو 12 ساعة"), if (clock24) t("24-hour", "24 ساعة") else t("12-hour", "12 ساعة"), 3),
            SRow(GoldIcon.SCREEN, t("Clock screensaver", "شاشة توقف الساعة"), t("A dim moving clock when the TV sits idle", "ساعة خافتة متحركة عند عدم الاستخدام"), if (saverMin == 0) t("Off", "متوقفة") else t("After $saverMin min", "بعد $saverMin د"), 12)),
        t("Home screen", "الشاشة الرئيسية") to listOf(
            SRow(GoldIcon.BOOK, t("Ayah of the day", "آية اليوم"), t("A short verse each day; press OK on it to read in Sahl Quran", "آية قصيرة كل يوم؛ اضغط عليها للقراءة في سهل القرآن"), onOff(ayahOn), 23),
            SRow(GoldIcon.SUN, t("Weather & Qatar dates", "الطقس ومناسبات قطر"), t("Weather for your city and the next national day or Eid, next to the clock", "طقس مدينتك والمناسبة الوطنية أو العيد القادم بجانب الساعة"), onOff(wxOn), 22),
            SRow(GoldIcon.PLAY, t("App previews", "معاينات التطبيقات"), t("Recommended videos from SmartTube and other apps", "فيديوهات مقترحة من SmartTube وغيره"), onOff(previewsOn), 1),
            SRow(GoldIcon.SORT, t("Sort all apps", "ترتيب التطبيقات"), t("Order of the All apps grid", "ترتيب شبكة كل التطبيقات"), if (sortMostUsed) t("Most used", "الأكثر استخدامًا") else t("A to Z", "أبجديًا"), 2),
            SRow(GoldIcon.EYE_OFF, t("Hidden apps", "التطبيقات المخفية"), t("Apps you removed from the home screen", "التطبيقات التي أخفيتها من الشاشة الرئيسية"), "${hidden.size}", 11),
            SRow(GoldIcon.PLAY, t("Now playing controls", "التحكم بالتشغيل"), t("Play/pause bar for videos playing in the background", "شريط تشغيل/إيقاف للفيديو العامل في الخلفية"), onOff(nowOn) + if (nowOn && !mediaAllowed) t(" · needs setup", " · يحتاج إعدادًا") else "", 13)),
        t("Family & safety", "العائلة والأمان") to listOf(
            SRow(GoldIcon.LOCK, t("PIN & locks", "الرمز السري والأقفال"), t("Lock apps or TV settings with a 4-digit PIN", "قفل التطبيقات أو إعدادات التلفاز برمز من 4 أرقام"), if (pinSet) t("${locked.size} locked", "${locked.size} مقفلة") else t("No PIN", "بدون رمز"), 10)),
        t("Phone & TV", "الهاتف والتلفاز") to listOf(
            SRow(GoldIcon.PHONE, t("Phone remote", "الريموت من الهاتف"), t("Control the TV and type from your phone's browser", "تحكّم بالتلفاز واكتب من متصفح هاتفك"), onOff(remoteOn), 15),
            SRow(GoldIcon.BROOM, t("Auto clean", "التنظيف التلقائي"), t("Only when memory is low; keeps your recent apps open (off: apps open faster)", "فقط عند انخفاض الذاكرة؛ يُبقي تطبيقاتك الأخيرة (متوقف: التطبيقات تفتح أسرع)"), onOff(autoClean), 9),
            SRow(GoldIcon.STAR, t("Sahl apps", "تطبيقات سهل"), t("Sahl TV, Quran, PDF and Files: open them, or install a missing one", "سهل TV والقرآن وPDF والملفات: افتحها أو ثبّت الناقص منها"), "4", 30),
            SRow(GoldIcon.SCREEN, t("Diagnostics", "التشخيص"), t("Measured on this TV: start-up time, smoothness, memory, and whether cleaning really helps", "مقاس على هذا التلفاز: سرعة الفتح، السلاسة، الذاكرة، وهل يفيد التنظيف فعلًا"), "", 29),
            SRow(GoldIcon.UPDATE, t("Auto update", "التحديث التلقائي"), t("Get new versions of Sahl TV and Sahl Quran without searching", "احصل على الإصدارات الجديدة دون البحث عنها"), autoUpdName(), 20),
            SRow(GoldIcon.SAVE, t("Backup & restore", "النسخ الاحتياطي والاستعادة"), t("Save all settings to the Download folder, or bring them back", "حفظ كل الإعدادات في مجلد التنزيلات أو استعادتها"), "", 14)),
        t("About", "حول") to listOf(
            SRow(GoldIcon.INFO, t("About Sahl TV", "حول سهل TV"), t("Credits, privacy and contact", "الشكر والخصوصية والتواصل"), "", 17)))
}

internal fun MainActivity.showSettings() {
    if (settingsView != null) return
    val overlay = FrameLayout(this).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#F5061411"), Color.parseColor("#FA040D0B")))
        alpha = 0f; isClickable = true
    }
    val list = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; layoutDirection = if (ar) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        setPadding(dp(8), dp(28), dp(8), dp(40)); clipChildren = false; clipToPadding = false
    }
    val sc = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; addView(list) }
    val w = minOf(dp(780), resources.displayMetrics.widthPixels - dp(96))
    overlay.addView(sc, FrameLayout.LayoutParams(w, MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
    rootFrame.addView(overlay, rootFrame.childCount - 1, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))   // below the night dimmer
    settingsView = overlay; settingsList = list
    (rootFrame.getChildAt(0) as android.view.ViewGroup).descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS   // keep focus inside Settings
    fillSettings()
    overlay.animate().alpha(1f).setDuration(if (calm) 260 else 180).start()
}

internal fun MainActivity.hideSettings() {
    val v = settingsView ?: return
    settingsView = null; settingsList = null
    (rootFrame.getChildAt(0) as android.view.ViewGroup).descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
    v.animate().alpha(0f).setDuration(160).withEndAction { rootFrame.removeView(v) }.start()
    settingsFocus = -1
    render(keepFocus = true, force = true)
    updateClock()
}

internal fun MainActivity.fillSettings() {
    val list = settingsList ?: return
    list.removeAllViews()
    // header
    val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), dp(14)) }
    head.addView(GoldIcon(this, GoldIcon.STAR, GOLD), LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(12) })
    val ht = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    ht.addView(TextView(this).apply { text = t("Settings", "الإعدادات"); textSize = 30f; setTextColor(TEXT); typeface = Fonts.display(true); Fonts.heading(this) })
    ht.addView(TextView(this).apply { text = t("Sahl TV  ·  press Back to return home", "سهل TV  ·  اضغط رجوع للعودة"); textSize = 13f; setTextColor(MUTED) })
    head.addView(ht, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    list.addView(head)

    // update card
    val ready = prefs.getBoolean("updReady", false)
    list.addView(settingsRow(SRow(GoldIcon.UPDATE, if (ready) t("Update ready — install now", "يتوفّر تحديث — ثبّته الآن") else t("Check for updates", "البحث عن تحديثات"),
        t("Version ", "الإصدار ") + version() + t("  ·  updates Sahl TV and Sahl Quran", "  ·  يحدّث سهل TV وسهل القرآن"),
        if (ready) t("New", "جديد") else "", 16), strong = true))

    var focusView: View? = null
    for ((title, rows) in settingsSections()) {
        list.addView(TextView(this).apply {
            text = title; textSize = 17f; setTextColor(GOLD); typeface = Fonts.display(false); Fonts.heading(this)
            setPadding(dp(6), dp(20), dp(6), dp(8))
        })
        for (r in rows) { val v = settingsRow(r, false); list.addView(v); if (r.action == settingsFocus) focusView = v }
    }
    handler.post { (focusView ?: firstFocusable(list))?.requestFocus() }
}

internal fun MainActivity.firstFocusable(v: View): View? {
    if (v.isFocusable) return v
    if (v is android.view.ViewGroup) for (i in 0 until v.childCount) firstFocusable(v.getChildAt(i))?.let { return it }
    return null
}

internal fun MainActivity.settingsRow(r: SRow, strong: Boolean): View {
    val row = card(strong) { settingsFocus = r.action; menuAction(r.action); handler.postDelayed({ if (settingsView != null && hasWindowFocus()) fillSettings() }, 60) }.apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    row.setOnFocusChangeListener { v, has -> v.background = cardBg(strong, has); if (has) settingsFocus = r.action }
    val bubble = FrameLayout(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(38, 227, 184, 92)) } }
    bubble.addView(GoldIcon(this, r.icon, GOLD), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
    row.addView(bubble, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(16) })
    val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    texts.addView(TextView(this).apply { text = r.title; textSize = 17f; setTextColor(TEXT); setTypeface(typeface, Typeface.BOLD); maxLines = 1 })
    texts.addView(TextView(this).apply { text = r.desc; textSize = 13f; setTextColor(MUTED); maxLines = 2; setPadding(0, dp(2), 0, 0) })
    row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    if (r.value.isNotEmpty()) row.addView(TextView(this).apply {
        text = r.value; textSize = 13.5f; setTextColor(GOLD); maxLines = 1
        setPadding(dp(12), dp(5), dp(12), dp(5))
        background = GradientDrawable().apply { setColor(Color.argb(30, 227, 184, 92)); cornerRadius = dp(12).toFloat() }
    }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) })
    row.addView(TextView(this).apply { text = if (ar) "‹" else "›"; textSize = 22f; setTextColor(MUTED) },
        LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(10) })
    row.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(0, dp(5), 0, dp(5)) }
    return row
}
