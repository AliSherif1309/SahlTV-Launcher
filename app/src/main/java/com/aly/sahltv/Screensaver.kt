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

// The moving clock screensaver.  (part of the Sahl TV home screen, split out of MainActivity)

internal fun MainActivity.showSaver() {
    val clock = TextView(this).apply { textSize = 96f; setTextColor(Color.parseColor("#C9C2AE")); typeface = Fonts.display(false); includeFontPadding = false; fontFeatureSettings = "tnum" }
    val day = TextView(this).apply { textSize = 34f; setTextColor(Color.parseColor("#B08F4A")); typeface = Fonts.display(true); Fonts.heading(this) }
    val date = TextView(this).apply { textSize = 18f; setTextColor(Color.parseColor("#6E7C76")); typeface = Fonts.text(); setPadding(0, dp(6), 0, 0) }
    val pray = TextView(this).apply { textSize = 18f; setTextColor(Color.parseColor("#2E8F66")); setPadding(0, dp(6), 0, 0) }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; tag = "box"
        addView(clock); addView(day); addView(date); addView(pray)
    }
    val f = FrameLayout(this).apply {
        setBackgroundColor(Color.BLACK); alpha = 0f
        addView(box, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    }
    rootFrame.addView(f, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    saver = f
    fun fill() { clock.text = clockTv.text; day.text = SimpleDateFormat("EEEE", if (ar) Locale.forLanguageTag("ar") else Locale.US).format(Date()); date.text = digits(SimpleDateFormat("d MMMM yyyy", if (ar) Locale.forLanguageTag("ar") else Locale.US).format(Date())) + "\n" + hijriTv.text; pray.text = listOf(if (cityIndex in cities.indices) prayerTv.text.toString() else "", fastLine).filter { it.isNotEmpty() }.joinToString("\n") }
    fill()
    f.tag = Runnable { fill() }
    f.animate().alpha(1f).setDuration(800).start()
    f.post { moveSaver(f) }
}

/** Drift the clock to a new random spot every 30 s, so nothing stays burned into an OLED screen. */
internal fun MainActivity.moveSaver(f: FrameLayout) {
    (f.tag as? Runnable)?.run()
    val box = f.findViewWithTag<View>("box") ?: return
    val maxX = (f.width - box.width).coerceAtLeast(1); val maxY = (f.height - box.height).coerceAtLeast(1)
    box.animate().translationX((Math.random() * maxX).toFloat()).translationY((Math.random() * maxY).toFloat())
        .setDuration(2000).start()
}

internal fun MainActivity.hideSaver() {
    val f = saver ?: return
    saver = null
    rootFrame.removeView(f)
    lastInput = System.currentTimeMillis()
}
