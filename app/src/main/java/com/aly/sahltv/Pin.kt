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

// PIN lock for apps and settings.  (part of the Sahl TV home screen, split out of MainActivity)

internal fun MainActivity.hash(pin: String): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    return md.digest(("sahltv:" + pin).toByteArray()).joinToString("") { String.format("%02x", it) }
}

/** Runs the action directly when no PIN is set; otherwise only after the right PIN. */
internal fun MainActivity.guarded(action: () -> Unit) { if (pinSet) askPin(t("Enter PIN", "أدخل الرمز السري")) { action() } else action() }

internal fun MainActivity.askPin(title: String, onOk: () -> Unit) {
    if (!pinSet) { onOk(); return }
    pinPad(title) { pin, dlg, msg ->
        if (hash(pin) == prefs.getString("pinHash", "")) { dlg.dismiss(); onOk(); true }
        else { msg.text = t("Wrong PIN — try again", "رمز خاطئ — حاول مجددًا"); false }
    }
}

internal fun MainActivity.createPin(after: () -> Unit) {
    pinPad(t("Choose a 4-digit PIN", "اختر رمزًا من 4 أرقام")) { first, dlg, _ ->
        dlg.dismiss()
        pinPad(t("Enter the same PIN again", "أدخل الرمز نفسه مرة أخرى")) { second, dlg2, msg2 ->
            if (second == first) {
                prefs.edit().putString("pinHash", hash(first)).apply(); dlg2.dismiss(); toast(t("PIN saved", "تم حفظ الرمز")); after(); true
            } else { msg2.text = t("PINs didn't match — try again", "الرمزان غير متطابقين — حاول مجددًا"); false }
        }
        true
    }
}

internal fun MainActivity.pinMenu() {
    if (!pinSet) {
        dialog().setTitle(t("PIN & locks", "الرمز السري والأقفال"))
            .setMessage(if (ar) "اضبط رمزًا من 4 أرقام لقفل التطبيقات (مثل يوتيوب ومتجر Play) وزر إعدادات التلفاز.\n\nملاحظة: هذا القفل يعمل من هذه الشاشة الرئيسية. قد يتمكن شخص خبير بأندرويد من الوصول إلى التطبيقات بطريقة أخرى (مثل البحث الصوتي)." else "Set a 4-digit PIN to lock apps (e.g. YouTube, Play Store) and the TV settings button.\n\nNote: this lock works from this home screen. Someone who knows Android well could still reach apps another way (e.g. voice search).")
            .setPositiveButton(t("Set a PIN", "ضبط رمز")) { _, _ -> createPin { pinMenu() } }
            .setNegativeButton(t("Cancel", "إلغاء"), null).show()
        return
    }
    askPin(t("Enter PIN", "أدخل الرمز السري")) {
        val items = arrayOf(
            t("Change PIN", "تغيير الرمز"),
            t("Lock the TV settings button: ", "قفل زر إعدادات التلفاز: ") + onOff(lockSettings) + t("  (change)", "  (تغيير)"),
            t("Locked apps: ", "التطبيقات المقفلة: ") + (locked.mapNotNull { apps[it]?.label }.joinToString(", ").ifEmpty { t("none — hold OK on an app to lock it", "لا يوجد — اضغط مطولًا على تطبيق لقفله") }),
            t("Remove PIN (unlocks everything)", "إزالة الرمز (فتح كل الأقفال)")
        )
        dialog().setTitle(t("PIN & locks", "الرمز السري والأقفال")).setItems(items) { _, which ->
            when (which) {
                0 -> createPin { }
                1 -> { prefs.edit().putBoolean("lockSettings", !lockSettings).apply(); toast(t("TV settings lock: ", "قفل الإعدادات: ") + onOff(lockSettings)) }
                2 -> {}
                3 -> { prefs.edit().remove("pinHash").putBoolean("lockSettings", false).putString("locked", "").apply(); render(true); toast(t("PIN removed", "تمت إزالة الرمز")) }
            }
        }.show()
    }
}

/** Remote-friendly number pad. check(pin, dialog, messageView) returns true when finished. */
internal fun MainActivity.pinPad(title: String, check: (String, android.app.Dialog, TextView) -> Boolean) {
    var pin = ""
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
    val dots = TextView(this).apply { textSize = 28f; setTextColor(TEXT); gravity = Gravity.CENTER; text = "○ ○ ○ ○" }
    val msg = TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#F08A8A")); gravity = Gravity.CENTER }
    box.addView(dots); box.addView(msg)
    val grid = GridLayout(this).apply { columnCount = 3 }
    box.addView(grid)
    val dlg = dialog().setTitle(title).setView(box).setNegativeButton(t("Cancel", "إلغاء"), null).create()
    fun refresh() { dots.text = (0 until 4).joinToString(" ") { if (it < pin.length) "●" else "○" } }
    for (k in listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✓")) {
        grid.addView(TextView(this).apply {
            text = k; textSize = 22f; setTextColor(TEXT); gravity = Gravity.CENTER
            isFocusable = true; isClickable = true; defaultFocusHighlightEnabled = false
            val n = GradientDrawable().apply { setColor(Color.parseColor("#1AFFFFFF")); cornerRadius = dp(10).toFloat() }
            val f = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(10).toFloat() }
            background = n
            setOnFocusChangeListener { v, has -> v.background = if (has) f else n; (v as TextView).setTextColor(if (has) BG_TOP else TEXT) }
            setOnClickListener {
                msg.text = ""
                when (k) {
                    "⌫" -> pin = pin.dropLast(1)
                    "✓" -> if (pin.length == 4) { if (!check(pin, dlg, msg)) pin = "" }
                    else -> if (pin.length < 4) pin += k
                }
                if (k != "✓" && pin.length == 4) { if (!check(pin, dlg, msg)) pin = "" }
                refresh()
            }
        }, GridLayout.LayoutParams().apply { width = dp(64); height = dp(48); setMargins(dp(4), dp(4), dp(4), dp(4)) })
    }
    dlg.show()
}
