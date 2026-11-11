package com.aly.sahltv

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Reads the rows that apps publish to the Android TV home screen ("preview channels",
 * e.g. recommended videos) and "Continue watching" (Watch Next).
 *
 * Needs android.permission.READ_TV_LISTINGS (grant once with ADB if the TV shows no prompt):
 *   adb shell pm grant com.aly.sahltv android.permission.READ_TV_LISTINGS
 * Without the system-only ACCESS_ALL_EPG_DATA permission Android returns only rows the app marked
 * "searchable", and refuses a selection, so the whole table is read and filtered here.
 */
object Previews {
    const val PERMISSION = "android.permission.READ_TV_LISTINGS"
    private val CHANNELS = Uri.parse("content://android.media.tv/channel")
    private val PROGRAMS = Uri.parse("content://android.media.tv/preview_program")
    private val WATCH_NEXT = Uri.parse("content://android.media.tv/watch_next_program")

    class Item(val title: String, val poster: String?, val intentUri: String?)
    /** key: "ch:<channel id>" or "watchnext" */
    class Row(val key: String, val pkg: String, val name: String, val items: List<Item>)

    fun hasPermission(ctx: Context) = ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Reads everything (call on a background thread). Returns null when not allowed. */
    fun load(ctx: Context): List<Row>? {
        if (!hasPermission(ctx)) return null
        val cr = ctx.contentResolver
        val rows = ArrayList<Row>()
        try {
            // channels: id -> (package, name, shown?)
            val ch = HashMap<Long, Triple<String, String, Boolean>>()
            cr.query(CHANNELS, null, null, null, null)?.use { c ->
                val iId = c.getColumnIndex("_id"); val iPkg = c.getColumnIndex("package_name")
                val iName = c.getColumnIndex("display_name"); val iBr = c.getColumnIndex("browsable")
                while (c.moveToNext()) {
                    if (iId < 0 || iPkg < 0) continue
                    ch[c.getLong(iId)] = Triple(c.getString(iPkg) ?: "", if (iName >= 0) c.getString(iName) ?: "" else "",
                        iBr < 0 || c.getInt(iBr) != 0)
                }
            }
            // preview programs grouped by channel (keeping the app's own order by weight)
            class P(val ch: Long, val w: Int, val item: Item)
            val progs = ArrayList<P>()
            cr.query(PROGRAMS, null, null, null, null)?.use { c ->
                val iCh = c.getColumnIndex("channel_id"); val iT = c.getColumnIndex("title")
                val iPo = c.getColumnIndex("poster_art_uri"); val iTh = c.getColumnIndex("thumbnail_uri")
                val iIn = c.getColumnIndex("intent_uri"); val iW = c.getColumnIndex("weight"); val iBr = c.getColumnIndex("browsable")
                while (c.moveToNext()) {
                    if (iCh < 0) continue
                    if (iBr >= 0 && c.getInt(iBr) == 0) continue
                    val poster = (if (iPo >= 0) c.getString(iPo) else null) ?: (if (iTh >= 0) c.getString(iTh) else null)
                    progs.add(P(c.getLong(iCh), if (iW >= 0) c.getInt(iW) else 0,
                        Item(if (iT >= 0) c.getString(iT) ?: "" else "", poster, if (iIn >= 0) c.getString(iIn) else null)))
                }
            }
            for ((id, list) in progs.groupBy { it.ch }) {
                val info = ch[id] ?: continue
                if (!info.third) continue
                rows.add(Row("ch:$id", info.first, info.second, list.sortedByDescending { it.w }.map { it.item }.take(20)))
            }
            // continue watching
            val wn = ArrayList<Pair<String, Item>>()
            cr.query(WATCH_NEXT, null, null, null, null)?.use { c ->
                val iPkg = c.getColumnIndex("package_name"); val iT = c.getColumnIndex("title")
                val iPo = c.getColumnIndex("poster_art_uri"); val iTh = c.getColumnIndex("thumbnail_uri"); val iIn = c.getColumnIndex("intent_uri")
                while (c.moveToNext()) {
                    val poster = (if (iPo >= 0) c.getString(iPo) else null) ?: (if (iTh >= 0) c.getString(iTh) else null)
                    wn.add((if (iPkg >= 0) c.getString(iPkg) ?: "" else "") to
                        Item(if (iT >= 0) c.getString(iT) ?: "" else "", poster, if (iIn >= 0) c.getString(iIn) else null))
                }
            }
            if (wn.isNotEmpty()) rows.add(0, Row("watchnext", "", "", wn.map { it.second }.take(20)))
        } catch (_: SecurityException) {
            return null
        } catch (_: Exception) {
        }
        return rows
    }

    // ------------------------------------------------------------------ pictures

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3)
    private val mem = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun key(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { String.format("%02x", it) }

    /** Loads a poster scaled to about w x h pixels; memory + disk cache; result delivered on the main thread. */
    fun image(ctx: Context, uri: String, w: Int, h: Int, done: (Bitmap?) -> Unit) {
        mem.get(uri)?.let { done(it); return }
        pool.execute {
            var bmp: Bitmap? = null
            try {
                val dir = File(ctx.cacheDir, "posters").apply { mkdirs() }
                val f = File(dir, key(uri))
                if (!f.exists()) {
                    val input = if (uri.startsWith("http")) {
                        val con = URL(uri).openConnection() as HttpURLConnection
                        con.connectTimeout = 6000; con.readTimeout = 8000
                        con.inputStream
                    } else ctx.contentResolver.openInputStream(Uri.parse(uri))
                    input?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                }
                if (f.exists()) {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.path, o)
                    var sample = 1
                    while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2
                    bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
                }
                bmp?.let { mem.put(uri, it) }
                trimDisk(dir)
            } catch (_: Exception) {}
            main.post { done(bmp) }
        }
    }

    /** keep the disk cache under ~40 MB */
    private fun trimDisk(dir: File) {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total < 40L * 1024 * 1024) return
        for (f in files.sortedBy { it.lastModified() }) {
            total -= f.length(); f.delete()
            if (total < 30L * 1024 * 1024) break
        }
    }
}
