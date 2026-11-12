package com.aly.sahlquran

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Your own Mushaf from a PDF file kept inside the app (sent from your phone).
 * Pages are drawn by Android's built-in PDF renderer, one at a time on a background thread.
 * [offset] = PDF page index (0-based) that shows Mushaf page 1 (Al-Fatihah).
 */
class PdfMushaf private constructor(private val fd: ParcelFileDescriptor, private val renderer: PdfRenderer) {
    val pageCount get() = renderer.pageCount
    private val cache = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Bitmap>?) = size > 6
    }

    /** Renders a PDF page (0-based) so it fits [w] x [h]. Call from one background thread only. */
    @Synchronized
    fun render(index: Int, w: Int, h: Int): Bitmap? {
        if (index !in 0 until pageCount || w <= 0 || h <= 0) return null
        val key = "$index/$w/$h"
        cache[key]?.let { return it }
        return try {
            renderer.openPage(index).use { p ->
                val scale = minOf(w.toFloat() / p.width, h.toFloat() / p.height)
                val bw = (p.width * scale).toInt().coerceAtLeast(1); val bh = (p.height * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                cache[key] = bmp
                bmp
            }
        } catch (_: Exception) { null }
    }

    @Synchronized
    fun close() { try { renderer.close(); fd.close() } catch (_: Exception) {} }

    companion object {
        fun file(ctx: Context) = File(ctx.filesDir, "mushaf.pdf")
        fun open(f: File): PdfMushaf? = try {
            val fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfMushaf(fd, PdfRenderer(fd))
        } catch (_: Exception) { null }

        /** Paper look for the PDF: as printed, a dimmer warm "night paper", or dark (inverted). */
        fun filter(theme: String): ColorMatrixColorFilter? = when (theme) {
            "night" -> ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                0.78f, 0f, 0f, 0f, 0f,
                0f, 0.72f, 0f, 0f, 0f,
                0f, 0f, 0.58f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f)))
            "dark" -> ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                -0.85f, 0f, 0f, 0f, 235f,
                0f, -0.85f, 0f, 0f, 228f,
                0f, 0f, -0.85f, 0f, 212f,
                0f, 0f, 0f, 1f, 0f)))
            else -> null
        }
    }
}
