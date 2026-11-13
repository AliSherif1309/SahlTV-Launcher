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

// Home screen rows: Favorites, Recent, All apps, tiles and focus.  (part of the Sahl TV home screen, split out of MainActivity)

internal fun MainActivity.render(keepFocus: Boolean, force: Boolean = false) {
    // nothing changed since last time? keep the existing tiles: Home stays instant and focus stays put
    val sig = listOf(apps.keys.joinToString(), prefs.getString("fav", ""), prefs.getString("recent", ""),
        prefs.getString("hidden", ""), prefs.getString("locked", ""), sortMostUsed.toString(),
        resources.configuration.screenWidthDp.toString(), previewSig()).joinToString("#")
    if (!force && sig == lastSig && content.childCount > 0) return
    lastSig = sig
    val hid = hidden.toSet()
    val fav = favorites.filter { apps.containsKey(it) }
    val rec = recent.filter { apps.containsKey(it) && it !in hid }.take(6)
    val all = apps.values.filter { it.pkg !in hid }.let { l ->
        if (sortMostUsed) l.sortedWith(compareByDescending<App> { launches(it.pkg) }.thenBy { it.label.lowercase() })
        else l.sortedBy { it.label.lowercase() }
    }
    content.removeAllViews()
    (panel.parent as? android.view.ViewGroup)?.removeView(panel)
    content.addView(panel)

    if (fav.isNotEmpty()) {
        content.addView(rowTitle(t("Favorites", "المفضلة")))
        content.addView(hRow("fav", fav.mapNotNull { apps[it] }, big = true))
    }   // no favorites yet: no empty section, so the Recent apps show right after the cards (tip: hold OK on an app)
    renderPreviews()
    if (rec.size >= 2) {
        content.addView(rowTitle(t("Recent", "المستخدمة مؤخرًا")))
        content.addView(hRow("rec", rec.mapNotNull { apps[it] }, big = false))
    }
    content.addView(rowTitle(digits(t("All apps  ·  ${all.size}", "كل التطبيقات  ·  ${all.size}"))))
    // as many 142 dp columns as fit inside the TV safe area (48 dp each side)
    val cols = maxOf(4, (resources.configuration.screenWidthDp - 96) / 142)
    val grid = GridLayout(this).apply { columnCount = cols; useDefaultMargins = false; clipChildren = false; clipToPadding = false }
    for (a in all) grid.addView(tile(a, "all", big = false))
    content.addView(grid)

    if (keepFocus && focusPkg != null) {
        val tag = "$focusRow/$focusPkg"
        val v = content.findViewWithTag<View>(tag) ?: content.findViewWithTag<View>("all/$focusPkg")
        if (v != null) { v.requestFocus(); return }
    }
    focusFirstTile()
}

internal fun MainActivity.focusFirstTile() {
    handler.post {
        val first = favorites.firstOrNull { apps.containsKey(it) }?.let { content.findViewWithTag<View>("fav/$it") }
        (first ?: firstTile(content))?.requestFocus()
    }
}

internal fun MainActivity.firstTile(v: View): View? {
    if (v.tag is String && (v.tag as String).contains("/")) return v
    if (v is android.view.ViewGroup) for (i in 0 until v.childCount) firstTile(v.getChildAt(i))?.let { return it }
    return null
}

internal fun MainActivity.rowTitle(t: String) = TextView(this).apply {
    text = t; textSize = 16f; setTextColor(MUTED); setTypeface(typeface, Typeface.BOLD)
    setPadding(dp(2), dp(18), 0, dp(8))
}

internal fun MainActivity.hRow(row: String, list: List<App>, big: Boolean): View {
    // room around the tiles + no clipping, so the grown (focused) tile and its gold ring are never cut
    val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(12), dp(12), dp(12)); clipChildren = false; clipToPadding = false }
    for (a in list) line.addView(tile(a, row, big))
    return HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false; clipToPadding = false; clipChildren = false
        addView(line)
        // Arabic: rows start at the right edge (the first app is on the right, under the row title)
        if (ar) post { scrollTo(line.width, 0) }
    }
}

internal fun MainActivity.tile(a: App, row: String, big: Boolean): View {
    val w = if (big) dp(200) else dp(130)
    val h = if (big) dp(112) else dp(73)
    val radius = dp(10).toFloat()
    var nameStrip: TextView? = null
    val frame = FrameLayout(this).apply {
        tag = "$row/${a.pkg}"
        isFocusable = true; isClickable = true
        defaultFocusHighlightEnabled = false
        clipToOutline = true
        background = GradientDrawable().apply { setColor(a.tint); cornerRadius = radius }
        contentDescription = a.label
    }
    if (a.banner != null) {
        frame.addView(ImageView(this).apply { setImageDrawable(a.banner); scaleType = ImageView.ScaleType.CENTER_CROP },
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    } else {
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val s = if (big) dp(48) else dp(34)
        inner.addView(ImageView(this).apply { setImageDrawable(a.icon) }, LinearLayout.LayoutParams(s, s))
        inner.addView(TextView(this).apply {
            text = a.label; textSize = if (big) 14f else 11.5f; setTextColor(TEXT); maxLines = 1
            gravity = Gravity.CENTER; setPadding(dp(6), dp(5), dp(6), 0)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        frame.addView(inner, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }
    if (a.banner != null) {
        // app name slides in at the bottom of banner tiles when focused
        nameStrip = TextView(this).apply {
            text = a.label; textSize = if (big) 13f else 11f; setTextColor(Color.WHITE); maxLines = 1
            setPadding(dp(8), dp(14), dp(8), dp(5))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Color.parseColor("#CC000000")))
            alpha = 0f
        }
        frame.addView(nameStrip, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
    }
    if (a.pkg in locked) {
        frame.addView(TextView(this).apply { text = "🔒"; textSize = 12f; setPadding(dp(5), dp(3), dp(5), dp(3)) },
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END))
    }
    // Recent: a small star shows on the selected tile; the Menu key (≡) adds it to / removes it from Favorites in one press
    val star = if (row == "rec") TextView(this).apply {
        text = if (a.pkg in favorites) "★" else "☆ ≡"; textSize = 12f; setTextColor(Color.parseColor("#0B1E1A")); typeface = Fonts.get(2)
        background = GradientDrawable().apply { setColor(GOLD); cornerRadius = dp(10).toFloat() }
        setPadding(dp(7), dp(1), dp(7), dp(2)); alpha = 0f
    }.also { frame.addView(it, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }) } else null
    // focus ring drawn on top
    val ring = View(this).apply {
        background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(3), FOCUS); cornerRadius = radius }
        visibility = View.INVISIBLE
    }
    frame.addView(ring, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

    frame.setOnFocusChangeListener { v, has ->
        ring.visibility = if (has) View.VISIBLE else View.INVISIBLE
        v.animate().cancel()
        nameStrip?.animate()?.cancel()
        star?.animate()?.cancel()
        v.animate().scaleX(if (has) 1.1f else 1f).scaleY(if (has) 1.1f else 1f).translationZ(if (has) dp(10).toFloat() else 0f)
            .setInterpolator(ease).setDuration(110).start()
        nameStrip?.animate()?.alpha(if (has) 1f else 0f)?.setDuration(110)?.start()
        star?.animate()?.alpha(if (has) 1f else 0f)?.setDuration(110)?.start()
        if (has) { focusPkg = a.pkg; focusRow = row; if (row == "fav") scroll.smoothScrollTo(0, 0) }
    }
    frame.setOnClickListener { launch(a) }
    frame.setOnLongClickListener { appMenu(a, row); true }
    frame.setOnKeyListener { _, code, ev ->
        if (code == KeyEvent.KEYCODE_MENU && ev.action == KeyEvent.ACTION_UP) {
            if (row == "rec") {
                val fav = favorites
                if (a.pkg in fav) { fav.remove(a.pkg); toast(t("Removed from Favorites", "أُزيل من المفضلة")) }
                else { fav.add(a.pkg); toast(t("★  Added to Favorites", "★  أُضيف إلى المفضلة")) }
                saveList("fav", fav); focusRow = "rec"; render(true)
            } else appMenu(a, row)
            true
        } else false
    }
    val lp = if (row == "all") GridLayout.LayoutParams().apply { width = w; height = h; setMargins(dp(6), dp(6), dp(6), dp(6)) }
             else LinearLayout.LayoutParams(w, h).apply { setMargins(dp(6), 0, dp(6), 0) }
    frame.layoutParams = lp
    return frame
}
