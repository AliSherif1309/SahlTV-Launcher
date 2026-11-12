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

// Phone remote: settings screen, pairing a phone, paired phones.  (part of the Sahl TV home screen, split out of MainActivity)

internal fun MainActivity.remoteMenu() {
    val ip = RemoteServer.ip()
    val url = if (ip != null) "http://$ip:${RemoteServer.PORT}" else t("(connect the TV to Wi-Fi first)", "(وصّل التلفاز بالشبكة أولًا)")
    val phones = RemoteServer.phones(this)
    val msg = if (remoteOn) t(
        "On your phone (same Wi-Fi), open:\n\n$url\n\nPaired phones: ${phones.size}\n\nA new phone: choose “Pair a phone” — a one-time code appears for 3 minutes.\n\nYou can open apps, control playback and volume, go Home, and type text.\n\nFor typing, run these once from a PC:\nadb shell ime enable ${RemoteServer.IME_ID}\nadb shell pm grant com.aly.sahltv android.permission.WRITE_SECURE_SETTINGS\n\nLocked apps are not shown on the phone.",
        "على هاتفك (نفس شبكة الواي فاي) افتح:\n\n$url\n\nالهواتف المقترنة: ${phones.size}\n\nلهاتف جديد: اختر «اقتران هاتف» — يظهر رمز لمرة واحدة لمدة 3 دقائق.\n\nيمكنك فتح التطبيقات والتحكم بالتشغيل والصوت والرجوع للرئيسية والكتابة.\n\nللكتابة، نفّذ هذا مرة واحدة من الكمبيوتر:\nadb shell ime enable ${RemoteServer.IME_ID}\nadb shell pm grant com.aly.sahltv android.permission.WRITE_SECURE_SETTINGS\n\nالتطبيقات المقفلة لا تظهر على الهاتف.")
    else t("Control the TV from your phone's browser on the same Wi-Fi: open apps, play/pause, volume, Home, and type text.\n\nIt stays off until you turn it on. Each phone pairs once with a one-time code shown on the TV; you can remove phones at any time.",
           "تحكّم بالتلفاز من متصفح هاتفك على نفس الشبكة: فتح التطبيقات، التشغيل والإيقاف، الصوت، الرئيسية، والكتابة.\n\nيبقى متوقفًا حتى تفعّله. يقترن كل هاتف مرة واحدة برمز لمرة واحدة يظهر على التلفاز، ويمكنك إزالة الهواتف في أي وقت.")
    val d = dialog().setTitle(t("📱 Phone remote", "📱 الريموت من الهاتف")).setMessage(msg)
        .setPositiveButton(if (remoteOn) t("Turn off", "إيقاف") else t("Turn on", "تفعيل")) { _, _ ->
            guarded {
                prefs.edit().putBoolean("remoteOn", !remoteOn).apply()
                if (remoteOn) RemoteServer.start(this) else { RemoteServer.closePairing(); RemoteServer.stop() }
                handler.postDelayed({ remoteMenu() }, 300)
            }
        }
    if (remoteOn) d.setNeutralButton(t("Pair a phone", "اقتران هاتف")) { _, _ -> guarded { pairPhone() } }
    if (phones.isNotEmpty()) d.setNegativeButton(t("Paired phones (${phones.size})", "الهواتف المقترنة (${phones.size})")) { _, _ -> guarded { pairedPhones() } }
    else d.setNegativeButton(t("Close", "إغلاق"), null)
    d.show()
}

/** Opens pairing for 3 minutes and shows the one-time code, with a countdown. Closing the window ends pairing. */
internal fun MainActivity.pairPhone() {
    val ip = RemoteServer.ip() ?: run { toast(t("Connect the TV to Wi-Fi first", "وصّل التلفاز بالشبكة أولًا")); return }
    val code = RemoteServer.openPairing()
    val until = System.currentTimeMillis() + RemoteServer.PAIR_MS
    val tv = TextView(this).apply { textSize = 18f; setTextColor(TEXT); setPadding(dp(24), dp(12), dp(24), dp(4)); setLineSpacing(0f, 1.2f) }
    val big = TextView(this).apply { textSize = 46f; setTextColor(GOLD); typeface = Fonts.get(2); letterSpacing = 0.2f; gravity = Gravity.CENTER
        text = code.chunked(3).joinToString(" "); textDirection = View.TEXT_DIRECTION_LTR }
    val left = TextView(this).apply { textSize = 14f; setTextColor(MUTED); gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(10)) }
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(tv); addView(big); addView(left) }
    tv.text = t("On your phone open  http://$ip:${RemoteServer.PORT}\nand enter this one-time code:", "على هاتفك افتح  http://$ip:${RemoteServer.PORT}\nوأدخل هذا الرمز لمرة واحدة:")
    val tick = object : Runnable { override fun run() {
        val s = ((until - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        if (s == 0L || !RemoteServer.pairingOpen()) { pairDlg?.dismiss(); return }
        left.text = digits(t("Valid for ${s / 60}:${String.format(Locale.US, "%02d", s % 60)}  ·  closes after 5 wrong tries", "صالح لمدة ${s / 60}:${String.format(Locale.US, "%02d", s % 60)}  ·  يُغلق بعد 5 محاولات خاطئة"))
        handler.postDelayed(this, 1000)
    } }
    pairDlg = dialog().setTitle(t("Pair a phone", "اقتران هاتف")).setView(box)
        .setNegativeButton(t("Cancel", "إلغاء"), null).create().also { dl ->
            dl.setOnDismissListener { RemoteServer.closePairing(); handler.removeCallbacks(tick); pairDlg = null }
            dl.show()
        }
    tick.run()
}

internal fun MainActivity.pairedPhones() {
    val phones = RemoteServer.phones(this)
    val df = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, if (ar) Locale.forLanguageTag("ar") else Locale.US)
    val items = phones.map { p -> "📱  ${p.name}   ·   " + t("last used ", "آخر استخدام ") + df.format(Date(p.seen)) }.toTypedArray() +
        arrayOf(t("✕  Remove all phones", "✕  إزالة كل الهواتف"))
    dialog().setTitle(t("Paired phones", "الهواتف المقترنة")).setItems(items) { _, w ->
        if (w == phones.size) guarded { RemoteServer.removeAll(this); toast(t("All phones removed — each must pair again", "أُزيلت كل الهواتف — يجب إعادة الاقتران")) }
        else { val p = phones[w]
            dialog().setTitle(p.name).setMessage(t("Remove this phone? It will need to pair again.", "إزالة هذا الهاتف؟ سيحتاج إلى الاقتران مرة أخرى."))
                .setPositiveButton(t("Remove", "إزالة")) { _, _ -> guarded { RemoteServer.removePhone(this, p.hash); toast(t("Removed", "تمت الإزالة")) } }
                .setNegativeButton(t("Cancel", "إلغاء"), null).show() }
    }.setNegativeButton(t("Close", "إغلاق"), null).show()
}
