package com.aly.sahltv

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ComponentName
import android.content.ContentValues
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
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
class MainActivity : Activity(), RemoteServer.Host {

    private class App(val pkg: String, val label: String, val banner: Drawable?, val icon: Drawable?, val tint: Int)

    private val prefs by lazy { getSharedPreferences("sahl", MODE_PRIVATE) }

    /** Language chosen in the launcher menu (first start: follows the system language). */
    private val ar: Boolean by lazy {
        val saved = prefs.getString("lang", null)
        if (saved != null) saved == "ar" else resources.configuration.locales[0].language == "ar"
    }
    private fun t(en: String, arabic: String) = if (ar) arabic else en

    // app previews (rows that apps publish to the Android TV home screen)
    private var previewRows: List<Previews.Row> = emptyList()
    private var lastPreviewLoad = 0L
    private val previewsOn get() = prefs.getBoolean("pvOn", false)
    private val previewOff get() = list("pvOff")          // row keys the user switched off
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var apps: Map<String, App> = emptyMap()
    private var loading = false
    private var dirty = true

    // views
    private lateinit var clockTv: TextView
    private lateinit var dateTv: TextView
    private lateinit var hijriTv: TextView
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var memBtn: IconBtn
    private lateinit var prayerTv: TextView
    private lateinit var rootFrame: FrameLayout
    private lateinit var nowBar: LinearLayout
    private lateinit var nowTv: TextView
    private lateinit var nowPlayBtn: TextView
    private var saver: FrameLayout? = null
    private var lastInput = System.currentTimeMillis()
    private var msm: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var mediaAllowed = false
    private lateinit var settingsBtn: IconBtn
    private lateinit var updBtn: IconBtn
    private var lastSig = ""
    private var lastAutoClean = 0L
    private val ease = DecelerateInterpolator(1.6f)

    // look
    private val BG_TOP = Color.parseColor("#0E1A24")
    private val BG_BOTTOM = Color.parseColor("#10261F")
    private val TEXT = Color.parseColor("#EEF3F1")
    private val MUTED = Color.parseColor("#8FA39C")
    private val ACCENT = Color.parseColor("#3CC88F")
    private val GOLD = Color.parseColor("#E3B85C")
    /** Islamic theme (night sky, mosque, gold) or the plain classic look. */
    private val islamic by lazy { prefs.getBoolean("islamic", true) }
    private val FOCUS get() = if (islamic) GOLD else Color.WHITE

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
    private val saverMin get() = prefs.getInt("saverMin", 5)          // 0 = off
    private val nowOn get() = prefs.getBoolean("nowOn", true)
    private val remoteOn get() = prefs.getBoolean("remoteOn", false)
    private val hijriAdj get() = prefs.getInt("hijriAdj", 0)
    private val fastOn get() = prefs.getBoolean("fastOn", true)
    /** Calm mode (for little eyes): minute countdown instead of ticking seconds, softer focus, home screen dims at night. */
    private val calm by lazy { prefs.getBoolean("calm", true) }
    /** 0 = off, 1 = tell me (gold dot on Updates), 2 = download by itself and install when I'm on the home screen. */
    private val autoUpd get() = prefs.getInt("autoUpd", 1)
    private var resumed = false

    // ---- backgrounds: photos come with the app (see tools/fetch_backgrounds.py) and their credits
    private class Bg(val id: String, val en: String, val ar: String, val author: String, val licence: String)
    private val backgrounds: List<Bg> by lazy {
        try {
            val a = org.json.JSONArray(assets.open("backgrounds.json").bufferedReader().use { it.readText() })
            (0 until a.length()).map { a.getJSONObject(it).let { o -> Bg(o.getString("id"), o.getString("en"), o.getString("ar"), o.optString("author"), o.optString("licence")) } }
        } catch (_: Exception) { emptyList() }
    }
    private var bgKey = ""
    private val bgChoice get() = prefs.getString("bg", "auto") ?: "auto"
    private val bgDim get() = prefs.getInt("bgDim", 2)

    /** Which photo should show now: "auto" = the Kaaba by day, the Kaaba at night from Maghrib to Fajr. */
    private fun wantedBg(): String {
        val ids = backgrounds.map { it.id }
        if (bgChoice == "drawn" || ids.isEmpty()) return "drawn"
        if (bgChoice != "auto" && bgChoice in ids) return bgChoice
        val tt = timesFor(LocalDate.now())
        val now = System.currentTimeMillis()
        val night = if (tt != null) now >= epochAt(LocalDate.now(), tt.maghrib) || now < epochAt(LocalDate.now(), tt.fajr)
                    else java.time.LocalTime.now().let { it.hour >= 18 || it.hour < 5 }
        return if (night && "kaaba_night" in ids) "kaaba_night" else if ("kaaba_day" in ids) "kaaba_day" else ids.first()
    }

    private fun makeBackdrop(id: String): android.graphics.drawable.Drawable {
        bgKey = "$id/$bgDim"
        val drawn = IslamicBackdrop(resources.displayMetrics.density, ar)
        return if (id == "drawn") drawn else PhotoBackdrop(this, "bg_$id.jpg", bgDim, drawn)
    }

    private fun refreshBackground(force: Boolean = false) {
        if (!islamic) return
        val id = wantedBg()
        if (force || "$id/$bgDim" != bgKey) rootFrame.background = makeBackdrop(id)
    }
    private lateinit var dimView: View
    private val locked get() = list("locked")
    private val pinSet get() = prefs.getString("pinHash", null) != null
    private val lockSettings get() = prefs.getBoolean("lockSettings", false)

    // prayer-time locations (Qatar). Index stored in prefs; -1 = prayer times off.
    private val cities = Alerts.CITIES
    private val cityIndex get() = prefs.getInt("city", 0)

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prayer.load(this)
        val root = FrameLayout(this).also { rootFrame = it }.apply {
            background = if (islamic) makeBackdrop(wantedBg()) else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG_TOP, BG_BOTTOM)).also { bgKey = "classic" }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = if (ar) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            setPadding(dp(48), dp(24), dp(48), 0)       // TV safe area
        }
        col.addView(buildTopBar())
        col.addView(buildNowBar())
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
        panel = buildPanel()
        panel.layoutDirection = col.layoutDirection
        root.addView(col)
        dimView = View(this).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        root.addView(dimView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
        window.setBackgroundDrawable(null)      // the gradient covers everything: skip one full-screen draw

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(pkgReceiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(pkgReceiver, filter)
        loadApps()
        RemoteServer.host = this
        if (prefs.getBoolean("reopenSettings", false)) { prefs.edit().putBoolean("reopenSettings", false).apply(); handler.post { showSettings() } }
        if (remoteOn) RemoteServer.start(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(pkgReceiver) } catch (_: Exception) {}
        try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        controller?.unregisterCallback(mediaCallback)
        worker.shutdown()
    }

    override fun onResume() {
        super.onResume()
        updateClock()
        handler.post(clockTick)
        handler.post(secondTick)
        if (dirty && !loading) loadApps() else if (apps.isNotEmpty()) render(keepFocus = true)
        maybeAutoClean()
        Alerts.schedule(this)
        resumed = true
        autoUpdateTick()
        refreshPreviews(force = false)
        setupMedia()
        lastInput = System.currentTimeMillis()
        handler.removeCallbacks(saverTick); handler.postDelayed(saverTick, 15_000)
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        handler.removeCallbacks(clockTick)
        handler.removeCallbacks(secondTick)
        handler.removeCallbacks(saverTick)
        hideSaver()
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
        // one line only: the clock shrinks a little instead of wrapping (the column is shared with the weather and buttons)
        clockTv = TextView(this).apply { textSize = 56f; setTextColor(Color.WHITE); typeface = Fonts.get(2); includeFontPadding = false
            setShadowLayer(14f, 0f, 2f, Color.argb(220, 0, 0, 0)); fontFeatureSettings = "tnum"; maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(34, 56, 2, android.util.TypedValue.COMPLEX_UNIT_SP) }
        dateTv = TextView(this).apply { textSize = 17f; setTextColor(Color.parseColor("#E6EEEA")); setShadowLayer(8f, 0f, 1f, Color.BLACK); setPadding(0, dp(2), 0, 0)
            maxLines = 1; setAutoSizeTextTypeUniformWithConfiguration(11, 17, 1, android.util.TypedValue.COMPLEX_UNIT_SP) }
        hijriTv = TextView(this).apply { textSize = 18f; setTextColor(GOLD); typeface = Fonts.display(false); setPadding(0, dp(1), 0, 0); setShadowLayer(8f, 0f, 1f, Color.BLACK)
            maxLines = 1; setAutoSizeTextTypeUniformWithConfiguration(11, 18, 1, android.util.TypedValue.COMPLEX_UNIT_SP) }
        prayerTv = TextView(this).apply { textSize = 15f; setTextColor(ACCENT); visibility = View.GONE }
        val hRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        hRow.addView(GoldIcon(this, GoldIcon.CRESCENT, GOLD), LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) })
        hRow.addView(hijriTv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        left.addView(clockTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)); left.addView(dateTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)); left.addView(hRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        bar.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(buildWeather(), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(20) })

        val dark = Color.parseColor("#0B1E1A")
        fun gap() = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(6) }
        updBtn = IconBtn(this, GoldIcon.UPDATE, t("Updates", "التحديثات"), GOLD, dark) { setUpd(false); Updater.check(this, ar) }
        bar.addView(updBtn, gap())
        memBtn = IconBtn(this, GoldIcon.BROOM, t("Free memory", "تحرير الذاكرة"), GOLD, dark) { freeMemory() }
        bar.addView(memBtn, gap())
        settingsBtn = IconBtn(this, GoldIcon.GEAR, t("TV settings", "إعدادات التلفاز"), GOLD, dark) {
            if (lockSettings) askPin(t("TV settings are locked", "إعدادات التلفاز مقفلة")) { open(Intent(Settings.ACTION_SETTINGS)) } else open(Intent(Settings.ACTION_SETTINGS))
        }
        bar.addView(settingsBtn, gap())
        bar.addView(IconBtn(this, GoldIcon.SLIDERS, t("Settings", "الإعدادات"), GOLD, dark) { launcherMenu() }, gap())
        return bar
    }

    private fun pill(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 14f; setTextColor(TEXT)
        setPadding(dp(16), dp(9), dp(16), dp(9))
        isFocusable = true; isClickable = true
        defaultFocusHighlightEnabled = false
        val normal = GradientDrawable().apply { setColor(Color.parseColor("#1AFFFFFF")); cornerRadius = dp(20).toFloat() }
        val focused = GradientDrawable().apply { setColor(FOCUS); cornerRadius = dp(20).toFloat() }
        background = normal
        setOnFocusChangeListener { v, has ->
            v.background = if (has) focused else normal
            (v as TextView).setTextColor(if (has) Color.parseColor("#0E1A24") else TEXT)
        }
        setOnClickListener { onClick() }
    }

    private fun updateClock() {
        val now = Date()
        clockTv.text = digits(SimpleDateFormat(if (clock24) "HH:mm" else "h:mm a", if (ar) Locale.forLanguageTag("ar") else Locale.US).format(now))
        dateTv.text = digits(SimpleDateFormat("EEEE d MMMM yyyy", if (ar) Locale.forLanguageTag("ar") else Locale.US).format(now))
        hijriTv.text = digits(hijriText(LocalDate.now()))
        updatePrayer()
        updateIslamic()
    }

    private fun updatePrayer() {
        val idx = cityIndex
        if (idx < 0 || idx >= cities.size) { prayerTv.visibility = View.GONE; return }
        val (city, la, lo) = cities[idx]
        val zone = ZoneId.systemDefault()
        val nowZ = java.time.ZonedDateTime.now(zone)
        val tz = nowZ.offset.totalSeconds / 3600.0
        val nowH = nowZ.hour + nowZ.minute / 60.0 + nowZ.second / 3600.0
        val today = nowZ.toLocalDate()
        val t = Prayer.compute(today, la, lo, tz)
        val friday = today.dayOfWeek == DayOfWeek.FRIDAY
        val list = listOf(t("Fajr", "الفجر") to t.fajr, (if (friday) t("Jumu'ah", "الجمعة") else t("Dhuhr", "الظهر")) to t.dhuhr,
            t("Asr", "العصر") to t.asr, t("Maghrib", "المغرب") to t.maghrib, t("Isha", "العشاء") to t.isha)
        var next = list.firstOrNull { it.second > nowH }
        var hoursLeft = next?.let { it.second - nowH }
        if (next == null) {   // after Isha: tomorrow's Fajr
            val tm = Prayer.compute(today.plusDays(1), la, lo, tz)
            next = t("Fajr", "الفجر") to tm.fajr
            hoursLeft = tm.fajr + 24.0 - nowH
        }
        val mins = Math.round(hoursLeft!! * 60).toInt()
        val left = if (mins >= 60) t("${mins / 60} h ${mins % 60} min", "${mins / 60} س ${mins % 60} د") else t("$mins min", "$mins دقيقة")
        prayerTv.text = t("🕌  Next: ${next.first} ${Prayer.hm(next.second)}  ·  in $left  ·  $city",
                          "🕌  الصلاة القادمة: ${next.first} ${Prayer.hm(next.second)}  ·  بعد $left  ·  ${cityAr(idx)}")
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
        if (app.pkg in locked) { askPin(t("${app.label} is locked", "${app.label} مقفل")) { launchNow(app) }; return }
        launchNow(app)
    }

    private fun launchNow(app: App) {
        val pm = packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(app.pkg) ?: pm.getLaunchIntentForPackage(app.pkg)
        if (intent == null) { toast(t("Can't open ${app.label}", "تعذّر فتح ${app.label}")); return }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // remember for "Recent" and "most used"
        val r = recent; r.remove(app.pkg); r.add(0, app.pkg); saveList("recent", r.take(8))
        prefs.edit().putInt("n_${app.pkg}", launches(app.pkg) + 1).apply()
        open(intent)
    }

    private fun open(i: Intent) {
        try { startActivity(i) } catch (_: Exception) { toast(t("Not available on this TV", "غير متاح على هذا التلفاز")) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ screen

    private var focusPkg: String? = null
    private var focusRow: String? = null

    private fun render(keepFocus: Boolean, force: Boolean = false) {
        // nothing changed since last time? keep the existing tiles: Home stays instant and focus stays put
        val sig = listOf(apps.keys.joinToString(), prefs.getString("fav", ""), prefs.getString("recent", ""),
            prefs.getString("hidden", ""), prefs.getString("locked", ""), sortMostUsed.toString(),
            resources.configuration.screenWidthDp.toString(), previewSig()).joinToString("#")
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
        (panel.parent as? android.view.ViewGroup)?.removeView(panel)
        content.addView(panel)

        if (fav.isNotEmpty()) {
            content.addView(rowTitle(t("Favorites", "المفضلة")))
            content.addView(hRow("fav", fav.mapNotNull { apps[it] }, big = true))
        }   // no favorites yet: no empty section, so the Recent apps show right after the cards (tip: hold OK on an app)
        renderPreviews()
        if (rec.size >= 2) {
            content.addView(rowTitle(t("Recent", "المستخدمة مؤخرًا")))
            content.addView(hRow("rec", rec.mapNotNull { apps[it] }, big = false))
        }
        content.addView(rowTitle(t("All apps  ·  ${all.size}", "كل التطبيقات  ·  ${all.size}")))
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
        // room around the tiles + no clipping, so the grown (focused) tile and its gold ring are never cut
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(12), dp(12), dp(12)); clipChildren = false; clipToPadding = false }
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
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(3), FOCUS); cornerRadius = radius }
            visibility = View.INVISIBLE
        }
        frame.addView(ring, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        frame.setOnFocusChangeListener { v, has ->
            ring.visibility = if (has) View.VISIBLE else View.INVISIBLE
            v.animate().scaleX(if (has) 1.1f else 1f).scaleY(if (has) 1.1f else 1f).translationZ(if (has) dp(10).toFloat() else 0f)
                .setInterpolator(ease).setDuration(150).withLayer().start()
            nameStrip?.animate()?.alpha(if (has) 1f else 0f)?.setDuration(150)?.start()
            if (has) { focusPkg = a.pkg; focusRow = row; if (row == "fav") scroll.smoothScrollTo(0, 0) }
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

    // ------------------------------------------------------------------ app previews

    private fun previewSig() = if (!previewsOn) "off" else
        previewRows.joinToString("|") { r -> r.key + ":" + r.items.size + ":" + (r.items.firstOrNull()?.title ?: "") } + "~" + prefs.getString("pvOff", "")

    /** Reload the published rows at most every 3 minutes (or now, when forced). */
    private fun refreshPreviews(force: Boolean) {
        if (!previewsOn || !Previews.hasPermission(this)) return
        val now = System.currentTimeMillis()
        if (!force && now - lastPreviewLoad < 180_000) return
        lastPreviewLoad = now
        worker.execute {
            val rows = Previews.load(this) ?: return@execute
            handler.post { previewRows = rows; render(keepFocus = true) }
        }
    }

    private fun rowTitleFor(r: Previews.Row): String = if (r.key == "watchnext") t("Continue watching", "متابعة المشاهدة")
        else (apps[r.pkg]?.label ?: r.pkg) + (if (r.name.isNotBlank()) "  ·  ${r.name}" else "")

    private fun renderPreviews() {
        if (!previewsOn) return
        val off = previewOff.toSet()
        for (r in previewRows) {
            if (r.key in off || (r.pkg.isNotEmpty() && r.pkg in hidden)) continue
            if (r.items.isEmpty()) continue
            content.addView(rowTitle(rowTitleFor(r)))
            // room around the tiles + no clipping, so the grown (focused) tile and its gold ring are never cut
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(12), dp(12), dp(12)); clipChildren = false; clipToPadding = false }
            r.items.forEachIndexed { i, it -> line.addView(previewTile(r, i, it)) }
            content.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false; clipToPadding = false; clipChildren = false; addView(line)
            })
        }
    }

    private fun previewTile(r: Previews.Row, i: Int, item: Previews.Item): View {
        val w = dp(192); val h = dp(108); val radius = dp(10).toFloat()
        val frame = FrameLayout(this).apply {
            tag = "pv:${r.key}/$i"
            isFocusable = true; isClickable = true; defaultFocusHighlightEnabled = false
            clipToOutline = true
            background = GradientDrawable().apply { setColor(Color.parseColor("#1C2A30")); cornerRadius = radius }
            contentDescription = item.title
        }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(img, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        item.poster?.let { uri -> Previews.image(this, uri, w, h) { b -> if (b != null) img.setImageBitmap(b) } }
        frame.addView(TextView(this).apply {
            text = item.title; textSize = 12f; setTextColor(Color.WHITE); maxLines = 2
            setPadding(dp(8), dp(16), dp(8), dp(6))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Color.parseColor("#DD000000")))
        }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        val ring = View(this).apply {
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(3), FOCUS); cornerRadius = radius }
            visibility = View.INVISIBLE
        }
        frame.addView(ring, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        frame.setOnFocusChangeListener { v, has ->
            ring.visibility = if (has) View.VISIBLE else View.INVISIBLE
            v.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f).translationZ(if (has) dp(10).toFloat() else 0f)
                .setInterpolator(ease).setDuration(150).withLayer().start()
        }
        frame.setOnClickListener { openPreview(r, item) }
        frame.layoutParams = LinearLayout.LayoutParams(w, h).apply { setMargins(dp(6), 0, dp(6), 0) }
        return frame
    }

    private fun openPreview(r: Previews.Row, item: Previews.Item) {
        if (r.pkg in locked) { askPin(t("This app is locked", "هذا التطبيق مقفل")) { openPreviewNow(r, item) }; return }
        openPreviewNow(r, item)
    }

    private fun openPreviewNow(r: Previews.Row, item: Previews.Item) {
        try {
            val i = Intent.parseUri(item.intentUri ?: throw IllegalStateException(), Intent.URI_INTENT_SCHEME)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (_: Exception) {
            apps[r.pkg]?.let { launchNow(it) } ?: toast(t("Can't open this item", "تعذّر فتح هذا العنصر"))
        }
    }

    private fun previewsMenu() {
        if (!Previews.hasPermission(this)) {
            dialog().setTitle(t("App previews", "معاينات التطبيقات"))
                .setMessage(t("Show the rows that apps publish for the home screen (e.g. recommended videos from SmartTube, “Continue watching”).\n\nAndroid needs one permission for this. If no prompt appears on your TV, run this once from your PC:\n\nadb shell pm grant com.aly.sahltv android.permission.READ_TV_LISTINGS\n\nThe pictures are downloaded from the internet only while previews are on.",
                              "عرض الصفوف التي تنشرها التطبيقات للشاشة الرئيسية (مثل الفيديوهات المقترحة من SmartTube و«متابعة المشاهدة»).\n\nيحتاج أندرويد إلى إذن واحد لذلك. إذا لم تظهر نافذة طلب الإذن على التلفاز، نفّذ هذا الأمر مرة واحدة من الكمبيوتر:\n\nadb shell pm grant com.aly.sahltv android.permission.READ_TV_LISTINGS\n\nتُنزَّل الصور من الإنترنت فقط أثناء تفعيل المعاينات."))
                .setPositiveButton(t("Allow", "السماح")) { _, _ -> requestPermissions(arrayOf(Previews.PERMISSION), 7) }
                .setNegativeButton(t("Cancel", "إلغاء"), null).show()
            return
        }
        // load fresh rows so the list shows every app that currently publishes something
        worker.execute {
            val rows = Previews.load(this) ?: emptyList()
            handler.post {
                previewRows = rows
                val off = previewOff
                val labels = ArrayList<String>()
                labels.add(t("Previews: ", "المعاينات: ") + onOff(previewsOn) + t("  (turn ", "  (") + (if (previewsOn) t("off)", "إيقاف)") else t("on)", "تفعيل)")))
                for (r in rows) labels.add((if (r.key in off) "☐  " else "☑  ") + rowTitleFor(r) + "  (${r.items.size})")
                if (rows.isEmpty()) labels.add(t("No app publishes rows yet. In SmartTube/YouTube, enable their home-screen channels or recommendations option.",
                                                  "لا يوجد تطبيق ينشر صفوفًا بعد. في SmartTube أو YouTube فعّل خيار قنوات/اقتراحات الشاشة الرئيسية."))
                dialog().setTitle(t("App previews — choose rows", "معاينات التطبيقات — اختر الصفوف")).setItems(labels.toTypedArray()) { _, which ->
                    if (which == 0) {
                        prefs.edit().putBoolean("pvOn", !previewsOn).apply()
                        if (previewsOn) refreshPreviews(force = true) else render(true)
                        previewsMenu()
                    } else if (which - 1 < rows.size) {
                        val k = rows[which - 1].key
                        val l = previewOff; if (k in l) l.remove(k) else l.add(k); saveList("pvOff", l)
                        render(true); previewsMenu()
                    }
                }.show()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 8) { if (storageOk(write = true)) doBackup() else storageHelp(); return }
        if (requestCode == 9) { if (storageOk(write = false)) doRestore() else storageHelp(); return }
        if (requestCode == 7) {
            if (Previews.hasPermission(this)) { prefs.edit().putBoolean("pvOn", true).apply(); refreshPreviews(force = true); previewsMenu() }
            else toast(t("Not allowed — use the ADB command shown before.", "لم يُسمح — استخدم أمر ADB المذكور."))
        }
    }

    // ------------------------------------------------------------------ now playing (media sessions)

    private fun buildNowBar(): View {
        nowBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { setColor(Color.parseColor("#22FFFFFF")); cornerRadius = dp(14).toFloat() }
            visibility = View.GONE
        }
        nowTv = TextView(this).apply { textSize = 14f; setTextColor(TEXT); maxLines = 1 }
        nowBar.addView(nowTv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        nowBar.addView(pill("⏮") { controller?.transportControls?.skipToPrevious() })
        nowPlayBtn = pill("⏯") {
            val c = controller ?: return@pill
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
        }
        nowBar.addView(nowPlayBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        nowBar.addView(pill("⏭") { controller?.transportControls?.skipToNext() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        nowBar.addView(pill(t("Open", "فتح")) {
            controller?.packageName?.let { pkg -> apps[pkg]?.let { launch(it) } }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        val wrap = LinearLayout(this).apply { setPadding(0, dp(12), 0, 0) }
        wrap.addView(nowBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return wrap
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { handler.post { updateNowBar() } }
        override fun onMetadataChanged(metadata: MediaMetadata?) { handler.post { updateNowBar() } }
        override fun onSessionDestroyed() { handler.post { pickController(null) } }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        handler.post { pickController(list) }
    }

    /** Needs "notification access" (granted once with ADB); without it the bar simply stays hidden. */
    private fun setupMedia() {
        if (!nowOn) { pickController(emptyList()); return }
        try {
            val m = msm ?: getSystemService(MediaSessionManager::class.java).also { msm = it } ?: return
            val cn = ComponentName(this, MediaListener::class.java)
            val list = m.getActiveSessions(cn)
            if (!mediaAllowed) m.addOnActiveSessionsChangedListener(sessionsListener, cn)
            mediaAllowed = true
            pickController(list)
        } catch (_: SecurityException) {
            mediaAllowed = false
            pickController(emptyList())
        }
    }

    private fun pickController(list: List<MediaController>?) {
        val sessions = list ?: try { msm?.getActiveSessions(ComponentName(this, MediaListener::class.java)) } catch (_: Exception) { null } ?: emptyList()
        val playing = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val best = playing ?: sessions.firstOrNull {
            val st = it.playbackState?.state
            st == PlaybackState.STATE_PAUSED || st == PlaybackState.STATE_BUFFERING
        }
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(mediaCallback)
            controller = best
            best?.registerCallback(mediaCallback, handler)
        }
        updateNowBar()
    }

    private fun isPlaying() = controller?.playbackState?.state == PlaybackState.STATE_PLAYING

    private fun updateNowBar() {
        val c = controller
        if (!nowOn || c == null) { nowBar.visibility = View.GONE; return }
        val md = c.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val app = apps[c.packageName]?.label ?: c.packageName
        val state = if (isPlaying()) t("▶ Playing", "▶ يعمل الآن") else t("⏸ Paused", "⏸ متوقف مؤقتًا")
        nowTv.text = "$state  ·  $app" + (if (title.isNotBlank()) "  ·  $title" else "") + (if (artist.isNotBlank()) " — $artist" else "")
        nowBar.visibility = View.VISIBLE
    }

    private fun nowMenu() {
        if (!mediaAllowed) {
            dialog().setTitle(t("Now playing controls", "التحكم بالتشغيل الحالي"))
                .setMessage(t("Shows what's playing (e.g. SmartTube in picture-in-picture or background audio) with ⏮ ⏯ ⏭ buttons on the home screen.\n\nAndroid needs “notification access” for this (Sahl TV reads no notifications). Run once from your PC:\n\nadb shell cmd notification allow_listener com.aly.sahltv/com.aly.sahltv.MediaListener\n\nThen press Home again.",
                              "يعرض ما يتم تشغيله (مثل SmartTube في نافذة صغيرة أو صوت في الخلفية) مع أزرار ⏮ ⏯ ⏭ على الشاشة الرئيسية.\n\nيحتاج أندرويد إلى «إذن الوصول إلى الإشعارات» لذلك (سهل TV لا يقرأ أي إشعارات). نفّذ هذا مرة واحدة من الكمبيوتر:\n\nadb shell cmd notification allow_listener com.aly.sahltv/com.aly.sahltv.MediaListener\n\nثم اضغط زر الرئيسية مرة أخرى."))
                .setPositiveButton(t("OK", "حسنًا")) { _, _ -> setupMedia() }
                .setNegativeButton(if (nowOn) t("Turn off", "إيقاف") else t("Turn on", "تفعيل")) { _, _ ->
                    prefs.edit().putBoolean("nowOn", !nowOn).apply(); setupMedia()
                }.show()
            return
        }
        prefs.edit().putBoolean("nowOn", !nowOn).apply()
        setupMedia()
        toast(t("Now playing controls: ", "التحكم بالتشغيل الحالي: ") + onOff(nowOn))
    }

    // ------------------------------------------------------------------ clock screensaver

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        lastInput = System.currentTimeMillis()
        if (settingsView != null && saver == null && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) hideSettings()
            return true
        }
        if (saver != null) {                       // any key just wakes the screen
            if (event.action == KeyEvent.ACTION_UP) hideSaver()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private val saverTick = object : Runnable {
        override fun run() {
            val min = saverMin
            if (min > 0 && saver == null && !isPlaying() && System.currentTimeMillis() - lastInput > min * 60_000L) showSaver()
            saver?.let { moveSaver(it) }
            handler.postDelayed(this, if (saver != null) 30_000 else 15_000)
        }
    }

    private fun showSaver() {
        val clock = TextView(this).apply { textSize = 96f; setTextColor(Color.parseColor("#C9C2AE")); typeface = Fonts.display(false); includeFontPadding = false; fontFeatureSettings = "tnum" }
        val day = TextView(this).apply { textSize = 34f; setTextColor(Color.parseColor("#B08F4A")); typeface = Fonts.display(true); Fonts.heading(this) }
        val date = TextView(this).apply { textSize = 18f; setTextColor(Color.parseColor("#6E7C76")); typeface = Fonts.text(); setPadding(0, dp(6), 0, 0) }
        val pray = TextView(this).apply { textSize = 18f; setTextColor(Color.parseColor("#2E8F66")); setPadding(0, dp(6), 0, 0) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; tag = "box"
            addView(clock); addView(day); addView(date); addView(pray)
        }
        val f = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK); alpha = 0f
            addView(box, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        rootFrame.addView(f, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        saver = f
        fun fill() { clock.text = clockTv.text; day.text = SimpleDateFormat("EEEE", if (ar) Locale.forLanguageTag("ar") else Locale.US).format(Date()); date.text = digits(SimpleDateFormat("d MMMM yyyy", if (ar) Locale.forLanguageTag("ar") else Locale.US).format(Date())) + "\n" + hijriTv.text; pray.text = listOf(if (cityIndex in cities.indices) prayerTv.text.toString() else "", fastLine).filter { it.isNotEmpty() }.joinToString("\n") }
        fill()
        f.tag = Runnable { fill() }
        f.animate().alpha(1f).setDuration(800).start()
        f.post { moveSaver(f) }
    }

    /** Drift the clock to a new random spot every 30 s, so nothing stays burned into an OLED screen. */
    private fun moveSaver(f: FrameLayout) {
        (f.tag as? Runnable)?.run()
        val box = f.findViewWithTag<View>("box") ?: return
        val maxX = (f.width - box.width).coerceAtLeast(1); val maxY = (f.height - box.height).coerceAtLeast(1)
        box.animate().translationX((Math.random() * maxX).toFloat()).translationY((Math.random() * maxY).toFloat())
            .setDuration(2000).start()
    }

    private fun hideSaver() {
        val f = saver ?: return
        saver = null
        rootFrame.removeView(f)
        lastInput = System.currentTimeMillis()
    }

    // ------------------------------------------------------------------ backup & restore

    private val backupName = "SahlTV-backup.json"

    private fun backupMenu() {
        dialog().setTitle(t("Backup & restore", "النسخ الاحتياطي والاستعادة"))
            .setItems(arrayOf(
                t("💾  Back up settings now", "💾  نسخ الإعدادات احتياطيًا الآن"),
                t("♻  Restore settings from backup", "♻  استعادة الإعدادات من النسخة"),
            )) { _, which ->
                if (which == 0) { if (storageOk(true)) doBackup() else askStorage(8) }
                else guarded { if (storageOk(false)) doRestore() else askStorage(9) }
            }.show()
    }

    private fun storageOk(write: Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= 30) return true          // MediaStore: no permission needed for our own file
        val perm = if (write) "android.permission.WRITE_EXTERNAL_STORAGE" else "android.permission.READ_EXTERNAL_STORAGE"
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun askStorage(code: Int) =
        requestPermissions(arrayOf("android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.READ_EXTERNAL_STORAGE"), code)

    private fun storageHelp() {
        dialog().setTitle(t("Storage permission needed", "يلزم إذن التخزين"))
            .setMessage(t("If no prompt appeared on the TV, run once from your PC:\n\nadb shell pm grant com.aly.sahltv android.permission.WRITE_EXTERNAL_STORAGE\nadb shell pm grant com.aly.sahltv android.permission.READ_EXTERNAL_STORAGE",
                          "إذا لم تظهر نافذة طلب الإذن على التلفاز، نفّذ هذا مرة واحدة من الكمبيوتر:\n\nadb shell pm grant com.aly.sahltv android.permission.WRITE_EXTERNAL_STORAGE\nadb shell pm grant com.aly.sahltv android.permission.READ_EXTERNAL_STORAGE"))
            .setPositiveButton(t("OK", "حسنًا"), null).show()
    }

    private fun settingsJson(): String {
        val o = org.json.JSONObject()
        o.put("app", "SahlTV"); o.put("version", 1)
        val data = org.json.JSONObject()
        for ((k, v) in prefs.all) {
            if (k.startsWith("n_")) { data.put(k, org.json.JSONObject().put("t", "i").put("v", v)); continue }
            when (v) {
                is Boolean -> data.put(k, org.json.JSONObject().put("t", "b").put("v", v))
                is Int -> data.put(k, org.json.JSONObject().put("t", "i").put("v", v))
                is String -> data.put(k, org.json.JSONObject().put("t", "s").put("v", v))
            }
        }
        o.put("settings", data)
        return o.toString(2)
    }

    private fun doBackup() {
        worker.execute {
            val ok = try {
                val text = settingsJson().toByteArray()
                if (Build.VERSION.SDK_INT >= 30) {
                    val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    contentResolver.delete(uri, MediaStore.MediaColumns.DISPLAY_NAME + "=?", arrayOf(backupName))
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, backupName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val item = contentResolver.insert(uri, cv) ?: throw IllegalStateException()
                    contentResolver.openOutputStream(item)?.use { it.write(text) }
                } else {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    java.io.File(dir, backupName).writeBytes(text)
                }
                true
            } catch (_: Exception) { false }
            handler.post {
                if (!ok) { storageHelp(); return@post }
                dialog().setTitle(t("✓ Backup saved", "✓ تم حفظ النسخة الاحتياطية"))
                    .setMessage(t("Saved to Download/$backupName on the TV.\n\nTo keep a copy on your PC:\nadb pull /sdcard/Download/$backupName\n\nTo restore after a reinstall, first copy it back:\nadb push $backupName /sdcard/Download/",
                                  "تم الحفظ في Download/$backupName على التلفاز.\n\nللاحتفاظ بنسخة على الكمبيوتر:\nadb pull /sdcard/Download/$backupName\n\nللاستعادة بعد إعادة التثبيت، انسخها أولًا إلى التلفاز:\nadb push $backupName /sdcard/Download/"))
                    .setPositiveButton(t("OK", "حسنًا"), null).show()
            }
        }
    }

    private fun doRestore() {
        worker.execute {
            val text: String? = try {
                if (Build.VERSION.SDK_INT >= 30) {
                    val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    contentResolver.query(uri, arrayOf("_id"), MediaStore.MediaColumns.DISPLAY_NAME + "=?", arrayOf(backupName), null)?.use { c ->
                        if (c.moveToNext()) contentResolver.openInputStream(android.content.ContentUris.withAppendedId(uri, c.getLong(0)))
                            ?.use { it.readBytes().toString(Charsets.UTF_8) } else null
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val f = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), backupName)
                    if (f.exists()) f.readText() else null
                }
            } catch (_: Exception) { null }
            handler.post {
                if (text == null) {
                    dialog().setTitle(t("No backup found", "لم يتم العثور على نسخة"))
                        .setMessage(t("Put $backupName in the TV's Download folder first:\nadb push $backupName /sdcard/Download/",
                                      "ضع الملف $backupName في مجلد Download على التلفاز أولًا:\nadb push $backupName /sdcard/Download/"))
                        .setPositiveButton(t("OK", "حسنًا"), null).show()
                    return@post
                }
                try {
                    val data = org.json.JSONObject(text).getJSONObject("settings")
                    val e = prefs.edit().clear()
                    for (k in data.keys()) {
                        val o = data.getJSONObject(k)
                        when (o.getString("t")) {
                            "b" -> e.putBoolean(k, o.getBoolean("v"))
                            "i" -> e.putInt(k, o.getInt("v"))
                            else -> e.putString(k, o.getString("v"))
                        }
                    }
                    e.apply()
                    toast(t("✓ Settings restored", "✓ تمت استعادة الإعدادات"))
                    recreate()
                } catch (_: Exception) { toast(t("That backup file can't be read", "تعذّرت قراءة ملف النسخة")) }
            }
        }
    }

    // ------------------------------------------------------------------ phone remote

    override fun appList(): List<Pair<String, String>> {
        val fav = favorites.filter { apps.containsKey(it) }
        val rest = apps.values.filter { it.pkg !in fav && it.pkg !in hidden && it.pkg !in locked }.sortedBy { it.label.lowercase() }.map { it.pkg }
        return (fav.filter { it !in locked } + rest).mapNotNull { p -> apps[p]?.let { p to it.label } }
    }
    override fun launchPkg(pkg: String) { apps[pkg]?.let { if (it.pkg !in locked) launchNow(it) } }
    override fun media(action: String) {
        val c = controller?.transportControls ?: return
        when (action) {
            "toggle" -> if (isPlaying()) c.pause() else c.play()
            "next" -> c.skipToNext()
            "prev" -> c.skipToPrevious()
        }
    }
    override fun nowPlaying(): String = if (nowBar.visibility == View.VISIBLE) nowTv.text.toString() else ""
    override fun goHome() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    override fun arabic() = ar

    private fun remoteMenu() {
        val ip = RemoteServer.ip()
        val url = if (ip != null) "http://$ip:${RemoteServer.PORT}" else t("(connect the TV to Wi-Fi first)", "(وصّل التلفاز بالشبكة أولًا)")
        val msg = if (remoteOn) t(
            "On your phone (same Wi-Fi), open:\n\n$url\n\nPairing code:  ${RemoteServer.code(this)}\n\nYou can open apps, control playback and volume, go Home, and type text.\n\nFor typing, run these once from your PC:\nadb shell ime enable ${RemoteServer.IME_ID}\nadb shell pm grant com.aly.sahltv android.permission.WRITE_SECURE_SETTINGS\n\nLocked apps are not shown on the phone.",
            "على هاتفك (نفس شبكة الواي فاي) افتح:\n\n$url\n\nرمز الاقتران:  ${RemoteServer.code(this)}\n\nيمكنك فتح التطبيقات والتحكم بالتشغيل والصوت والعودة للرئيسية والكتابة.\n\nللكتابة، نفّذ هذين الأمرين مرة واحدة من الكمبيوتر:\nadb shell ime enable ${RemoteServer.IME_ID}\nadb shell pm grant com.aly.sahltv android.permission.WRITE_SECURE_SETTINGS\n\nالتطبيقات المقفلة لا تظهر على الهاتف.")
        else t("Control the TV from your phone's browser on the same Wi-Fi: open apps, play/pause, volume, Home, and type text.\n\nIt stays off until you turn it on, and each phone must enter a pairing code once.",
               "تحكّم بالتلفاز من متصفح هاتفك على نفس الشبكة: فتح التطبيقات، التشغيل والإيقاف، الصوت، الرئيسية، والكتابة.\n\nيبقى متوقفًا حتى تفعّله، ويجب إدخال رمز اقتران مرة واحدة لكل هاتف.")
        dialog().setTitle(t("📱 Phone remote", "📱 الريموت من الهاتف")).setMessage(msg)
            .setPositiveButton(if (remoteOn) t("Turn off", "إيقاف") else t("Turn on", "تفعيل")) { _, _ ->
                guarded {
                    prefs.edit().putBoolean("remoteOn", !remoteOn).apply()
                    if (remoteOn) RemoteServer.start(this) else RemoteServer.stop()
                    handler.postDelayed({ remoteMenu() }, 300)
                }
            }
            .setNeutralButton(t("New code", "رمز جديد")) { _, _ ->
                guarded { RemoteServer.resetPairing(this); toast(t("New pairing code — phones must pair again", "رمز جديد — يجب إعادة الاقتران")); remoteMenu() }
            }
            .setNegativeButton(t("Close", "إغلاق"), null).show()
    }

    // ------------------------------------------------------------------ menus

    private fun dialog() = FontDialog(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun appMenu(a: App, row: String) {
        val fav = favorites
        val isFav = a.pkg in fav
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (isFav) {
            items.add(t("★  Remove from Favorites", "★  إزالة من المفضلة") to { fav.remove(a.pkg); saveList("fav", fav); render(true) })
            val i = fav.indexOf(a.pkg)
            if (i > 0) items.add((if (ar) "→  تحريك لليمين" else "←  Move left") to { fav.removeAt(i); fav.add(i - 1, a.pkg); saveList("fav", fav); focusRow = "fav"; render(true) })
            if (i < fav.size - 1) items.add((if (ar) "←  تحريك لليسار" else "→  Move right") to { fav.removeAt(i); fav.add(i + 1, a.pkg); saveList("fav", fav); focusRow = "fav"; render(true) })
        } else {
            items.add(t("☆  Add to Favorites", "☆  إضافة إلى المفضلة") to { fav.add(a.pkg); saveList("fav", fav); focusRow = "fav"; render(true) })
        }
        if (a.pkg in locked) items.add(t("🔓  Remove PIN lock", "🔓  إزالة القفل") to {
            askPin(t("Enter PIN to unlock ${a.label}", "أدخل الرمز لفتح قفل ${a.label}")) { val l = locked; l.remove(a.pkg); saveList("locked", l); render(true) }
        }) else items.add(t("🔒  Lock with PIN", "🔒  قفل برمز سري") to {
            val doLock = { val l = locked; l.add(a.pkg); saveList("locked", l); render(true); toast(t("${a.label} now needs the PIN", "${a.label} يحتاج الآن إلى الرمز")) }
            if (pinSet) askPin(t("Enter PIN to lock ${a.label}", "أدخل الرمز لقفل ${a.label}")) { doLock() } else createPin { doLock() }
        })
        items.add(t("🙈  Hide this app", "🙈  إخفاء هذا التطبيق") to {
            guarded {
                val h = hidden; h.add(a.pkg); saveList("hidden", h)
                fav.remove(a.pkg); saveList("fav", fav); render(false)
            }
        })
        items.add(t("ⓘ  App info", "ⓘ  معلومات التطبيق") to { guarded { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}"))) } })
        items.add(t("🗑  Uninstall", "🗑  إلغاء التثبيت") to { guarded { open(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}"))) } })
        dialog().setTitle(a.label)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun onOff(b: Boolean) = if (b) t("On", "مُفعّل") else t("Off", "متوقف")
    private val citiesAr = listOf("الدوحة", "الوكرة", "الريان", "لوسيل", "أم صلال", "الخور", "مسيعيد", "دخان", "مدينة الشمال")
    private fun cityAr(i: Int) = citiesAr.getOrElse(i) { cities[i].first }

    private fun launcherMenu() = showSettings()

    /** One place for every setting: used by the Settings screen rows. */
    private fun menuAction(which: Int) {
        if (which in listOf(0, 5, 7)) prefs.edit().putBoolean("reopenSettings", true).apply()
            when (which) {
                0 -> { prefs.edit().putString("lang", if (ar) "en" else "ar").apply(); recreate() }
                1 -> previewsMenu()
                2 -> { prefs.edit().putBoolean("sortUsed", !sortMostUsed).apply(); render(false) }
                3 -> { prefs.edit().putBoolean("clock24", !clock24).apply(); updateClock() }
                4 -> cityMenu()
                5 -> { prefs.edit().putBoolean("islamic", !islamic).apply(); recreate() }
                6 -> islamicMenu()
                7 -> { prefs.edit().putBoolean("calm", !calm).apply(); recreate() }
                8 -> alertMenu()
                9 -> { prefs.edit().putBoolean("autoClean", !autoClean).apply(); toast(t("Auto clean: ", "التنظيف التلقائي: ") + onOff(autoClean)) }
                10 -> pinMenu()
                11 -> guarded { hiddenMenu() }
                12 -> {
                    val next = when (saverMin) { 0 -> 3; 3 -> 5; 5 -> 10; else -> 0 }
                    prefs.edit().putInt("saverMin", next).apply()
                    toast(t("Clock screensaver: ", "شاشة توقف الساعة: ") + if (next == 0) t("Off", "متوقفة") else t("after $next min", "بعد $next دقائق"))
                }
                13 -> nowMenu()
                14 -> backupMenu()
                15 -> remoteMenu()
                16 -> { setUpd(false); Updater.check(this, ar) }
                18 -> if (islamic) backgroundMenu() else toast(t("Choose the Islamic theme first", "اختر المظهر الإسلامي أولًا"))
                19 -> dimMenu()
                20 -> autoUpdMenu()
                21 -> prayerAdjustMenu()
                22 -> { prefs.edit().putBoolean("wxOn", !wxOn).putLong("wxTry", 0L).apply(); updateClock() }
                23 -> { prefs.edit().putBoolean("ayahOn", !ayahOn).apply(); ayahToday = null; updateClock() }
                24 -> textSizeMenu()
                25 -> { prefs.edit().putBoolean("nightStyle", !nightStyle).commit(); Fonts.night = PrefsProvider.isNight(this); recreate() }
                26 -> { prefs.edit().putBoolean("arDigits", !arDigits).apply(); updateClock(); tickCountdowns() }
                27 -> greetingMenu()
                17 -> dialog().setTitle("Sahl TV  ·  سهل")
                    .setMessage(t("A fast, ad-free home screen for Android TV.\n\nNo tracking, no background services. Internet is used only for preview pictures, the weather, and checking for updates. Nothing about you is sent anywhere.\n\nPrayer times are calculated on the TV (Qatar method: Fajr 18°, Asr Shafi'i, Isha 90 min after Maghrib). Please compare with the official Awqaf timetable.\n\nTip: hold OK on an app (or press Menu) for options.\n\nFree & open source (MIT).\n" + photoCredits() + "Weather: Open-Meteo.com (CC BY 4.0). Ayah of the day: Tanzil Quran text (CC BY 3.0), Amiri Quran font (SIL OFL).\nContact: alysherif1309@gmail.com",
                                  "واجهة رئيسية سريعة وبلا إعلانات لأجهزة Android TV.\n\nبلا تتبّع وبلا خدمات تعمل في الخلفية. يُستخدم الإنترنت فقط لصور المعاينات والطقس والبحث عن تحديثات. لا يُرسل أي شيء عنك.\n\nتُحسب مواقيت الصلاة على التلفاز (طريقة قطر: الفجر 18°، العصر شافعي، العشاء بعد المغرب بـ 90 دقيقة). يُرجى مقارنتها بتقويم الأوقاف الرسمي.\n\nنصيحة: اضغط مطولًا على OK فوق تطبيق (أو زر القائمة) لعرض الخيارات.\n\nمجاني ومفتوح المصدر (MIT).\n" + photoCredits() + "الطقس: Open-Meteo.com (CC BY 4.0). آية اليوم: نص القرآن من مشروع تنزيل (CC BY 3.0) وخط Amiri Quran (SIL OFL).\nللتواصل: alysherif1309@gmail.com"))
                    .setPositiveButton(t("OK", "حسنًا"), null).show()
            }
    }

    private fun alertName() = when (Alerts.mode(this)) {
        Alerts.OFF -> t("Off", "متوقف"); Alerts.WITH_REMINDER -> t("At prayer time + 10 min before", "عند الأذان + قبله بـ 10 دقائق")
        else -> t("At prayer time", "عند وقت الصلاة") } + if (Alerts.mode(this) != Alerts.OFF && !Alerts.canShow(this)) t("  (needs setup)", "  (يحتاج إعدادًا)") else ""

    private fun alertMenu() {
        val items = arrayOf(t("At prayer time", "عند وقت الصلاة"), t("At prayer time + 10 minutes before", "عند وقت الصلاة + قبله بـ 10 دقائق"),
            t("Off", "إيقاف"), t("Show a test banner now", "عرض تنبيه تجريبي الآن"))
        val msg = if (Alerts.canShow(this)) null else t("One-time setup so the banner can appear over SmartTube and other apps. On your PC run:\n\nadb shell appops set com.aly.sahltv SYSTEM_ALERT_WINDOW allow",
            "إعداد لمرة واحدة ليظهر التنبيه فوق SmartTube والتطبيقات الأخرى. شغّل على الكمبيوتر:\n\nadb shell appops set com.aly.sahltv SYSTEM_ALERT_WINDOW allow")
        val d = dialog().setTitle(t("Prayer-time banner (no sound)", "تنبيه وقت الصلاة (بدون صوت)"))
        if (msg != null) d.setMessage(msg).setPositiveButton(t("Choose…", "اختيار…")) { _, _ -> alertChoices(items) }.setNegativeButton(t("Close", "إغلاق"), null).show()
        else alertChoices(items)
    }

    private fun alertChoices(items: Array<String>) {
        dialog().setTitle(t("Prayer-time banner (no sound)", "تنبيه وقت الصلاة (بدون صوت)")).setItems(items) { _, w ->
            when (w) {
                0 -> prefs.edit().putInt("alertMode", Alerts.AT_TIME).apply()
                1 -> prefs.edit().putInt("alertMode", Alerts.WITH_REMINDER).apply()
                2 -> prefs.edit().putInt("alertMode", Alerts.OFF).apply()
                3 -> if (Alerts.canShow(this)) Alerts.show(this, t("It's time for Dhuhr", "حان الآن موعد صلاة الظهر"), t("11:18  ·  This is a test", "11:18  ·  هذا تنبيه تجريبي"), 8000)
                     else toast(t("Run the adb command first", "شغّل أمر adb أولًا"))
            }
            if (w < 3) { Alerts.schedule(this); toast(t("Prayer-time banner: ", "تنبيه وقت الصلاة: ") + alertName()) }
        }.show()
    }

    private fun islamicMenu() {
        val items = arrayOf(t("Adjust Hijri date (now: ", "ضبط التاريخ الهجري (حاليًا: ") + hijriText(LocalDate.now()) + ")",
            t("Sunnah fasting reminders: ", "تذكير صيام السنة: ") + onOff(fastOn),
            t("Upcoming Islamic dates", "المناسبات الإسلامية القادمة"))
        dialog().setTitle(t("Hijri date & fasting", "التاريخ الهجري والصيام")).setItems(items) { _, w ->
            when (w) {
                0 -> hijriAdjMenu()
                1 -> { prefs.edit().putBoolean("fastOn", !fastOn).apply(); updateClock(); toast(t("Fasting reminders: ", "تذكير الصيام: ") + onOff(fastOn)) }
                2 -> islamicDatesDialog()
            }
        }.show()
    }

    private fun cityMenu() {
        val names = cities.indices.map { if (ar) cityAr(it) else cities[it].first } + t("Turn prayer times off", "إيقاف مواقيت الصلاة")
        dialog().setTitle(t("Prayer times for", "مواقيت الصلاة في")).setItems(names.toTypedArray()) { _, which ->
            prefs.edit().putInt("city", if (which < cities.size) which else -1).apply(); updateClock(); Alerts.schedule(this)
        }.show()
    }

    // ------------------------------------------------------------------ Islamic panel: prayer countdown, Ramadan, Sunnah fasts

    private lateinit var panel: LinearLayout
    private lateinit var prayRow: LinearLayout
    private lateinit var nextNameTv: TextView
    private lateinit var nextCountTv: TextView
    private val cellBoxes = ArrayList<LinearLayout>()
    private val cellNames = ArrayList<TextView>()
    private val cellTimes = ArrayList<TextView>()
    private lateinit var ramCard: LinearLayout
    private lateinit var ramTitle: TextView
    private lateinit var ramSub: TextView
    private lateinit var fastCard: LinearLayout
    private lateinit var fastTitle: TextView
    private lateinit var fastSub: TextView
    private lateinit var nextLabelTv: TextView
    private lateinit var ayahCard: LinearLayout
    private lateinit var ayahTv: TextView
    private lateinit var ayahRef: TextView
    private lateinit var newsCard: LinearLayout
    private lateinit var greetCard: LinearLayout
    private lateinit var greetIconBox: FrameLayout
    private lateinit var greetTitle: TextView
    private lateinit var greetSub: TextView
    private var greetPreview: String? = null
    private var greetPreviewUntil = 0L
    private var greetShown: String? = null
    private lateinit var newsSub: TextView
    private var nextAt = 0L
    private var ramAt = 0L
    private var ramLabel = ""
    private var fastLine = ""
    private var ramCacheKey = ""
    private var ramCache: LocalDate? = null

    private val secondTick = object : Runnable {
        override fun run() { tickCountdowns(); handler.postDelayed(this, 1000 - System.currentTimeMillis() % 1000) }
    }

    private fun cardBg(strong: Boolean, focused: Boolean, glow: Boolean = false) = GradientDrawable().apply {
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
        colors = if (focused) intArrayOf(Color.parseColor("#F2183A31"), Color.parseColor("#F2102A24"))
                 else if (islamic) intArrayOf(Color.parseColor("#B00E211D"), Color.parseColor("#B8091714"))      // frosted glass: the Kaaba shows through
                 else intArrayOf(Color.parseColor("#E8102521"), Color.parseColor("#E80A1A17"))
        cornerRadius = dp(16).toFloat()
        val w = if (focused) dp(3) else if (strong || glow) dp(2) else dp(1)
        setStroke(w, if (focused) Color.parseColor("#F3D58A") else if (strong || glow) GOLD else Color.argb(60, 227, 184, 92))
    }

    private fun card(strong: Boolean, onClick: () -> Unit) = LinearLayout(this).apply {
        isFocusable = true; isClickable = true; defaultFocusHighlightEnabled = false
        gravity = Gravity.CENTER_VERTICAL
        background = cardBg(strong, false)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setOnFocusChangeListener { v, has ->
            v.background = cardBg(strong, has, v.tag == "glow")
            if (has) scroll.smoothScrollTo(0, 0)
        }
        setOnClickListener { onClick() }
    }

    private fun iconLabel(kind: Int, text: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(GoldIcon(this@MainActivity, kind, GOLD), LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(6) })
        addView(small(text))
    }

    private fun small(text: String, color: Int = MUTED) = TextView(this).apply { this.text = text; textSize = 13f; setTextColor(color); maxLines = 1 }

    private fun buildPanel(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(12), dp(4), dp(2)); clipChildren = false; clipToPadding = false }

        // row 1: next prayer + countdown, then the five prayers
        prayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        val hero = card(true) { prayerDialog() }
        val a = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val lbl = iconLabel(GoldIcon.MOSQUE, t("Next prayer", "الصلاة القادمة")); nextLabelTv = lbl.getChildAt(1) as TextView
        a.addView(lbl)
        nextNameTv = TextView(this).apply { textSize = 28f; setTextColor(TEXT); typeface = Fonts.display(true); maxLines = 1; includeFontPadding = false; Fonts.heading(this) }
        a.addView(nextNameTv)
        hero.addView(a, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        hero.addView(View(this).apply { setBackgroundColor(Color.argb(90, 227, 184, 92)) }, LinearLayout.LayoutParams(dp(1), dp(46)).apply { setMargins(dp(12), 0, dp(12), 0) })
        val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        b.addView(iconLabel(GoldIcon.HOURGLASS, t("Time left", "الوقت المتبقي")))
        nextCountTv = TextView(this).apply {
            textSize = 28f; setTextColor(GOLD); setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD)); maxLines = 1
            fontFeatureSettings = "tnum"; textDirection = View.TEXT_DIRECTION_LTR
        }
        b.addView(nextCountTv)
        hero.addView(b, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        prayRow.addView(hero, LinearLayout.LayoutParams(0, dp(84), 0.42f))

        val strip = card(false) { prayerDialog() }.apply { setPadding(dp(6), dp(6), dp(6), dp(6)) }
        val stripIcons = intArrayOf(GoldIcon.FAJR, GoldIcon.SUN, GoldIcon.DHUHR, GoldIcon.ASR, GoldIcon.MAGHRIB, GoldIcon.ISHA)
        for (i in 0 until 6) {
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            cell.addView(GoldIcon(this, stripIcons[i], GOLD), LinearLayout.LayoutParams(dp(20), dp(20)).apply { bottomMargin = dp(2) })
            val n = TextView(this).apply { textSize = 13f; setTextColor(MUTED); gravity = Gravity.CENTER; maxLines = 1 }
            val tm = TextView(this).apply { textSize = 16f; setTextColor(TEXT); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; maxLines = 1 }
            cell.addView(n); cell.addView(tm)
            cellBoxes.add(cell); cellNames.add(n); cellTimes.add(tm)
            strip.addView(cell, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        prayRow.addView(strip, LinearLayout.LayoutParams(0, dp(84), 0.58f).apply { marginStart = dp(12) })
        col.addView(prayRow)

        // row 2: Ramadan / Hijri occasions, and the next Sunnah fast
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0); clipChildren = false }
        ramCard = card(false) { islamicDatesDialog() }
        ramCard.addView(GoldIcon(this, GoldIcon.LANTERN, GOLD), LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(14) })
        val rc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ramTitle = TextView(this).apply { textSize = 18f; setTextColor(TEXT); typeface = Fonts.display(true); maxLines = 1; Fonts.heading(this) }
        ramSub = TextView(this).apply { textSize = 14f; setTextColor(GOLD); maxLines = 1; fontFeatureSettings = "tnum" }
        rc.addView(ramTitle); rc.addView(ramSub)
        ramCard.addView(rc, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        ramCard.minimumHeight = dp(64)
        row2.addView(ramCard, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        fastCard = card(false) { fastDialog() }
        fastCard.addView(GoldIcon(this, GoldIcon.DATES, GOLD), LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(14) })
        val fc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fastTitle = TextView(this).apply { textSize = 18f; setTextColor(TEXT); typeface = Fonts.display(true); maxLines = 1; Fonts.heading(this) }
        fastSub = TextView(this).apply { textSize = 14f; setTextColor(MUTED); maxLines = 1 }
        fc.addView(fastTitle); fc.addView(fastSub)
        fastCard.addView(fc, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        fastCard.minimumHeight = dp(64)
        row2.addView(fastCard, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        col.addView(row2)

        // row 3: Ayah of the day (text copied unchanged from the verified Quran text at build time)
        ayahCard = card(false) { openAyah() }.apply { setPadding(dp(18), dp(10), dp(18), dp(10)) }
        val bub = FrameLayout(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(38, 227, 184, 92)) } }
        bub.addView(GoldIcon(this, GoldIcon.BOOK, GOLD), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        ayahCard.addView(bub, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(16) })
        val ac = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ac.addView(small(t("Ayah of the day", "آية اليوم"), GOLD))
        ayahTv = TextView(this).apply {
            textSize = 23f; setTextColor(Color.parseColor("#F5EFDF")); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            textDirection = View.TEXT_DIRECTION_RTL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.START
            quranFont?.let { typeface = it }; setLineSpacing(0f, 1.15f)
        }
        ac.addView(ayahTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        ayahRef = small("", MUTED)
        ac.addView(ayahRef)
        ayahCard.addView(ac, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        col.addView(ayahCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })

        // What's new: shown for two days after an update
        newsCard = card(true) { whatsNewDialog() }.apply { setPadding(dp(18), dp(10), dp(18), dp(10)); tag = "glow" }
        val nb = FrameLayout(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(46, 227, 184, 92)) } }
        nb.addView(GoldIcon(this, GoldIcon.STAR, GOLD), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        newsCard.addView(nb, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
        val nc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nc.addView(TextView(this).apply { text = t("Updated to Sahl TV ", "تم التحديث إلى سهل TV ") + (whatsNew?.optString("version") ?: ""); textSize = 17f; setTextColor(TEXT); typeface = Fonts.display(true) })
        newsSub = small("", GOLD); nc.addView(newsSub)
        newsCard.addView(nc, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        col.addView(newsCard, 0, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) })

        // Seasonal greeting (Ramadan, Eid, Arafah, Hijri new year, Qatar National Day): Thmanyah Display with its swash
        greetCard = card(true) { greetingMenu() }.apply { setPadding(dp(22), dp(12), dp(22), dp(12)); tag = "glow"; visibility = View.GONE }
        greetIconBox = FrameLayout(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(46, 227, 184, 92)) } }
        greetCard.addView(greetIconBox, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(18) })
        val gc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        greetTitle = TextView(this).apply { textSize = 30f; setTextColor(Color.parseColor("#F6E7C1")); typeface = Fonts.display(true); maxLines = 1; includeFontPadding = false; Fonts.heading(this) }
        greetSub = TextView(this).apply { textSize = 15f; setTextColor(GOLD); typeface = Fonts.text(); maxLines = 1; setPadding(0, dp(4), 0, 0) }
        gc.addView(greetTitle); gc.addView(greetSub)
        greetCard.addView(gc, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        col.addView(greetCard, 0, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) })
        return col
    }

    private fun epochAt(day: LocalDate, hours: Double) =
        day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + Math.round(hours * 3_600_000.0)

    private fun fmtLeft(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        if (calm) {
            val m = (s + 59) / 60
            return if (m >= 60) t("${m / 60}h ${m % 60}m", "${m / 60}س ${m % 60}د") else if (m > 0) t("$m min", "$m د") else t("now", "الآن")
        }
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }

    private fun prayerNames(friday: Boolean) = listOf(t("Fajr", "الفجر"), if (friday) t("Jumu'ah", "الجمعة") else t("Dhuhr", "الظهر"),
        t("Asr", "العصر"), t("Maghrib", "المغرب"), t("Isha", "العشاء"))

    private fun timesFor(day: LocalDate): Prayer.Times? {
        val idx = cityIndex
        if (idx !in cities.indices) return null
        val (_, la, lo) = cities[idx]
        val tz = java.time.ZonedDateTime.now(ZoneId.systemDefault()).offset.totalSeconds / 3600.0
        return Prayer.compute(day, la, lo, tz)
    }

    private fun weekday(d: LocalDate) = SimpleDateFormat("EEEE", if (ar) Locale.forLanguageTag("ar") else Locale.US)
        .format(Date(epochAt(d, 12.0)))
    private fun shortDate(d: LocalDate) = SimpleDateFormat("EEE d MMM yyyy", if (ar) Locale.forLanguageTag("ar") else Locale.US)
        .format(Date(epochAt(d, 12.0)))

    /** Recomputes the whole panel (every 20 s, and whenever a countdown reaches zero). */
    private fun updateIslamic() {
        if (!::panel.isInitialized) return
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val adj = hijriAdj
        val tt = timesFor(today)
        val tm = timesFor(today.plusDays(1))

        // prayers
        if (tt != null && tm != null) {
            prayRow.visibility = View.VISIBLE
            val p5 = prayerNames(today.dayOfWeek == DayOfWeek.FRIDAY)
            val names = listOf(p5[0], t("Sunrise", "الشروق"), p5[1], p5[2], p5[3], p5[4])
            val hours = listOf(tt.fajr, tt.sunrise, tt.dhuhr, tt.asr, tt.maghrib, tt.isha)
            var nextI = hours.indexOfFirst { epochAt(today, it) > now }
            if (nextI < 0) { nextI = 0; nextAt = epochAt(today.plusDays(1), tm.fajr); nextNameTv.text = t("Fajr", "الفجر") }
            else { nextAt = epochAt(today, hours[nextI]); nextNameTv.text = names[nextI] }
            nextLabelTv.text = if (nextI == 1) t("Coming up", "التالي") else t("Next prayer", "الصلاة القادمة")
            for (i in 0 until 6) {
                cellNames[i].text = names[i]
                cellTimes[i].text = digits(Prayer.hm(if (i == 0 && epochAt(today, tt.isha) < now) tm.fajr else hours[i]))
                val on = i == nextI
                cellBoxes[i].background = if (on) GradientDrawable().apply {
                    setColor(Color.parseColor("#1C4337")); cornerRadius = dp(12).toFloat(); setStroke(dp(1), GOLD)
                } else null
                cellNames[i].setTextColor(if (on) GOLD else MUTED)
                cellTimes[i].setTextColor(if (on) Color.parseColor("#FFF6E0") else TEXT)
            }
        } else { prayRow.visibility = View.GONE; nextAt = 0 }

        // Ramadan / occasions
        val h = Islamic.hijri(today, adj)
        ramAt = 0; ramLabel = ""
        ramCard.tag = null
        when {
            h.m == 8 -> {
                ramTitle.text = t("Ramadan Kareem  ·  day ${h.d}", "رمضان كريم  ·  اليوم ${h.d}")
                ramCard.tag = "glow"
                if (tt != null && tm != null) {
                    when {
                        now < epochAt(today, tt.fajr) -> { ramAt = epochAt(today, tt.fajr); ramLabel = t("Suhoor ends in", "ينتهي السحور بعد") }
                        now < epochAt(today, tt.maghrib) -> { ramAt = epochAt(today, tt.maghrib); ramLabel = t("Iftar in", "الإفطار بعد") }
                        else -> { ramAt = epochAt(today.plusDays(1), tm.fajr); ramLabel = t("Suhoor ends in", "ينتهي السحور بعد") }
                    }
                } else ramSub.text = t("May Allah accept your fasting", "تقبّل الله صيامكم")
            }
            h.m == 9 && h.d <= 3 -> { ramTitle.text = t("Eid al-Fitr Mubarak 🎉", "عيد فطر مبارك 🎉"); ramSub.text = t("Taqabbal Allahu minna wa minkum", "تقبّل الله منّا ومنكم"); ramCard.tag = "glow" }
            h.m == 11 && h.d == 9 -> { ramTitle.text = t("Day of Arafah", "يوم عرفة"); ramSub.text = t("The best day for du'a", "خير الدعاء دعاء يوم عرفة"); ramCard.tag = "glow" }
            h.m == 11 && h.d in 10..13 -> { ramTitle.text = t("Eid al-Adha Mubarak 🎉", "عيد أضحى مبارك 🎉"); ramSub.text = t("Taqabbal Allahu minna wa minkum", "تقبّل الله منّا ومنكم"); ramCard.tag = "glow" }
            else -> {
                val key = "$today/$adj"
                if (key != ramCacheKey) { ramCacheKey = key; ramCache = Islamic.firstDate(today, 400) { d -> Islamic.hijri(d, adj).let { it.m == 8 && it.d == 1 } } }
                val start = ramCache
                if (start != null) {
                    val days = java.time.temporal.ChronoUnit.DAYS.between(today, start)
                    val y = Islamic.hijri(start, adj).y
                    ramTitle.text = t("Ramadan $y in $days days", "رمضان $y بعد $days يومًا")
                    ramSub.text = t("Expected ${shortDate(start)}  ·  depends on moon sighting", "متوقع ${shortDate(start)}  ·  حسب رؤية الهلال")
                    if (days <= 30) ramCard.tag = "glow"
                } else { ramTitle.text = t("Islamic dates", "المناسبات الإسلامية"); ramSub.text = "" }
            }
        }
        if (!ramCard.hasFocus()) ramCard.background = cardBg(false, false, ramCard.tag == "glow")

        // next Sunnah fast (hidden in Ramadan: the Ramadan card covers it)
        fastLine = ""
        if (!fastOn || h.m == 8) { fastCard.visibility = View.GONE }
        else {
            fastCard.visibility = View.VISIBLE
            val beforeFajr = tt != null && now < epochAt(today, tt.fajr)
            val from = if (beforeFajr) today else today.plusDays(1)
            val next = Islamic.firstDate(from, 40) { d -> Islamic.sunnahFast(d, Islamic.hijri(d, adj), ar) != null }
            fastCard.tag = null
            if (next == null) { fastTitle.text = t("No Sunnah fast soon", "لا صيام سنة قريبًا"); fastSub.text = "" }
            else {
                val why = Islamic.sunnahFast(next, Islamic.hijri(next, adj), ar)!!
                val days = java.time.temporal.ChronoUnit.DAYS.between(today, next)
                val nf = timesFor(next)
                when (days) {
                    0L -> { fastTitle.text = t("Today: $why", "اليوم: $why"); fastCard.tag = "glow"
                        fastSub.text = if (nf != null) t("Suhoor until Fajr ${Prayer.hm(nf.fajr)}  ·  Iftar ${Prayer.hm(nf.maghrib)}", "السحور حتى الفجر ${Prayer.hm(nf.fajr)}  ·  الإفطار ${Prayer.hm(nf.maghrib)}") else "" }
                    1L -> { fastTitle.text = t("Tomorrow: $why", "غدًا: $why"); fastCard.tag = "glow"
                        fastSub.text = if (nf != null) t("Make your intention  ·  Suhoor before Fajr ${Prayer.hm(nf.fajr)}", "انوِ الصيام  ·  السحور قبل الفجر ${Prayer.hm(nf.fajr)}") else t("Make your intention tonight", "انوِ الصيام الليلة") }
                    else -> { fastTitle.text = "${weekday(next)}: $why"; fastSub.text = t("Next Sunnah fast  ·  in $days days", "صيام السنة القادم  ·  بعد $days أيام") }
                }
                fastSub.setTextColor(if (days <= 1) GOLD else MUTED)
                if (days <= 1) fastLine = fastTitle.text.toString()
            }
            if (!fastCard.hasFocus()) fastCard.background = cardBg(false, false, fastCard.tag == "glow")
        }
        for (tv in listOf(ramTitle, ramSub, fastTitle, fastSub)) { val d = digits(tv.text.toString()); if (d != tv.text.toString()) tv.text = d }
        updateAyah(); updateNews(); updateGreeting(); refreshWeather()
        refreshBackground()
        // calm mode: soften the whole home screen from an hour after Isha until Fajr
        val night = if (tt != null) now > epochAt(today, tt.isha) + 3_600_000L || now < epochAt(today, tt.fajr)
                    else java.time.LocalTime.now().let { it.hour >= 21 || it.hour < 5 }
        dimView.animate().alpha(if (calm && night) 0.35f else 0f).setDuration(1500).start()
        // night style (all Sahl apps): from Isha until Fajr. The other apps ask Sahl TV, so keep the times of day here.
        if (tt != null) {
            val im = ((tt.isha * 60).toInt() % 1440); val fm = ((tt.fajr * 60).toInt() % 1440)
            if (prefs.getInt("ishaMin", -1) != im || prefs.getInt("fajrMin", -1) != fm) prefs.edit().putInt("ishaMin", im).putInt("fajrMin", fm).apply()
        }
        val nightNow = PrefsProvider.isNight(this)
        if (nightNow != Fonts.night) { Fonts.night = nightNow; if (!isFinishing) handler.post { recreate() } }
        panel.visibility = if (prayRow.visibility == View.GONE && ramCard.visibility == View.GONE && fastCard.visibility == View.GONE) View.GONE else View.VISIBLE
        tickCountdowns()
    }

    private fun tickCountdowns() {
        if (!::panel.isInitialized) return
        val now = System.currentTimeMillis()
        if ((nextAt in 1..now) || (ramAt in 1..now)) { updateClock(); return }
        if (nextAt > 0) {
            val left = nextAt - now
            nextCountTv.text = digits(fmtLeft(left))
        }
        if (ramAt > 0) ramSub.text = digits("$ramLabel  ${fmtLeft(ramAt - now)}")
    }

    private fun prayerDialog() {
        val today = LocalDate.now()
        val tt = timesFor(today) ?: run { cityMenu(); return }
        val tm = timesFor(today.plusDays(1))!!
        val n = prayerNames(today.dayOfWeek == DayOfWeek.FRIDAY)
        val msg = StringBuilder()
        msg.append(t("Today", "اليوم")).append("  ·  ").append(shortDate(today)).append("\n\n")
        msg.append("${n[0]}   ${Prayer.hm(tt.fajr)}\n${t("Sunrise", "الشروق")}   ${Prayer.hm(tt.sunrise)}\n${n[1]}   ${Prayer.hm(tt.dhuhr)}\n")
        msg.append("${n[2]}   ${Prayer.hm(tt.asr)}\n${n[3]}   ${Prayer.hm(tt.maghrib)}\n${n[4]}   ${Prayer.hm(tt.isha)}\n\n")
        msg.append(t("Tomorrow's Fajr", "فجر الغد")).append("   ").append(Prayer.hm(tm.fajr)).append("\n\n")
        msg.append(t("Qatar method: Fajr 18°, Asr Shafi'i, Isha 90 min after Maghrib. Compare with the Awqaf timetable.",
                      "طريقة قطر: الفجر 18°، العصر شافعي، العشاء بعد المغرب بـ 90 دقيقة. قارنها بتقويم الأوقاف."))
        val city = if (ar) cityAr(cityIndex) else cities[cityIndex].first
        dialog().setTitle(t("Prayer times  ·  $city", "مواقيت الصلاة  ·  $city")).setMessage(msg.toString())
            .setPositiveButton(t("OK", "حسنًا"), null)
            .setNeutralButton(t("Change city", "تغيير المدينة")) { _, _ -> cityMenu() }.show()
    }

    private fun hijriText(d: LocalDate): String {
        val h = Islamic.hijri(d, hijriAdj)
        if (h.m < 0) return ""
        return "${h.d} ${(if (ar) Islamic.monthsAr else Islamic.monthsEn)[h.m]} ${h.y} ${t("AH", "هـ")}"
    }

    private fun islamicDatesDialog() {
        val today = LocalDate.now()
        val msg = StringBuilder(t("Today: ", "اليوم: ") + hijriText(today) + "\n\n")
        for ((d, en, a) in Islamic.occasions(today, hijriAdj)) {
            val days = java.time.temporal.ChronoUnit.DAYS.between(today, d)
            msg.append("• ").append(t(en, a)).append("  —  ").append(shortDate(d))
                .append(if (days == 0L) t("  (today)", "  (اليوم)") else t("  (in $days days)", "  (بعد $days يومًا)")).append("\n")
        }
        msg.append("\n").append(t("Dates follow the Umm al-Qura calendar. Months start by moon sighting, so a date can move by a day. If the TV is a day off from Qatar's announcement, use “Adjust Hijri date”.",
            "التواريخ حسب تقويم أم القرى. تبدأ الشهور برؤية الهلال، لذا قد يختلف التاريخ يومًا. إذا اختلف التلفاز عن إعلان قطر استخدم «ضبط التاريخ الهجري»."))
        dialog().setTitle(t("Islamic dates", "المناسبات الإسلامية")).setMessage(msg.toString())
            .setPositiveButton(t("OK", "حسنًا"), null)
            .setNeutralButton(t("Adjust Hijri date", "ضبط التاريخ الهجري")) { _, _ -> hijriAdjMenu() }.show()
    }

    private fun hijriAdjMenu() {
        val opts = listOf(-2, -1, 0, 1, 2)
        val today = LocalDate.now()
        val labels = opts.map { o ->
            val h = Islamic.hijri(today, o)
            val s = if (o == 0) t("No change", "بدون تعديل") else if (o > 0) t("+$o day", "+$o يوم") else t("$o day", "$o يوم")
            "$s   →   ${h.d} ${(if (ar) Islamic.monthsAr else Islamic.monthsEn)[h.m.coerceAtLeast(0)]}" + if (o == hijriAdj) "  ✓" else ""
        }
        dialog().setTitle(t("Adjust Hijri date", "ضبط التاريخ الهجري")).setItems(labels.toTypedArray()) { _, w ->
            prefs.edit().putInt("hijriAdj", opts[w]).apply(); updateClock()
        }.show()
    }

    private fun fastDialog() {
        val today = LocalDate.now()
        val msg = StringBuilder()
        var d = today
        var count = 0
        repeat(31) {
            val h = Islamic.hijri(d, hijriAdj)
            val why = if (h.m == 8) t("Ramadan", "رمضان") else Islamic.sunnahFast(d, h, ar)
            if (why != null && count < 14) { msg.append("• ").append(shortDate(d)).append("  —  ").append(why).append("\n"); count++ }
            d = d.plusDays(1)
        }
        if (count == 0) msg.append(t("No Sunnah fasts in the next 30 days.", "لا يوجد صيام سنة في الثلاثين يومًا القادمة.")).append("\n")
        msg.append("\n").append(t("Includes Mondays & Thursdays, the White days (13–15), Arafah, Ashura & Tasu'a, the first days of Dhu al-Hijjah and the six days of Shawwal. Eid days and the days of Tashreeq are skipped.",
            "يشمل الاثنين والخميس، والأيام البيض (13–15)، وعرفة، وعاشوراء وتاسوعاء، وعشر ذي الحجة، وست شوال. وتُستثنى أيام العيد وأيام التشريق."))
        dialog().setTitle(t("Upcoming Sunnah fasts", "صيام السنة القادم")).setMessage(msg.toString())
            .setPositiveButton(t("OK", "حسنًا"), null)
            .setNeutralButton(t("Turn reminders off", "إيقاف التذكير")) { _, _ -> prefs.edit().putBoolean("fastOn", false).apply(); updateClock()
                toast(t("Fasting reminders off. Turn on again in ☰ Launcher.", "تم إيقاف تذكير الصيام. يمكن تفعيله من ☰ الواجهة.")) }.show()
    }

    // ------------------------------------------------------------------ Settings screen

    private var settingsView: FrameLayout? = null
    private var settingsList: LinearLayout? = null
    private var settingsFocus = -1

    private class SRow(val icon: Int, val title: String, val desc: String, val value: String, val action: Int)

    /** Background update check, by the Auto update setting. */
    private fun autoUpdateTick() {
        val now = System.currentTimeMillis()
        val mode = autoUpd
        if (mode == 2 && Updater.hasDownloaded(this) && settingsView == null && now - prefs.getLong("autoAsk", 0L) > 6 * 3_600_000L) {
            prefs.edit().putLong("autoAsk", now).apply()
            handler.postDelayed({ if (resumed && settingsView == null) Updater.installDownloaded(this) }, 1500)
        }
        val every = if (mode == 2) 6 * 3_600_000L else 12 * 3_600_000L
        if (mode == 0 || now - prefs.getLong("updChecked", 0L) < every) { setUpd(prefs.getBoolean("updReady", false)); return }
        prefs.edit().putLong("updChecked", now).apply()
        if (mode == 2) Updater.autoDownload(this, ar) {
            setUpd(true); prefs.edit().putLong("autoAsk", 0L).apply()
            if (resumed && settingsView == null) { prefs.edit().putLong("autoAsk", System.currentTimeMillis()).apply(); Updater.installDownloaded(this) }
        } else Updater.checkQuietly(this, ar) { has -> setUpd(has) }
    }

    private fun autoUpdName() = when (autoUpd) { 0 -> t("Off", "متوقف"); 2 -> t("Download & install", "تنزيل وتثبيت"); else -> t("Tell me", "إعلامي فقط") }

    private fun autoUpdMenu() {
        val items = arrayOf(t("Download & install by itself — Android asks you to press Install, only when you're on the home screen", "تنزيل وتثبيت تلقائي — يطلب Android الضغط على «تثبيت» فقط وأنت في الشاشة الرئيسية"),
            t("Tell me — a gold dot on Updates", "إعلامي فقط — نقطة ذهبية على زر التحديثات"),
            t("Off — I'll check myself", "إيقاف — سأبحث بنفسي"))
        dialog().setTitle(t("Auto update", "التحديث التلقائي")).setItems(items) { _, w ->
            prefs.edit().putInt("autoUpd", when (w) { 0 -> 2; 1 -> 1; else -> 0 }).putLong("updChecked", 0L).apply()
            autoUpdateTick()
        }.show()
    }

    private fun bgName(): String = when (bgChoice) {
        "auto" -> t("Automatic (day / night)", "تلقائي (نهار / ليل)")
        "drawn" -> t("Drawn night sky", "سماء مرسومة")
        else -> backgrounds.firstOrNull { it.id == bgChoice }?.let { if (ar) it.ar else it.en } ?: t("Automatic", "تلقائي")
    }

    private fun backgroundMenu() {
        val ids = listOf("auto") + backgrounds.map { it.id } + "drawn"
        val names = ids.map { id -> when (id) {
            "auto" -> t("Automatic — the Kaaba by day, at night after Maghrib", "تلقائي — الكعبة نهارًا، وليلًا بعد المغرب")
            "drawn" -> t("Drawn night sky (no photo)", "سماء ليلية مرسومة (بدون صورة)")
            else -> backgrounds.first { it.id == id }.let { if (ar) it.ar else it.en } } + if (id == bgChoice) "  ✓" else "" }
        dialog().setTitle(t("Background", "الخلفية")).setItems(names.toTypedArray()) { _, w ->
            prefs.edit().putString("bg", ids[w]).apply(); refreshBackground(true)
        }.show()
    }

    private fun dimName(l: Int = bgDim) = arrayOf(t("Brightest", "الأسطع"), t("Bright", "ساطع"), t("Balanced", "متوازن"), t("Dim", "خافت"), t("Darkest", "الأغمق"))[l.coerceIn(0, 4)]

    private fun dimMenu() {
        val names = (0..4).map { dimName(it) + (if (it == 2) t("  (recommended)", "  (مُوصى به)") else "") + (if (it == bgDim) "  ✓" else "") }
        dialog().setTitle(t("Photo brightness", "سطوع الصورة")).setItems(names.toTypedArray()) { _, w ->
            prefs.edit().putInt("bgDim", w).apply(); refreshBackground(true)
        }.show()
    }

    private fun setUpd(has: Boolean) {
        prefs.edit().putBoolean("updReady", has).apply()
        updBtn.setName(if (has) t("Update ready", "يتوفّر تحديث") else t("Updates", "التحديثات")); updBtn.setBadge(has)
    }

    private fun photoCredits(): String = if (backgrounds.isEmpty()) "" else
        t("Background photos (Wikimedia Commons; cropped, darkened, lower part softened):\n", "صور الخلفية (Wikimedia Commons؛ مقصوصة ومعتّمة وأسفلها مُنعَّم):\n") +
        backgrounds.joinToString("\n") { "• " + (if (ar) it.ar else it.en) + " — " + it.author + ", " + it.licence } + "\n"

    private fun version(): String = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        "${pi.versionName}  ·  " + t("build ", "رقم ") + (if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong())
    } catch (_: Exception) { "" }

    // ------------------------------------------------------------------ seasonal greeting card

    private val greetOn get() = prefs.getBoolean("greetOn", true)

    /** The occasion today (by the Hijri date as set in the launcher, and Qatar National Day), or null. */
    private fun occasion(today: LocalDate): String? {
        val h = Islamic.hijri(today, hijriAdj)
        return when {
            today.monthValue == 12 && today.dayOfMonth == 18 -> "national"
            h.m == 8 && h.d >= 20 -> "lastten"
            h.m == 8 -> "ramadan"
            h.m == 9 && h.d <= 3 -> "fitr"
            h.m == 11 && h.d in 1..8 -> "dhulhijja"
            h.m == 11 && h.d == 9 -> "arafah"
            h.m == 11 && h.d in 10..13 -> "adha"
            h.m == 0 && h.d == 1 -> "newyear"
            else -> null
        }
    }

    /** Title (short, for the swash), line under it, icon. */
    private fun greeting(key: String): Triple<String, String, Int> {
        val hy = Islamic.hijri(LocalDate.now(), hijriAdj).y
        return when (key) {
            "ramadan" -> Triple(t("Ramadan Kareem", "رمضان كريم"), t("May Allah accept your fasting and prayers", "تقبّل الله صيامكم وقيامكم"), GoldIcon.LANTERN)
            "lastten" -> Triple(t("The last ten nights", "العشر الأواخر"), t("O Allah, You pardon and love to pardon, so pardon us", "اللهم إنك عفوٌّ تحب العفو فاعفُ عنّا"), GoldIcon.LANTERN)
            "fitr" -> Triple(t("Eid Mubarak", "عيد مبارك"), t("Eid al-Fitr · May Allah accept from us and from you", "عيد الفطر · تقبّل الله منّا ومنكم"), GoldIcon.CRESCENT)
            "dhulhijja" -> Triple(t("The ten days of Dhul Hijjah", "عشر ذي الحجة"), t("The best days of the year for good deeds", "أفضل أيام الدنيا للعمل الصالح"), GoldIcon.STAR)
            "arafah" -> Triple(t("Day of Arafah", "يوم عرفة"), t("The best supplication is on the Day of Arafah", "خير الدعاء دعاء يوم عرفة"), GoldIcon.MOSQUE)
            "adha" -> Triple(t("Eid al-Adha Mubarak", "عيد أضحى مبارك"), t("May Allah accept from us and from you", "تقبّل الله منّا ومنكم"), GoldIcon.CRESCENT)
            "newyear" -> Triple(t("Happy Hijri New Year", "عام هجري سعيد"), t("$hy AH", "$hy هـ"), GoldIcon.CRESCENT)
            else -> Triple(t("Qatar National Day", "اليوم الوطني"), t("18 December · Qatar", "١٨ ديسمبر · قطر"), GoldIcon.STAR)
        }
    }

    private fun updateGreeting() {
        if (!::greetCard.isInitialized) return
        val preview = greetPreview?.takeIf { System.currentTimeMillis() < greetPreviewUntil }
        val key = preview ?: if (greetOn) occasion(LocalDate.now()) else null
        if (key == null) { greetCard.visibility = View.GONE; greetShown = null; return }
        greetCard.visibility = View.VISIBLE
        if (greetShown == key) return
        greetShown = key
        val (title, sub, icon) = greeting(key)
        greetTitle.text = title; greetSub.text = sub
        greetIconBox.removeAllViews()
        greetIconBox.addView(GoldIcon(this, icon, GOLD), FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
    }

    private fun greetingMenu() {
        val keys = listOf("ramadan", "lastten", "fitr", "dhulhijja", "arafah", "adha", "newyear", "national")
        val items = arrayOf((if (greetOn) t("✓ Show on occasions (on)", "✓ الإظهار في المناسبات (مُفعّل)") else t("Show on occasions (off)", "الإظهار في المناسبات (متوقف)"))) +
            keys.map { t("Preview: ", "معاينة: ") + greeting(it).first }.toTypedArray()
        dialog().setTitle(t("Seasonal greetings", "تهاني المناسبات")).setItems(items) { _, w ->
            if (w == 0) { prefs.edit().putBoolean("greetOn", !greetOn).apply(); greetPreview = null }
            else { greetPreview = keys[w - 1]; greetPreviewUntil = System.currentTimeMillis() + 60_000L
                toast(t("Shown for one minute", "تظهر لمدة دقيقة")); scroll.smoothScrollTo(0, 0) }
            greetShown = null; updateGreeting()
        }.setNegativeButton(t("Close", "إغلاق"), null).show()
    }

    // ------------------------------------------------------------------ text size (shared with all Sahl apps)

    private val textScale get() = prefs.getFloat("textScale", 1f)
    /** Numbers as 123 or ١٢٣ (Thmanyah's Arabic numerals) for the clock, prayer times and countdown. */
    private val arDigits get() = prefs.getBoolean("arDigits", false)
    private fun digits(s: String): String = if (!arDigits) s.map { if (it in '٠'..'٩') '0' + (it - '٠') else it }.joinToString("")
        else s.map { if (it in '0'..'9') '٠' + (it - '0') else it }.joinToString("")
    private val nightStyle get() = prefs.getBoolean("nightStyle", true)

    private fun textSizeName() = when { textScale >= 1.19f -> t("Extra large", "كبير جدًا"); textScale >= 1.09f -> t("Large", "كبير"); else -> t("Normal", "عادي") }

    private fun textSizeMenu() {
        val sizes = floatArrayOf(1f, 1.1f, 1.2f)
        val names = arrayOf(t("Normal", "عادي"), t("Large", "كبير"), t("Extra large", "كبير جدًا"))
        dialog().setTitle(t("Text size", "حجم الخط"))
            .setSingleChoiceItems(names, sizes.indexOfFirst { kotlin.math.abs(it - textScale) < 0.01f }.coerceAtLeast(0)) { d, w ->
                d.dismiss()
                prefs.edit().putFloat("textScale", sizes[w]).commit()
                Fonts.scale = sizes[w]
                toast(t("Text size: ", "حجم الخط: ") + names[w] + t("  ·  other Sahl apps use it next time they open", "  ·  تطبيقات سهل الأخرى تستخدمه عند فتحها"))
                recreate()
            }.setNegativeButton(t("Cancel", "إلغاء"), null).show()
    }

    private fun settingsSections(): List<Pair<String, List<SRow>>> {
        val city = if (cityIndex in cities.indices) (if (ar) cityAr(cityIndex) else cities[cityIndex].first) else t("Off", "متوقف")
        val alert = when (Alerts.mode(this)) { Alerts.OFF -> t("Off", "متوقف"); Alerts.WITH_REMINDER -> t("On + 10 min before", "مُفعّل + قبله بـ 10 د"); else -> t("On", "مُفعّل") } +
            if (Alerts.mode(this) != Alerts.OFF && !Alerts.canShow(this)) t(" · needs setup", " · يحتاج إعدادًا") else ""
        return listOf(
            t("Prayer & Islamic", "الصلاة والمناسبات") to listOf(
                SRow(GoldIcon.MOSQUE, t("Prayer times city", "مدينة مواقيت الصلاة"), t("Times are calculated on the TV with the Qatar method", "تُحسب المواقيت على التلفاز بطريقة قطر"), city, 4),
                SRow(GoldIcon.CLOCK, t("Adjust prayer times", "ضبط مواقيت الصلاة"), t("Matches the Qatar Calendar House; fine-tune any prayer by a few minutes", "مطابقة لدار التقويم القطري؛ عدّل أي صلاة بدقائق"),
                    if (Prayer.user.any { it != 0 }) t("Adjusted", "معدّلة") else t("Qatar calendar", "تقويم قطر"), 21),
                SRow(GoldIcon.BELL, t("Prayer-time banner", "تنبيه وقت الصلاة"), t("A quiet card at each prayer time, even over videos. No sound", "بطاقة هادئة عند كل صلاة حتى فوق الفيديو، بدون صوت"), alert, 8),
                SRow(GoldIcon.CRESCENT, t("Hijri date & fasting", "التاريخ الهجري والصيام"), t("Adjust the Hijri date, Sunnah fast reminders, Islamic dates", "ضبط التاريخ الهجري، تذكير صيام السنة، المناسبات"), hijriText(LocalDate.now()), 6)),
            t("Look & comfort", "المظهر والراحة") to listOf(
                SRow(GoldIcon.GLOBE, t("Language", "اللغة"), t("Language of the home screen and menus", "لغة الشاشة الرئيسية والقوائم"), if (ar) "العربية" else "English", 0),
                SRow(GoldIcon.STAR, t("Theme", "المظهر"), t("Islamic: Kaaba photo with gold details. Classic: plain dark", "إسلامي: صورة الكعبة بلمسات ذهبية. كلاسيكي: داكن بسيط"), if (islamic) t("Islamic", "إسلامي") else t("Classic", "كلاسيكي"), 5),
                SRow(GoldIcon.IMAGE, t("Background", "الخلفية"), t("The Kaaba, the Prophet's Mosque, Al-Aqsa — or automatic day / night", "الكعبة أو المسجد النبوي أو الأقصى — أو تلقائي نهار / ليل"), if (islamic) bgName() else t("Classic theme", "المظهر الكلاسيكي"), 18),
                SRow(GoldIcon.SUN, t("Photo brightness", "سطوع الصورة"), t("Darker is calmer and easier to read", "الأغمق أهدأ وأسهل للقراءة"), dimName(), 19),
                SRow(GoldIcon.EYE, t("Calm mode", "الوضع الهادئ"), t("Gentle for little eyes: minute countdown, soft focus, dims at night", "لطيف لعيون الصغار: عدّ بالدقائق، تركيز هادئ، تعتيم ليلًا"), onOff(calm), 7),
                SRow(GoldIcon.STAR, t("Seasonal greetings", "تهاني المناسبات"), t("Ramadan, Eid, Arafah, Hijri new year and Qatar National Day, on the home screen", "رمضان والعيدين وعرفة ورأس السنة الهجرية واليوم الوطني، على الشاشة الرئيسية"), onOff(greetOn), 27),
                SRow(GoldIcon.CRESCENT, t("Night style", "النمط الليلي"), t("From Isha until Fajr: warmer, softer colours and the reading font, in all Sahl apps", "من العشاء حتى الفجر: ألوان أدفأ وأهدأ وخط القراءة، في كل تطبيقات سهل"), onOff(nightStyle), 25),
                SRow(GoldIcon.CLOCK, t("Numbers", "الأرقام"), t("Clock, prayer times and countdown", "الساعة ومواقيت الصلاة والعدّ التنازلي"), if (arDigits) "١٢٣" else "123", 26),
                SRow(GoldIcon.EYE, t("Text size", "حجم الخط"), t("For all Sahl apps: Sahl TV, Quran, PDF and Files", "لكل تطبيقات سهل: سهل TV والقرآن وPDF والملفات"), textSizeName(), 24),
                SRow(GoldIcon.CLOCK, t("Clock", "الساعة"), t("Show time as 24-hour or 12-hour", "عرض الوقت بنظام 24 أو 12 ساعة"), if (clock24) t("24-hour", "24 ساعة") else t("12-hour", "12 ساعة"), 3),
                SRow(GoldIcon.SCREEN, t("Clock screensaver", "شاشة توقف الساعة"), t("A dim moving clock when the TV sits idle", "ساعة خافتة متحركة عند عدم الاستخدام"), if (saverMin == 0) t("Off", "متوقفة") else t("After $saverMin min", "بعد $saverMin د"), 12)),
            t("Home screen", "الشاشة الرئيسية") to listOf(
                SRow(GoldIcon.BOOK, t("Ayah of the day", "آية اليوم"), t("A short verse each day; press OK on it to read in Sahl Quran", "آية قصيرة كل يوم؛ اضغط عليها للقراءة في سهل القرآن"), onOff(ayahOn), 23),
                SRow(GoldIcon.SUN, t("Weather & Qatar dates", "الطقس ومناسبات قطر"), t("Weather for your city and the next national day or Eid, next to the clock", "طقس مدينتك والمناسبة الوطنية أو العيد القادم بجانب الساعة"), onOff(wxOn), 22),
                SRow(GoldIcon.PLAY, t("App previews", "معاينات التطبيقات"), t("Recommended videos from SmartTube and other apps", "فيديوهات مقترحة من SmartTube وغيره"), onOff(previewsOn), 1),
                SRow(GoldIcon.SORT, t("Sort all apps", "ترتيب التطبيقات"), t("Order of the All apps grid", "ترتيب شبكة كل التطبيقات"), if (sortMostUsed) t("Most used", "الأكثر استخدامًا") else t("A to Z", "أبجديًا"), 2),
                SRow(GoldIcon.EYE_OFF, t("Hidden apps", "التطبيقات المخفية"), t("Apps you removed from the home screen", "التطبيقات التي أخفيتها من الشاشة الرئيسية"), "${hidden.size}", 11),
                SRow(GoldIcon.PLAY, t("Now playing controls", "التحكم بالتشغيل"), t("Play/pause bar for videos playing in the background", "شريط تشغيل/إيقاف للفيديو العامل في الخلفية"), onOff(nowOn) + if (nowOn && !mediaAllowed) t(" · needs setup", " · يحتاج إعدادًا") else "", 13)),
            t("Family & safety", "العائلة والأمان") to listOf(
                SRow(GoldIcon.LOCK, t("PIN & locks", "الرمز السري والأقفال"), t("Lock apps or TV settings with a 4-digit PIN", "قفل التطبيقات أو إعدادات التلفاز برمز من 4 أرقام"), if (pinSet) t("${locked.size} locked", "${locked.size} مقفلة") else t("No PIN", "بدون رمز"), 10)),
            t("Phone & TV", "الهاتف والتلفاز") to listOf(
                SRow(GoldIcon.PHONE, t("Phone remote", "الريموت من الهاتف"), t("Control the TV and type from your phone's browser", "تحكّم بالتلفاز واكتب من متصفح هاتفك"), onOff(remoteOn), 15),
                SRow(GoldIcon.BROOM, t("Auto clean", "التنظيف التلقائي"), t("Frees memory when the TV gets low", "يحرّر الذاكرة عند انخفاضها"), onOff(autoClean), 9),
                SRow(GoldIcon.UPDATE, t("Auto update", "التحديث التلقائي"), t("Get new versions of Sahl TV and Sahl Quran without searching", "احصل على الإصدارات الجديدة دون البحث عنها"), autoUpdName(), 20),
                SRow(GoldIcon.SAVE, t("Backup & restore", "النسخ الاحتياطي والاستعادة"), t("Save all settings to the Download folder, or bring them back", "حفظ كل الإعدادات في مجلد التنزيلات أو استعادتها"), "", 14)),
            t("About", "حول") to listOf(
                SRow(GoldIcon.INFO, t("About Sahl TV", "حول سهل TV"), t("Credits, privacy and contact", "الشكر والخصوصية والتواصل"), "", 17)))
    }

    private fun showSettings() {
        if (settingsView != null) return
        val overlay = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#F5061411"), Color.parseColor("#FA040D0B")))
            alpha = 0f; isClickable = true
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = if (ar) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            setPadding(dp(8), dp(28), dp(8), dp(40)); clipChildren = false; clipToPadding = false
        }
        val sc = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; addView(list) }
        val w = minOf(dp(780), resources.displayMetrics.widthPixels - dp(96))
        overlay.addView(sc, FrameLayout.LayoutParams(w, MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
        rootFrame.addView(overlay, rootFrame.childCount - 1, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))   // below the night dimmer
        settingsView = overlay; settingsList = list
        (rootFrame.getChildAt(0) as android.view.ViewGroup).descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS   // keep focus inside Settings
        fillSettings()
        overlay.animate().alpha(1f).setDuration(if (calm) 260 else 180).start()
    }

    private fun hideSettings() {
        val v = settingsView ?: return
        settingsView = null; settingsList = null
        (rootFrame.getChildAt(0) as android.view.ViewGroup).descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        v.animate().alpha(0f).setDuration(160).withEndAction { rootFrame.removeView(v) }.start()
        settingsFocus = -1
        render(keepFocus = true, force = true)
        updateClock()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && settingsView != null) fillSettings()      // a dialog closed: show the new values
    }

    private fun fillSettings() {
        val list = settingsList ?: return
        list.removeAllViews()
        // header
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), dp(14)) }
        head.addView(GoldIcon(this, GoldIcon.STAR, GOLD), LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(12) })
        val ht = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ht.addView(TextView(this).apply { text = t("Settings", "الإعدادات"); textSize = 30f; setTextColor(TEXT); typeface = Fonts.display(true); Fonts.heading(this) })
        ht.addView(TextView(this).apply { text = t("Sahl TV  ·  press Back to return home", "سهل TV  ·  اضغط رجوع للعودة"); textSize = 13f; setTextColor(MUTED) })
        head.addView(ht, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        list.addView(head)

        // update card
        val ready = prefs.getBoolean("updReady", false)
        list.addView(settingsRow(SRow(GoldIcon.UPDATE, if (ready) t("Update ready — install now", "يتوفّر تحديث — ثبّته الآن") else t("Check for updates", "البحث عن تحديثات"),
            t("Version ", "الإصدار ") + version() + t("  ·  updates Sahl TV and Sahl Quran", "  ·  يحدّث سهل TV وسهل القرآن"),
            if (ready) t("New", "جديد") else "", 16), strong = true))

        var focusView: View? = null
        for ((title, rows) in settingsSections()) {
            list.addView(TextView(this).apply {
                text = title; textSize = 17f; setTextColor(GOLD); typeface = Fonts.display(false); Fonts.heading(this)
                setPadding(dp(6), dp(20), dp(6), dp(8))
            })
            for (r in rows) { val v = settingsRow(r, false); list.addView(v); if (r.action == settingsFocus) focusView = v }
        }
        handler.post { (focusView ?: firstFocusable(list))?.requestFocus() }
    }

    private fun firstFocusable(v: View): View? {
        if (v.isFocusable) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) firstFocusable(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun settingsRow(r: SRow, strong: Boolean): View {
        val row = card(strong) { settingsFocus = r.action; menuAction(r.action); handler.postDelayed({ if (settingsView != null && hasWindowFocus()) fillSettings() }, 60) }.apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        row.setOnFocusChangeListener { v, has -> v.background = cardBg(strong, has); if (has) settingsFocus = r.action }
        val bubble = FrameLayout(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(38, 227, 184, 92)) } }
        bubble.addView(GoldIcon(this, r.icon, GOLD), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        row.addView(bubble, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(16) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply { text = r.title; textSize = 17f; setTextColor(TEXT); setTypeface(typeface, Typeface.BOLD); maxLines = 1 })
        texts.addView(TextView(this).apply { text = r.desc; textSize = 13f; setTextColor(MUTED); maxLines = 2; setPadding(0, dp(2), 0, 0) })
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (r.value.isNotEmpty()) row.addView(TextView(this).apply {
            text = r.value; textSize = 13.5f; setTextColor(GOLD); maxLines = 1
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = GradientDrawable().apply { setColor(Color.argb(30, 227, 184, 92)); cornerRadius = dp(12).toFloat() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) })
        row.addView(TextView(this).apply { text = if (ar) "‹" else "›"; textSize = 22f; setTextColor(MUTED) },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(10) })
        row.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(0, dp(5), 0, dp(5)) }
        return row
    }

    // ------------------------------------------------------------------ Ayah of the day, weather & Qatar, what's new

    private val quranFont: Typeface? by lazy { try { Typeface.createFromAsset(assets, "AmiriQuran.ttf") } catch (_: Exception) { null } }
    private val ayat: org.json.JSONArray? by lazy { try { org.json.JSONArray(assets.open("ayat.json").bufferedReader().use { it.readText() }) } catch (_: Exception) { null } }
    private val whatsNew: org.json.JSONObject? by lazy { try { org.json.JSONObject(assets.open("whatsnew.json").bufferedReader().use { it.readText() }) } catch (_: Exception) { null } }
    private val ayahOn get() = prefs.getBoolean("ayahOn", true)
    private val wxOn get() = prefs.getBoolean("wxOn", true)
    private var ayahToday: org.json.JSONObject? = null

    private fun arDigits(n: Int) = n.toString().map { "٠١٢٣٤٥٦٧٨٩"[it - '0'] }.joinToString("")

    private fun updateAyah() {
        val list = ayat
        if (!ayahOn || list == null || list.length() == 0) { ayahCard.visibility = View.GONE; return }
        ayahCard.visibility = View.VISIBLE
        val o = list.getJSONObject((LocalDate.now().toEpochDay() % list.length()).toInt())
        if (ayahToday?.toString() == o.toString()) return
        ayahToday = o
        ayahTv.text = o.getString("text") + " ﴿" + arDigits(o.getInt("a")) + "﴾"
        val nameAr = o.optString("ar"); val nameEn = o.optString("en")
        ayahRef.text = if (ar) "سورة ${nameAr.ifEmpty { arDigits(o.getInt("s")) }} · الآية ${arDigits(o.getInt("a"))}  ·  اضغط للقراءة"
                       else "Surah ${nameEn.ifEmpty { o.getInt("s").toString() }} ${o.getInt("s")}:${o.getInt("a")}  ·  press OK to read"
    }

    private fun openAyah() {
        val o = ayahToday ?: return
        try {
            startActivity(Intent().setClassName("com.aly.sahlquran", "com.aly.sahlquran.QuranActivity")
                .putExtra("surah", o.getInt("s")).putExtra("ayah", o.getInt("a")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { toast(t("Install Sahl Quran from ⬆ Updates to read the whole surah", "ثبّت سهل · القرآن من زر التحديثات لقراءة السورة كاملة")) }
    }

    private fun updateNews() {
        val o = whatsNew
        val mine = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        if (o == null || o.optString("version") != mine || prefs.getString("wnSeen", "") == mine) { newsCard.visibility = View.GONE; return }
        val first = prefs.getLong("wnFirst_$mine", 0L).let { if (it == 0L) System.currentTimeMillis().also { n -> prefs.edit().putLong("wnFirst_$mine", n).apply() } else it }
        if (System.currentTimeMillis() - first > 48 * 3_600_000L) { newsCard.visibility = View.GONE; return }
        val items = o.optJSONArray(if (ar) "ar" else "en")
        newsSub.text = (if (items != null && items.length() > 0) items.getString(0) else "") + t("  ·  press OK for all", "  ·  اضغط لعرض الكل")
        newsCard.visibility = View.VISIBLE
    }

    private fun whatsNewDialog() {
        val o = whatsNew ?: return
        val items = o.optJSONArray(if (ar) "ar" else "en")
        val msg = StringBuilder()
        if (items != null) for (i in 0 until items.length()) msg.append("• ").append(items.getString(i)).append("\n\n")
        dialog().setTitle(t("What's new in ", "الجديد في ") + o.optString("version")).setMessage(msg.toString().trim())
            .setPositiveButton(t("Got it", "تم")) { _, _ -> prefs.edit().putString("wnSeen", o.optString("version")).apply(); newsCard.visibility = View.GONE }.show()
    }

    // weather (Open-Meteo, no account needed) is fetched in the background at most every 45 minutes and kept, so Home never waits for it
    private lateinit var wxBox: LinearLayout
    private lateinit var wxIcon: GoldIcon
    private lateinit var wxTemp: TextView
    private lateinit var wxLine: TextView
    private lateinit var qaLine: TextView
    private var qaKey = ""
    private var qaText = ""

    private fun buildWeather(): View {
        wxBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        wxIcon = GoldIcon(this, GoldIcon.SUN, GOLD)
        wxBox.addView(wxIcon, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) })
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wxTemp = TextView(this).apply { textSize = 28f; setTextColor(Color.WHITE); setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); includeFontPadding = false; setShadowLayer(10f, 0f, 1f, Color.BLACK) }
        wxLine = TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#DCE6E2")); setShadowLayer(6f, 0f, 1f, Color.BLACK); maxLines = 1 }
        qaLine = TextView(this).apply { textSize = 13f; setTextColor(GOLD); setShadowLayer(6f, 0f, 1f, Color.BLACK); maxLines = 1 }
        c.addView(wxTemp); c.addView(wxLine); c.addView(qaLine)
        wxBox.addView(c)
        return wxBox
    }

    private fun wxCity(): Pair<Double, Double> = cities.getOrElse(cityIndex) { cities[0] }.let { it.second to it.third }

    private fun refreshWeather() {
        if (!::wxBox.isInitialized) return
        if (!wxOn) { wxBox.visibility = View.GONE; return }
        wxBox.visibility = View.VISIBLE
        showWeather()
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("wxTry", 0L) < 45 * 60_000L) return
        prefs.edit().putLong("wxTry", now).apply()
        val (la, lo) = wxCity()
        worker.execute {
            try {
                val u = java.net.URL("https://api.open-meteo.com/v1/forecast?latitude=$la&longitude=$lo&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1")
                val c = u.openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                val body = c.inputStream.bufferedReader().use { it.readText() }
                org.json.JSONObject(body)            // valid?
                prefs.edit().putString("wx", body).putLong("wxAt", System.currentTimeMillis()).apply()
                handler.post { showWeather() }
            } catch (_: Exception) {}
        }
    }

    private fun showWeather() {
        val o = try { org.json.JSONObject(prefs.getString("wx", "") ?: "") } catch (_: Exception) { null }
        val fresh = System.currentTimeMillis() - prefs.getLong("wxAt", 0L) < 6 * 3_600_000L
        if (o != null && fresh) {
            val cur = o.getJSONObject("current"); val day = o.getJSONObject("daily")
            val code = cur.getInt("weather_code"); val isDay = cur.optInt("is_day", 1) == 1
            wxTemp.text = "${Math.round(cur.getDouble("temperature_2m"))}°"
            wxIcon.kind = when (code) { 0, 1 -> if (isDay) GoldIcon.SUN else GoldIcon.CRESCENT; 2, 3, 45, 48 -> GoldIcon.CLOUD; in 51..67, in 80..82, in 95..99 -> GoldIcon.RAIN; else -> GoldIcon.CLOUD }
            val desc = when (code) { 0 -> t("Clear", "صافٍ"); 1, 2 -> t("Partly cloudy", "غائم جزئيًا"); 3 -> t("Cloudy", "غائم"); 45, 48 -> t("Fog", "ضباب")
                in 51..67, in 80..82 -> t("Rain", "أمطار"); in 95..99 -> t("Thunderstorm", "عاصفة رعدية"); else -> "" }
            val hi = Math.round(day.getJSONArray("temperature_2m_max").getDouble(0)); val lo = Math.round(day.getJSONArray("temperature_2m_min").getDouble(0))
            wxLine.text = (if (desc.isNotEmpty()) "$desc  ·  " else "") + t("H $hi°  L $lo°", "العظمى $hi°  الصغرى $lo°")
            wxTemp.visibility = View.VISIBLE; wxLine.visibility = View.VISIBLE; wxIcon.visibility = View.VISIBLE
        } else { wxTemp.visibility = View.GONE; wxLine.visibility = View.GONE; wxIcon.visibility = View.GONE }
        qaLine.text = qatarNext()
    }

    /** Next Qatar occasion: National Day (18 Dec), National Sport Day (2nd Tuesday of February), the two Eids. Worked out on the TV. */
    private fun qatarNext(): String {
        val today = LocalDate.now()
        val key = "$today/$hijriAdj/$ar"
        if (key == qaKey) return qaText
        val c = ArrayList<Pair<LocalDate, String>>()
        for (y in listOf(today.year, today.year + 1)) {
            c.add(LocalDate.of(y, 12, 18) to t("Qatar National Day", "اليوم الوطني لقطر"))
            var f = LocalDate.of(y, 2, 1); while (f.dayOfWeek != DayOfWeek.TUESDAY) f = f.plusDays(1)
            c.add(f.plusDays(7) to t("National Sport Day", "اليوم الرياضي للدولة"))
        }
        Islamic.firstDate(today, 400) { d -> Islamic.hijri(d, hijriAdj).let { it.m == 9 && it.d == 1 } }?.let { c.add(it to t("Eid al-Fitr", "عيد الفطر")) }
        Islamic.firstDate(today, 400) { d -> Islamic.hijri(d, hijriAdj).let { it.m == 11 && it.d == 10 } }?.let { c.add(it to t("Eid al-Adha", "عيد الأضحى")) }
        val next = c.filter { !it.first.isBefore(today) }.minByOrNull { it.first }
        qaText = if (next == null) "" else {
            val days = java.time.temporal.ChronoUnit.DAYS.between(today, next.first)
            when (days) { 0L -> t("Today: ${next.second} 🎉", "اليوم: ${next.second} 🎉"); 1L -> t("Tomorrow: ${next.second}", "غدًا: ${next.second}")
                else -> t("${next.second} in $days days", "${next.second} بعد $days يومًا") }
        }
        qaKey = key
        return qaText
    }

    private fun prayerAdjustMenu() {
        val tt = timesFor(LocalDate.now()) ?: run { cityMenu(); return }
        val names = listOf(t("Fajr", "الفجر"), t("Sunrise", "الشروق"), t("Dhuhr", "الظهر"), t("Asr", "العصر"), t("Maghrib (Isha follows +90 min)", "المغرب (العشاء بعده بـ 90 دقيقة)"))
        val times = listOf(tt.fajr, tt.sunrise, tt.dhuhr, tt.asr, tt.maghrib)
        val items = names.indices.map { "${names[it]}   ${Prayer.hm(times[it])}" + (if (Prayer.user[it] != 0) "   (${if (Prayer.user[it] > 0) "+" else ""}${Prayer.user[it]} ${t("min", "د")})" else "") } +
            t("Reset to the Qatar calendar", "إعادة إلى تقويم قطر")
        dialog().setTitle(t("Adjust prayer times (today)", "ضبط مواقيت الصلاة (اليوم)")).setItems(items.toTypedArray()) { _, w ->
            if (w == names.size) { Prayer.user = IntArray(5); Prayer.save(this); updateClock(); Alerts.schedule(this); return@setItems }
            val opts = (-5..5).toList()
            val labels = opts.map { (if (it > 0) "+$it" else "$it") + " " + t("min", "د") + (if (it == 0) t("  (Qatar calendar)", "  (تقويم قطر)") else "") + (if (it == Prayer.user[w]) "  ✓" else "") }
            dialog().setTitle(names[w]).setItems(labels.toTypedArray()) { _, k ->
                Prayer.user[w] = opts[k]; Prayer.save(this); updateClock(); Alerts.schedule(this); prayerAdjustMenu()
            }.show()
        }.show()
    }

    // ------------------------------------------------------------------ PIN

    private fun hash(pin: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(("sahltv:" + pin).toByteArray()).joinToString("") { String.format("%02x", it) }
    }

    /** Runs the action directly when no PIN is set; otherwise only after the right PIN. */
    private fun guarded(action: () -> Unit) { if (pinSet) askPin(t("Enter PIN", "أدخل الرمز السري")) { action() } else action() }

    private fun askPin(title: String, onOk: () -> Unit) {
        if (!pinSet) { onOk(); return }
        pinPad(title) { pin, dlg, msg ->
            if (hash(pin) == prefs.getString("pinHash", "")) { dlg.dismiss(); onOk(); true }
            else { msg.text = t("Wrong PIN — try again", "رمز خاطئ — حاول مجددًا"); false }
        }
    }

    private fun createPin(after: () -> Unit) {
        pinPad(t("Choose a 4-digit PIN", "اختر رمزًا من 4 أرقام")) { first, dlg, _ ->
            dlg.dismiss()
            pinPad(t("Enter the same PIN again", "أدخل الرمز نفسه مرة أخرى")) { second, dlg2, msg2 ->
                if (second == first) {
                    prefs.edit().putString("pinHash", hash(first)).apply(); dlg2.dismiss(); toast(t("PIN saved", "تم حفظ الرمز")); after(); true
                } else { msg2.text = t("PINs didn't match — try again", "الرمزان غير متطابقين — حاول مجددًا"); false }
            }
            true
        }
    }

    private fun pinMenu() {
        if (!pinSet) {
            dialog().setTitle(t("PIN & locks", "الرمز السري والأقفال"))
                .setMessage(if (ar) "اضبط رمزًا من 4 أرقام لقفل التطبيقات (مثل يوتيوب ومتجر Play) وزر إعدادات التلفاز.\n\nملاحظة: هذا القفل يعمل من هذه الشاشة الرئيسية. قد يتمكن شخص خبير بأندرويد من الوصول إلى التطبيقات بطريقة أخرى (مثل البحث الصوتي)." else "Set a 4-digit PIN to lock apps (e.g. YouTube, Play Store) and the TV settings button.\n\nNote: this lock works from this home screen. Someone who knows Android well could still reach apps another way (e.g. voice search).")
                .setPositiveButton(t("Set a PIN", "ضبط رمز")) { _, _ -> createPin { pinMenu() } }
                .setNegativeButton(t("Cancel", "إلغاء"), null).show()
            return
        }
        askPin(t("Enter PIN", "أدخل الرمز السري")) {
            val items = arrayOf(
                t("Change PIN", "تغيير الرمز"),
                t("Lock the TV settings button: ", "قفل زر إعدادات التلفاز: ") + onOff(lockSettings) + t("  (change)", "  (تغيير)"),
                t("Locked apps: ", "التطبيقات المقفلة: ") + (locked.mapNotNull { apps[it]?.label }.joinToString(", ").ifEmpty { t("none — hold OK on an app to lock it", "لا يوجد — اضغط مطولًا على تطبيق لقفله") }),
                t("Remove PIN (unlocks everything)", "إزالة الرمز (فتح كل الأقفال)")
            )
            dialog().setTitle(t("PIN & locks", "الرمز السري والأقفال")).setItems(items) { _, which ->
                when (which) {
                    0 -> createPin { }
                    1 -> { prefs.edit().putBoolean("lockSettings", !lockSettings).apply(); toast(t("TV settings lock: ", "قفل الإعدادات: ") + onOff(lockSettings)) }
                    2 -> {}
                    3 -> { prefs.edit().remove("pinHash").putBoolean("lockSettings", false).putString("locked", "").apply(); render(true); toast(t("PIN removed", "تمت إزالة الرمز")) }
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
        val dlg = dialog().setTitle(title).setView(box).setNegativeButton(t("Cancel", "إلغاء"), null).create()
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
                memBtn.text = t("Auto-freed $freed MB", "تم تحرير $freed ميغابايت")
            }
        }
    }

    private fun hiddenMenu() {
        val h = hidden.filter { apps.containsKey(it) }
        if (h.isEmpty()) { toast(t("No hidden apps", "لا توجد تطبيقات مخفية")); return }
        dialog().setTitle(t("Tap an app to show it again", "اختر تطبيقًا لإظهاره من جديد"))
            .setItems(h.map { apps[it]?.label ?: it }.toTypedArray()) { _, which ->
                val l = hidden; l.remove(h[which]); saveList("hidden", l); render(true)
            }.show()
    }

    // ------------------------------------------------------------------ free memory

    /** Asks Android to close apps running in the background (not the one you're using). */
    private fun freeMemory() {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val before = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
        memBtn.text = t("Cleaning…", "جارٍ التنظيف…")
        worker.execute {
            for (pkg in apps.keys) try { am.killBackgroundProcesses(pkg) } catch (_: Exception) {}
            Thread.sleep(600)
            val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val freed = ((info.availMem - before) / (1024 * 1024)).coerceAtLeast(0)
            val freeMb = info.availMem / (1024 * 1024)
            val totalMb = info.totalMem / (1024 * 1024)
            handler.post {
                toast(t("Freed $freed MB  ·  $freeMb MB of $totalMb MB now free", "تم تحرير $freed ميغابايت  ·  المتاح الآن $freeMb من $totalMb ميغابايت"))
            }
        }
    }
}
