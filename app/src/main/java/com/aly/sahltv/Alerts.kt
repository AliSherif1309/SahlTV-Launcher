package com.aly.sahltv

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Prayer-time banner: a quiet card in the corner of the screen at each prayer time (no sound, no Adhan),
 * shown over whatever is playing. Optional heads-up 10 minutes before.
 * Needs the "display over other apps" permission (given once with adb).
 */
object Alerts {
    val CITIES = listOf(
        Triple("Doha", 25.2854, 51.5310), Triple("Al Wakrah", 25.1715, 51.6034), Triple("Al Rayyan", 25.2919, 51.4244),
        Triple("Lusail", 25.4230, 51.4900), Triple("Umm Salal", 25.4697, 51.3975), Triple("Al Khor", 25.6839, 51.5058),
        Triple("Mesaieed", 24.9909, 51.5493), Triple("Dukhan", 25.4248, 50.7820), Triple("Madinat ash Shamal", 26.1293, 51.2009)
    )
    private const val ACTION = "com.aly.sahltv.PRAYER_BANNER"
    const val OFF = 0; const val AT_TIME = 1; const val WITH_REMINDER = 2

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sahl", Context.MODE_PRIVATE)
    fun mode(ctx: Context) = prefs(ctx).getInt("alertMode", AT_TIME)
    fun canShow(ctx: Context) = Settings.canDrawOverlays(ctx)
    private fun arabic(ctx: Context): Boolean {
        val l = prefs(ctx).getString("lang", null)
        return if (l != null) l == "ar" else ctx.resources.configuration.locales[0].language == "ar"
    }

    private fun epochAt(day: LocalDate, hours: Double) =
        day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + Math.round(hours * 3_600_000.0)

    /** Sets one alarm for the next banner. Called by the launcher and again after every banner. */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, AlertReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val mode = mode(ctx)
        val city = prefs(ctx).getInt("city", 0)
        Prayer.load(ctx)
        am.cancel(pi)
        if (mode == OFF || city !in CITIES.indices) return
        val (_, la, lo) = CITIES[city]
        val tz = ZonedDateTime.now().offset.totalSeconds / 3600.0
        val now = System.currentTimeMillis()
        var best = Long.MAX_VALUE
        for (k in 0..1) {
            val day = LocalDate.now().plusDays(k.toLong())
            val t = Prayer.compute(day, la, lo, tz)
            for (hrs in listOf(t.fajr, t.dhuhr, t.asr, t.maghrib, t.isha)) {
                val at = epochAt(day, hrs)
                if (at > now + 1000 && at < best) best = at
                val pre = at - 10 * 60_000L
                if (mode == WITH_REMINDER && pre > now + 1000 && pre < best) best = pre
            }
        }
        if (best == Long.MAX_VALUE) return
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, best, pi)
        else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, best, pi)
    }

    /** Works out which prayer is now (or in 10 minutes) and shows its banner. */
    fun fire(ctx: Context) {
        val city = prefs(ctx).getInt("city", 0)
        if (city !in CITIES.indices || mode(ctx) == OFF) return
        Prayer.load(ctx)
        val (_, la, lo) = CITIES[city]
        val tz = ZonedDateTime.now().offset.totalSeconds / 3600.0
        val today = LocalDate.now()
        val t = Prayer.compute(today, la, lo, tz)
        val now = System.currentTimeMillis()
        val ar = arabic(ctx)
        val friday = today.dayOfWeek == java.time.DayOfWeek.FRIDAY
        val names = if (ar) listOf("الفجر", if (friday) "الجمعة" else "الظهر", "العصر", "المغرب", "العشاء")
                    else listOf("Fajr", if (friday) "Jumu'ah" else "Dhuhr", "Asr", "Maghrib", "Isha")
        val hours = listOf(t.fajr, t.dhuhr, t.asr, t.maghrib, t.isha)
        for (i in 0 until 5) {
            val at = epochAt(today, hours[i])
            val diff = now - at
            if (diff in -60_000L..5 * 60_000L) {
                if (!once(ctx, "a$at")) return
                show(ctx, if (ar) "حان الآن موعد صلاة ${names[i]}" else "It's time for ${names[i]}",
                    (if (ar) "${Prayer.hm(hours[i])}  ·  تقبّل الله" else "${Prayer.hm(hours[i])}  ·  May Allah accept your prayer"), 60_000L)
                return
            }
            if (mode(ctx) == WITH_REMINDER && (at - now) in 8 * 60_000L..11 * 60_000L) {
                if (!once(ctx, "r$at")) return
                show(ctx, if (ar) "${names[i]} بعد 10 دقائق" else "${names[i]} in 10 minutes",
                    (if (ar) "${Prayer.hm(hours[i])}  ·  استعدّ للصلاة" else "${Prayer.hm(hours[i])}  ·  Get ready for prayer"), 20_000L)
                return
            }
        }
    }

    /** Each banner shows only once, even if the TV wakes the alarm twice. */
    private fun once(ctx: Context, key: String): Boolean {
        if (prefs(ctx).getString("lastBanner", "") == key) return false
        prefs(ctx).edit().putString("lastBanner", key).apply(); return true
    }

    private var current: View? = null
    private val main = Handler(Looper.getMainLooper())

    /** The banner: dark card, thin gold border, gentle fade in and out. It never takes the remote's focus. */
    fun show(context: Context, title: String, sub: String, ms: Long) {
        val ctx = context.applicationContext
        if (!canShow(ctx)) return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        current?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val gold = Color.parseColor("#E3B85C")
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutDirection = if (arabic(ctx)) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            setPadding(dp(18), dp(12), dp(24), dp(12))
            background = GradientDrawable().apply { setColor(Color.parseColor("#F00A1D19")); cornerRadius = dp(16).toFloat(); setStroke(dp(2), gold) }
            alpha = 0f
        }
        card.addView(GoldIcon(ctx, GoldIcon.MOSQUE, gold), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(ctx).apply { text = title; textSize = 20f; setTextColor(Color.parseColor("#F3EFE4")); setTypeface(typeface, Typeface.BOLD) })
        texts.addView(TextView(ctx).apply { text = sub; textSize = 14f; setTextColor(gold) })
        card.addView(texts)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; x = dp(40); y = dp(32) }
        try { wm.addView(card, lp) } catch (_: Exception) { return }
        current = card
        card.animate().alpha(1f).setDuration(700).start()
        main.postDelayed({
            card.animate().alpha(0f).setDuration(700).withEndAction {
                try { wm.removeView(card) } catch (_: Exception) {}
                if (current === card) current = null
            }.start()
        }, ms)
    }
}

class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Alerts.schedule(ctx)               // next one first, so a problem here never stops future banners
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) return
        Alerts.fire(ctx)
    }
}
