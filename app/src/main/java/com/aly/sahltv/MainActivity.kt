package com.aly.sahltv

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * Sahl TV (سهل = "easy") — a fast, ad-free home screen for Android TV.
 *
 *  - No libraries, no network, no background services: it only lists your apps and opens them.
 *  - Apps load on a background thread; icons are cached in memory, so going Home is instant.
 *  - Rows: Favorites (your order), Recent, All apps. Long-press OK (or Menu) on a tile for options.
 */
class MainActivity : Activity() {

    private class App(val pkg: String, val label: String, val banner: Drawable?, val icon: Drawable?, val tint: Int)

    private val prefs by lazy { getSharedPreferences("sahl", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var apps: Map<String, App> = emptyMap()
    private var loading = false
    private var dirty = true

    // views
    private lateinit var clockTv: TextView
    private lateinit var dateTv: TextView
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var memBtn: TextView
    private lateinit var prayerTv: TextView
    private lateinit var settingsBtn: TextView
    private var lastSig = ""
    private var lastAutoClean = 0L
    private val ease = DecelerateInterpolator(1.6f)

    // look
    private val BG_TOP = Color.parseColor("#0E1A24")
    private val BG_BOTTOM = Color.parseColor("#10261F")
    private val TEXT = Color.parseColor("#EEF3F1")
    private val MUTED = Color.parseColor("#8FA39C")
    private val ACCENT = Color.parseColor("#3CC88F")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ settings stored on the TV

    private fun list(key: String): MutableList<String> =
        (prefs.getString(key, "") ?: "").split("|").filter { it.isNotEmpty() }.toMutableList()
    private fun saveList(key: String, l: List<String>) = prefs.edit().putString(key, l.joinToString("|")).apply()
    private val favorites get() = list("fav")
    private val hidden get() = list("hidden")
    private val recent get() = list("recent")
    private fun launches(pkg: String) = prefs.getInt("n_$pkg", 0)
    private val sortMostUsed get() = prefs.getBoolean("sortUsed", true)
    private val clock24 get() = prefs.getBoolean("clock24", true)
    private val autoClean get() = prefs.getBoolean("autoClean", true)
    private val locked get() = list("locked")
    private val pinSet get() = prefs.getString("pinHash", null) != null
    private val lockSettings get() = prefs.getBoolean("lockSettings", false)

    // prayer-time locations (Qatar). Index stored in prefs; -1 = prayer times off.
    private val cities = listOf(
        Triple("Doha", 25.2854, 51.5310), Triple("Al Wakrah", 25.1715, 51.6034), Triple("Al Rayyan", 25.2919, 51.4244),
        Triple("Lusail", 25.4230, 51.4900), Triple("Umm Salal", 25.4697, 51.3975), Triple("Al Khor", 25.6839, 51.5058),
        Triple("Mesaieed", 24.9909, 51.5493), Triple("Dukhan", 25.4248, 50.7820), Triple("Madinat ash Shamal", 26.1293, 51.2009)
    )
    private val cityIndex get() = prefs.getInt("city", 0)

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG_TOP, BG_BOTTOM))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(24), dp(48), 0)       // TV safe area
        }
        col.addView(buildTopBar())
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(40)); clipChildren = false; clipToPadding = false
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isSmoothScrollingEnabled = true
            clipToPadding = false
            addView(content)
        }
        col.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(col)
        setContentView(root)
        window.setBackgroundDrawable(null)      // the gradient covers everything: skip one full-screen draw

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(pkgReceiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(pkgReceiver, filter)
        loadApps()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(pkgReceiver) } catch (_: Exception) {}
        worker.shutdown()
    }

    override fun onResume() {
        super.onResume()
        updateClock()
        handler.post(clockTick)
        if (dirty && !loading) loadApps() else if (apps.isNotEmpty()) render(keepFocus = true)
        maybeAutoClean()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
    }

    /** Pressing Home while already home: jump back to the top. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        scroll.smoothScrollTo(0, 0)
        focusFirstTile()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // a home screen has nowhere to go back to: just return to the top
        scroll.smoothScrollTo(0, 0)
        focusFirstTile()
    }

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { dirty = true; loadApps() }
    }

    // ------------------------------------------------------------------ top bar: clock, dates, quick buttons

    private val clockTick = object : Runnable {
        override fun run() { updateClock(); handler.postDelayed(this, 20_000) }
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clockTv = TextView(this).apply { textSize = 40f; setTextColor(TEXT); typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        dateTv = TextView(this).apply { textSize = 15f; setTextColor(MUTED) }
        prayerTv = TextView(this).apply { textSize = 15f; setTextColor(ACCENT); setPadding(0, dp(2), 0, 0) }
        left.addView(clockTv); left.addView(dateTv); left.addView(prayerTv)
        bar.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        memBtn = pill("🧹  Free memory") { freeMemory() }
        bar.addView(memBtn)
        settingsBtn = pill("⚙  TV settings") {
            if (lockSettings) askPin("TV settings are locked") { open(Intent(Settings.ACTION_SETTINGS)) } else open(Intent(Settings.ACTION_SETTINGS))
        }
        bar.addView(settingsBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) })
        bar.addView(pill("☰  Launcher") { launcherMenu() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) })
        return bar
    }

    private fun pill(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 14f; setTextColor(TEXT)
        setPadding(dp(16), dp(9), dp(16), dp(9))
        isFocusable = true; isClickable = true
        defaultFocusHighlightEnabled = false
        val normal = GradientDrawable().apply { setColor(Color.parseColor("#1AFFFFFF")); cornerRadius = dp(20).toFloat() }
        val focused = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(20).toFloat() }
        background = normal
        setOnFocusChangeListener { v, has ->
            v.background = if (has) focused else normal
            (v as TextView).setTextColor(if (has) Color.parseColor("#0E1A24") else TEXT)
        }
        setOnClickListener { onClick() }
    }

    private val hijriMonths = arrayOf("Muharram", "Safar", "Rabi' al-Awwal", "Rabi' al-Thani", "Jumada al-Ula", "Jumada al-Akhirah",
        "Rajab", "Sha'ban", "Ramadan", "Shawwal", "Dhu al-Qa'dah", "Dhu al-Hijjah")

    private fun updateClock() {
        val now = Date()
        clockTv.text = SimpleDateFormat(if (clock24) "HH:mm" else "h:mm a", Locale.US).format(now)
        var d = SimpleDateFormat("EEEE d MMMM yyyy", Locale.US).format(now)
        try {
            val cal = android.icu.util.IslamicCalendar()
            cal.calculationType = android.icu.util.IslamicCalendar.CalculationType.ISLAMIC_UMALQURA
            cal.timeInMillis = now.time
            d += "  ·  ${cal.get(android.icu.util.Calendar.DAY_OF_MONTH)} ${hijriMonths[cal.get(android.icu.util.Calendar.MONTH)]} ${cal.get(android.icu.util.Calendar.YEAR)} AH"
        } catch (_: Throwable) {}
        dateTv.text = d
        updatePrayer()
    }

    private fun updatePrayer() {
        val idx = cityIndex
        if (idx < 0 || idx >= cities.size) { prayerTv.visibility = View.GONE; return }
        prayerTv.visibility = View.VISIBLE
        val (city, la, lo) = cities[idx]
        val zone = ZoneId.systemDefault()
        val nowZ = java.time.ZonedDateTime.now(zone)
        val tz = nowZ.offset.totalSeconds / 3600.0
        val nowH = nowZ.hour + nowZ.minute / 60.0 + nowZ.second / 3600.0
        val today = nowZ.toLocalDate()
        val t = Prayer.compute(today, la, lo, tz)
        val friday = today.dayOfWeek == DayOfWeek.FRIDAY
        val list = listOf("Fajr" to t.fajr, (if (friday) "Jumu'ah" else "Dhuhr") to t.dhuhr, "Asr" to t.asr, "Maghrib" to t.maghrib, "Isha" to t.isha)
        var next = list.firstOrNull { it.second > nowH }
        var hoursLeft = next?.let { it.second - nowH }
        if (next == null) {   // after Isha: tomorrow's Fajr
            val tm = Prayer.compute(today.plusDays(1), la, lo, tz)
            next = "Fajr" to tm.fajr
            hoursLeft = tm.fajr + 24.0 - nowH
        }
        val mins = Math.round(hoursLeft!! * 60).toInt()
        val left = if (mins >= 60) "${mins / 60} h ${mins % 60} min" else "$mins min"
        prayerTv.text = "🕌  Next: ${next.first} ${Prayer.hm(next.second)}  ·  in $left  ·  $city"
    }

    // ------------------------------------------------------------------ apps

    private fun loadApps() {
        if (loading) return
        loading = true
        dirty = false
        worker.execute {
            val pm = packageManager
            val found = LinkedHashMap<String, App>()
            val intents = listOf(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            )
            for (i in intents) {
                for (ri in pm.queryIntentActivities(i, 0)) {
                    val pkg = ri.activityInfo.packageName
                    if (pkg == packageName || found.containsKey(pkg)) continue
                    val ai = ri.activityInfo.applicationInfo
                    val banner = try { (ri.activityInfo.loadBanner(pm) ?: ai.loadBanner(pm))?.let { prescale(it, dp(200), dp(112)) } } catch (_: Exception) { null }
                    val icon = try { ri.loadIcon(pm) } catch (_: Exception) { null }
                    val label = ri.loadLabel(pm)?.toString() ?: pkg
                    found[pkg] = App(pkg, label, banner, icon, if (banner == null) tintOf(icon) else Color.BLACK)
                }
            }
            handler.post {
                apps = found
                loading = false
                render(keepFocus = true)
            }
        }
    }

    /** Draw the banner once at tile size, so scrolling never has to rescale vector or large images. */
    private fun prescale(d: Drawable, w: Int, h: Int): Drawable {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, w, h); d.draw(c)
        return BitmapDrawable(resources, b)
    }

    /** A pleasant tile colour taken from the icon (average of its pixels, darkened). */
    private fun tintOf(d: Drawable?): Int {
        if (d == null) return Color.parseColor("#22343C")
        return try {
            val b = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            d.setBounds(0, 0, 8, 8); d.draw(c)
            var r = 0; var g = 0; var bl = 0; var n = 0
            for (x in 0 until 8) for (y in 0 until 8) {
                val p = b.getPixel(x, y)
                if (Color.alpha(p) < 128) continue
                r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++
            }
            if (n == 0) Color.parseColor("#22343C")
            else Color.rgb((r / n * 0.45 + 20).toInt(), (g / n * 0.45 + 24).toInt(), (bl / n * 0.45 + 30).toInt())
        } catch (_: Exception) { Color.parseColor("#22343C") }
    }

    private fun launch(app: App) {
        if (app.pkg in locked) { askPin("${app.label} is locked") { launchNow(app) }; return }
        launchNow(app)
    }

    private fun launchNow(app: App) {
        val pm = packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(app.pkg) ?: pm.getLaunchIntentForPackage(app.pkg)
        if (intent == null) { toast("Can't open ${app.label}"); return }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // remember for "Recent" and "most used"
        val r = recent; r.remove(app.pkg); r.add(0, app.pkg); saveList("recent", r.take(8))
        prefs.edit().putInt("n_${app.pkg}", launches(app.pkg) + 1).apply()
        open(intent)
    }

    private fun open(i: Intent) {
        try { startActivity(i) } catch (_: Exception) { toast("Not available on this TV") }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ screen

    private var focusPkg: String? = null
    private var focusRow: String? = null

    private fun render(keepFocus: Boolean, force: Boolean = false) {
        // nothing changed since last time? keep the existing tiles: Home stays instant and focus stays put
        val sig = listOf(apps.keys.joinToString(), prefs.getString("fav", ""), prefs.getString("recent", ""),
            prefs.getString("hidden", ""), prefs.getString("locked", ""), sortMostUsed.toString(),
            resources.configuration.screenWidthDp.toString()).joinToString("#")
        if (!force && sig == lastSig && content.childCount > 0) return
        lastSig = sig
        val hid = hidden.toSet()
        val fav = favorites.filter { apps.containsKey(it) }
        val rec = recent.filter { apps.containsKey(it) && it !in hid }.take(6)
        val all = apps.values.filter { it.pkg !in hid }.let { l ->
            if (sortMostUsed) l.sortedWith(compareByDescending<App> { launches(it.pkg) }.thenBy { it.label.lowercase() })
            else l.sortedBy { it.label.lowercase() }
        }
        content.removeAllViews()

        if (fav.isNotEmpty()) {
            content.addView(rowTitle("Favorites"))
            content.addView(hRow("fav", fav.mapNotNull { apps[it] }, big = true))
        } else {
            content.addView(rowTitle("Favorites"))
            content.addView(TextView(this).apply {
                text = "Tip: hold OK on any app (or press Menu) and choose “Add to Favorites”."
                textSize = 14f; setTextColor(MUTED); setPadding(0, dp(4), 0, dp(8))
            })
        }
        if (rec.size >= 2) {
            content.addView(rowTitle("Recent"))
            content.addView(hRow("rec", rec.mapNotNull { apps[it] }, big = false))
        }
        content.addView(rowTitle("All apps  ·  ${all.size}"))
        // as many 142 dp columns as fit inside the TV safe area (48 dp each side)
        val cols = maxOf(4, (resources.configuration.screenWidthDp - 96) / 142)
        val grid = GridLayout(this).apply { columnCount = cols; useDefaultMargins = false; clipChildren = false; clipToPadding = false }
        for (a in all) grid.addView(tile(a, "all", big = false))
        content.addView(grid)

        if (keepFocus && focusPkg != null) {
            val tag = "$focusRow/$focusPkg"
            val v = content.findViewWithTag<View>(tag) ?: content.findViewWithTag<View>("all/$focusPkg")
            if (v != null) { v.requestFocus(); return }
        }
        focusFirstTile()
    }

    private fun focusFirstTile() {
        handler.post {
            val first = favorites.firstOrNull { apps.containsKey(it) }?.let { content.findViewWithTag<View>("fav/$it") }
            (first ?: firstTile(content))?.requestFocus()
        }
    }

    private fun firstTile(v: View): View? {
        if (v.tag is String && (v.tag as String).contains("/")) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) firstTile(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun rowTitle(t: String) = TextView(this).apply {
        text = t; textSize = 16f; setTextColor(MUTED); setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(2), dp(18), 0, dp(8))
    }

    private fun hRow(row: String, list: List<App>, big: Boolean): View {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), dp(6), dp(6), dp(6)) }
        for (a in list) line.addView(tile(a, row, big))
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false; clipChildren = false
            addView(line)
        }
    }

    private fun tile(a: App, row: String, big: Boolean): View {
        val w = if (big) dp(200) else dp(130)
        val h = if (big) dp(112) else dp(73)
        val radius = dp(10).toFloat()
        var nameStrip: TextView? = null
        val frame = FrameLayout(this).apply {
            tag = "$row/${a.pkg}"
            isFocusable = true; isClickable = true
            defaultFocusHighlightEnabled = false
            clipToOutline = true
            background = GradientDrawable().apply { setColor(a.tint); cornerRadius = radius }
            contentDescription = a.label
        }
        if (a.banner != null) {
            frame.addView(ImageView(this).apply { setImageDrawable(a.banner); scaleType = ImageView.ScaleType.CENTER_CROP },
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        } else {
            val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            val s = if (big) dp(48) else dp(34)
            inner.addView(ImageView(this).apply { setImageDrawable(a.icon) }, LinearLayout.LayoutParams(s, s))
            inner.addView(TextView(this).apply {
                text = a.label; textSize = if (big) 14f else 11.5f; setTextColor(TEXT); maxLines = 1
                gravity = Gravity.CENTER; setPadding(dp(6), dp(5), dp(6), 0)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            frame.addView(inner, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        if (a.banner != null) {
            // app name slides in at the bottom of banner tiles when focused
            nameStrip = TextView(this).apply {
                text = a.label; textSize = if (big) 13f else 11f; setTextColor(Color.WHITE); maxLines = 1
                setPadding(dp(8), dp(14), dp(8), dp(5))
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Color.parseColor("#CC000000")))
                alpha = 0f
            }
            frame.addView(nameStrip, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        }
        if (a.pkg in locked) {
            frame.addView(TextView(this).apply { text = "🔒"; textSize = 12f; setPadding(dp(5), dp(3), dp(5), dp(3)) },
                FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END))
        }
        // focus ring drawn on top
        val ring = View(this).apply {
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(3), Color.WHITE); cornerRadius = radius }
            visibility = View.INVISIBLE
        }
        frame.addView(ring, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        frame.setOnFocusChangeListener { v, has ->
            ring.visibility = if (has) View.VISIBLE else View.INVISIBLE
            v.animate().scaleX(if (has) 1.1f else 1f).scaleY(if (has) 1.1f else 1f).translationZ(if (has) dp(10).toFloat() else 0f)
                .setInterpolator(ease).setDuration(150).withLayer().start()
            nameStrip?.animate()?.alpha(if (has) 1f else 0f)?.setDuration(150)?.start()
            if (has) { focusPkg = a.pkg; focusRow = row }
        }
        frame.setOnClickListener { launch(a) }
        frame.setOnLongClickListener { appMenu(a, row); true }
        frame.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_MENU && ev.action == KeyEvent.ACTION_UP) { appMenu(a, row); true } else false
        }
        val lp = if (row == "all") GridLayout.LayoutParams().apply { width = w; height = h; setMargins(dp(6), dp(6), dp(6), dp(6)) }
                 else LinearLayout.LayoutParams(w, h).apply { setMargins(dp(6), 0, dp(6), 0) }
        frame.layoutParams = lp
        return frame
    }

    // ------------------------------------------------------------------ menus

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun appMenu(a: App, row: String) {
        val fav = favorites
        val isFav = a.pkg in fav
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (isFav) {
            items.add("★  Remove from Favorites" to { fav.remove(a.pkg); saveList("fav", fav); render(true) })
            val i = fav.indexOf(a.pkg)
            if (i > 0) items.add("←  Move left" to { fav.removeAt(i); fav.add(i - 1, a.pkg); saveList("fav", fav); focusRow = "fav"; render(true) })
            if (i < fav.size - 1) items.add("→  Move right" to { fav.removeAt(i); fav.add(i + 1, a.pkg); saveList("fav", fav); focusRow = "fav"; render(true) })
        } else {
            items.add("☆  Add to Favorites" to { fav.add(a.pkg); saveList("fav", fav); focusRow = "fav"; render(true) })
        }
        if (a.pkg in locked) items.add("🔓  Remove PIN lock" to {
            askPin("Enter PIN to unlock ${a.label}") { val l = locked; l.remove(a.pkg); saveList("locked", l); render(true) }
        }) else items.add("🔒  Lock with PIN" to {
            val doLock = { val l = locked; l.add(a.pkg); saveList("locked", l); render(true); toast("${a.label} now needs the PIN") }
            if (pinSet) askPin("Enter PIN to lock ${a.label}") { doLock() } else createPin { doLock() }
        })
        items.add("🙈  Hide this app" to {
            guarded {
                val h = hidden; h.add(a.pkg); saveList("hidden", h)
                fav.remove(a.pkg); saveList("fav", fav); render(false)
            }
        })
        items.add("ⓘ  App info" to { guarded { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}"))) } })
        items.add("🗑  Uninstall" to { guarded { open(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}"))) } })
        dialog().setTitle(a.label)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun launcherMenu() {
        val city = if (cityIndex in cities.indices) cities[cityIndex].first else "Off"
        val items = arrayOf(
            "Sort all apps: " + (if (sortMostUsed) "Most used first" else "A to Z") + "  (change)",
            "Clock: " + (if (clock24) "24-hour" else "12-hour") + "  (change)",
            "Prayer times: $city  (change)",
            "Auto clean when memory is low: " + (if (autoClean) "On" else "Off") + "  (change)",
            "🔒  PIN & locks" + (if (pinSet) "  (${locked.size} apps locked)" else "  (no PIN set)"),
            "Show hidden apps (${hidden.size})",
            "About Sahl TV"
        )
        dialog().setTitle("Launcher").setItems(items) { _, which ->
            when (which) {
                0 -> { prefs.edit().putBoolean("sortUsed", !sortMostUsed).apply(); render(false) }
                1 -> { prefs.edit().putBoolean("clock24", !clock24).apply(); updateClock() }
                2 -> cityMenu()
                3 -> { prefs.edit().putBoolean("autoClean", !autoClean).apply(); toast("Auto clean " + if (autoClean) "on" else "off") }
                4 -> pinMenu()
                5 -> guarded { hiddenMenu() }
                6 -> dialog().setTitle("Sahl TV  ·  سهل")
                    .setMessage("A fast, ad-free home screen for Android TV.\n\nNo internet access, no tracking, no background services — it only shows your apps and opens them.\n\nPrayer times are calculated on the TV (Qatar method: Fajr 18°, Asr Shafi'i, Isha 90 min after Maghrib). Please compare with the official Awqaf timetable.\n\nTip: hold OK on an app (or press Menu) for options.\n\nFree & open source (MIT).\nContact: alysherif1309@gmail.com")
                    .setPositiveButton("OK", null).show()
            }
        }.show()
    }

    private fun cityMenu() {
        val names = cities.map { it.first } + "Turn prayer times off"
        dialog().setTitle("Prayer times for").setItems(names.toTypedArray()) { _, which ->
            prefs.edit().putInt("city", if (which < cities.size) which else -1).apply(); updatePrayer()
        }.show()
    }

    // ------------------------------------------------------------------ PIN

    private fun hash(pin: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(("sahltv:" + pin).toByteArray()).joinToString("") { String.format("%02x", it) }
    }

    /** Runs the action directly when no PIN is set; otherwise only after the right PIN. */
    private fun guarded(action: () -> Unit) { if (pinSet) askPin("Enter PIN") { action() } else action() }

    private fun askPin(title: String, onOk: () -> Unit) {
        if (!pinSet) { onOk(); return }
        pinPad(title) { pin, dlg, msg ->
            if (hash(pin) == prefs.getString("pinHash", "")) { dlg.dismiss(); onOk(); true }
            else { msg.text = "Wrong PIN — try again"; false }
        }
    }

    private fun createPin(after: () -> Unit) {
        pinPad("Choose a 4-digit PIN") { first, dlg, _ ->
            dlg.dismiss()
            pinPad("Enter the same PIN again") { second, dlg2, msg2 ->
                if (second == first) {
                    prefs.edit().putString("pinHash", hash(first)).apply(); dlg2.dismiss(); toast("PIN saved"); after(); true
                } else { msg2.text = "PINs didn't match — try again"; false }
            }
            true
        }
    }

    private fun pinMenu() {
        if (!pinSet) {
            dialog().setTitle("PIN & locks")
                .setMessage("Set a 4-digit PIN to lock apps (e.g. YouTube, Play Store) and the TV settings button.\n\nNote: this lock works from this home screen. Someone who knows Android well could still reach apps another way (e.g. voice search).")
                .setPositiveButton("Set a PIN") { _, _ -> createPin { pinMenu() } }
                .setNegativeButton("Cancel", null).show()
            return
        }
        askPin("Enter PIN") {
            val items = arrayOf(
                "Change PIN",
                "Lock the TV settings button: " + (if (lockSettings) "On" else "Off") + "  (change)",
                "Locked apps: " + (locked.mapNotNull { apps[it]?.label }.joinToString(", ").ifEmpty { "none — hold OK on an app to lock it" }),
                "Remove PIN (unlocks everything)"
            )
            dialog().setTitle("PIN & locks").setItems(items) { _, which ->
                when (which) {
                    0 -> createPin { }
                    1 -> { prefs.edit().putBoolean("lockSettings", !lockSettings).apply(); toast("TV settings lock " + if (lockSettings) "on" else "off") }
                    2 -> {}
                    3 -> { prefs.edit().remove("pinHash").putBoolean("lockSettings", false).putString("locked", "").apply(); render(true); toast("PIN removed") }
                }
            }.show()
        }
    }

    /** Remote-friendly number pad. check(pin, dialog, messageView) returns true when finished. */
    private fun pinPad(title: String, check: (String, android.app.Dialog, TextView) -> Boolean) {
        var pin = ""
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val dots = TextView(this).apply { textSize = 28f; setTextColor(TEXT); gravity = Gravity.CENTER; text = "○ ○ ○ ○" }
        val msg = TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#F08A8A")); gravity = Gravity.CENTER }
        box.addView(dots); box.addView(msg)
        val grid = GridLayout(this).apply { columnCount = 3 }
        box.addView(grid)
        val dlg = dialog().setTitle(title).setView(box).setNegativeButton("Cancel", null).create()
        fun refresh() { dots.text = (0 until 4).joinToString(" ") { if (it < pin.length) "●" else "○" } }
        for (k in listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✓")) {
            grid.addView(TextView(this).apply {
                text = k; textSize = 22f; setTextColor(TEXT); gravity = Gravity.CENTER
                isFocusable = true; isClickable = true; defaultFocusHighlightEnabled = false
                val n = GradientDrawable().apply { setColor(Color.parseColor("#1AFFFFFF")); cornerRadius = dp(10).toFloat() }
                val f = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(10).toFloat() }
                background = n
                setOnFocusChangeListener { v, has -> v.background = if (has) f else n; (v as TextView).setTextColor(if (has) BG_TOP else TEXT) }
                setOnClickListener {
                    msg.text = ""
                    when (k) {
                        "⌫" -> pin = pin.dropLast(1)
                        "✓" -> if (pin.length == 4) { if (!check(pin, dlg, msg)) pin = "" }
                        else -> if (pin.length < 4) pin += k
                    }
                    if (k != "✓" && pin.length == 4) { if (!check(pin, dlg, msg)) pin = "" }
                    refresh()
                }
            }, GridLayout.LayoutParams().apply { width = dp(64); height = dp(48); setMargins(dp(4), dp(4), dp(4), dp(4)) })
        }
        dlg.show()
    }

    // ------------------------------------------------------------------ auto clean

    /** When you come back Home and memory is low, quietly close background apps (at most every 2 minutes). */
    private fun maybeAutoClean() {
        if (!autoClean) return
        val now = System.currentTimeMillis()
        if (now - lastAutoClean < 120_000) return
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        if (!info.lowMemory && info.availMem > info.totalMem / 4) return
        lastAutoClean = now
        val before = info.availMem
        worker.execute {
            for (pkg in apps.keys) try { am.killBackgroundProcesses(pkg) } catch (_: Exception) {}
            Thread.sleep(500)
            val after = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
            val freed = ((after - before) / (1024 * 1024)).coerceAtLeast(0)
            if (freed > 0) handler.post {
                memBtn.text = "🧹  Auto-freed $freed MB"
                handler.postDelayed({ memBtn.text = "🧹  Free memory" }, 4000)
            }
        }
    }

    private fun hiddenMenu() {
        val h = hidden.filter { apps.containsKey(it) }
        if (h.isEmpty()) { toast("No hidden apps"); return }
        dialog().setTitle("Tap an app to show it again")
            .setItems(h.map { apps[it]?.label ?: it }.toTypedArray()) { _, which ->
                val l = hidden; l.remove(h[which]); saveList("hidden", l); render(true)
            }.show()
    }

    // ------------------------------------------------------------------ free memory

    /** Asks Android to close apps running in the background (not the one you're using). */
    private fun freeMemory() {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val before = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
        memBtn.text = "🧹  Cleaning…"
        worker.execute {
            for (pkg in apps.keys) try { am.killBackgroundProcesses(pkg) } catch (_: Exception) {}
            Thread.sleep(600)
            val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val freed = ((info.availMem - before) / (1024 * 1024)).coerceAtLeast(0)
            val freeMb = info.availMem / (1024 * 1024)
            val totalMb = info.totalMem / (1024 * 1024)
            handler.post {
                memBtn.text = "🧹  Free memory"
                toast("Freed $freed MB  ·  $freeMb MB of $totalMb MB now free")
            }
        }
    }
}
