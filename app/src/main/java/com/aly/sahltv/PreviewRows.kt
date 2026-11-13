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

// Rows that other apps publish to the Android TV home screen.  (part of the Sahl TV home screen, split out of MainActivity)

/** Reload the published rows at most every 3 minutes (or now, when forced). */
internal fun MainActivity.refreshPreviews(force: Boolean) {
    if (!previewsOn || !Previews.hasPermission(this)) return
    val now = System.currentTimeMillis()
    if (!force && now - lastPreviewLoad < 180_000) return
    lastPreviewLoad = now
    worker.execute {
        val rows = Previews.load(this) ?: return@execute
        handler.post { previewRows = rows; render(keepFocus = true) }
    }
}

internal fun MainActivity.rowTitleFor(r: Previews.Row): String = if (r.key == "watchnext") t("Continue watching", "متابعة المشاهدة")
    else (apps[r.pkg]?.label ?: r.pkg) + (if (r.name.isNotBlank()) "  ·  ${r.name}" else "")

internal fun MainActivity.renderPreviews(target: LinearLayout = content) {
    if (!previewsOn) return
    val off = previewOff.toSet()
    for (r in previewRows) {
        if (r.key in off || (r.pkg.isNotEmpty() && r.pkg in hidden)) continue
        if (r.items.isEmpty()) continue
        target.addView(rowTitle(rowTitleFor(r)))
        // room around the tiles + no clipping, so the grown (focused) tile and its gold ring are never cut
    val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(12), dp(12), dp(12)); clipChildren = false; clipToPadding = false }
        r.items.forEachIndexed { i, it -> line.addView(previewTile(r, i, it)) }
        target.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false; clipChildren = false; addView(line)
            if (ar) post { scrollTo(line.width, 0) }
        })
    }
}

internal fun MainActivity.previewTile(r: Previews.Row, i: Int, item: Previews.Item): View {
    val w = dp(192); val h = dp(108); val radius = dp(10).toFloat()
    val frame = FrameLayout(this).apply {
        tag = "pv:${r.key}/$i"
        isFocusable = true; isClickable = true; defaultFocusHighlightEnabled = false
        clipToOutline = true
        background = GradientDrawable().apply { setColor(Color.parseColor("#1C2A30")); cornerRadius = radius }
        contentDescription = item.title
    }
    val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    frame.addView(img, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    item.poster?.let { uri -> Previews.image(this, uri, w, h) { b -> if (b != null) img.setImageBitmap(b) } }
    frame.addView(TextView(this).apply {
        text = item.title; textSize = 12f; setTextColor(Color.WHITE); maxLines = 2
        setPadding(dp(8), dp(16), dp(8), dp(6))
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Color.parseColor("#DD000000")))
    }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
    val ring = View(this).apply {
        background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(3), FOCUS); cornerRadius = radius }
        visibility = View.INVISIBLE
    }
    frame.addView(ring, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    frame.setOnFocusChangeListener { v, has ->
        ring.visibility = if (has) View.VISIBLE else View.INVISIBLE
        v.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f).translationZ(if (has) dp(10).toFloat() else 0f)
            .setInterpolator(ease).setDuration(150).withLayer().start()
    }
    frame.setOnClickListener { openPreview(r, item) }
    frame.layoutParams = LinearLayout.LayoutParams(w, h).apply { setMargins(dp(6), 0, dp(6), 0) }
    return frame
}

internal fun MainActivity.openPreview(r: Previews.Row, item: Previews.Item) {
    if (r.pkg in locked) { askPin(t("This app is locked", "هذا التطبيق مقفل")) { openPreviewNow(r, item) }; return }
    openPreviewNow(r, item)
}

internal fun MainActivity.openPreviewNow(r: Previews.Row, item: Previews.Item) {
    try {
        val i = Intent.parseUri(item.intentUri ?: throw IllegalStateException(), Intent.URI_INTENT_SCHEME)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    } catch (_: Exception) {
        apps[r.pkg]?.let { launchNow(it) } ?: toast(t("Can't open this item", "تعذّر فتح هذا العنصر"))
    }
}

internal fun MainActivity.previewsMenu() {
    if (!Previews.hasPermission(this)) {
        dialog().setTitle(t("App previews", "معاينات التطبيقات"))
            .setMessage(t("Show the rows that apps publish for the home screen (e.g. recommended videos from SmartTube, “Continue watching”).\n\nAndroid needs one permission for this. If no prompt appears on your TV, run this once from your PC:\n\nadb shell pm grant com.aly.sahltv android.permission.READ_TV_LISTINGS\n\nThe pictures are downloaded from the internet only while previews are on.",
                          "عرض الصفوف التي تنشرها التطبيقات للشاشة الرئيسية (مثل الفيديوهات المقترحة من SmartTube و«متابعة المشاهدة»).\n\nيحتاج أندرويد إلى إذن واحد لذلك. إذا لم تظهر نافذة طلب الإذن على التلفاز، نفّذ هذا الأمر مرة واحدة من الكمبيوتر:\n\nadb shell pm grant com.aly.sahltv android.permission.READ_TV_LISTINGS\n\nتُنزَّل الصور من الإنترنت فقط أثناء تفعيل المعاينات."))
            .setPositiveButton(t("Allow", "السماح")) { _, _ -> requestPermissions(arrayOf(Previews.PERMISSION), 7) }
            .setNegativeButton(t("Cancel", "إلغاء"), null).show()
        return
    }
    // load fresh rows so the list shows every app that currently publishes something
    worker.execute {
        val rows = Previews.load(this) ?: emptyList()
        handler.post {
            previewRows = rows
            val off = previewOff
            val labels = ArrayList<String>()
            labels.add(t("Previews: ", "المعاينات: ") + onOff(previewsOn) + t("  (turn ", "  (") + (if (previewsOn) t("off)", "إيقاف)") else t("on)", "تفعيل)")))
            for (r in rows) labels.add((if (r.key in off) "☐  " else "☑  ") + rowTitleFor(r) + "  (${r.items.size})")
            if (rows.isEmpty()) labels.add(t("No app publishes rows yet. In SmartTube/YouTube, enable their home-screen channels or recommendations option.",
                                              "لا يوجد تطبيق ينشر صفوفًا بعد. في SmartTube أو YouTube فعّل خيار قنوات/اقتراحات الشاشة الرئيسية."))
            dialog().setTitle(t("App previews — choose rows", "معاينات التطبيقات — اختر الصفوف")).setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    prefs.edit().putBoolean("pvOn", !previewsOn).apply()
                    if (previewsOn) refreshPreviews(force = true) else render(true)
                    previewsMenu()
                } else if (which - 1 < rows.size) {
                    val k = rows[which - 1].key
                    val l = previewOff; if (k in l) l.remove(k) else l.add(k); saveList("pvOff", l)
                    render(true); previewsMenu()
                }
            }.show()
        }
    }
}
