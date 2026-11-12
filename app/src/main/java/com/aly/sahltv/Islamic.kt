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
 * Night-sky background in the Islamic theme: deep green gradient, a faint gold eight-point-star pattern,
 * a crescent with a few stars and a soft mosque silhouette. Drawn once into a bitmap, so it costs nothing while browsing.
 */
class IslamicBackdrop(private val density: Float) : Drawable() {
    private var cache: Bitmap? = null
    private fun dp(v: Float) = v * density

    override fun onBoundsChange(bounds: Rect) {
        cache = null
    }

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

        // sky: night blue at the top fading to deep green
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(Color.parseColor("#0A1B2A"), Color.parseColor("#0B2620"), Color.parseColor("#071A15")),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null

        // warm glow behind the mosque
        val gx = w * 0.62f; val gy = dp(150f)
        p.shader = RadialGradient(gx, gy, w * 0.38f, intArrayOf(Color.argb(70, 227, 170, 90), Color.argb(0, 227, 170, 90)), null, Shader.TileMode.CLAMP)
        c.drawCircle(gx, gy, w * 0.38f, p)
        p.shader = null

        // eight-point stars (two overlapping squares) in a lattice, very faint gold
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1f); p.color = Color.argb(20, 227, 184, 92)
        val step = dp(72f); val r = dp(20f)
        var y = 0f; var row = 0
        while (y < h + step) {
            var x = if (row % 2 == 0) 0f else step / 2
            while (x < w + step) { star(c, p, x, y, r); x += step }
            y += step / 2; row++
        }

        // stars in the sky
        p.style = Paint.Style.FILL
        val rnd = java.util.Random(7)
        repeat(40) {
            p.color = Color.argb(60 + rnd.nextInt(120), 255, 240, 210)
            c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * dp(170f), dp(0.6f + rnd.nextFloat() * 1.1f), p)
        }

        // crescent
        val mx = w * 0.47f; val my = dp(62f); val mr = dp(24f)
        val moon = Path().apply { addCircle(mx, my, mr, Path.Direction.CW) }
        val bite = Path().apply { addCircle(mx + mr * 0.45f, my - mr * 0.25f, mr * 0.88f, Path.Direction.CW) }
        moon.op(bite, Path.Op.DIFFERENCE)
        p.color = Color.parseColor("#F3D58A"); p.setShadowLayer(dp(14f), 0f, 0f, Color.argb(160, 243, 213, 138))
        c.drawPath(moon, p)
        p.clearShadowLayer()

        mosque(c, p, w * 0.62f, dp(206f), w * 0.40f)

        // fade the bottom of the silhouette into the background, so the panels stay easy to read
        p.shader = LinearGradient(0f, dp(120f), 0f, dp(240f), intArrayOf(Color.argb(0, 11, 38, 32), Color.argb(235, 11, 38, 32)), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, dp(120f), w.toFloat(), dp(240f), p)
        p.shader = null
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

    /** A simple mosque: big dome, two side domes, two minarets with crescents; lit arched windows. */
    private fun mosque(c: Canvas, p: Paint, cx: Float, base: Float, width: Float) {
        val body = Color.parseColor("#13302A"); val rim = Color.argb(110, 243, 213, 138)
        val u = width / 20f
        val s = Path()
        // main hall
        s.addRect(cx - 6 * u, base - 5 * u, cx + 6 * u, base, Path.Direction.CW)
        // main dome (onion shape)
        val dome = Path().apply {
            moveTo(cx - 4.2f * u, base - 5 * u)
            cubicTo(cx - 5.2f * u, base - 9.5f * u, cx - 1.2f * u, base - 10.5f * u, cx, base - 12.5f * u)
            cubicTo(cx + 1.2f * u, base - 10.5f * u, cx + 5.2f * u, base - 9.5f * u, cx + 4.2f * u, base - 5 * u)
            close()
        }
        s.addPath(dome)
        // side wings with small domes
        for (side in listOf(-1f, 1f)) {
            val wx = cx + side * 8f * u
            s.addRect(wx - 2.2f * u, base - 3.5f * u, wx + 2.2f * u, base, Path.Direction.CW)
            s.addPath(Path().apply {
                moveTo(wx - 1.8f * u, base - 3.5f * u)
                cubicTo(wx - 2.2f * u, base - 5.6f * u, wx - 0.5f * u, base - 5.8f * u, wx, base - 6.6f * u)
                cubicTo(wx + 0.5f * u, base - 5.8f * u, wx + 2.2f * u, base - 5.6f * u, wx + 1.8f * u, base - 3.5f * u)
                close()
            })
            // minaret
            val mx = cx + side * 11.6f * u
            s.addRect(mx - 0.55f * u, base - 13f * u, mx + 0.55f * u, base, Path.Direction.CW)
            s.addRect(mx - 0.95f * u, base - 10.2f * u, mx + 0.95f * u, base - 9.6f * u, Path.Direction.CW)
            s.addRect(mx - 0.85f * u, base - 13.4f * u, mx + 0.85f * u, base - 12.9f * u, Path.Direction.CW)
            s.addPath(Path().apply {
                moveTo(mx - 0.6f * u, base - 13.4f * u); lineTo(mx, base - 15.6f * u); lineTo(mx + 0.6f * u, base - 13.4f * u); close()
            })
        }
        p.style = Paint.Style.FILL; p.color = body
        c.drawPath(s, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = density * 1.2f; p.color = rim
        c.drawPath(s, p)

        // finials: small crescents on the main dome and minarets
        p.style = Paint.Style.FILL; p.color = Color.parseColor("#E3B85C")
        for (fx in listOf(cx to base - 12.5f * u, cx - 11.6f * u to base - 15.6f * u, cx + 11.6f * u to base - 15.6f * u)) {
            c.drawRect(fx.first - u * 0.06f, fx.second - u * 0.9f, fx.first + u * 0.06f, fx.second, p)
            val m = Path().apply { addCircle(fx.first, fx.second - u * 1.1f, u * 0.32f, Path.Direction.CW) }
            m.op(Path().apply { addCircle(fx.first + u * 0.14f, fx.second - u * 1.18f, u * 0.27f, Path.Direction.CW) }, Path.Op.DIFFERENCE)
            c.drawPath(m, p)
        }
        // lit arched windows
        p.color = Color.argb(150, 243, 196, 110)
        for (i in -2..2) arch(c, p, cx + i * 2.2f * u, base - 0.6f * u, 0.7f * u, 2.6f * u)
        for (side in listOf(-1f, 1f)) arch(c, p, cx + side * 8f * u, base - 0.5f * u, 0.6f * u, 1.8f * u)
    }

    private fun arch(c: Canvas, p: Paint, x: Float, bottom: Float, w: Float, h: Float) {
        val path = Path().apply {
            moveTo(x - w / 2, bottom); lineTo(x - w / 2, bottom - h + w * 0.6f)
            quadTo(x - w / 2, bottom - h, x, bottom - h - w * 0.2f)
            quadTo(x + w / 2, bottom - h, x + w / 2, bottom - h + w * 0.6f)
            lineTo(x + w / 2, bottom); close()
        }
        c.drawPath(path, p)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
