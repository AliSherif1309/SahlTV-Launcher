package com.aly.sahltv

import com.aly.sahl.common.Fonts
import com.aly.sahl.common.FontDialog

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
/*
 * Sahl TV home screen. This file keeps the screen's state (views and settings), the lifecycle and Android callbacks.
 * Each part of the screen lives in its own file:
 *   AppCatalog      finding installed apps, banners, launching, usage counts
 *   HomeRows        Favorites / Recent / All apps rows, tiles and focus      PreviewRows   rows published by other apps
 *   TopBar          clock, dates, quick buttons                              Wallpaper     background photo and dimming
 *   PrayerPanel     prayer times, countdown, Ramadan and fasting cards       Greetings     seasonal greeting card
 *   HomeCards       Ayah of the day, weather and Qatar dates, what's new     Comfort       text size, numbers, night style
 *   SettingsScreen  the Settings screen    Menus  launcher menus             Pin           PIN locks
 *   PhoneRemote     phone remote settings and pairing (server: RemoteServer) NowPlaying    background playback bar
 *   Screensaver     moving clock           Backup  backup and restore        Memory        memory cleaning
 * Shared with the other Sahl apps (fonts, colours, dialogs, network safety): the :common module.
 */
class MainActivity : Activity(), RemoteServer.Host {
    // start-up speed: work that waits until the first frame is on screen
    internal var drawn = false
    private val pending = ArrayList<() -> Unit>()
    internal fun afterFirstFrame(r: () -> Unit) { if (drawn) r() else pending.add(r) }

    // for the split-out parts: whether a view has been built yet
    internal val greetCardReady get() = ::greetCard.isInitialized
    internal val panelReady get() = ::panel.isInitialized
    internal val adhkarCardReady get() = ::adhkarCard.isInitialized
    internal val wxBoxReady get() = ::wxBox.isInitialized

    internal class App(val pkg: String, val label: String, val banner: Drawable?, val icon: Drawable?, val tint: Int)

    internal val prefs by lazy { getSharedPreferences("sahl", MODE_PRIVATE) }

    /** Language chosen in the launcher menu (first start: follows the system language). */
    internal val ar: Boolean by lazy {
        val saved = prefs.getString("lang", null)
        if (saved != null) saved == "ar" else resources.configuration.locales[0].language == "ar"
    }
    internal fun t(en: String, arabic: String) = if (ar) arabic else en

    // app previews (rows that apps publish to the Android TV home screen)
    internal var previewRows: List<Previews.Row> = emptyList()
    internal var lastPreviewLoad = 0L
    internal val previewsOn get() = prefs.getBoolean("pvOn", false)
    internal val previewOff get() = list("pvOff")          // row keys the user switched off
    internal val handler = Handler(Looper.getMainLooper())
    internal val worker = Executors.newSingleThreadExecutor()
    internal var apps: Map<String, App> = emptyMap()
    internal var loading = false
    internal var dirty = true

    // views
    internal lateinit var clockTv: TextView
    internal lateinit var dateTv: TextView
    internal lateinit var hijriTv: TextView
    internal lateinit var content: LinearLayout
    internal lateinit var scroll: ScrollView
    internal lateinit var memBtn: IconBtn
    internal lateinit var prayerTv: TextView
    internal lateinit var rootFrame: FrameLayout
    internal lateinit var nowBar: LinearLayout
    internal lateinit var nowTv: TextView
    internal lateinit var nowPlayBtn: TextView
    internal var saver: FrameLayout? = null
    internal var lastInput = System.currentTimeMillis()
    internal var msm: MediaSessionManager? = null
    internal var controller: MediaController? = null
    internal var mediaAllowed = false
    internal lateinit var settingsBtn: IconBtn
    internal lateinit var updBtn: IconBtn
    internal val homeSections = mutableMapOf<String, HomeSection>()
    internal var lastAutoClean = 0L
    internal val ease = DecelerateInterpolator(1.6f)

    // look
    internal val BG_TOP = Color.parseColor("#0E1A24")
    internal val BG_BOTTOM = Color.parseColor("#10261F")
    internal val TEXT = Color.parseColor("#EEF3F1")
    internal val MUTED = Color.parseColor("#8FA39C")
    internal val ACCENT = Color.parseColor("#3CC88F")
    internal val GOLD = Color.parseColor("#E3B85C")
    /** Islamic theme (night sky, mosque, gold) or the plain classic look. */
    internal val islamic by lazy { prefs.getBoolean("islamic", true) }
    internal val FOCUS get() = if (islamic) GOLD else Color.WHITE

    internal fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ settings stored on the TV

    internal val favorites get() = list("fav")
    internal val hidden get() = list("hidden")
    internal val recent get() = list("recent")
    internal val sortMostUsed get() = prefs.getBoolean("sortUsed", true)
    internal val clock24 get() = prefs.getBoolean("clock24", true)
    internal val autoClean get() = prefs.getBoolean("autoClean", false)   // off by default: measured, closing apps makes them open 5–7× slower next time
    internal val saverMin get() = prefs.getInt("saverMin", 5)          // 0 = off
    internal val nowOn get() = prefs.getBoolean("nowOn", true)
    internal val remoteOn get() = prefs.getBoolean("remoteOn", false)
    internal val hijriAdj get() = prefs.getInt("hijriAdj", 0)
    internal val fastOn get() = prefs.getBoolean("fastOn", true)
    /** Calm mode (for little eyes): minute countdown instead of ticking seconds, softer focus, home screen dims at night. */
    internal val calm by lazy { prefs.getBoolean("calm", true) }
    /** 0 = off, 1 = tell me (gold dot on Updates), 2 = download by itself and install when I'm on the home screen. */
    internal val autoUpd get() = prefs.getInt("autoUpd", 1)
    internal var resumed = false

    // ---- backgrounds: photos come with the app (see tools/fetch_backgrounds.py) and their credits
    internal class Bg(val id: String, val en: String, val ar: String, val author: String, val licence: String)
    internal val backgrounds: List<Bg> by lazy {
        try {
            val a = org.json.JSONArray(assets.open("backgrounds.json").bufferedReader().use { it.readText() })
            (0 until a.length()).map { a.getJSONObject(it).let { o -> Bg(o.getString("id"), o.getString("en"), o.getString("ar"), o.optString("author"), o.optString("licence")) } }
        } catch (_: Exception) { emptyList() }
    }
    internal var bgKey = ""
    internal val bgChoice get() = prefs.getString("bg", "auto") ?: "auto"
    internal val bgDim get() = prefs.getInt("bgDim", 2)

    internal lateinit var dimView: View
    internal val locked get() = list("locked")
    internal val pinSet get() = prefs.getString("pinHash", null) != null
    internal val lockSettings get() = prefs.getBoolean("lockSettings", false)

    // prayer-time locations (Qatar). Index stored in prefs; -1 = prayer times off.
    internal val cities = Alerts.CITIES
    internal val cityIndex get() = prefs.getInt("city", 0)

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diagnostics.onCreate()
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
        Diagnostics.watchFirstFrame(this)
        root.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (drawn) return
                drawn = true
                handler.post { root.viewTreeObserver.removeOnDrawListener(this); val l = ArrayList(pending); pending.clear(); l.forEach { it() } }
            }
        })
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
        if (intent?.action == com.aly.sahl.common.Family.UPDATES) handler.postDelayed({ Updater.check(this, ar) }, 400)
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
        Diagnostics.startFrames(window)
        resumed = true
        if (dirty && !loading) loadApps() else if (apps.isNotEmpty()) render(keepFocus = true)
        // speed: the first frame shows the screen with its time and date; prayer times, Islamic cards,
        // previews, media, alarms and update checks fill in right after it is on screen
        updateClock(full = drawn)
        afterFirstFrame {
            if (!resumed) return@afterFirstFrame
            updateClock()
            handler.removeCallbacks(clockTick); handler.removeCallbacks(secondTick)
            handler.post(clockTick)
            handler.post(secondTick)
            maybeAutoClean()
            Alerts.schedule(this)
            autoUpdateTick()
            refreshPreviews(force = false)
            setupMedia()
        }
        lastInput = System.currentTimeMillis()
        handler.removeCallbacks(saverTick); handler.postDelayed(saverTick, 15_000)
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        Diagnostics.stopFrames(window)
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
        if (intent?.action == com.aly.sahl.common.Family.UPDATES) handler.post { Updater.check(this, ar) }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // a home screen has nowhere to go back to: just return to the top
        scroll.smoothScrollTo(0, 0)
        focusFirstTile()
    }

    internal val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { dirty = true; loadApps() }
    }

    // ------------------------------------------------------------------ top bar: clock, dates, quick buttons

    internal val clockTick = object : Runnable {
        override fun run() { updateClock(); handler.postDelayed(this, 20_000) }
    }

    // ------------------------------------------------------------------ apps

    // ------------------------------------------------------------------ screen

    internal var focusPkg: String? = null
    internal var focusRow: String? = null

    // ------------------------------------------------------------------ app previews

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

    internal val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { handler.post { updateNowBar() } }
        override fun onMetadataChanged(metadata: MediaMetadata?) { handler.post { updateNowBar() } }
        override fun onSessionDestroyed() { handler.post { pickController(null) } }
    }

    internal val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        handler.post { pickController(list) }
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

    internal val saverTick = object : Runnable {
        override fun run() {
            val min = saverMin
            if (min > 0 && saver == null && !isPlaying() && System.currentTimeMillis() - lastInput > min * 60_000L) showSaver()
            saver?.let { moveSaver(it) }
            handler.postDelayed(this, if (saver != null) 30_000 else 15_000)
        }
    }

    // ------------------------------------------------------------------ backup & restore

    internal val backupName = "SahlTV-backup.json"

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

    internal var pairDlg: android.app.AlertDialog? = null

    override fun paired(name: String) {
        pairDlg?.dismiss()
        toast(t("📱 Phone paired: ", "📱 تم اقتران هاتف: ") + name)
    }

    // ------------------------------------------------------------------ menus

    internal val citiesAr = listOf("الدوحة", "الوكرة", "الريان", "لوسيل", "أم صلال", "الخور", "مسيعيد", "دخان", "مدينة الشمال")

    // ------------------------------------------------------------------ Islamic panel: prayer countdown, Ramadan, Sunnah fasts

    internal lateinit var panel: LinearLayout
    internal lateinit var prayRow: LinearLayout
    internal lateinit var nextNameTv: TextView
    internal lateinit var nextCountTv: TextView
    internal val cellBoxes = ArrayList<LinearLayout>()
    internal val cellNames = ArrayList<TextView>()
    internal val cellTimes = ArrayList<TextView>()
    internal lateinit var ramCard: LinearLayout
    internal lateinit var ramTitle: TextView
    internal lateinit var ramSub: TextView
    internal lateinit var fastCard: LinearLayout
    internal lateinit var adhkarCard: LinearLayout
    internal lateinit var adhkarTitle: TextView
    internal lateinit var fastTitle: TextView
    internal lateinit var fastSub: TextView
    internal lateinit var nextLabelTv: TextView
    internal lateinit var ayahCard: LinearLayout
    internal lateinit var ayahTv: TextView
    internal lateinit var ayahRef: TextView
    internal lateinit var newsCard: LinearLayout
    internal lateinit var greetCard: LinearLayout
    internal lateinit var greetIconBox: FrameLayout
    internal lateinit var greetTitle: TextView
    internal lateinit var greetSub: TextView
    internal var greetPreview: String? = null
    internal var greetPreviewUntil = 0L
    internal var greetShown: String? = null
    internal lateinit var newsSub: TextView
    internal var nextAt = 0L
    internal var ramAt = 0L
    internal var ramLabel = ""
    internal var fastLine = ""
    internal var ramCacheKey = ""
    internal var ramCache: LocalDate? = null

    internal val secondTick = object : Runnable {
        override fun run() { tickCountdowns(); handler.postDelayed(this, 1000 - System.currentTimeMillis() % 1000) }
    }

    // ------------------------------------------------------------------ Settings screen

    internal var settingsView: FrameLayout? = null
    internal var settingsList: LinearLayout? = null
    internal var settingsFocus = -1

    internal class SRow(val icon: Int, val title: String, val desc: String, val value: String, val action: Int)

    internal var slim: Boolean? = null

    // ------------------------------------------------------------------ seasonal greeting card

    internal val greetOn get() = prefs.getBoolean("greetOn", true)

    // ------------------------------------------------------------------ text size (shared with all Sahl apps)

    internal val textScale get() = prefs.getFloat("textScale", 1f)
    /** Numbers as 123 or ١٢٣ (Thmanyah's Arabic numerals) for the clock, prayer times and countdown. */
    internal val arDigits get() = prefs.getBoolean("arDigits", false)
    internal val nightStyle get() = prefs.getBoolean("nightStyle", true)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && settingsView != null) fillSettings()      // a dialog closed: show the new values
    }

    // ------------------------------------------------------------------ Ayah of the day, weather & Qatar, what's new

    internal val quranFont: Typeface? by lazy { try { Typeface.createFromAsset(assets, "AmiriQuran.ttf") } catch (_: Exception) { null } }
    internal val ayat: org.json.JSONArray? by lazy { try { org.json.JSONArray(assets.open("ayat.json").bufferedReader().use { it.readText() }) } catch (_: Exception) { null } }
    internal val whatsNew: org.json.JSONObject? by lazy { try { org.json.JSONObject(assets.open("whatsnew.json").bufferedReader().use { it.readText() }) } catch (_: Exception) { null } }
    internal val ayahOn get() = prefs.getBoolean("ayahOn", true)
    internal val wxOn get() = prefs.getBoolean("wxOn", true)
    internal var ayahToday: org.json.JSONObject? = null

    // weather (Open-Meteo, no account needed) is fetched in the background at most every 45 minutes and kept, so Home never waits for it
    internal lateinit var wxBox: LinearLayout
    internal lateinit var wxIcon: GoldIcon
    internal lateinit var wxTemp: TextView
    internal lateinit var wxLine: TextView
    internal lateinit var qaLine: TextView
    internal var qaKey = ""
    internal var qaText = ""

    // ------------------------------------------------------------------ PIN

    // ------------------------------------------------------------------ auto clean

    // ------------------------------------------------------------------ free memory

}
