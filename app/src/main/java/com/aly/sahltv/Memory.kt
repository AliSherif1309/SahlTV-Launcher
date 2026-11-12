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

// Memory: automatic and manual cleaning.  (part of the Sahl TV home screen, split out of MainActivity)

/** When you come back Home and memory is low, quietly close background apps (at most every 2 minutes). */
internal fun MainActivity.maybeAutoClean() {
    if (!autoClean) return
    val now = System.currentTimeMillis()
    if (now - lastAutoClean < 120_000) return
    val am = getSystemService(android.content.Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    if (!info.lowMemory && info.availMem > info.totalMem / 4) return
    lastAutoClean = now
    val before = info.availMem
    worker.execute {
        for (pkg in cleanTargets()) try { am.killBackgroundProcesses(pkg) } catch (_: Exception) {}
        Thread.sleep(500)
        val after = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
        val freed = ((after - before) / (1024 * 1024)).coerceAtLeast(0)
        Diagnostics.recordClean(this, "auto", before / (1024 * 1024), after / (1024 * 1024))
        if (freed > 0) handler.post {
            memBtn.text = t("Auto-freed $freed MB", "تم تحرير $freed ميغابايت")
        }
    }
}

internal fun MainActivity.hiddenMenu() {
    val h = hidden.filter { apps.containsKey(it) }
    if (h.isEmpty()) { toast(t("No hidden apps", "لا توجد تطبيقات مخفية")); return }
    dialog().setTitle(t("Tap an app to show it again", "اختر تطبيقًا لإظهاره من جديد"))
        .setItems(h.map { apps[it]?.label ?: it }.toTypedArray()) { _, which ->
            val l = hidden; l.remove(h[which]); saveList("hidden", l); render(true)
        }.show()
}

/**
 * Which apps cleaning may close. Measured on the emulator: closed apps open 5–7× slower the next time
 * (they must start from zero), and about half the freed memory is used again within a minute.
 * So the apps you are likely to open again stay: your 3 most recent, anything used in the last 30 minutes,
 * and whatever is playing in the background.
 */
internal fun MainActivity.cleanTargets(): List<String> {
    val now = System.currentTimeMillis()
    val keep = HashSet<String>(recent.take(3))
    controller?.packageName?.let { keep.add(it) }
    return apps.keys.filter { it !in keep && now - prefs.getLong("t_$it", 0L) > 30 * 60_000L && !it.startsWith("com.aly.sahl") }
}

/** The broom: explains the trade-off once, then cleans. */
internal fun MainActivity.freeMemory() {
    if (!prefs.getBoolean("cleanExplained", false)) {
        dialog().setTitle(t("Free memory", "تحرير الذاكرة"))
            .setMessage(t("Measured on a TV: closing apps frees memory, but those apps then open 5–7 times slower, and about half the memory is used again within a minute.\n\nSo Sahl TV keeps your 3 most recent apps, anything used in the last 30 minutes and what is playing, and closes only the others.\n\nSee the real effect on your TV in Settings → Diagnostics.",
                          "مقاس على تلفاز: إغلاق التطبيقات يحرّر الذاكرة، لكن هذه التطبيقات تفتح بعدها أبطأ بـ 5 إلى 7 مرات، ويُستخدم نصف الذاكرة تقريبًا من جديد خلال دقيقة.\n\nلذلك يُبقي سهل TV آخر 3 تطبيقات استخدمتها، وأي تطبيق استُخدم في آخر 30 دقيقة، وما يعمل الآن، ويغلق الباقي فقط.\n\nشاهد الأثر الحقيقي على تلفازك في الإعدادات ← التشخيص."))
            .setPositiveButton(t("Clean", "تنظيف")) { _, _ -> prefs.edit().putBoolean("cleanExplained", true).apply(); cleanNow() }
            .setNegativeButton(t("Cancel", "إلغاء"), null).show()
        return
    }
    cleanNow()
}

/** Asks Android to close background apps that you are not likely to open again soon. */
internal fun MainActivity.cleanNow() {
    val am = getSystemService(android.content.Context.ACTIVITY_SERVICE) as ActivityManager
    val before = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
    memBtn.text = t("Cleaning…", "جارٍ التنظيف…")
    worker.execute {
        for (pkg in cleanTargets()) try { am.killBackgroundProcesses(pkg) } catch (_: Exception) {}
        Thread.sleep(600)
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val freed = ((info.availMem - before) / (1024 * 1024)).coerceAtLeast(0)
        val freeMb = info.availMem / (1024 * 1024)
        val totalMb = info.totalMem / (1024 * 1024)
        Diagnostics.recordClean(this, "broom", before / (1024 * 1024), freeMb)
        handler.post {
            toast(t("Freed $freed MB  ·  $freeMb MB of $totalMb MB now free", "تم تحرير $freed ميغابايت  ·  المتاح الآن $freeMb من $totalMb ميغابايت"))
        }
    }
}
