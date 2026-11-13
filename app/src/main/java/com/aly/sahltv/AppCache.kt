package com.aly.sahltv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Saved copy of the app list with ready-drawn banners and icons, so the home rows appear instantly at start-up.
 * An app is redrawn only when it was installed or updated (its "last update" time changed).
 */
internal class AppCache(private val a: MainActivity) {
    // Scoped to one scan: reuse decoded artwork without retaining it across activity lifetimes.
    private val decoded = mutableMapOf<String, MainActivity.App>()
    private val dir = File(a.cacheDir, "apps").apply { mkdirs() }
    private val index = File(dir, "index.json")
    private val entries: MutableMap<String, JSONObject> = try {
        val arr = JSONArray(index.readText())
        (0 until arr.length()).map { arr.getJSONObject(it) }.associateBy { it.getString("p") }.toMutableMap()
    } catch (_: Exception) { mutableMapOf() }

    private fun file(pkg: String, kind: String) = File(dir, pkg.replace(Regex("[^A-Za-z0-9._]"), "_") + "_" + kind + ".png")

    private fun load(f: File): Drawable? = if (f.isFile) BitmapFactory.decodeFile(f.path)?.let { BitmapDrawable(a.resources, it) } else null

    private fun toApp(o: JSONObject): MainActivity.App? {
        val pkg = o.getString("p")
        decoded[pkg]?.let { return it }
        val banner = if (o.optBoolean("b")) load(file(pkg, "b")) ?: return null else null
        val icon = if (o.optBoolean("i")) load(file(pkg, "i")) else null
        return MainActivity.App(pkg, o.optString("l", pkg), banner, icon, o.optInt("t")).also { decoded[pkg] = it }
    }

    /** Everything saved last time, in the same order (shown before the real scan finishes). */
    fun read(): LinkedHashMap<String, MainActivity.App> {
        val out = LinkedHashMap<String, MainActivity.App>()
        for ((p, o) in entries) toApp(o)?.let { out[p] = it }
        return out
    }

    /** The saved app, if it has not changed since. */
    fun get(pkg: String, updated: Long): MainActivity.App? {
        val o = entries[pkg] ?: return null
        if (o.optLong("u") != updated || updated == 0L) return null
        return toApp(o)
    }

    fun put(app: MainActivity.App, updated: Long) {
        fun save(d: Drawable?, kind: String): Boolean {
            val bmp = (d as? BitmapDrawable)?.bitmap ?: return false
            return try { file(app.pkg, kind).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }; true } catch (_: Exception) { false }
        }
        val o = JSONObject().put("p", app.pkg).put("l", app.label).put("t", app.tint).put("u", updated)
            .put("b", save(app.banner, "b")).put("i", save(app.icon, "i"))
        entries[app.pkg] = o
        decoded[app.pkg] = app
    }

    /** Keeps only the apps still installed, in the order found. */
    fun write(keep: Collection<String>) {
        val arr = JSONArray()
        for (p in keep) entries[p]?.let { arr.put(it) }
        for (p in entries.keys - keep.toSet()) { file(p, "b").delete(); file(p, "i").delete() }
        try { File(dir, "index.tmp").apply { writeText(arr.toString()); renameTo(index) } } catch (_: Exception) {}
    }
}
