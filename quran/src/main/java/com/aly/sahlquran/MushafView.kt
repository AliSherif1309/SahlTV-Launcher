package com.aly.sahlquran

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ReplacementSpan
import android.view.View

/** Colours of the paper, ink and ornaments. */
class MushafStyle(val paper: Int, val ink: Int, val gold: Int, val green: Int, val muted: Int, val around: Int) {
    companion object {
        fun of(theme: String) = when (theme) {
            "light" -> MushafStyle(Color.parseColor("#FFFEFA"), Color.parseColor("#141414"), Color.parseColor("#B08A35"), Color.parseColor("#5D8A64"), Color.parseColor("#8A7E66"), Color.parseColor("#2A2723"))
            "dark" -> MushafStyle(Color.parseColor("#141C18"), Color.parseColor("#E9E3D2"), Color.parseColor("#C9A54F"), Color.parseColor("#6E9A76"), Color.parseColor("#8E9A92"), Color.parseColor("#080C0A"))
            else -> MushafStyle(Color.parseColor("#FBF3DF"), Color.parseColor("#1B1610"), Color.parseColor("#B08A35"), Color.parseColor("#5D8A64"), Color.parseColor("#8A7A5C"), Color.parseColor("#2B241B"))
        }
    }
}

/** Round verse-end marker with the verse number inside, drawn like the medallions in a printed Mushaf. */
class AyahMarkerSpan(private val num: String, private val gold: Int, private val green: Int, private val ink: Int) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
        (paint.textSize * 1.08f).toInt()

    override fun draw(c: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val size = paint.textSize
        val r = size * 0.43f
        val cx = x + size * 0.54f
        val cy = y - size * 0.30f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // petals
        p.style = Paint.Style.FILL; p.color = Color.argb(70, Color.red(green), Color.green(green), Color.blue(green))
        for (i in 0 until 8) {
            val a = Math.PI / 4 * i
            c.drawCircle(cx + (r * 0.98f * Math.cos(a)).toFloat(), cy + (r * 0.98f * Math.sin(a)).toFloat(), r * 0.2f, p)
        }
        p.color = Color.argb(40, Color.red(gold), Color.green(gold), Color.blue(gold)); c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.055f; p.color = gold
        c.drawCircle(cx, cy, r, p)
        p.strokeWidth = size * 0.03f; p.color = green
        c.drawCircle(cx, cy, r * 0.8f, p)
        val tp = Paint(paint); tp.color = ink; tp.textAlign = Paint.Align.CENTER
        tp.textSize = size * (if (num.length >= 3) 0.36f else 0.44f)
        c.drawText(num, cx, cy + tp.textSize * 0.36f, tp)
    }
}

/**
 * One Mushaf page: the same verses as the same page of the Madinah Mushaf (604 pages),
 * framed like a printed page. Text is fitted to fill the page, as in print.
 */
class MushafPage(ctx: Context, private val font: Typeface?) : View(ctx) {
    var data: QuranData? = null
    var page = 0
        set(v) { if (field != v) { field = v; built = null; invalidate() } }
    var style = MushafStyle.of("sepia")
        set(v) { field = v; built = null; invalidate() }
    /** Verse to tint softly (for "Ayah of the day"), or null. */
    var highlight: Pair<Int, Int>? = null
        set(v) { field = v; built = null; invalidate() }
    /** true = right-hand page (odd numbers): the spine shadow is on its left edge. */
    var rightSide = true

    private val d get() = resources.displayMetrics.density
    private fun arNum(n: Int) = n.toString().map { "٠١٢٣٤٥٦٧٨٩"[it - '0'] }.joinToString("")

    private sealed class Block { abstract val h: Float }
    private class Header(val surah: Int, override val h: Float) : Block()
    private class Basmala(val layout: StaticLayout, override val h: Float) : Block()
    private class Text(val layout: StaticLayout, override val h: Float) : Block()
    private class Built(val key: String, val blocks: List<Block>, val gap: Float, val size: Float)
    private var built: Built? = null

    private fun basmalaText(q: QuranData) = q.ayahs[0][0]

    private fun ayahText(q: QuranData, s: Int, a: Int): String {
        val t = q.ayahs[s - 1][a - 1]
        if (a == 1 && s != 1 && s != 9) { val b = basmalaText(q); if (t.startsWith(b)) return t.substring(b.length).trimStart() }
        return t
    }

    private fun paint(size: Float): TextPaint { val st = style; return TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; color = st.ink; font?.let { typeface = it } } }

    private fun layoutOf(text: CharSequence, size: Float, width: Int, justify: Boolean, centre: Boolean): StaticLayout {
        val b = StaticLayout.Builder.obtain(text, 0, text.length, paint(size), width)
            .setTextDirection(TextDirectionHeuristics.RTL)
            .setAlignment(if (centre) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.0f).setIncludePad(false)
        if (justify && android.os.Build.VERSION.SDK_INT >= 26) b.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        return b.build()
    }

    /** The page's pieces in reading order: surah headers, basmalas and verse text. */
    private fun pieces(q: QuranData): List<Any> {
        val out = ArrayList<Any>()
        val ayahs = q.pageAyahs(page)
        var sb: SpannableStringBuilder? = null
        for ((s, a) in ayahs) {
            if (a == 1) {
                sb?.let { out.add(it) }; sb = null
                out.add(intArrayOf(s))                       // header
                if (s != 1 && s != 9) out.add("basmala")
            }
            val b = sb ?: SpannableStringBuilder().also { sb = it }
            val t0 = b.length
            b.append(ayahText(q, s, a))
            if (highlight == s to a) b.setSpan(BackgroundColorSpan(Color.argb(60, 201, 165, 79)), t0, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            b.append(" ")
            val m = b.length; b.append("۝")
            b.setSpan(AyahMarkerSpan(arNum(a), style.gold, style.green, style.ink), m, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            b.append(" ")
        }
        sb?.let { out.add(it) }
        return out
    }

    /** Fits the page: starts from 15 lines per page (like the Madinah Mushaf) and shrinks if the text needs it. */
    private fun build(q: QuranData, w: Int, h: Int): Built {
        val ps = pieces(q)
        var size = h / 15f / 1.62f
        var blocks: List<Block> = emptyList()
        var total = 0f
        for (attempt in 0 until 8) {
            val line = size * 1.62f
            val bl = ArrayList<Block>()
            for (p in ps) when (p) {
                is IntArray -> bl.add(Header(p[0], line * 1.15f))
                "basmala" -> layoutOf(basmalaText(q), size * 0.95f, w, false, true).let { bl.add(Basmala(it, line)) }
                is CharSequence -> layoutOf(p, size, w, true, false).let { bl.add(Text(it, it.lineCount * line)) }
            }
            total = bl.sumOf { it.h.toDouble() }.toFloat()
            blocks = bl
            if (total <= h) break
            size *= (h / total).coerceIn(0.85f, 0.985f)
        }
        val lines = blocks.sumOf { if (it is Text) it.layout.lineCount else 1 }
        val gap = if (page <= 2) 0f else ((h - total) / lines.coerceAtLeast(1)).coerceIn(0f, size * 0.6f)
        return Built("$w/$h/$page", blocks, gap, size)
    }

    override fun onDraw(c: Canvas) {
        val q = data ?: return
        if (page !in 1..604) return
        val W = width.toFloat(); val H = height.toFloat()
        val s = style
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // paper with a soft shadow towards the spine
        p.color = s.paper; c.drawRect(0f, 0f, W, H, p)
        val spineX = if (rightSide) 0f else W
        p.shader = LinearGradient(spineX, 0f, if (rightSide) W * 0.08f else W * 0.92f, 0f, Color.argb(55, 0, 0, 0), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H, p); p.shader = null

        // header line: surah name and juz', outside the frame
        val first = q.pageAyahs(page).firstOrNull() ?: return
        val small = paint(H * 0.024f).apply { color = s.muted; textAlign = Paint.Align.CENTER }
        val m = W * 0.06f
        val top = H * 0.055f
        c.drawText("سورة " + QuranData.NAMES_AR[first.first - 1], W - m - W * 0.12f, top, small)
        c.drawText("الجزء " + arNum(q.juzOf(first.first, first.second)), m + W * 0.12f, top, small)

        // frame: gold outer line, green band, gold inner line, small rosettes in the corners
        val fr = RectF(m, H * 0.075f, W - m, H * 0.915f)
        p.style = Paint.Style.STROKE
        p.color = s.gold; p.strokeWidth = d * 2.4f; c.drawRect(fr, p)
        p.color = s.green; p.strokeWidth = d * 1.4f; c.drawRect(RectF(fr.left + d * 4, fr.top + d * 4, fr.right - d * 4, fr.bottom - d * 4), p)
        p.color = s.gold; p.strokeWidth = d * 1f; c.drawRect(RectF(fr.left + d * 7, fr.top + d * 7, fr.right - d * 7, fr.bottom - d * 7), p)
        p.style = Paint.Style.FILL
        for ((x, y) in listOf(fr.left to fr.top, fr.right to fr.top, fr.left to fr.bottom, fr.right to fr.bottom)) {
            p.color = s.gold; c.drawCircle(x, y, d * 5f, p); p.color = s.paper; c.drawCircle(x, y, d * 2.4f, p)
        }

        // page number in a small cartouche
        val cx = W / 2; val py = H * 0.955f
        p.style = Paint.Style.STROKE; p.color = s.gold; p.strokeWidth = d * 1.2f
        c.drawRoundRect(RectF(cx - W * 0.07f, py - H * 0.021f, cx + W * 0.07f, py + H * 0.017f), H * 0.02f, H * 0.02f, p)
        val num = paint(H * 0.024f).apply { color = s.ink; textAlign = Paint.Align.CENTER }
        c.drawText(arNum(page), cx, py + H * 0.007f, num)

        // text area
        val pad = d * 14f
        val tx = fr.left + pad; val ty = fr.top + pad
        val tw = (fr.width() - pad * 2).toInt(); val th = (fr.height() - pad * 2).toInt()
        val b = built?.takeIf { it.key == "$tw/$th/$page" } ?: build(q, tw, th).also { built = it }
        var y = ty
        val used = b.blocks.sumOf { (if (it is Text) it.h + it.layout.lineCount * b.gap else it.h + b.gap).toDouble() }.toFloat()
        if (page <= 2 || used < th * 0.8f) y += (th - used) / 2f            // short pages (Al-Fatihah, the start of Al-Baqarah, surah ends) sit in the middle
        for (bl in b.blocks) {
            when (bl) {
                is Header -> { drawHeader(c, bl.surah, tx, y, tw.toFloat(), bl.h, b.size); y += bl.h + b.gap }
                is Basmala -> { c.save(); c.translate(tx, y + (bl.h - bl.layout.height) / 2); bl.layout.draw(c); c.restore(); y += bl.h + b.gap }
                is Text -> {
                    val line = bl.h / bl.layout.lineCount
                    for (i in 0 until bl.layout.lineCount) {
                        // draw line by line so each sits on the page's even line grid
                        c.save()
                        val lt = bl.layout.getLineTop(i).toFloat(); val lb = bl.layout.getLineBottom(i).toFloat()
                        c.translate(tx, y + (line + b.gap - (lb - lt)) / 2 - lt)
                        c.clipRect(0f, lt, tw.toFloat(), lb)
                        bl.layout.draw(c)
                        c.restore()
                        y += line + b.gap
                    }
                }
            }
        }
    }

    /** Surah title band: decorated ends and a cartouche with the name, like the printed headers. */
    private fun drawHeader(c: Canvas, surah: Int, x: Float, y: Float, w: Float, h: Float, size: Float) {
        val s = style
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = RectF(x, y + h * 0.1f, x + w, y + h * 0.9f)
        p.color = Color.argb(45, Color.red(s.green), Color.green(s.green), Color.blue(s.green)); c.drawRect(r, p)
        // simple repeating diamonds on both ends
        p.color = Color.argb(150, Color.red(s.gold), Color.green(s.gold), Color.blue(s.gold))
        val step = r.height() * 0.7f
        var dx = r.left + step / 2
        while (dx < r.right) {
            if (dx < r.left + w * 0.27f || dx > r.right - w * 0.27f) {
                val cy = r.centerY(); val k = r.height() * 0.26f
                c.drawPath(Path().apply { moveTo(dx, cy - k); lineTo(dx + k, cy); lineTo(dx, cy + k); lineTo(dx - k, cy); close() }, p)
            }
            dx += step
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = d * 1.6f; p.color = s.gold; c.drawRect(r, p)
        // cartouche
        val cr = RectF(r.left + w * 0.27f, r.top + r.height() * 0.12f, r.right - w * 0.27f, r.bottom - r.height() * 0.12f)
        p.style = Paint.Style.FILL; p.color = s.paper; c.drawRoundRect(cr, cr.height() / 2, cr.height() / 2, p)
        p.style = Paint.Style.STROKE; p.color = s.gold; c.drawRoundRect(cr, cr.height() / 2, cr.height() / 2, p)
        val tp = paint(size * 0.82f).apply { textAlign = Paint.Align.CENTER }
        c.drawText("سُورَةُ " + QuranData.NAMES_AR[surah - 1], cr.centerX(), cr.centerY() + tp.textSize * 0.3f, tp)
    }
}
