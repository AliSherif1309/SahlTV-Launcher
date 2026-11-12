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
        for (pkg in apps.keys) try { am.killBackgroundProcesses(pkg) } catch (_: Exception) {}
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

/** Asks Android to close apps running in the background (not the one you're using). */
internal fun MainActivity.freeMemory() {
    val am = getSystemService(android.content.Context.ACTIVITY_SERVICE) as ActivityManager
    val before = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
    memBtn.text = t("Cleaning…", "جارٍ التنظيف…")
    worker.execute {
        for (pkg in apps.keys) try { am.killBackgroundProcesses(pkg) } catch (_: Exception) {}
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
