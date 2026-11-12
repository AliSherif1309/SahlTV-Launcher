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

// Now playing bar: what is playing in the background, with play/pause.  (part of the Sahl TV home screen, split out of MainActivity)

internal fun MainActivity.buildNowBar(): View {
    nowBar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPaddingRelative(dp(14), dp(8), dp(8), dp(8))
        background = GradientDrawable().apply { setColor(Color.parseColor("#22FFFFFF")); cornerRadius = dp(14).toFloat() }
        visibility = View.GONE
    }
    nowTv = TextView(this).apply { textSize = 14f; setTextColor(TEXT); maxLines = 1 }
    nowBar.addView(nowTv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    nowBar.addView(pill("⏮") { controller?.transportControls?.skipToPrevious() })
    nowPlayBtn = pill("⏯") {
        val c = controller ?: return@pill
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }
    nowBar.addView(nowPlayBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
    nowBar.addView(pill("⏭") { controller?.transportControls?.skipToNext() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
    nowBar.addView(pill(t("Open", "فتح")) {
        controller?.packageName?.let { pkg -> apps[pkg]?.let { launch(it) } }
    }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
    val wrap = LinearLayout(this).apply { setPadding(0, dp(12), 0, 0) }
    wrap.addView(nowBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    return wrap
}

/** Needs "notification access" (granted once with ADB); without it the bar simply stays hidden. */
internal fun MainActivity.setupMedia() {
    if (!nowOn) { pickController(emptyList()); return }
    try {
        val m = msm ?: getSystemService(MediaSessionManager::class.java).also { msm = it } ?: return
        val cn = ComponentName(this, MediaListener::class.java)
        val list = m.getActiveSessions(cn)
        if (!mediaAllowed) m.addOnActiveSessionsChangedListener(sessionsListener, cn)
        mediaAllowed = true
        pickController(list)
    } catch (_: SecurityException) {
        mediaAllowed = false
        pickController(emptyList())
    }
}

internal fun MainActivity.pickController(list: List<MediaController>?) {
    val sessions = list ?: try { msm?.getActiveSessions(ComponentName(this, MediaListener::class.java)) } catch (_: Exception) { null } ?: emptyList()
    val playing = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
    val best = playing ?: sessions.firstOrNull {
        val st = it.playbackState?.state
        st == PlaybackState.STATE_PAUSED || st == PlaybackState.STATE_BUFFERING
    }
    if (best?.sessionToken != controller?.sessionToken) {
        controller?.unregisterCallback(mediaCallback)
        controller = best
        best?.registerCallback(mediaCallback, handler)
    }
    updateNowBar()
}

internal fun MainActivity.isPlaying() = controller?.playbackState?.state == PlaybackState.STATE_PLAYING

internal fun MainActivity.updateNowBar() {
    val c = controller
    if (!nowOn || c == null) { nowBar.visibility = View.GONE; return }
    val md = c.metadata
    val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
    val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
    val app = apps[c.packageName]?.label ?: c.packageName
    val state = if (isPlaying()) t("▶ Playing", "▶ يعمل الآن") else t("⏸ Paused", "⏸ متوقف مؤقتًا")
    nowTv.text = "$state  ·  $app" + (if (title.isNotBlank()) "  ·  $title" else "") + (if (artist.isNotBlank()) " — $artist" else "")
    nowBar.visibility = View.VISIBLE
}

internal fun MainActivity.nowMenu() {
    if (!mediaAllowed) {
        dialog().setTitle(t("Now playing controls", "التحكم بالتشغيل الحالي"))
            .setMessage(t("Shows what's playing (e.g. SmartTube in picture-in-picture or background audio) with ⏮ ⏯ ⏭ buttons on the home screen.\n\nAndroid needs “notification access” for this (Sahl TV reads no notifications). Run once from your PC:\n\nadb shell cmd notification allow_listener com.aly.sahltv/com.aly.sahltv.MediaListener\n\nThen press Home again.",
                          "يعرض ما يتم تشغيله (مثل SmartTube في نافذة صغيرة أو صوت في الخلفية) مع أزرار ⏮ ⏯ ⏭ على الشاشة الرئيسية.\n\nيحتاج أندرويد إلى «إذن الوصول إلى الإشعارات» لذلك (سهل TV لا يقرأ أي إشعارات). نفّذ هذا مرة واحدة من الكمبيوتر:\n\nadb shell cmd notification allow_listener com.aly.sahltv/com.aly.sahltv.MediaListener\n\nثم اضغط زر الرئيسية مرة أخرى."))
            .setPositiveButton(t("OK", "حسنًا")) { _, _ -> setupMedia() }
            .setNegativeButton(if (nowOn) t("Turn off", "إيقاف") else t("Turn on", "تفعيل")) { _, _ ->
                prefs.edit().putBoolean("nowOn", !nowOn).apply(); setupMedia()
            }.show()
        return
    }
    prefs.edit().putBoolean("nowOn", !nowOn).apply()
    setupMedia()
    toast(t("Now playing controls: ", "التحكم بالتشغيل الحالي: ") + onOff(nowOn))
}
