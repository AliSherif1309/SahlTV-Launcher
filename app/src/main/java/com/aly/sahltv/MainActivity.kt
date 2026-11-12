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
    private lateinit var memBtn: TextView
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
    private lateinit var settingsBtn: TextView
    private lateinit var updBtn: TextView
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
        val root = FrameLayout(this).also { rootFrame = it }.apply {
            background = if (islamic) IslamicBackdrop(resources.displayMetrics.density, ar)
                         else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG_TOP, BG_BOTTOM))
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
        // look for a new version at most every 12 hours; quietly shows the "Update ready" button
        val lastUpd = prefs.getLong("updChecked", 0L)
        if (System.currentTimeMillis() - lastUpd > 12 * 3_600_000L) {
            prefs.edit().putLong("updChecked", System.currentTimeMillis()).apply()
            Updater.checkQuietly(this, ar) { has -> prefs.edit().putBoolean("updReady", has).apply(); updBtn.visibility = if (has) View.VISIBLE else View.GONE }
        } else updBtn.visibility = if (prefs.getBoolean("updReady", false)) View.VISIBLE else View.GONE
        refreshPreviews(force = false)
        setupMedia()
        lastInput = System.currentTimeMillis()
        handler.removeCallbacks(saverTick); handler.postDelayed(saverTick, 15_000)
    }

    override fun onPause() {
        super.onPause()
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
        clockTv = TextView(this).apply { textSize = 40f; setTextColor(TEXT); typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        dateTv = TextView(this).apply { textSize = 15f; setTextColor(MUTED) }
        hijriTv = TextView(this).apply { textSize = 15f; setTextColor(GOLD); setPadding(0, dp(1), 0, 0) }
        prayerTv = TextView(this).apply { textSize = 15f; setTextColor(ACCENT); visibility = View.GONE }
        val hRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        hRow.addView(GoldIcon(this, GoldIcon.CRESCENT, GOLD), LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) })
        hRow.addView(hijriTv)
        left.addView(clockTv); left.addView(dateTv); left.addView(hRow)
        bar.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        updBtn = pill(t("⬆  Update ready", "⬆  يتوفّر تحديث")) { Updater.check(this, ar) }.apply { visibility = View.GONE; setTextColor(GOLD) }
        bar.addView(updBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(12) })
        memBtn = pill(t("🧹  Free memory", "🧹  تحرير الذاكرة")) { freeMemory() }
        bar.addView(memBtn)
        settingsBtn = pill(t("⚙  TV settings", "⚙  إعدادات التلفاز")) {
            if (lockSettings) askPin(t("TV settings are locked", "إعدادات التلفاز مقفلة")) { open(Intent(Settings.ACTION_SETTINGS)) } else open(Intent(Settings.ACTION_SETTINGS))
        }
        bar.addView(settingsBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) })
        bar.addView(pill(t("☰  Launcher", "☰  الواجهة")) { launcherMenu() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) })
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
        clockTv.text = SimpleDateFormat(if (clock24) "HH:mm" else "h:mm a", Locale.US).format(now)
        dateTv.text = SimpleDateFormat("EEEE d MMMM yyyy", if (ar) Locale.forLanguageTag("ar") else Locale.US).format(now)
        hijriTv.text = hijriText(LocalDate.now())
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
        } else {
            content.addView(rowTitle(t("Favorites", "المفضلة")))
            content.addView(TextView(this).apply {
                text = t("Tip: hold OK on any app (or press Menu) and choose “Add to Favorites”.", "نصيحة: اضغط مطولًا على OK فوق أي تطبيق (أو زر القائمة) واختر «إضافة إلى المفضلة».")
                textSize = 14f; setTextColor(MUTED); setPadding(0, dp(4), 0, dp(8))
            })
        }
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
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), dp(6), dp(6), dp(6)) }
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
        val clock = TextView(this).apply { textSize = 72f; setTextColor(Color.parseColor("#B8C4BF")); typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL) }
        val date = TextView(this).apply { textSize = 18f; setTextColor(Color.parseColor("#6E7C76")) }
        val pray = TextView(this).apply { textSize = 18f; setTextColor(Color.parseColor("#2E8F66")); setPadding(0, dp(6), 0, 0) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; tag = "box"
            addView(clock); addView(date); addView(pray)
        }
        val f = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK); alpha = 0f
            addView(box, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        rootFrame.addView(f, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        saver = f
        fun fill() { clock.text = clockTv.text; date.text = dateTv.text; date.text = "${dateTv.text}\n${hijriTv.text}"; pray.text = listOf(if (cityIndex in cities.indices) prayerTv.text.toString() else "", fastLine).filter { it.isNotEmpty() }.joinToString("\n") }
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

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

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

    private fun launcherMenu() {
        val city = if (cityIndex in cities.indices) (if (ar) cityAr(cityIndex) else cities[cityIndex].first) else t("Off", "متوقف")
        val ch = t("  (change)", "  (تغيير)")
        val items = arrayOf(
            t("Language: English  (العربية)", "اللغة: العربية  (English)"),
            t("App previews (recommended videos…): ", "معاينات التطبيقات (فيديوهات مقترحة…): ") + onOff(previewsOn) + t("  (settings)", "  (إعدادات)"),
            t("Sort all apps: ", "ترتيب التطبيقات: ") + (if (sortMostUsed) t("Most used first", "الأكثر استخدامًا أولًا") else t("A to Z", "أبجديًا")) + ch,
            t("Clock: ", "الساعة: ") + (if (clock24) t("24-hour", "24 ساعة") else t("12-hour", "12 ساعة")) + ch,
            t("Prayer times: ", "مواقيت الصلاة: ") + city + ch,
            t("🎨  Theme: ", "🎨  المظهر: ") + (if (islamic) t("Islamic", "إسلامي") else t("Classic", "كلاسيكي")) + ch,
            t("☪  Hijri date & fasting reminders", "☪  التاريخ الهجري وتذكير الصيام"),
            t("👶  Calm mode (for little eyes): ", "👶  الوضع الهادئ (لعيون الصغار): ") + onOff(calm) + ch,
            t("🔔  Prayer-time banner: ", "🔔  تنبيه وقت الصلاة: ") + alertName() + t("  (settings)", "  (إعدادات)"),
            t("Auto clean when memory is low: ", "التنظيف التلقائي عند انخفاض الذاكرة: ") + onOff(autoClean) + ch,
            t("🔒  PIN & locks", "🔒  الرمز السري والأقفال") + (if (pinSet) t("  (${locked.size} apps locked)", "  (${locked.size} تطبيقات مقفلة)") else t("  (no PIN set)", "  (لا يوجد رمز)")),
            t("Show hidden apps (${hidden.size})", "إظهار التطبيقات المخفية (${hidden.size})"),
            t("Clock screensaver: ", "شاشة توقف الساعة: ") + (if (saverMin == 0) t("Off", "متوقفة") else t("after $saverMin min", "بعد $saverMin دقائق")) + ch,
            t("Now playing controls: ", "التحكم بالتشغيل الحالي: ") + onOff(nowOn) + (if (nowOn && !mediaAllowed) t("  (needs setup)", "  (يحتاج إعدادًا)") else ""),
            t("💾  Backup & restore settings", "💾  نسخ الإعدادات احتياطيًا واستعادتها"),
            t("📱  Phone remote: ", "📱  الريموت من الهاتف: ") + onOff(remoteOn) + t("  (settings)", "  (إعدادات)"),
            t("⬆  Check for updates  (version ", "⬆  البحث عن تحديثات  (الإصدار ") + (try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }) + ")",
            t("About Sahl TV", "حول سهل TV")
        )
        dialog().setTitle(t("Launcher", "الواجهة")).setItems(items) { _, which ->
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
                16 -> { prefs.edit().putBoolean("updReady", false).apply(); updBtn.visibility = View.GONE; Updater.check(this, ar) }
                17 -> dialog().setTitle("Sahl TV  ·  سهل")
                    .setMessage(t("A fast, ad-free home screen for Android TV.\n\nNo tracking, no background services. Internet is used only to download preview pictures when you turn App previews on.\n\nPrayer times are calculated on the TV (Qatar method: Fajr 18°, Asr Shafi'i, Isha 90 min after Maghrib). Please compare with the official Awqaf timetable.\n\nTip: hold OK on an app (or press Menu) for options.\n\nFree & open source (MIT).\nContact: alysherif1309@gmail.com",
                                  "واجهة رئيسية سريعة وبلا إعلانات لأجهزة Android TV.\n\nبلا تتبّع وبلا خدمات تعمل في الخلفية. يُستخدم الإنترنت فقط لتنزيل صور المعاينات عند تفعيل «معاينات التطبيقات».\n\nتُحسب مواقيت الصلاة على التلفاز (طريقة قطر: الفجر 18°، العصر شافعي، العشاء بعد المغرب بـ 90 دقيقة). يُرجى مقارنتها بتقويم الأوقاف الرسمي.\n\nنصيحة: اضغط مطولًا على OK فوق تطبيق (أو زر القائمة) لعرض الخيارات.\n\nمجاني ومفتوح المصدر (MIT).\nللتواصل: alysherif1309@gmail.com"))
                    .setPositiveButton(t("OK", "حسنًا"), null).show()
            }
        }.show()
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
            v.animate().scaleX(if (has) 1.02f else 1f).scaleY(if (has) 1.02f else 1f).setInterpolator(ease).setDuration(if (calm) 220 else 150).start()
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
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0); clipChildren = false; clipToPadding = false }

        // row 1: next prayer + countdown, then the five prayers
        prayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        val hero = card(true) { prayerDialog() }
        val a = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        a.addView(iconLabel(GoldIcon.MOSQUE, t("Next prayer", "الصلاة القادمة")))
        nextNameTv = TextView(this).apply { textSize = 26f; setTextColor(TEXT); setTypeface(typeface, Typeface.BOLD); maxLines = 1 }
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
        for (i in 0 until 5) {
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            cell.addView(GoldIcon(this, i, GOLD), LinearLayout.LayoutParams(dp(20), dp(20)).apply { bottomMargin = dp(2) })
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
        ramTitle = TextView(this).apply { textSize = 17f; setTextColor(TEXT); setTypeface(typeface, Typeface.BOLD); maxLines = 1 }
        ramSub = TextView(this).apply { textSize = 14f; setTextColor(GOLD); maxLines = 1; fontFeatureSettings = "tnum" }
        rc.addView(ramTitle); rc.addView(ramSub)
        ramCard.addView(rc, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row2.addView(ramCard, LinearLayout.LayoutParams(0, dp(64), 1f))

        fastCard = card(false) { fastDialog() }
        fastCard.addView(GoldIcon(this, GoldIcon.DATES, GOLD), LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(14) })
        val fc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fastTitle = TextView(this).apply { textSize = 17f; setTextColor(TEXT); setTypeface(typeface, Typeface.BOLD); maxLines = 1 }
        fastSub = TextView(this).apply { textSize = 14f; setTextColor(MUTED); maxLines = 1 }
        fc.addView(fastTitle); fc.addView(fastSub)
        fastCard.addView(fc, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row2.addView(fastCard, LinearLayout.LayoutParams(0, dp(64), 1f).apply { marginStart = dp(12) })
        col.addView(row2)
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
            val names = prayerNames(today.dayOfWeek == DayOfWeek.FRIDAY)
            val hours = listOf(tt.fajr, tt.dhuhr, tt.asr, tt.maghrib, tt.isha)
            var nextI = hours.indexOfFirst { epochAt(today, it) > now }
            if (nextI < 0) { nextI = 0; nextAt = epochAt(today.plusDays(1), tm.fajr); nextNameTv.text = t("Fajr", "الفجر") }
            else { nextAt = epochAt(today, hours[nextI]); nextNameTv.text = names[nextI] }
            for (i in 0 until 5) {
                cellNames[i].text = names[i]
                cellTimes[i].text = Prayer.hm(if (i == 0 && epochAt(today, tt.isha) < now) tm.fajr else hours[i])
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
        // calm mode: soften the whole home screen from an hour after Isha until Fajr
        val night = if (tt != null) now > epochAt(today, tt.isha) + 3_600_000L || now < epochAt(today, tt.fajr)
                    else java.time.LocalTime.now().let { it.hour >= 21 || it.hour < 5 }
        dimView.animate().alpha(if (calm && night) 0.35f else 0f).setDuration(1500).start()
        panel.visibility = if (prayRow.visibility == View.GONE && ramCard.visibility == View.GONE && fastCard.visibility == View.GONE) View.GONE else View.VISIBLE
        tickCountdowns()
    }

    private fun tickCountdowns() {
        if (!::panel.isInitialized) return
        val now = System.currentTimeMillis()
        if ((nextAt in 1..now) || (ramAt in 1..now)) { updateClock(); return }
        if (nextAt > 0) {
            val left = nextAt - now
            nextCountTv.text = fmtLeft(left)
        }
        if (ramAt > 0) ramSub.text = "$ramLabel  ${fmtLeft(ramAt - now)}"
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
                memBtn.text = t("🧹  Auto-freed $freed MB", "🧹  تم تحرير $freed ميغابايت تلقائيًا")
                handler.postDelayed({ memBtn.text = t("🧹  Free memory", "🧹  تحرير الذاكرة") }, 4000)
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
        memBtn.text = t("🧹  Cleaning…", "🧹  جارٍ التنظيف…")
        worker.execute {
            for (pkg in apps.keys) try { am.killBackgroundProcesses(pkg) } catch (_: Exception) {}
            Thread.sleep(600)
            val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val freed = ((info.availMem - before) / (1024 * 1024)).coerceAtLeast(0)
            val freeMb = info.availMem / (1024 * 1024)
            val totalMb = info.totalMem / (1024 * 1024)
            handler.post {
                memBtn.text = t("🧹  Free memory", "🧹  تحرير الذاكرة")
                toast(t("Freed $freed MB  ·  $freeMb MB of $totalMb MB now free", "تم تحرير $freed ميغابايت  ·  المتاح الآن $freeMb من $totalMb ميغابايت"))
            }
        }
    }
}
