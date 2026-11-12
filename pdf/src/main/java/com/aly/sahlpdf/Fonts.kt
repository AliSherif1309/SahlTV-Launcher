package com.aly.sahlpdf

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import java.io.File

/**
 * The Thmanyah font (ثمانية) for all the app's text, in four weights.
 * The font is inside the app only in scrambled form (its licence does not allow sharing the font files),
 * and is unpacked once into the app's private folder. Without it the app simply uses the TV's normal font.
 * Quran text keeps its own Mushaf font: only the TV's standard fonts are replaced.
 */
object Fonts {
    private const val KEY = "Sahl-TV/thmanyah-font/scramble-v1"   // only hides the font inside the app file
    private const val VERSION = 1
    private var light: Typeface? = null
    private var regular: Typeface? = null
    private var medium: Typeface? = null
    private var bold: Typeface? = null
    private var map: Map<Typeface, Typeface> = emptyMap()
    private var ours: Set<Typeface> = emptySet()

    fun init(ctx: Context) {
        if (regular != null) return
        regular = load(ctx, "r") ?: return
        light = load(ctx, "l") ?: regular
        medium = load(ctx, "m") ?: regular
        bold = load(ctx, "b") ?: regular
        val r = regular!!; val l = light!!; val m = medium!!; val b = bold!!
        val mm = HashMap<Typeface, Typeface>()
        fun put(tf: Typeface?, to: Typeface) { if (tf != null && tf !in mm) mm[tf] = to }   // first match wins (a missing family can fall back to the default one)
        put(Typeface.DEFAULT, r); put(Typeface.SANS_SERIF, r)
        put(Typeface.DEFAULT_BOLD, b); put(Typeface.create(Typeface.DEFAULT, Typeface.BOLD), b); put(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD), b)
        put(Typeface.create("sans-serif", Typeface.NORMAL), r); put(Typeface.create("sans-serif", Typeface.BOLD), b)
        for (n in listOf("sans-serif-thin", "sans-serif-light")) { put(Typeface.create(n, Typeface.NORMAL), l); put(Typeface.create(n, Typeface.BOLD), m) }
        put(Typeface.create("sans-serif-medium", Typeface.NORMAL), m); put(Typeface.create("sans-serif-medium", Typeface.BOLD), b)
        // these are other font families on this TV; never replace them (monospace stays for codes)
        for (k in listOf(Typeface.MONOSPACE, Typeface.SERIF)) mm.remove(k)
        map = mm; ours = setOf(r, l, m, b)
    }

    private fun load(ctx: Context, name: String): Typeface? = try {
        val out = File(ctx.noBackupFilesDir, "font_${name}_v$VERSION")
        if (!out.isFile) {
            val k = KEY.toByteArray(Charsets.US_ASCII)
            val data = ctx.assets.open("f/$name.bin").use { it.readBytes() }
            for (i in data.indices) data[i] = (data[i].toInt() xor k[i % k.size].toInt()).toByte()
            val tmp = File(out.path + ".tmp"); tmp.writeBytes(data); tmp.renameTo(out)
        }
        Typeface.createFromFile(out)
    } catch (_: Throwable) { null }

    /** The font for a weight, for code that needs it directly (falls back to the TV font). */
    fun get(boldOrMedium: Int = 0): Typeface = when (boldOrMedium) { 2 -> bold; 1 -> medium; -1 -> light; else -> regular } ?: Typeface.DEFAULT

    fun applyTree(v: View) {
        if (map.isEmpty()) return
        if (v is TextView) {
            val tf = v.typeface
            if (tf == null) v.typeface = regular
            else if (tf !in ours) map[tf]?.let { v.typeface = it }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) applyTree(v.getChildAt(i))
    }

    /** Keeps every text in this window (now and later) in the Sahl font. */
    private val watched = java.util.WeakHashMap<Window, Boolean>()

    fun watch(w: Window?) {
        if (w == null || map.isEmpty() || watched.put(w, true) != null) return
        val d = w.decorView
        d.viewTreeObserver.addOnGlobalLayoutListener { applyTree(d) }
        applyTree(d)
    }
}

/** Starts the font for the whole app, for every screen. */
class SahlApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Fonts.init(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) = Fonts.watch(a.window)   // after the screen is built
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}

/** AlertDialog in the Sahl font. */
class FontDialog(ctx: Context, theme: Int) : AlertDialog.Builder(ctx, theme) {
    override fun create(): AlertDialog = super.create().also { d ->
        // wait until it is shown (its layout is built then), checking for a few seconds
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        var tries = 0
        val check = object : Runnable { override fun run() { if (d.isShowing) Fonts.watch(d.window) else if (++tries < 50) h.postDelayed(this, 100) } }
        h.post(check)
    }
}
