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
import java.time.LocalDate

// Seasonal looks that change by themselves: Ramadan lanterns, Eid stars, Qatar National Day maroon and white.
// Drawn only in the side margins of the screen (never behind text or apps), once, as a picture.

/** "ramadan", "eid", "national" or null — by the Hijri date set in the launcher and the calendar. */
internal fun MainActivity.season(today: LocalDate = LocalDate.now()): String? {
    val forced = seasonPreview?.takeIf { System.currentTimeMillis() < seasonPreviewUntil }
    if (forced != null) return forced
    if (!prefs.getBoolean("seasonOn", true)) return null
    val h = Islamic.hijri(today, hijriAdj)
    return when {
        h.m == 8 -> "ramadan"
        (h.m == 9 && h.d <= 3) || (h.m == 11 && h.d in 10..13) -> "eid"
        today.monthValue == 12 && today.dayOfMonth in 10..18 -> "national"
        else -> null
    }
}

internal var seasonPreview: String? = null
internal var seasonPreviewUntil = 0L

/** The background with its seasonal decoration on top (or just the background). */
internal class SeasonalBackdrop(private val base: Drawable, private val kind: String, private val density: Float, private val rtl: Boolean) : Drawable() {
    private var cache: Bitmap? = null
    private fun dp(v: Float) = v * density

    override fun onBoundsChange(b: Rect) { base.bounds = b; cache = null }
    override fun draw(c: Canvas) {
        base.draw(c)
        val b = bounds
        if (b.width() <= 0) return
        val bmp = cache ?: Bitmap.createBitmap(b.width(), b.height(), Bitmap.Config.ARGB_8888).also { paint(Canvas(it), b.width(), b.height()); cache = it }
        c.drawBitmap(bmp, b.left.toFloat(), b.top.toFloat(), null)
    }
    override fun invalidateSelf() { super.invalidateSelf() }
    init { base.callback = object : Callback {
        override fun invalidateDrawable(who: Drawable) { invalidateSelf() }
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {}
        override fun unscheduleDrawable(who: Drawable, what: Runnable) {}
    } }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT

    private fun paint(c: Canvas, w: Int, h: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        when (kind) {
            "ramadan" -> {
                glow(c, p, w, h, Color.argb(40, 240, 190, 100))
                // lanterns hanging in both side margins, at different heights
                for ((x, len) in listOf(dp(22f) to dp(60f), dp(46f) to dp(120f), w - dp(22f) to dp(90f), w - dp(46f) to dp(36f))) lantern(c, p, x, len)
            }
            "eid" -> {
                glow(c, p, w, h, Color.argb(34, 227, 184, 92))
                for (side in listOf(dp(24f), w - dp(24f))) {
                    p.color = Color.argb(90, 227, 184, 92); p.strokeWidth = dp(1f)
                    c.drawLine(side, 0f, side, h * 0.55f, p)
                    var y = dp(40f); var i = 0
                    while (y < h * 0.55f) {
                        if (i % 3 == 1) crescent(c, p, side, y, dp(9f)) else star(c, p, side, y, dp(if (i % 2 == 0) 7f else 5f))
                        y += dp(46f); i++
                    }
                }
            }
            "national" -> {
                // maroon edges with the flag's white serrated line (nine points), soft
                for (left in listOf(true, false)) {
                    val x0 = if (left) 0f else w - dp(34f)
                    p.shader = LinearGradient(x0, 0f, x0 + dp(34f), 0f,
                        if (left) intArrayOf(Color.argb(200, 138, 21, 56), Color.argb(120, 138, 21, 56)) else intArrayOf(Color.argb(120, 138, 21, 56), Color.argb(200, 138, 21, 56)),
                        null, Shader.TileMode.CLAMP)
                    c.drawRect(x0, 0f, x0 + dp(34f), h.toFloat(), p); p.shader = null
                    val edge = if (left) dp(34f) else w - dp(34f)
                    val zig = Path(); val n = 9; val step = h / (n * 2f); val d = if (left) -dp(9f) else dp(9f)
                    zig.moveTo(edge, 0f)
                    for (k in 0 until n * 2) zig.lineTo(if (k % 2 == 0) edge + d else edge, step * (k + 1))
                    p.style = Paint.Style.STROKE; p.strokeWidth = dp(3f); p.color = Color.argb(150, 255, 255, 255)
                    c.drawPath(zig, p); p.style = Paint.Style.FILL
                }
            }
        }
    }

    private fun glow(c: Canvas, p: Paint, w: Int, h: Int, col: Int) {
        val cx = if (rtl) w * 0.85f else w * 0.15f
        p.shader = RadialGradient(cx, 0f, w * 0.45f, intArrayOf(col, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h * 0.6f, p); p.shader = null
    }

    private fun lantern(c: Canvas, p: Paint, x: Float, len: Float) {
        val gold = Color.argb(200, 227, 184, 92)
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1f); p.color = Color.argb(120, 227, 184, 92)
        c.drawLine(x, 0f, x, len, p)
        val top = len; val bw = dp(13f); val bh = dp(26f)
        // warm light
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(x, top + bh * 0.6f, dp(26f), intArrayOf(Color.argb(90, 255, 200, 110), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawCircle(x, top + bh * 0.6f, dp(26f), p); p.shader = null
        // cap, body and base
        val cap = Path().apply { moveTo(x - bw * 0.55f, top + dp(6f)); lineTo(x, top); lineTo(x + bw * 0.55f, top + dp(6f)); close() }
        p.color = gold; c.drawPath(cap, p)
        p.color = Color.argb(150, 255, 214, 140); c.drawRoundRect(x - bw / 2, top + dp(6f), x + bw / 2, top + dp(6f) + bh, dp(4f), dp(4f), p)
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1.2f); p.color = gold
        c.drawRoundRect(x - bw / 2, top + dp(6f), x + bw / 2, top + dp(6f) + bh, dp(4f), dp(4f), p)
        c.drawLine(x, top + dp(6f), x, top + dp(6f) + bh, p)
        p.style = Paint.Style.FILL; c.drawRect(x - bw * 0.35f, top + dp(6f) + bh, x + bw * 0.35f, top + dp(9f) + bh, p)
    }

    private fun star(c: Canvas, p: Paint, x: Float, y: Float, r: Float) {
        val s = Path()
        for (i in 0 until 10) {
            val a = Math.PI / 5 * i - Math.PI / 2; val rr = if (i % 2 == 0) r else r * 0.45f
            val px = x + (rr * Math.cos(a)).toFloat(); val py = y + (rr * Math.sin(a)).toFloat()
            if (i == 0) s.moveTo(px, py) else s.lineTo(px, py)
        }
        s.close(); p.style = Paint.Style.FILL; p.color = Color.argb(180, 240, 205, 140); c.drawPath(s, p)
    }

    private fun crescent(c: Canvas, p: Paint, x: Float, y: Float, r: Float) {
        val m = Path().apply { addCircle(x, y, r, Path.Direction.CW) }
        m.op(Path().apply { addCircle(x + r * 0.45f, y - r * 0.2f, r * 0.85f, Path.Direction.CW) }, Path.Op.DIFFERENCE)
        p.style = Paint.Style.FILL; p.color = Color.argb(190, 238, 211, 154); c.drawPath(m, p)
    }
}
