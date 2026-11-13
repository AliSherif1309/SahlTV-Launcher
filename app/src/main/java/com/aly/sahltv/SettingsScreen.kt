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

// The Settings screen: sections and rows.  (part of the Sahl TV home screen, split out of MainActivity)

/** Background update check, by the Auto update setting. */
internal fun MainActivity.autoUpdateTick() {
    if (packageName.endsWith(".connectpreview")) return // isolated previews do not join the stable updater
    val now = System.currentTimeMillis()
    val mode = autoUpd
    if (mode == 2 && Updater.hasDownloaded(this) && settingsView == null && now - prefs.getLong("autoAsk", 0L) > 6 * 3_600_000L) {
        prefs.edit().putLong("autoAsk", now).apply()
        handler.postDelayed({ if (resumed && settingsView == null) Updater.installDownloaded(this) }, 1500)
    }
    val every = if (mode == 2) 6 * 3_600_000L else 12 * 3_600_000L
    if (mode == 0 || now - prefs.getLong("updChecked", 0L) < every) { setUpd(prefs.getBoolean("updReady", false)); return }
    prefs.edit().putLong("updChecked", now).apply()
    if (mode == 2) Updater.autoDownload(this, ar) {
        setUpd(true); prefs.edit().putLong("autoAsk", 0L).apply()
        if (resumed && settingsView == null) { prefs.edit().putLong("autoAsk", System.currentTimeMillis()).apply(); Updater.installDownloaded(this) }
    } else Updater.checkQuietly(this, ar) { has -> setUpd(has) }
}

internal fun MainActivity.autoUpdName() = when (autoUpd) { 0 -> t("Off", "متوقف"); 2 -> t("Download & install", "تنزيل وتثبيت"); else -> t("Tell me", "إعلامي فقط") }

internal fun MainActivity.autoUpdMenu() {
    val items = arrayOf(t("Download & install by itself — Android asks you to press Install, only when you're on the home screen", "تنزيل وتثبيت تلقائي — يطلب Android الضغط على «تثبيت» فقط وأنت في الشاشة الرئيسية"),
        t("Tell me — a gold dot on Updates", "إعلامي فقط — نقطة ذهبية على زر التحديثات"),
        t("Off — I'll check myself", "إيقاف — سأبحث بنفسي"))
    dialog().setTitle(t("Auto update", "التحديث التلقائي")).setItems(items) { _, w ->
        prefs.edit().putInt("autoUpd", when (w) { 0 -> 2; 1 -> 1; else -> 0 }).putLong("updChecked", 0L).apply()
        autoUpdateTick()
    }.show()
}

internal fun MainActivity.setUpd(has: Boolean) {
    prefs.edit().putBoolean("updReady", has).apply()
    updBtn.setName(if (has) t("Update ready", "يتوفّر تحديث") else t("Updates", "التحديثات")); updBtn.setBadge(has)
}

internal fun MainActivity.version(): String = try {
    val pi = packageManager.getPackageInfo(packageName, 0)
    "${pi.versionName}  ·  " + t("build ", "رقم ") + (if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong())
} catch (_: Exception) { "" }

internal fun MainActivity.slimIslamic(on: Boolean) {
    if (slim == on) return
    slim = on
    for (sub in listOf(ramSub, fastSub)) sub.visibility = if (on) View.GONE else View.VISIBLE
    for (tt in listOf(ramTitle, fastTitle)) tt.textSize = if (on) 16f else 18f
    for (c in listOf(ramCard, fastCard)) { c.minimumHeight = dp(if (on) 46 else 64); c.setPadding(c.paddingLeft, dp(if (on) 6 else 10), c.paddingRight, dp(if (on) 6 else 10)) }
}
