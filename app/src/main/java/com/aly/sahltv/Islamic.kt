package com.aly.sahltv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/** Hijri dates, Islamic occasions and Sunnah fasting days. Everything is calculated on the TV, offline. */
object Islamic {

    /** m is 0-based: 0 = Muharram … 8 = Ramadan, 9 = Shawwal, 11 = Dhu al-Hijjah. */
    class H(val y: Int, val m: Int, val d: Int)

    /** Umm al-Qura calendar, shifted by [adj] days (set in the launcher menu to match local moon sighting). */
    fun hijri(date: LocalDate, adj: Int): H {
        return try {
            val cal = android.icu.util.IslamicCalendar()
            cal.calculationType = android.icu.util.IslamicCalendar.CalculationType.ISLAMIC_UMALQURA
            cal.timeInMillis = date.plusDays(adj.toLong()).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            H(cal.get(android.icu.util.Calendar.YEAR), cal.get(android.icu.util.Calendar.MONTH), cal.get(android.icu.util.Calendar.DAY_OF_MONTH))
        } catch (_: Throwable) { H(0, -1, 0) }
    }

    val monthsEn = arrayOf("Muharram", "Safar", "Rabi' al-Awwal", "Rabi' al-Thani", "Jumada al-Ula", "Jumada al-Akhirah",
        "Rajab", "Sha'ban", "Ramadan", "Shawwal", "Dhu al-Qa'dah", "Dhu al-Hijjah")
    val monthsAr = arrayOf("محرم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى", "جمادى الآخرة",
        "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة")

    /** Days when fasting is not allowed: the two Eids and the days of Tashreeq. */
    fun forbidden(h: H) = (h.m == 9 && h.d == 1) || (h.m == 11 && h.d in 10..13)

    /** Why this day is a recommended (Sunnah) fast, or null. Ramadan itself is handled separately. */
    fun sunnahFast(date: LocalDate, h: H, ar: Boolean): String? {
        fun t(en: String, a: String) = if (ar) a else en
        if (h.m < 0 || h.m == 8 || forbidden(h)) return null
        return when {
            h.m == 11 && h.d == 9 -> t("Day of Arafah", "يوم عرفة")
            h.m == 11 && h.d <= 8 -> t("First days of Dhu al-Hijjah", "العشر من ذي الحجة")
            h.m == 0 && h.d == 10 -> t("Ashura", "يوم عاشوراء")
            h.m == 0 && h.d == 9 -> t("Tasu'a (before Ashura)", "تاسوعاء")
            h.d in 13..15 -> t("White day (${h.d})", "الأيام البيض (${h.d})")
            h.m == 9 -> t("Six days of Shawwal", "ست من شوال")
            date.dayOfWeek == DayOfWeek.MONDAY -> t("Monday fast", "صيام الاثنين")
            date.dayOfWeek == DayOfWeek.THURSDAY -> t("Thursday fast", "صيام الخميس")
            else -> null
        }
    }

    /** First date from [from] (inclusive) for which [test] is true, looking ahead up to [maxDays]. */
    fun firstDate(from: LocalDate, maxDays: Int, test: (LocalDate) -> Boolean): LocalDate? {
        var d = from
        repeat(maxDays) { if (test(d)) return d; d = d.plusDays(1) }
        return null
    }

    /** Upcoming occasions within a year: (date, English, Arabic). */
    fun occasions(from: LocalDate, adj: Int): List<Triple<LocalDate, String, String>> {
        val out = ArrayList<Triple<LocalDate, String, String>>()
        val wanted = listOf(
            Triple(0, 1, "Islamic New Year" to "رأس السنة الهجرية"),
            Triple(0, 10, "Ashura" to "يوم عاشوراء"),
            Triple(8, 1, "Ramadan begins" to "بداية رمضان"),
            Triple(8, 21, "Last ten nights begin" to "بداية العشر الأواخر"),
            Triple(9, 1, "Eid al-Fitr" to "عيد الفطر"),
            Triple(11, 1, "First ten days of Dhu al-Hijjah" to "بداية عشر ذي الحجة"),
            Triple(11, 9, "Day of Arafah" to "يوم عرفة"),
            Triple(11, 10, "Eid al-Adha" to "عيد الأضحى"))
        var d = from
        repeat(370) {
            val h = hijri(d, adj)
            wanted.firstOrNull { it.first == h.m && it.second == h.d }?.let { out.add(Triple(d, it.third.first, it.third.second)) }
            d = d.plusDays(1)
        }
        return out
    }
}

/**
 * Calm night background for the Islamic theme: deep navy-green, a soft crescent, a very faint star pattern,
 * and a dark mosque on the horizon at the bottom right. Drawn once into a bitmap: no animation, no flicker.
 */
class IslamicBackdrop(private val density: Float, private val rtl: Boolean = false) : Drawable() {
    private var cache: Bitmap? = null
    private fun dp(v: Float) = v * density

    override fun onBoundsChange(bounds: Rect) { cache = null }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val bmp = cache ?: build(b.width(), b.height()).also { cache = it }
        canvas.drawBitmap(bmp, b.left.toFloat(), b.top.toFloat(), null)
    }

    private fun build(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(Color.parseColor("#071320"), Color.parseColor("#0A1C1B"), Color.parseColor("#06110E")),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)

        // a little warm light around the moon and on the horizon
        val mx = w * (if (rtl) 0.60f else 0.40f); val my = dp(50f)
        p.shader = RadialGradient(mx, my, dp(220f), intArrayOf(Color.argb(34, 240, 205, 140), Color.argb(0, 240, 205, 140)), null, Shader.TileMode.CLAMP)
        c.drawCircle(mx, my, dp(220f), p)
        p.shader = RadialGradient(w * 0.8f, h.toFloat(), w * 0.45f, intArrayOf(Color.argb(30, 227, 184, 92), Color.argb(0, 227, 184, 92)), null, Shader.TileMode.CLAMP)
        c.drawCircle(w * 0.8f, h.toFloat(), w * 0.45f, p)
        p.shader = null

        // eight-point stars: barely there, a texture rather than a picture
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1f); p.color = Color.argb(9, 227, 184, 92)
        val step = dp(92f); val r = dp(22f)
        var y = 0f; var row = 0
        while (y < h + step) {
            var x = if (row % 2 == 0) 0f else step / 2
            while (x < w + step) { star(c, p, x, y, r); x += step }
            y += step / 2; row++
        }

        // a few quiet stars in the sky
        p.style = Paint.Style.FILL
        val rnd = java.util.Random(7)
        repeat(28) {
            p.color = Color.argb(40 + rnd.nextInt(70), 255, 240, 210)
            c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * dp(150f), dp(0.6f + rnd.nextFloat() * 0.9f), p)
        }

        // crescent
        val mr = dp(19f)
        val moon = Path().apply { addCircle(mx, my, mr, Path.Direction.CW) }
        moon.op(Path().apply { addCircle(mx + mr * 0.45f, my - mr * 0.25f, mr * 0.86f, Path.Direction.CW) }, Path.Op.DIFFERENCE)
        p.color = Color.parseColor("#EED39A"); p.setShadowLayer(dp(10f), 0f, 0f, Color.argb(110, 238, 211, 154))
        c.drawPath(moon, p)
        p.clearShadowLayer()

        // mosque on the horizon, darker than the sky: a silhouette, not a picture to look at
        val u = minOf(w * 0.30f / 20f, h * 0.30f / 15.6f)
        mosque(c, p, w * 0.82f, h.toFloat(), u)
        return bmp
    }

    private fun star(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float) {
        val path = Path()
        for (k in 0 until 2) {
            val a0 = Math.PI / 4 * k
            for (i in 0..4) {
                val a = a0 + Math.PI / 2 * i
                val x = cx + r * Math.cos(a).toFloat(); val y = cy + r * Math.sin(a).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
        }
        c.drawPath(path, p)
    }

    private fun mosque(c: Canvas, p: Paint, cx: Float, base: Float, u: Float) {
        val s = Path()
        s.addRect(cx - 6 * u, base - 5 * u, cx + 6 * u, base, Path.Direction.CW)
        s.addPath(Path().apply {
            moveTo(cx - 4.2f * u, base - 5 * u)
            cubicTo(cx - 5.2f * u, base - 9.5f * u, cx - 1.2f * u, base - 10.5f * u, cx, base - 12.5f * u)
            cubicTo(cx + 1.2f * u, base - 10.5f * u, cx + 5.2f * u, base - 9.5f * u, cx + 4.2f * u, base - 5 * u)
            close()
        })
        for (side in listOf(-1f, 1f)) {
            val wx = cx + side * 8f * u
            s.addRect(wx - 2.2f * u, base - 3.5f * u, wx + 2.2f * u, base, Path.Direction.CW)
            s.addPath(Path().apply {
                moveTo(wx - 1.8f * u, base - 3.5f * u)
                cubicTo(wx - 2.2f * u, base - 5.6f * u, wx - 0.5f * u, base - 5.8f * u, wx, base - 6.6f * u)
                cubicTo(wx + 0.5f * u, base - 5.8f * u, wx + 2.2f * u, base - 5.6f * u, wx + 1.8f * u, base - 3.5f * u)
                close()
            })
            val mx = cx + side * 11.6f * u
            s.addRect(mx - 0.55f * u, base - 13f * u, mx + 0.55f * u, base, Path.Direction.CW)
            s.addRect(mx - 0.95f * u, base - 10.2f * u, mx + 0.95f * u, base - 9.6f * u, Path.Direction.CW)
            s.addPath(Path().apply { moveTo(mx - 0.6f * u, base - 13f * u); lineTo(mx, base - 15.2f * u); lineTo(mx + 0.6f * u, base - 13f * u); close() })
        }
        p.style = Paint.Style.FILL; p.color = Color.parseColor("#050F0D")
        c.drawPath(s, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = density; p.color = Color.argb(55, 238, 211, 154)
        c.drawPath(s, p)
        p.style = Paint.Style.FILL; p.color = Color.argb(70, 243, 196, 110)
        for (i in -2..2) arch(c, p, cx + i * 2.2f * u, base - 0.6f * u, 0.7f * u, 2.6f * u)
    }

    private fun arch(c: Canvas, p: Paint, x: Float, bottom: Float, w: Float, h: Float) {
        c.drawPath(Path().apply {
            moveTo(x - w / 2, bottom); lineTo(x - w / 2, bottom - h + w * 0.6f)
            quadTo(x - w / 2, bottom - h, x, bottom - h - w * 0.2f)
            quadTo(x + w / 2, bottom - h, x + w / 2, bottom - h + w * 0.6f)
            lineTo(x + w / 2, bottom); close()
        }, p)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/** Small line icons drawn in one colour, so every icon matches (no mixed emoji styles). */
class GoldIcon(ctx: android.content.Context, private val kind: Int, private val color: Int) : android.view.View(ctx) {
    companion object { const val FAJR = 0; const val DHUHR = 1; const val ASR = 2; const val MAGHRIB = 3; const val ISHA = 4
        const val MOSQUE = 5; const val HOURGLASS = 6; const val LANTERN = 7; const val DATES = 8; const val CRESCENT = 9 }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun crescent(c: Canvas, x: Float, y: Float, r: Float) {
        val m = Path().apply { addCircle(x, y, r, Path.Direction.CW) }
        m.op(Path().apply { addCircle(x + r * 0.45f, y - r * 0.3f, r * 0.85f, Path.Direction.CW) }, Path.Op.DIFFERENCE)
        p.style = Paint.Style.FILL; c.drawPath(m, p); p.style = Paint.Style.STROKE
    }
    private fun rays(c: Canvas, x: Float, y: Float, r1: Float, r2: Float, from: Int, to: Int) {
        for (i in from..to) { val a = Math.PI / 4 * i
            c.drawLine(x + r1 * Math.cos(a).toFloat(), y - r1 * Math.sin(a).toFloat(), x + r2 * Math.cos(a).toFloat(), y - r2 * Math.sin(a).toFloat(), p) }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val s = minOf(w, h); val cx = w / 2; val cy = h / 2
        p.color = color; p.strokeWidth = s * 0.08f; p.strokeCap = Paint.Cap.ROUND; p.style = Paint.Style.STROKE
        when (kind) {
            FAJR -> { val hy = cy + s * 0.18f
                c.drawLine(cx - s * 0.42f, hy, cx + s * 0.42f, hy, p)
                c.drawPath(Path().apply { addArc(RectF(cx - s * 0.2f, hy - s * 0.2f, cx + s * 0.2f, hy + s * 0.2f), 180f, 180f) }, p)
                rays(c, cx, hy, s * 0.29f, s * 0.4f, 1, 3) }
            DHUHR -> { p.style = Paint.Style.FILL; c.drawCircle(cx, cy, s * 0.17f, p); p.style = Paint.Style.STROKE; rays(c, cx, cy, s * 0.27f, s * 0.4f, 0, 7) }
            ASR -> { val hy = cy + s * 0.3f
                c.drawLine(cx - s * 0.42f, hy, cx + s * 0.42f, hy, p)
                p.style = Paint.Style.FILL; c.drawCircle(cx + s * 0.1f, cy - s * 0.05f, s * 0.15f, p); p.style = Paint.Style.STROKE
                rays(c, cx + s * 0.1f, cy - s * 0.05f, s * 0.23f, s * 0.32f, 0, 4) }
            MAGHRIB -> { val hy = cy + s * 0.05f
                c.drawLine(cx - s * 0.42f, hy, cx + s * 0.42f, hy, p)
                c.drawPath(Path().apply { addArc(RectF(cx - s * 0.2f, hy - s * 0.2f, cx + s * 0.2f, hy + s * 0.2f), 180f, 180f) }, p)
                c.drawLine(cx, hy + s * 0.12f, cx, hy + s * 0.36f, p)
                c.drawLine(cx - s * 0.1f, hy + s * 0.26f, cx, hy + s * 0.36f, p); c.drawLine(cx + s * 0.1f, hy + s * 0.26f, cx, hy + s * 0.36f, p) }
            ISHA -> { crescent(c, cx - s * 0.04f, cy + s * 0.02f, s * 0.3f); p.style = Paint.Style.FILL; c.drawCircle(cx + s * 0.28f, cy - s * 0.24f, s * 0.05f, p) }
            CRESCENT -> crescent(c, cx, cy, s * 0.36f)
            MOSQUE -> { val b = cy + s * 0.36f
                c.drawLine(cx - s * 0.42f, b, cx + s * 0.42f, b, p)
                c.drawPath(Path().apply { moveTo(cx - s * 0.22f, b); lineTo(cx - s * 0.22f, cy)
                    cubicTo(cx - s * 0.26f, cy - s * 0.22f, cx - s * 0.05f, cy - s * 0.24f, cx, cy - s * 0.36f)
                    cubicTo(cx + s * 0.05f, cy - s * 0.24f, cx + s * 0.26f, cy - s * 0.22f, cx + s * 0.22f, cy); lineTo(cx + s * 0.22f, b) }, p)
                c.drawLine(cx + s * 0.38f, b, cx + s * 0.38f, cy - s * 0.3f, p); c.drawLine(cx - s * 0.38f, b, cx - s * 0.38f, cy - s * 0.3f, p) }
            HOURGLASS -> { c.drawLine(cx - s * 0.24f, cy - s * 0.38f, cx + s * 0.24f, cy - s * 0.38f, p); c.drawLine(cx - s * 0.24f, cy + s * 0.38f, cx + s * 0.24f, cy + s * 0.38f, p)
                c.drawPath(Path().apply { moveTo(cx - s * 0.18f, cy - s * 0.38f); lineTo(cx + s * 0.18f, cy + s * 0.38f); moveTo(cx + s * 0.18f, cy - s * 0.38f); lineTo(cx - s * 0.18f, cy + s * 0.38f) }, p) }
            LANTERN -> { c.drawCircle(cx, cy - s * 0.4f, s * 0.05f, p)
                c.drawPath(Path().apply { moveTo(cx - s * 0.16f, cy - s * 0.22f); lineTo(cx, cy - s * 0.34f); lineTo(cx + s * 0.16f, cy - s * 0.22f); close() }, p)
                c.drawRoundRect(cx - s * 0.2f, cy - s * 0.2f, cx + s * 0.2f, cy + s * 0.26f, s * 0.08f, s * 0.08f, p)
                p.style = Paint.Style.FILL; p.alpha = 90; c.drawRoundRect(cx - s * 0.12f, cy - s * 0.12f, cx + s * 0.12f, cy + s * 0.18f, s * 0.05f, s * 0.05f, p); p.alpha = 255; p.style = Paint.Style.STROKE
                c.drawLine(cx - s * 0.12f, cy + s * 0.36f, cx + s * 0.12f, cy + s * 0.36f, p) }
            DATES -> { p.style = Paint.Style.FILL
                for (i in -1..1) c.drawOval(RectF(cx + i * s * 0.17f - s * 0.08f, cy - s * 0.24f, cx + i * s * 0.17f + s * 0.08f, cy + s * 0.02f), p)
                p.style = Paint.Style.STROKE
                c.drawPath(Path().apply { addArc(RectF(cx - s * 0.4f, cy - s * 0.24f, cx + s * 0.4f, cy + s * 0.4f), 0f, 180f) }, p)
                c.drawLine(cx - s * 0.4f, cy + s * 0.08f, cx + s * 0.4f, cy + s * 0.08f, p) }
        }
    }
}
