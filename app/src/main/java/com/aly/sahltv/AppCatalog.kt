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

// App discovery: finding installed TV apps, their banners/icons, launching and usage counts.  (part of the Sahl TV home screen, split out of MainActivity)

internal fun MainActivity.loadApps() {
    if (loading) return
    loading = true
    dirty = false
    worker.execute {
        val pm = packageManager
        val found = LinkedHashMap<String, App>()
        val intents = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        )
        for (i in intents) {
            for (ri in pm.queryIntentActivities(i, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName || found.containsKey(pkg)) continue
                val ai = ri.activityInfo.applicationInfo
                val banner = try { (ri.activityInfo.loadBanner(pm) ?: ai.loadBanner(pm))?.let { prescale(it, dp(200), dp(112)) } } catch (_: Exception) { null }
                val icon = try { ri.loadIcon(pm) } catch (_: Exception) { null }
                val label = ri.loadLabel(pm)?.toString() ?: pkg
                found[pkg] = App(pkg, label, banner, icon, if (banner == null) tintOf(icon) else Color.BLACK)
            }
        }
        handler.post {
            apps = found
            loading = false
            render(keepFocus = true)
        }
    }
}

/** Draw the banner once at tile size, so scrolling never has to rescale vector or large images. */
internal fun MainActivity.prescale(d: Drawable, w: Int, h: Int): Drawable {
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    d.setBounds(0, 0, w, h); d.draw(c)
    return BitmapDrawable(resources, b)
}

/** A pleasant tile colour taken from the icon (average of its pixels, darkened). */
internal fun MainActivity.tintOf(d: Drawable?): Int {
    if (d == null) return Color.parseColor("#22343C")
    return try {
        val b = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, 8, 8); d.draw(c)
        var r = 0; var g = 0; var bl = 0; var n = 0
        for (x in 0 until 8) for (y in 0 until 8) {
            val p = b.getPixel(x, y)
            if (Color.alpha(p) < 128) continue
            r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++
        }
        if (n == 0) Color.parseColor("#22343C")
        else Color.rgb((r / n * 0.45 + 20).toInt(), (g / n * 0.45 + 24).toInt(), (bl / n * 0.45 + 30).toInt())
    } catch (_: Exception) { Color.parseColor("#22343C") }
}

internal fun MainActivity.launch(app: App) {
    if (app.pkg in locked) { askPin(t("${app.label} is locked", "${app.label} مقفل")) { launchNow(app) }; return }
    launchNow(app)
}

internal fun MainActivity.launchNow(app: App) {
    val pm = packageManager
    val intent = pm.getLeanbackLaunchIntentForPackage(app.pkg) ?: pm.getLaunchIntentForPackage(app.pkg)
    if (intent == null) { toast(t("Can't open ${app.label}", "تعذّر فتح ${app.label}")); return }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // remember for "Recent" and "most used"
    val r = recent; r.remove(app.pkg); r.add(0, app.pkg); saveList("recent", r.take(8))
    prefs.edit().putInt("n_${app.pkg}", launches(app.pkg) + 1).apply()
    open(intent)
}

internal fun MainActivity.open(i: Intent) {
    try { startActivity(i) } catch (_: Exception) { toast(t("Not available on this TV", "غير متاح على هذا التلفاز")) }
}

internal fun MainActivity.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
