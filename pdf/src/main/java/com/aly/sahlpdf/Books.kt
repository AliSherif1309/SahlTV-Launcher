package com.aly.sahlpdf

import com.aly.sahl.common.*

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.pdf.PdfRenderer
import android.os.Environment
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** One open PDF, drawn by Android's built-in PDF renderer (no extra libraries). Use from one thread at a time. */
class PdfDoc private constructor(private val fd: ParcelFileDescriptor, private val r: PdfRenderer) {
    val count get() = r.pageCount
    private val sizes = HashMap<Int, Pair<Int, Int>>()

    @Synchronized
    fun pageSize(i: Int): Pair<Int, Int> = sizes[i] ?: try {
        r.openPage(i).use { p -> (p.width to p.height).also { sizes[i] = it } }
    } catch (_: Exception) { 595 to 842 }

    /** Page [i] drawn at [scale] (1 = PDF points), on white paper. */
    @Synchronized
    fun render(i: Int, scale: Float): Bitmap? {
        if (i !in 0 until count) return null
        return try {
            r.openPage(i).use { p ->
                val w = (p.width * scale).toInt().coerceIn(1, 4096); val h = (p.height * scale).toInt().coerceIn(1, 4096)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                val m = android.graphics.Matrix().apply { setScale(w.toFloat() / p.width, h.toFloat() / p.height) }
                p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        } catch (_: Throwable) { null }
    }

    @Synchronized
    fun close() { try { r.close() } catch (_: Exception) {}; try { fd.close() } catch (_: Exception) {} }

    companion object {
        fun open(fd: ParcelFileDescriptor): PdfDoc? = try { PdfDoc(fd, PdfRenderer(fd)) } catch (_: Throwable) { try { fd.close() } catch (_: Exception) {}; null }
        fun open(f: File): PdfDoc? = try { open(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)) } catch (_: Throwable) { null }

        /** Paper colour: as printed, warm (softer at night), or dark (white text on dark). */
        fun paper(kind: String): ColorMatrixColorFilter? = when (kind) {
            "warm" -> ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                0.93f, 0f, 0f, 0f, 0f,  0f, 0.86f, 0f, 0f, 0f,  0f, 0f, 0.70f, 0f, 0f,  0f, 0f, 0f, 1f, 0f)))
            "dark" -> ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                -0.80f, 0f, 0f, 0f, 225f,  0f, -0.80f, 0f, 0f, 222f,  0f, 0f, -0.80f, 0f, 210f,  0f, 0f, 0f, 1f, 0f)))
            else -> null
        }
    }
}

/** What Sahl PDF remembers about each book: where you stopped, bookmarks, and how you like to read it. */
class Book(val path: String, val o: JSONObject) {
    val file get() = File(path)
    val title get() = file.name.removeSuffix(".pdf").removeSuffix(".PDF")
    var page: Int get() = o.optInt("page", 0); set(v) { o.put("page", v) }
    var count: Int get() = o.optInt("count", 0); set(v) { o.put("count", v) }
    var opened: Long get() = o.optLong("t", 0); set(v) { o.put("t", v) }
    var finished: Boolean get() = o.optBoolean("done", false); set(v) { o.put("done", v) }
    val marks: MutableList<Int> get() {
        val a = o.optJSONArray("bm") ?: JSONArray().also { o.put("bm", it) }
        return (0 until a.length()).map { a.getInt(it) }.toMutableList()
    }
    fun setMarks(l: List<Int>) { o.put("bm", JSONArray(l.distinct().sorted())) }
    fun opt(k: String, def: String) = o.optString(k, def)
    fun set(k: String, v: Any) { o.put(k, v) }
    val progress: Float get() = if (count <= 1) 0f else (page + 1f) / count
    /** Started and not finished: shown in "Continue reading". */
    val reading get() = opened > 0 && !finished && page > 0 && file.exists()
}

object Books {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("books", Context.MODE_PRIVATE)

    fun get(ctx: Context, path: String): Book = Book(path, try { JSONObject(sp(ctx).getString(path, "{}")!!) } catch (_: Exception) { JSONObject() })
    fun save(ctx: Context, b: Book) { sp(ctx).edit().putString(b.path, b.o.toString()).apply() }
    fun forget(ctx: Context, path: String) { sp(ctx).edit().remove(path).apply() }
    fun all(ctx: Context): List<Book> = sp(ctx).all.keys.map { get(ctx, it) }

    /** Where books sent from the phone go: "Download/Sahl PDF" (same folder as before, so earlier books stay), or the app's own folder without file access. */
    @Suppress("DEPRECATION")
    fun inbox(ctx: Context): File {
        if (Storage.granted(ctx)) {
            val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Sahl PDF")
            if (d.isDirectory || d.mkdirs()) return d
        }
        return File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "Books").apply { mkdirs() }
    }

    /** All PDFs in the usual places (Sahl PDF folder, Download, Documents, USB drives) plus books opened before. */
    @Suppress("DEPRECATION")
    fun scan(ctx: Context): List<File> {
        val out = LinkedHashMap<String, File>()
        fun walk(d: File, depth: Int) {
            val list = d.listFiles() ?: return
            for (f in list) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) { if (depth > 0 && f.name != "Android") walk(f, depth - 1) }
                else if (f.name.endsWith(".pdf", true) && f.length() > 0) out[f.absolutePath] = f
            }
        }
        walk(inbox(ctx), 3)
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "Books").let { if (it.isDirectory) walk(it, 2) }
        if (Storage.granted(ctx)) {
            walk(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), 2)
            walk(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), 2)
            walk(Environment.getExternalStorageDirectory(), 0)
            for (u in Storage.removable()) walk(u, 2)
        }
        for (b in all(ctx)) if (b.opened > 0 && b.file.isFile) out[b.path] = b.file
        return out.values.toList()
    }
}

/** Book covers (first page), made once in the background and kept as small pictures. */
object Covers {
    private val exec = Executors.newSingleThreadExecutor()
    private val mem = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Bitmap>?) = size > 80
    }

    private fun key(f: File): String {
        val md = MessageDigest.getInstance("MD5").digest((f.absolutePath + f.length() + f.lastModified()).toByteArray())
        return md.joinToString("") { "%02x".format(it) }
    }

    fun load(ctx: Context, f: File, done: (Bitmap?) -> Unit) {
        val k = key(f)
        synchronized(mem) { mem[k] }?.let { done(it); return }
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        exec.execute {
            val cache = File(ctx.cacheDir, "covers/$k.jpg")
            var bmp: Bitmap? = if (cache.isFile) BitmapFactory.decodeFile(cache.path) else null
            if (bmp == null) {
                PdfDoc.open(f)?.let { doc ->
                    val (w, _) = doc.pageSize(0)
                    bmp = doc.render(0, 360f / w.coerceAtLeast(1))
                    doc.close()
                }
                bmp?.let { b ->
                    cache.parentFile?.mkdirs()
                    try { cache.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) } } catch (_: Exception) {}
                }
            }
            bmp?.let { synchronized(mem) { mem[k] = it } }
            main.post { done(bmp) }
        }
    }
}
