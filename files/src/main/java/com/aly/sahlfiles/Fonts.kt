package com.aly.sahlfiles

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
    private var dispMed: Typeface? = null      // Thmanyah Serif Display: titles and headings
    private var dispBold: Typeface? = null
    private var textReg: Typeface? = null      // Thmanyah Serif Text: text you read (messages, notes, book titles)
    private var textBold: Typeface? = null

    /** Text size for all Sahl apps (set in Sahl TV → Settings → Text size): 1.0, 1.1 or 1.2. */
    @Volatile var scale = 1f
    private val sizes = java.util.WeakHashMap<TextView, Pair<Float, Float>>()
    private var map: Map<Typeface, Typeface> = emptyMap()
    private var ours: Set<Typeface> = emptySet()

    fun init(ctx: Context) {
        if (regular != null) return
        regular = load(ctx, "r") ?: return
        light = load(ctx, "l") ?: regular
        medium = load(ctx, "m") ?: regular
        bold = load(ctx, "b") ?: regular
        dispMed = load(ctx, "dm"); dispBold = load(ctx, "db") ?: dispMed
        textReg = load(ctx, "tr"); textBold = load(ctx, "tb") ?: textReg
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
        map = mm; ours = setOfNotNull(r, l, m, b, dispMed, dispBold, textReg, textBold)
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

    /** Serif Display, for titles and headings. */
    fun display(bold: Boolean = true): Typeface = (if (bold) dispBold else dispMed) ?: get(if (bold) 2 else 1)
    /** Serif Text, for text you read. */
    fun text(bold: Boolean = false): Typeface = (if (bold) textBold else textReg) ?: get(if (bold) 2 else 0)
    /** Sans Light, for very large numbers (the clock). */
    fun light(): Typeface = light ?: Typeface.create("sans-serif-light", Typeface.NORMAL)

    fun applyTree(v: View) {
        if (v is TextView) {
            if (map.isNotEmpty()) {
                val tf = v.typeface
                if (tf == null) v.typeface = regular
                else if (tf !in ours) map[tf]?.let { v.typeface = it }
            }
            // text size: remember the app's own size, so it is scaled once (and again only if the app changes it)
            val rec = sizes[v]
            if (scale != 1f || rec != null) {
                if (rec == null || kotlin.math.abs(v.textSize - rec.second) > 0.5f) {
                    val o = v.textSize; val want = o * scale
                    sizes[v] = o to want
                    if (want != o) v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, want)
                }
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) applyTree(v.getChildAt(i))
    }

    /** Keeps every text in this window (now and later) in the Sahl font. */
    private val watched = java.util.WeakHashMap<Window, Boolean>()

    fun watch(w: Window?) {
        if (w == null || (map.isEmpty() && scale == 1f) || watched.put(w, true) != null) return
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
        readScale()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) { readScale() }
            override fun onActivityStarted(a: Activity) = Fonts.watch(a.window)   // after the screen is built
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /** The text size chosen in Sahl TV (shared with all Sahl apps through Sahl TV). */
    private fun readScale() {
        try {
            contentResolver.query(android.net.Uri.parse("content://com.aly.sahltv.prefs/scale"), null, null, null, null)?.use { c ->
                if (c.moveToFirst()) Fonts.scale = c.getFloat(0).coerceIn(1f, 1.25f)
            }
        } catch (_: Exception) {}
    }
}

/** AlertDialog in the Sahl font: titles in Serif Display, messages in Serif Text. */
class FontDialog(ctx: Context, theme: Int) : AlertDialog.Builder(ctx, theme) {
    override fun create(): AlertDialog = super.create().also { d ->
        // wait until it is shown (its layout is built then), checking for a few seconds
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        var tries = 0
        val check = object : Runnable { override fun run() {
            if (!d.isShowing) { if (++tries < 50) h.postDelayed(this, 100); return }
            val res = d.context.resources
            (d.findViewById<View>(res.getIdentifier("alertTitle", "id", "android")) as? TextView)?.typeface = Fonts.display(true)
            (d.findViewById<View>(android.R.id.message) as? TextView)?.let { it.typeface = Fonts.text(); it.setLineSpacing(0f, 1.2f) }
            Fonts.watch(d.window)
        } }
        h.post(check)
    }
}
