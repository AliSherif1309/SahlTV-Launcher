package com.aly.sahlquran

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Sahl Quran — read the Quran on the TV with the remote.
 *  ◀ / ▶ turn pages (right-to-left, like a printed Mushaf) · ▲ / ▼ scroll a long page · OK or Menu: options.
 * Pages are laid out to fit the screen at the chosen text size; each surah starts on a new page.
 */
class QuranActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("quran", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var data: QuranData? = null

    // position: surah + index of the page inside that surah (pages of a surah are computed on demand)
    private var surah = 1
    private var pageIdx = 0
    /** pages per surah for the current size/screen: each page = (first ayah, last ayah) */
    private val pageCache = HashMap<Int, List<IntArray>>()
    private var cacheKey = ""

    // views
    private lateinit var root: LinearLayout
    private lateinit var frame: FrameLayout
    private lateinit var bar: LinearLayout
    private lateinit var spread: LinearLayout
    private lateinit var leftPage: MushafPage
    private lateinit var rightPage: MushafPage
    private lateinit var timerTv: TextView
    /** Right-hand page of the open spread (odd: 1, 3, 5 … 603). */
    private var mPage = 1
    /** "mushaf" = two printed pages side by side, "text" = large flowing text. */
    private val view get() = prefs.getString("view", "mushaf") ?: "mushaf"
    private val mushaf get() = view == "mushaf" && data?.pageStarts?.size == 604
    private lateinit var topTv: TextView
    private lateinit var hintTv: TextView
    private lateinit var scroll: ScrollView
    private lateinit var textTv: TextView
    private var amiri: Typeface? = null

    // settings
    private val sizes = floatArrayOf(26f, 30f, 34f, 38f, 44f)
    private val sizeIdx get() = prefs.getInt("size", 2).coerceIn(0, sizes.size - 1)
    private val theme get() = prefs.getString("theme", "sepia") ?: "sepia"
    private val ar: Boolean by lazy {
        val saved = prefs.getString("lang", null)
        if (saved != null) saved == "ar" else resources.configuration.locales[0].language == "ar"
    }
    private fun t(en: String, arabic: String) = if (ar) arabic else en

    private val bg get() = when (theme) { "light" -> "#FBF6E9"; "sepia" -> "#F1E4C6"; else -> "#0F1714" }
    private val ink get() = when (theme) { "light" -> "#1F1A12"; "sepia" -> "#3A2C18"; else -> "#ECE6D6" }
    private val accent get() = when (theme) { "dark" -> "#D4B15A"; else -> "#8A6A1C" }
    private val muted get() = when (theme) { "dark" -> "#7F8C86"; else -> "#8C7F66" }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ setup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        amiri = try { Typeface.createFromAsset(assets, "AmiriQuran.ttf") } catch (_: Exception) { null }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(20), dp(56), dp(14))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        topTv = TextView(this).apply { textSize = 15f }
        hintTv = TextView(this).apply { textSize = 13f }
        bar.addView(topTv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(hintTv)
        root.addView(bar)
        textTv = TextView(this).apply {
            textDirection = View.TEXT_DIRECTION_RTL
            setLineSpacing(0f, 1.55f)
            if (android.os.Build.VERSION.SDK_INT >= 26) justificationMode = Layout.JUSTIFICATION_MODE_INTER_WORD
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; isFocusable = true; isFocusableInTouchMode = true
            addView(textTv, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })
        // the open Mushaf: left page (even) and right page (odd), like a printed copy
        spread = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_LTR
            isFocusable = true; isFocusableInTouchMode = true; visibility = View.GONE }
        leftPage = MushafPage(this, amiri).apply { rightSide = false }
        rightPage = MushafPage(this, amiri).apply { rightSide = true }
        spread.addView(leftPage, LinearLayout.LayoutParams(0, MATCH_PARENT))
        spread.addView(rightPage, LinearLayout.LayoutParams(0, MATCH_PARENT))
        root.addView(spread, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        frame = FrameLayout(this)
        frame.addView(root, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // reading timer: a small, quiet chip at the top
        timerTv = TextView(this).apply {
            textSize = 14f; setPadding(dp(14), dp(5), dp(14), dp(5)); visibility = View.GONE
            background = GradientDrawable().apply { setColor(Color.parseColor("#CC101814")); cornerRadius = dp(14).toFloat(); setStroke(dp(1), Color.parseColor("#80C9A54F")) }
            setTextColor(Color.parseColor("#EDE3C8"))
        }
        frame.addView(timerTv, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(4) })
        setContentView(frame)
        applyTheme()

        // opened from the launcher's "Ayah of the day": go straight to that verse
        val askedSurah = intent?.getIntExtra("surah", 0) ?: 0
        surah = (if (askedSurah in 1..114) askedSurah else prefs.getInt("s", 1)).coerceIn(1, 114)
        val startAyah = if (askedSurah in 1..114) (intent?.getIntExtra("ayah", 1) ?: 1) else prefs.getInt("a", 1)
        textTv.text = t("Loading…", "جارٍ التحميل…")
        worker.execute {
            val d = QuranData.load(this)
            handler.post {
                data = d
                if (d == null) {
                    textTv.text = t("The Quran text isn't included in this build. Please install the version from the project's GitHub release.",
                                    "نص القرآن غير مضمَّن في هذه النسخة. يُرجى تثبيت النسخة من صفحة الإصدارات على GitHub.")
                    return@post
                }
                highlight = if (askedSurah in 1..114) askedSurah to startAyah else null
                mPage = prefs.getInt("mp", 0).takeIf { it in 1..604 && askedSurah !in 1..114 } ?: d.pageOf(surah, startAyah).coerceAtLeast(1)
                applyView()
                if (mushaf) spread.post { sizePages(); showSpread() } else scroll.post { goTo(surah, startAyah) }
                if (prefs.getBoolean("firstMushaf", true) && mushaf) { prefs.edit().putBoolean("firstMushaf", false).apply()
                    toast(t("◀ next page   ▶ previous page   OK: menu", "◀ الصفحة التالية   ▶ السابقة   OK: القائمة")) }
            }
        }
    }

    private var highlight: Pair<Int, Int>? = null
    private var styleTheme = ""

    /** Shows either the Mushaf spread or the large-text reader. */
    private fun applyView() {
        val m = mushaf
        spread.visibility = if (m) View.VISIBLE else View.GONE
        scroll.visibility = if (m) View.GONE else View.VISIBLE
        bar.visibility = if (m) View.GONE else View.VISIBLE
        root.setPadding(if (m) dp(16) else dp(56), if (m) dp(10) else dp(20), if (m) dp(16) else dp(56), if (m) dp(10) else dp(14))
        applyTheme()
        if (m) spread.requestFocus()
    }

    /** Page size like a printed Mushaf (about 2 : 3), as tall as the screen allows. */
    private fun sizePages() {
        val h = spread.height; if (h <= 0) return
        val w = minOf((h * 0.70f).toInt(), spread.width / 2)
        for (pg in listOf(leftPage, rightPage)) pg.layoutParams = LinearLayout.LayoutParams(w, h)
        spread.requestLayout()
    }

    private fun showSpread() {
        val d = data ?: return
        mPage = mPage.coerceIn(1, 604)
        if (mPage % 2 == 0) mPage -= 1
        rightPage.data = d; leftPage.data = d
        if (styleTheme != theme) { styleTheme = theme; val st = MushafStyle.of(theme); rightPage.style = st; leftPage.style = st }
        rightPage.highlight = highlight; leftPage.highlight = highlight
        rightPage.page = mPage
        leftPage.page = if (mPage + 1 <= 604) mPage + 1 else 0
        leftPage.visibility = if (mPage + 1 <= 604) View.VISIBLE else View.INVISIBLE
        val first = d.pageAyahs(mPage).firstOrNull() ?: (1 to 1)
        surah = first.first
        prefs.edit().putInt("mp", mPage).putInt("s", first.first).putInt("a", first.second).apply()
    }

    private fun applyTheme() {
        val bgCol = if (mushaf) MushafStyle.of(theme).around else Color.parseColor(bg)
        root.setBackgroundColor(bgCol)
        window.decorView.setBackgroundColor(bgCol)
        textTv.setTextColor(Color.parseColor(ink))
        textTv.textSize = sizes[sizeIdx]
        amiri?.let { textTv.typeface = it }
        topTv.setTextColor(Color.parseColor(accent))
        hintTv.setTextColor(Color.parseColor(muted))
        hintTv.text = t("◀ ▶ pages   ·   OK: menu", "◀ ▶ الصفحات   ·   OK: القائمة")
    }

    // ------------------------------------------------------------------ pagination

    private fun basmala(d: QuranData) = d.ayahs[0][0]

    /** Ayah text as shown: for ayah 1 of surahs that carry the basmala inside the text, the basmala is shown as the header instead. */
    private fun shownAyah(d: QuranData, s: Int, a: Int): String {
        val txt = d.ayahs[s - 1][a - 1]
        if (a == 1 && s != 1 && s != 9) {
            val b = basmala(d)
            if (txt.startsWith(b)) return txt.substring(b.length).trimStart()
        }
        return txt
    }

    private fun arabicNum(n: Int) = n.toString().map { "٠١٢٣٤٥٦٧٨٩"[it - '0'] }.joinToString("")

    /** Builds the styled text of a page (surah header + basmala when the page starts the surah). */
    private fun pageText(d: QuranData, s: Int, from: Int, to: Int): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        val gold = Color.parseColor(accent)
        if (from == 1) {
            val h0 = sb.length
            sb.append("سُورَةُ ").append(QuranData.NAMES_AR[s - 1]).append("\n")
            sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), h0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(gold), h0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.9f), h0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (s != 1 && s != 9) {
                val b0 = sb.length
                sb.append(basmala(d)).append("\n")
                sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), b0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        for (a in from..to) {
            sb.append(shownAyah(d, s, a))
            val n0 = sb.length
            sb.append(" ﴿").append(arabicNum(a)).append("﴾ ")
            sb.setSpan(ForegroundColorSpan(gold), n0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun measureHeight(text: CharSequence, width: Int): Int {
        val paint = TextPaint(textTv.paint)
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setLineSpacing(0f, 1.55f).build().height
    }

    /** Splits a surah into pages that fit the reading area. */
    private fun pagesOf(s: Int): List<IntArray> {
        val d = data ?: return listOf(intArrayOf(1, 1))
        val w = scroll.width - scroll.paddingLeft - scroll.paddingRight
        val h = scroll.height
        val key = "${sizeIdx}x${w}x$h"
        if (key != cacheKey) { pageCache.clear(); cacheKey = key }
        pageCache[s]?.let { return it }
        val pages = ArrayList<IntArray>()
        val n = d.ayahCount(s)
        var a = 1
        while (a <= n) {
            var end = a
            // grow the page while it still fits (a single long ayah always gets its own page and can scroll)
            while (end < n && measureHeight(pageText(d, s, a, end + 1), w.coerceAtLeast(100)) <= h - dp(8)) end++
            pages.add(intArrayOf(a, end))
            a = end + 1
        }
        pageCache[s] = pages
        return pages
    }

    private fun goTo(s: Int, a: Int) {
        val d = data
        if (mushaf && d != null) { mPage = d.pageOf(s.coerceIn(1, 114), a).coerceAtLeast(1); showSpread(); return }
        surah = s.coerceIn(1, 114)
        val pages = pagesOf(surah)
        pageIdx = pages.indexOfFirst { a in it[0]..it[1] }.coerceAtLeast(0)
        show()
    }

    private fun show() {
        val d = data ?: return
        val pages = pagesOf(surah)
        pageIdx = pageIdx.coerceIn(0, pages.size - 1)
        val (from, to) = pages[pageIdx].let { it[0] to it[1] }
        textTv.text = pageText(d, surah, from, to)
        scroll.scrollTo(0, 0)
        scroll.requestFocus()
        val juz = d.juzOf(surah, from)
        val mp = d.pageOf(surah, from)
        val name = if (ar) "سورة ${QuranData.NAMES_AR[surah - 1]}" else "${surah}. ${QuranData.NAMES_EN[surah - 1]}"
        topTv.text = name + "   ·   " + t("Juz' $juz", "الجزء ${arabicNum(juz)}") +
            (if (mp > 0) "   ·   " + t("Mushaf page $mp", "صفحة المصحف ${arabicNum(mp)}") else "") +
            "   ·   " + t("${pageIdx + 1}/${pages.size}", "${arabicNum(pageIdx + 1)}/${arabicNum(pages.size)}")
        prefs.edit().putInt("s", surah).putInt("a", from).apply()
    }

    private fun nextPage() {
        if (data == null) return
        if (mushaf) {
            if (mPage + 2 > 604) { toast(t("End of the Quran — صدق الله العظيم", "صدق الله العظيم")); return }
            mPage += 2; highlight = null; pagesRead += 2; showSpread(); return
        }
        pagesRead++
        if (pageIdx < pagesOf(surah).size - 1) pageIdx++
        else if (surah < 114) { surah++; pageIdx = 0 } else { toast(t("End of the Quran — صدق الله العظيم", "صدق الله العظيم")); return }
        show()
    }

    private fun prevPage() {
        if (data == null) return
        if (mushaf) { if (mPage > 1) { mPage -= 2; highlight = null; showSpread() }; return }
        if (pageIdx > 0) pageIdx--
        else if (surah > 1) { surah--; pageIdx = pagesOf(surah).size - 1 } else return
        show()
    }

    // ------------------------------------------------------------------ reading timer

    private var timerOn = false
    private var timerStart = 0L
    private var timerAcc = 0L
    private var pagesRead = 0
    private fun timerMs() = timerAcc + if (timerOn && timerStart > 0) System.currentTimeMillis() - timerStart else 0L
    private fun fmt(ms: Long): String { val s = ms / 1000; return if (s >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60) }
    private fun dayKey() = "read_" + java.time.LocalDate.now()
    private fun todayMs() = prefs.getLong(dayKey(), 0L)

    private val tick = object : Runnable {
        override fun run() {
            if (!timerOn) return
            timerTv.text = "⏱  " + fmt(timerMs()) + (if (pagesRead > 0) t("   ·   $pagesRead pages", "   ·   ${arabicNum(pagesRead)} صفحة") else "")
            handler.postDelayed(this, 1000)
        }
    }

    private fun startTimer() {
        timerOn = true; timerAcc = 0L; pagesRead = 0; timerStart = System.currentTimeMillis()
        timerTv.visibility = View.VISIBLE; handler.removeCallbacks(tick); handler.post(tick)
    }

    private fun stopTimer() {
        val ms = timerMs()
        timerOn = false; timerAcc = 0L; handler.removeCallbacks(tick); timerTv.visibility = View.GONE
        val total = todayMs() + ms
        prefs.edit().putLong(dayKey(), total).apply()
        dialog().setTitle(t("May Allah accept it 🤲", "تقبّل الله منك 🤲"))
            .setMessage(t("You read for ${fmt(ms)}" + (if (pagesRead > 0) " · $pagesRead pages" else "") + ".\n\nToday in total: ${total / 60_000} min.",
                          "قرأت لمدة ${fmt(ms)}" + (if (pagesRead > 0) " · ${arabicNum(pagesRead)} صفحة" else "") + ".\n\nمجموع اليوم: ${arabicNum((total / 60_000).toInt())} دقيقة."))
            .setPositiveButton(t("OK", "حسنًا"), null).show()
        pagesRead = 0
    }

    override fun onPause() {
        super.onPause()
        if (timerOn) { timerAcc += System.currentTimeMillis() - timerStart; timerStart = 0L; handler.removeCallbacks(tick) }   // paused while away
    }

    override fun onResume() {
        super.onResume()
        if (timerOn) { timerStart = System.currentTimeMillis(); handler.post(tick) }
    }

    // ------------------------------------------------------------------ remote keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_PAGE_DOWN -> { nextPage(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_PAGE_UP -> { prevPage(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU -> { menu(); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (!mushaf) scroll.smoothScrollBy(0, scroll.height / 3); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { if (!mushaf) scroll.smoothScrollBy(0, -scroll.height / 3); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------ menu

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private val bookmarks: MutableList<Pair<Int, Int>>
        get() = (prefs.getString("bm", "") ?: "").split("|").filter { it.contains(":") }
            .map { it.substringBefore(":").toInt() to it.substringAfter(":").toInt() }.toMutableList()
    private fun saveBookmarks(l: List<Pair<Int, Int>>) = prefs.edit().putString("bm", l.joinToString("|") { "${it.first}:${it.second}" }).apply()

    private fun surahLabel(s: Int) = if (ar) "${arabicNum(s)}. ${QuranData.NAMES_AR[s - 1]}" else "$s. ${QuranData.NAMES_EN[s - 1]}  ·  ${QuranData.NAMES_AR[s - 1]}"

    private fun menu() {
        val d = data ?: return
        val hasPages = d.pageStarts.isNotEmpty()
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add((if (timerOn) t("⏱  Stop reading timer  (${fmt(timerMs())})", "⏱  إيقاف مؤقت القراءة  (${fmt(timerMs())})") else t("⏱  Start reading timer", "⏱  بدء مؤقت القراءة")) to { if (timerOn) stopTimer() else startTimer() })
        items.add(t("📖  Go to surah", "📖  الانتقال إلى سورة") to { surahMenu() })
        items.add(t("🔢  Go to juz'", "🔢  الانتقال إلى جزء") to { juzMenu() })
        if (hasPages) items.add(t("📄  Go to Mushaf page", "📄  الانتقال إلى صفحة المصحف") to { pageMenu() })
        items.add(t("🔖  Bookmark this page", "🔖  إضافة علامة لهذه الصفحة") to {
            if (mushaf) d.pageAyahs(mPage).firstOrNull()?.let { surah = it.first }
            val from = if (mushaf) (d.pageAyahs(mPage).firstOrNull()?.second ?: 1) else pagesOf(surah)[pageIdx][0]
            val l = bookmarks; if (l.none { it.first == surah && it.second == from }) l.add(0, surah to from); saveBookmarks(l.take(30))
            toast(t("Bookmarked", "تمت إضافة العلامة"))
        })
        items.add(t("📑  Bookmarks (${bookmarks.size})", "📑  العلامات (${arabicNum(bookmarks.size)})") to { bookmarksMenu() })
        if (hasPages) items.add((if (mushaf) t("📗  View: Mushaf pages  (switch to large text)", "📗  العرض: صفحات المصحف  (التبديل إلى خط كبير)")
                                 else t("📗  View: Large text  (switch to Mushaf pages)", "📗  العرض: خط كبير  (التبديل إلى صفحات المصحف)")) to {
            val keep = if (mushaf) d.pageAyahs(mPage).firstOrNull() ?: (surah to 1) else (surah to pagesOf(surah)[pageIdx][0])
            prefs.edit().putString("view", if (mushaf) "text" else "mushaf").apply()
            applyView()
            if (mushaf) spread.post { sizePages(); mPage = d.pageOf(keep.first, keep.second); showSpread() } else scroll.post { goTo(keep.first, keep.second) }
        })
        if (!mushaf) items.add(t("🔠  Text size: ${sizeIdx + 1}/5  (change)", "🔠  حجم الخط: ${arabicNum(sizeIdx + 1)}/٥  (تغيير)") to { sizeMenu() })
        items.add(t("🎨  Background: ", "🎨  الخلفية: ") + themeName(theme) + t("  (change)", "  (تغيير)") to {
            val next = when (theme) { "dark" -> "light"; "light" -> "sepia"; else -> "dark" }
            prefs.edit().putString("theme", next).apply(); applyTheme(); if (mushaf) showSpread() else show()
        })
        items.add(t("🌐  Language: English  (العربية)", "🌐  اللغة: العربية  (English)") to {
            prefs.edit().putString("lang", if (ar) "en" else "ar").apply(); recreate()
        })
        items.add(t("⬆  Check for updates", "⬆  البحث عن تحديثات") to { Updater.check(this, ar) })
        items.add(t("ⓘ  About", "ⓘ  حول التطبيق") to { about() })
        val today = todayMs() + (if (timerOn) timerMs() else 0L)
        val title = surahLabel(surah) + (if (mushaf) "  ·  " + t("page $mPage", "صفحة ${arabicNum(mPage)}") else "") +
            (if (today >= 60_000) "  ·  " + t("today ${today / 60_000} min", "اليوم ${arabicNum((today / 60_000).toInt())} د") else "")
        dialog().setTitle(title).setItems(items.map { it.first }.toTypedArray()) { _, w -> items[w].second() }.show()
    }

    private fun themeName(th: String) = when (th) { "light" -> t("Light", "فاتحة"); "sepia" -> t("Sepia", "ورقية"); else -> t("Dark", "داكنة") }

    private fun surahMenu() {
        dialog().setTitle(t("Surah", "السورة")).setItems((1..114).map { surahLabel(it) }.toTypedArray()) { _, w -> goTo(w + 1, 1) }.show()
    }

    private fun juzMenu() {
        val d = data ?: return
        val labels = d.juzStarts.mapIndexed { i, st ->
            t("Juz' ${i + 1}  ·  ${QuranData.NAMES_EN[st.first - 1]} ${st.second}", "الجزء ${arabicNum(i + 1)}  ·  ${QuranData.NAMES_AR[st.first - 1]} ${arabicNum(st.second)}")
        }
        dialog().setTitle(t("Juz'", "الجزء")).setItems(labels.toTypedArray()) { _, w -> d.juzStarts[w].let { goTo(it.first, it.second) } }.show()
    }

    private fun pageMenu() {
        val d = data ?: return
        // pick hundreds first, then the page — easier with a remote than a 604-line list
        val groups = (0 until 7).map { g -> val a = g * 100 + 1; val b = minOf(604, a + 99); a to b }
        dialog().setTitle(t("Mushaf page", "صفحة المصحف")).setItems(groups.map { t("Pages ${it.first}–${it.second}", "الصفحات ${arabicNum(it.first)}–${arabicNum(it.second)}") }.toTypedArray()) { _, g ->
            val (a, b) = groups[g]
            dialog().setTitle(t("Page", "الصفحة")).setItems((a..b).map {
                val st = d.pageStarts[it - 1]; t("Page $it  ·  ${QuranData.NAMES_EN[st.first - 1]} ${st.second}", "صفحة ${arabicNum(it)}  ·  ${QuranData.NAMES_AR[st.first - 1]} ${arabicNum(st.second)}")
            }.toTypedArray()) { _, w -> d.pageStarts[a + w - 1].let { goTo(it.first, it.second) } }.show()
        }.show()
    }

    private fun bookmarksMenu() {
        val l = bookmarks
        if (l.isEmpty()) { toast(t("No bookmarks yet", "لا توجد علامات بعد")); return }
        val labels = l.map { (s, a) -> if (ar) "${QuranData.NAMES_AR[s - 1]} ${arabicNum(a)}" else "${QuranData.NAMES_EN[s - 1]} $s:$a" }.toMutableList()
        labels.add(t("🗑  Clear all bookmarks", "🗑  حذف كل العلامات"))
        dialog().setTitle(t("Bookmarks", "العلامات")).setItems(labels.toTypedArray()) { _, w ->
            if (w < l.size) goTo(l[w].first, l[w].second) else saveBookmarks(emptyList())
        }.show()
    }

    private fun sizeMenu() {
        val labels = sizes.indices.map { t("Size ${it + 1}", "الحجم ${arabicNum(it + 1)}") + (if (it == sizeIdx) "  ✓" else "") }
        dialog().setTitle(t("Text size", "حجم الخط")).setItems(labels.toTypedArray()) { _, w ->
            val from = pagesOf(surah)[pageIdx][0]
            prefs.edit().putInt("size", w).apply()
            applyTheme(); pageCache.clear(); cacheKey = ""
            scroll.post { goTo(surah, from) }
        }.show()
    }

    private fun about() {
        dialog().setTitle(t("Sahl Quran", "سهل · القرآن"))
            .setMessage(t("Read the Quran on your TV.\n\nQuran text: Tanzil Project (tanzil.net) — Uthmani text, copied verbatim, licensed under Creative Commons Attribution 3.0. The text is not modified.\n\nFont: Amiri Quran by Khaled Hosny, SIL Open Font License 1.1.\n\nMushaf view: each page holds exactly the verses of the same page of the Madinah Mushaf (604 pages, page breaks from Tanzil). The lettering is Amiri Quran, so line breaks differ slightly from print.\n\nContact: alysherif1309@gmail.com",
                          "اقرأ القرآن الكريم على التلفاز.\n\nنص القرآن: مشروع تنزيل (tanzil.net) — الرسم العثماني، منقول حرفيًا دون أي تعديل، برخصة المشاع الإبداعي (نسب المصنف 3.0).\n\nالخط: أميري قرآن لخالد حسني، برخصة SIL للخطوط المفتوحة 1.1.\n\nعرض المصحف: كل صفحة تضم نفس آيات الصفحة نفسها في مصحف المدينة المنورة (٦٠٤ صفحات، وفق بيانات تنزيل). الخط هو أميري قرآن، لذا قد يختلف توزيع الأسطر قليلًا عن المطبوع.\n\nللتواصل: alysherif1309@gmail.com"))
            .setPositiveButton(t("OK", "حسنًا"), null).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }
}
