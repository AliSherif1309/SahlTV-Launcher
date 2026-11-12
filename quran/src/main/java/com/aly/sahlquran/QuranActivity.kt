package com.aly.sahlquran

import com.aly.sahl.common.Fonts
import com.aly.sahl.common.FontDialog

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
    private lateinit var noteTv: TextView
    private lateinit var leftImg: android.widget.ImageView
    private lateinit var rightImg: android.widget.ImageView
    private lateinit var zoomScroll: ScrollView
    private lateinit var zoomPageView: MushafPage
    private lateinit var zoomImg: android.widget.ImageView
    /** One large page you can scroll (▲ ▼), instead of the two-page spread. */
    private var zoom = false
    private var zoomPage = 1
    /** Lining up your PDF: which PDF page shows Al-Fatihah. */
    private var aligning = false
    private var alignIdx = 0

    // your own Mushaf PDF (sent from the phone)
    private var pdf: PdfMushaf? = null
    private val usePdf get() = pdf != null && prefs.getString("src", "pdf") == "pdf"
    private val pdfOffset get() = prefs.getInt("pdfOffset", -1)

    // readers: each family member keeps their own page, bookmarks, timer totals and goal
    private val reader get() = prefs.getInt("reader", 0).coerceIn(0, 3)
    private fun k(name: String) = if (reader == 0) name else "r${reader}_$name"
    private fun readerName(i: Int = reader) = prefs.getString("rname_$i", null) ?: t("Reader ${i + 1}", "القارئ ${arabicNum(i + 1)}")
    private val pure get() = prefs.getBoolean("pure", false)
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
    /** The paper you chose; [theme] is what shows now (dim "night paper" from 7 pm to 5 am when that's on). */
    private val themePref get() = prefs.getString("theme", "sepia") ?: "sepia"
    private val autoNight get() = prefs.getBoolean("autoNight", true)
    private val theme: String get() {
        val h = java.time.LocalTime.now().hour
        return if (autoNight && themePref != "dark" && (h >= 19 || h < 5)) "night" else themePref
    }
    private val ar: Boolean by lazy {
        val saved = prefs.getString("lang", null)
        if (saved != null) saved == "ar" else resources.configuration.locales[0].language == "ar"
    }
    private fun t(en: String, arabic: String) = if (ar) arabic else en

    private val bg get() = when (theme) { "light" -> "#FBF6E9"; "sepia" -> "#F1E4C6"; "night" -> "#C9BD9E"; else -> "#0F1714" }
    private val ink get() = when (theme) { "light" -> "#1F1A12"; "sepia" -> "#3A2C18"; "night" -> "#1E170D"; else -> "#ECE6D6" }
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
        leftImg = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
        rightImg = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
        spread.addView(leftPage, LinearLayout.LayoutParams(0, MATCH_PARENT))
        spread.addView(leftImg, LinearLayout.LayoutParams(0, MATCH_PARENT))
        spread.addView(rightPage, LinearLayout.LayoutParams(0, MATCH_PARENT))
        spread.addView(rightImg, LinearLayout.LayoutParams(0, MATCH_PARENT))
        root.addView(spread, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        // zoom: one big page that scrolls (also used to line up your PDF)
        zoomScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFocusable = true; visibility = View.GONE }
        val zb = FrameLayout(this)
        zoomPageView = MushafPage(this, amiri).apply { rightSide = true }
        zoomImg = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
        zb.addView(zoomPageView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
        zb.addView(zoomImg, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        zoomScroll.addView(zb, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(zoomScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        frame = FrameLayout(this)
        frame.addView(root, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // reading timer: a small, quiet chip at the top
        timerTv = TextView(this).apply {
            textSize = 14f; setPadding(dp(14), dp(5), dp(14), dp(5)); visibility = View.GONE
            background = GradientDrawable().apply { setColor(Color.parseColor("#CC101814")); cornerRadius = dp(14).toFloat(); setStroke(dp(1), Color.parseColor("#80C9A54F")) }
            setTextColor(Color.parseColor("#EDE3C8"))
        }
        frame.addView(timerTv, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(4) })
        // small notes at the bottom (sajdah on this page, lining up your PDF)
        noteTv = TextView(this).apply {
            textSize = 14f; setPadding(dp(14), dp(5), dp(14), dp(5)); visibility = View.GONE; setTextColor(Color.parseColor("#EDE3C8"))
            background = GradientDrawable().apply { setColor(Color.parseColor("#CC101814")); cornerRadius = dp(14).toFloat(); setStroke(dp(1), Color.parseColor("#80C9A54F")) }
        }
        frame.addView(noteTv, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(6) })
        setContentView(frame)
        applyTheme()

        // opened from the launcher's "Ayah of the day": go straight to that verse
        val askedSurah = intent?.getIntExtra("surah", 0) ?: 0
        surah = (if (askedSurah in 1..114) askedSurah else prefs.getInt(k("s"), 1)).coerceIn(1, 114)
        val startAyah = if (askedSurah in 1..114) (intent?.getIntExtra("ayah", 1) ?: 1) else prefs.getInt(k("a"), 1)
        textTv.text = t("Loading…", "جارٍ التحميل…")
        worker.execute {
            val d = QuranData.load(this)
            val f = PdfMushaf.file(this)
            val opened = if (f.exists()) PdfMushaf.open(f) else null
            handler.post {
                pdf = opened
                data = d
                if (d == null) {
                    textTv.text = t("The Quran text isn't included in this build. Please install the version from the project's GitHub release.",
                                    "نص القرآن غير مضمَّن في هذه النسخة. يُرجى تثبيت النسخة من صفحة الإصدارات على GitHub.")
                    return@post
                }
                highlight = if (askedSurah in 1..114) askedSurah to startAyah else null
                mPage = prefs.getInt(k("mp"), 0).takeIf { it in 1..604 && askedSurah !in 1..114 } ?: d.pageOf(surah, startAyah).coerceAtLeast(1)
                if (prefs.getBoolean(k("autoTimer"), false)) startTimer()
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
        spread.visibility = if (m && !zoom && !aligning) View.VISIBLE else View.GONE
        zoomScroll.visibility = if (m && (zoom || aligning)) View.VISIBLE else View.GONE
        scroll.visibility = if (m) View.GONE else View.VISIBLE
        bar.visibility = if (m) View.GONE else View.VISIBLE
        root.setPadding(if (m) dp(16) else dp(56), if (m) dp(10) else dp(20), if (m) dp(16) else dp(56), if (m) dp(10) else dp(14))
        applyTheme()
        if (m) (if (zoom || aligning) zoomScroll else spread).requestFocus()
    }

    private var pageW = 0
    private var pageH = 0

    /** Page size like a printed Mushaf (about 2 : 3), as tall as the screen allows. */
    private fun sizePages() {
        val h = spread.height.takeIf { it > 0 } ?: zoomScroll.height; if (h <= 0) return
        val w = minOf((h * 0.70f).toInt(), (spread.width.takeIf { it > 0 } ?: root.width) / 2)
        pageW = w; pageH = h
        for (pg in listOf(leftPage, rightPage, leftImg, rightImg)) pg.layoutParams = LinearLayout.LayoutParams(w, h)
        spread.requestLayout()
    }

    /** Draws one Mushaf page from your PDF into an ImageView, off the main thread. */
    private fun pdfInto(img: android.widget.ImageView, page: Int, w: Int, h: Int) {
        val doc = pdf ?: return
        val idx = pdfOffset.coerceAtLeast(0) + page - 1
        img.tag = idx
        worker.execute {
            val bmp = doc.render(idx, w, h)
            handler.post { if (img.tag == idx) { img.setImageBitmap(bmp); img.colorFilter = PdfMushaf.filter(theme) } }
        }
    }

    private fun prefetch(page: Int) {
        val doc = pdf ?: return
        if (!usePdf || pageW <= 0 || page > 604) return
        val idx = pdfOffset.coerceAtLeast(0) + page - 1
        worker.execute { doc.render(idx, pageW, pageH) }
    }

    private fun showSpread() {
        val d = data ?: return
        if (zoom) { showZoom(); return }
        mPage = mPage.coerceIn(1, 604)
        if (mPage % 2 == 0) mPage -= 1
        val left = if (mPage + 1 <= 604) mPage + 1 else 0
        val p = usePdf && pdfOffset >= 0
        leftPage.visibility = if (!p && left > 0) View.VISIBLE else if (!p) View.INVISIBLE else View.GONE
        rightPage.visibility = if (!p) View.VISIBLE else View.GONE
        leftImg.visibility = if (p) (if (left > 0) View.VISIBLE else View.INVISIBLE) else View.GONE
        rightImg.visibility = if (p) View.VISIBLE else View.GONE
        if (p) {
            if (pageW <= 0) sizePages()
            pdfInto(rightImg, mPage, pageW, pageH)
            if (left > 0) pdfInto(leftImg, left, pageW, pageH) else leftImg.setImageDrawable(null)
            prefetch(mPage + 2); prefetch(mPage + 3)
        } else {
            rightPage.data = d; leftPage.data = d
            if (styleTheme != theme) { styleTheme = theme; val st = MushafStyle.of(theme); rightPage.style = st; leftPage.style = st }
            rightPage.highlight = highlight; leftPage.highlight = highlight
            rightPage.page = mPage
            leftPage.page = left
        }
        savePos(mPage)
        updateNote(listOf(mPage, left).filter { it > 0 })
    }

    private fun savePos(page: Int) {
        val d = data ?: return
        val first = d.pageAyahs(page).firstOrNull() ?: (1 to 1)
        surah = first.first
        prefs.edit().putInt(k("mp"), page).putInt(k("s"), first.first).putInt(k("a"), first.second).apply()
    }

    /** Sajdah reminder at the bottom when a verse of prostration is on the open pages. */
    private fun updateNote(pages: List<Int>) {
        val d = data ?: return
        if (aligning) return
        val sj = pages.flatMap { d.sajdasOn(it) }
        if (sj.isEmpty() || pure) { noteTv.visibility = View.GONE; return }
        noteTv.text = "۩  " + sj.joinToString("، ") { (s, a) -> t("Sajdah: ${QuranData.NAMES_EN[s - 1]} $a", "سجدة تلاوة: ${QuranData.NAMES_AR[s - 1]} ${arabicNum(a)}") }
        noteTv.visibility = View.VISIBLE
    }

    // ---- zoom: one page, larger, scroll with ▲ ▼
    private fun enterZoom() {
        if (!mushaf) return
        zoom = true; zoomPage = mPage; applyView(); zoomScroll.post { showZoom() }
    }

    private fun exitZoom() {
        zoom = false; mPage = if (zoomPage % 2 == 0) zoomPage - 1 else zoomPage
        applyView(); spread.post { sizePages(); showSpread() }
    }

    private fun showZoom() {
        val d = data ?: return
        zoomPage = zoomPage.coerceIn(1, 604)
        val w = (root.width * 0.62f).toInt().coerceAtLeast(dp(300)); val h = (w / 0.70f).toInt()
        val p = usePdf && pdfOffset >= 0
        zoomPageView.visibility = if (p) View.GONE else View.VISIBLE
        zoomImg.visibility = if (p) View.VISIBLE else View.GONE
        if (p) {
            zoomImg.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER_HORIZONTAL)
            pdfInto(zoomImg, zoomPage, w, h)
        } else {
            zoomPageView.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER_HORIZONTAL)
            zoomPageView.data = d; zoomPageView.style = MushafStyle.of(theme); zoomPageView.highlight = highlight
            zoomPageView.rightSide = zoomPage % 2 == 1; zoomPageView.page = zoomPage
        }
        zoomScroll.scrollTo(0, 0)
        savePos(zoomPage)
        updateNote(listOf(zoomPage))
    }

    // ---- lining up your PDF: find the PDF page that shows Al-Fatihah
    private fun startAlign() {
        val doc = pdf ?: return
        if (!mushaf) prefs.edit().putString("view", "mushaf").apply()
        aligning = true; zoom = false
        alignIdx = if (pdfOffset >= 0) pdfOffset else (doc.pageCount - 604).coerceIn(0, 6)
        applyView(); zoomScroll.post { showAlign() }
    }

    private fun showAlign() {
        val doc = pdf ?: return
        alignIdx = alignIdx.coerceIn(0, doc.pageCount - 1)
        val h = zoomScroll.height.coerceAtLeast(dp(300)); val w = (h * 0.72f).toInt()
        zoomPageView.visibility = View.GONE; zoomImg.visibility = View.VISIBLE
        zoomImg.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER_HORIZONTAL)
        zoomImg.tag = -1
        val i = alignIdx
        worker.execute { val b = doc.render(i, w, h); handler.post { if (alignIdx == i) { zoomImg.setImageBitmap(b); zoomImg.colorFilter = null } } }
        noteTv.text = t("PDF page ${alignIdx + 1} of ${doc.pageCount}  ·  ◀ ▶ to move  ·  OK on the page with Al-Fatihah",
                        "صفحة الملف ${arabicNum(alignIdx + 1)} من ${arabicNum(doc.pageCount)}  ·  ◀ ▶ للتنقل  ·  اضغط OK على صفحة الفاتحة")
        noteTv.visibility = View.VISIBLE
    }

    private fun finishAlign(ok: Boolean) {
        if (ok) { prefs.edit().putInt("pdfOffset", alignIdx).putString("src", "pdf").apply(); toast(t("Lined up ✓ — your Mushaf is ready", "تمت المطابقة ✓ — مصحفك جاهز")) }
        aligning = false; noteTv.visibility = View.GONE
        applyView(); spread.post { sizePages(); showSpread() }
    }

    private fun applyTheme() {
        val bgCol = if (mushaf) (if (usePdf) Color.parseColor(if (theme == "dark") "#050706" else "#1C1813") else MushafStyle.of(theme).around) else Color.parseColor(bg)
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
        return d.withoutBasmala(s, a, d.ayahs[s - 1][a - 1])
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
        prefs.edit().putInt(k("s"), surah).putInt(k("a"), from).apply()
    }

    private fun nextPage() {
        if (data == null) return
        if (mushaf && zoom) {
            if (zoomPage >= 604) { toast("صدق الله العظيم"); return }
            zoomPage++; highlight = null; pagesRead++; showZoom(); return
        }
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
        if (mushaf && zoom) { if (zoomPage > 1) { zoomPage--; highlight = null; showZoom() }; return }
        if (mushaf) { if (mPage > 1) { mPage -= 2; highlight = null; showSpread() }; return }
        if (pageIdx > 0) pageIdx--
        else if (surah > 1) { surah--; pageIdx = pagesOf(surah).size - 1 } else return
        show()
    }

    // ------------------------------------------------------------------ reading timer

    private var timerOn = false          // a session exists
    private var timerPaused = false
    private var runStart = 0L            // when the current running stretch began (0 = not running)
    private var sessionMs = 0L
    private var pagesRead = 0
    private var goalShownFor = ""
    private val goalMin get() = prefs.getInt(k("goal"), 0)
    private fun running() = timerOn && !timerPaused && runStart > 0
    private fun liveMs() = if (running()) System.currentTimeMillis() - runStart else 0L
    private fun timerMs() = sessionMs + liveMs()
    private fun fmt(ms: Long): String { val s = ms / 1000; return if (s >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60) }
    private fun dayKey() = k("read_" + java.time.LocalDate.now())
    /** Today's reading time, including what is running now. */
    private fun todayMs() = prefs.getLong(dayKey(), 0L) + liveMs()

    /** Moves the running time into the session and today's total (so nothing is lost if the TV turns off). */
    private fun flush() {
        if (!running()) return
        val now = System.currentTimeMillis(); val d = now - runStart
        sessionMs += d; runStart = now
        prefs.edit().putLong(dayKey(), prefs.getLong(dayKey(), 0L) + d).apply()
    }

    private var ticks = 0
    private val tick = object : Runnable {
        override fun run() {
            if (!timerOn) return
            if (++ticks % 30 == 0) flush()
            val goal = goalMin
            val today = todayMs()
            timerTv.text = (if (timerPaused) "⏸  " else "⏱  ") + fmt(timerMs()) +
                (if (pagesRead > 0) t("   ·   $pagesRead pages", "   ·   ${arabicNum(pagesRead)} صفحة") else "") +
                (if (goal > 0) "   ·   🎯 " + t("${today / 60_000}/$goal min", "${arabicNum((today / 60_000).toInt())}/${arabicNum(goal)} د") else "")
            timerTv.visibility = if (pure) View.GONE else View.VISIBLE
            val key = dayKey()
            if (goal > 0 && today >= goal * 60_000L && goalShownFor != key && prefs.getString(k("goalDone"), "") != key) {
                goalShownFor = key; prefs.edit().putString(k("goalDone"), key).apply()
                toast(t("✓ Today's goal reached — may Allah bless you", "✓ أتممت هدف اليوم — بارك الله فيك"))
            }
            if (!timerPaused) handler.postDelayed(this, 1000)
        }
    }

    private fun startTimer() {
        timerOn = true; timerPaused = false; sessionMs = 0L; pagesRead = 0; runStart = System.currentTimeMillis()
        handler.removeCallbacks(tick); handler.post(tick)
    }

    private fun pauseTimer() { if (!timerOn || timerPaused) return; flush(); runStart = 0L; timerPaused = true; handler.removeCallbacks(tick); handler.post(tick) }
    private fun resumeTimer() { if (!timerOn || !timerPaused) return; timerPaused = false; runStart = System.currentTimeMillis(); handler.removeCallbacks(tick); handler.post(tick) }

    private fun stopTimer(quiet: Boolean = false) {
        flush()
        val ms = sessionMs
        timerOn = false; timerPaused = false; runStart = 0L; handler.removeCallbacks(tick); timerTv.visibility = View.GONE
        val total = prefs.getLong(dayKey(), 0L)
        if (!quiet) dialog().setTitle(t("May Allah accept it 🤲", "تقبّل الله منك 🤲"))
            .setMessage(t("You read for ${fmt(ms)}" + (if (pagesRead > 0) " · $pagesRead pages" else "") + ".\n\nToday in total: ${total / 60_000} min" + (if (goalMin > 0) " of your $goalMin-minute goal." else "."),
                          "قرأت لمدة ${fmt(ms)}" + (if (pagesRead > 0) " · ${arabicNum(pagesRead)} صفحة" else "") + ".\n\nمجموع اليوم: ${arabicNum((total / 60_000).toInt())} دقيقة" + (if (goalMin > 0) " من هدفك ${arabicNum(goalMin)} دقيقة." else ".")))
            .setPositiveButton(t("OK", "حسنًا"), null).show()
        pagesRead = 0; sessionMs = 0L
    }

    private var pausedByLeaving = false
    override fun onPause() {
        super.onPause()
        if (running()) { pauseTimer(); pausedByLeaving = true }          // paused while you're away
    }

    override fun onResume() {
        super.onResume()
        if (pausedByLeaving) { pausedByLeaving = false; resumeTimer() }
        if (data != null && mushaf && !aligning) { if (zoom) showZoom() else showSpread() }   // night paper may have started
    }

    // ------------------------------------------------------------------ remote keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (aligning) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { alignIdx++; showAlign() }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { alignIdx--; showAlign() }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> finishAlign(true)
                KeyEvent.KEYCODE_BACK -> finishAlign(false)
            }
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_PAGE_DOWN -> { nextPage(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_PAGE_UP -> { prevPage(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU -> { menu(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                when { !timerOn -> startTimer(); timerPaused -> resumeTimer(); else -> pauseTimer() }; return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!mushaf) scroll.smoothScrollBy(0, scroll.height / 3)
                else if (!zoom) enterZoom() else zoomScroll.smoothScrollBy(0, (zoomScroll.height * 0.45f).toInt())
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (!mushaf) scroll.smoothScrollBy(0, -scroll.height / 3)
                else if (!zoom) enterZoom() else if (zoomScroll.scrollY <= 0) exitZoom() else zoomScroll.smoothScrollBy(0, -(zoomScroll.height * 0.45f).toInt())
                return true
            }
            KeyEvent.KEYCODE_BACK -> if (zoom) { exitZoom(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------ menu

    private fun dialog() = FontDialog(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private val bookmarks: MutableList<Pair<Int, Int>>
        get() = (prefs.getString(k("bm"), "") ?: "").split("|").filter { it.contains(":") }
            .map { it.substringBefore(":").toInt() to it.substringAfter(":").toInt() }.toMutableList()
    private fun saveBookmarks(l: List<Pair<Int, Int>>) = prefs.edit().putString(k("bm"), l.joinToString("|") { "${it.first}:${it.second}" }).apply()

    private fun surahLabel(s: Int) = if (ar) "${arabicNum(s)}. ${QuranData.NAMES_AR[s - 1]}" else "$s. ${QuranData.NAMES_EN[s - 1]}  ·  ${QuranData.NAMES_AR[s - 1]}"

    private fun menu() {
        val d = data ?: return
        val hasPages = d.pageStarts.isNotEmpty()
        val curPage = if (zoom) zoomPage else mPage
        val items = ArrayList<Pair<String, () -> Unit>>()
        // timer
        if (!timerOn) items.add(t("⏱  Start reading timer  (or ⏯ on the remote)", "⏱  بدء مؤقت القراءة  (أو ⏯ في الريموت)") to { startTimer() })
        else {
            items.add((if (timerPaused) t("▶  Resume timer  (${fmt(timerMs())})", "▶  متابعة المؤقت  (${fmt(timerMs())})") else t("⏸  Pause timer  (${fmt(timerMs())})", "⏸  إيقاف مؤقت  (${fmt(timerMs())})")) to { if (timerPaused) resumeTimer() else pauseTimer() })
            items.add(t("⏹  Stop timer and see today's reading", "⏹  إنهاء المؤقت وعرض قراءة اليوم") to { stopTimer() })
        }
        // moving around
        items.add(t("📖  Go to surah", "📖  الانتقال إلى سورة") to { surahMenu() })
        items.add(t("🔢  Go to juz'", "🔢  الانتقال إلى جزء") to { juzMenu() })
        if (d.quarterStarts.isNotEmpty()) items.add(t("۞  Go to hizb", "۞  الانتقال إلى حزب") to { hizbMenu() })
        if (hasPages) items.add(t("📄  Go to Mushaf page", "📄  الانتقال إلى صفحة المصحف") to { pageMenu() })
        items.add(t("🔖  Bookmark this page", "🔖  إضافة علامة لهذه الصفحة") to {
            val first = if (mushaf) d.pageAyahs(curPage).firstOrNull() ?: (surah to 1) else surah to pagesOf(surah)[pageIdx][0]
            val l = bookmarks; if (l.none { it == first }) l.add(0, first); saveBookmarks(l.take(30))
            toast(t("Bookmarked", "تمت إضافة العلامة"))
        })
        items.add(t("📑  Bookmarks (${bookmarks.size})", "📑  العلامات (${arabicNum(bookmarks.size)})") to { bookmarksMenu() })
        // view
        if (hasPages) items.add((if (mushaf) t("📗  View: Mushaf pages  (switch to large text)", "📗  العرض: صفحات المصحف  (التبديل إلى خط كبير)")
                                 else t("📗  View: Large text  (switch to Mushaf pages)", "📗  العرض: خط كبير  (التبديل إلى صفحات المصحف)")) to {
            val keep = if (mushaf) d.pageAyahs(curPage).firstOrNull() ?: (surah to 1) else (surah to pagesOf(surah)[pageIdx][0])
            zoom = false
            prefs.edit().putString("view", if (mushaf) "text" else "mushaf").apply()
            applyView()
            if (mushaf) spread.post { sizePages(); mPage = d.pageOf(keep.first, keep.second); showSpread() } else scroll.post { goTo(keep.first, keep.second) }
        })
        if (mushaf) items.add((if (zoom) t("🔍  Back to two pages", "🔍  العودة إلى صفحتين") else t("🔍  One large page  (or press ▲ ▼)", "🔍  صفحة واحدة كبيرة  (أو ▲ ▼)")) to { if (zoom) exitZoom() else enterZoom() })
        if (mushaf) items.add(t("📥  My Mushaf (PDF): ", "📥  مصحفي (PDF): ") + (if (pdf == null) t("add from your phone", "إضافته من الهاتف") else if (usePdf) t("in use", "مستخدم") else t("added, not in use", "مُضاف وغير مستخدم")) to { myMushafMenu() })
        if (!mushaf) items.add(t("🔠  Text size: ${sizeIdx + 1}/5  (change)", "🔠  حجم الخط: ${arabicNum(sizeIdx + 1)}/٥  (تغيير)") to { sizeMenu() })
        items.add(t("🎨  Paper: ", "🎨  الورق: ") + themeName(themePref) + (if (theme == "night" && themePref != "night") t("  (night paper now)", "  (ورق ليلي الآن)") else "") to { paperMenu() })
        // people & habits
        items.add(t("👤  Reader: ", "👤  القارئ: ") + readerName() + t("  (switch)", "  (تبديل)") to { readerMenu() })
        items.add(t("🎯  Daily goal: ", "🎯  هدف يومي: ") + (if (goalMin > 0) t("$goalMin min", "${arabicNum(goalMin)} دقيقة") else t("Off", "متوقف")) to { goalMenu() })
        items.add(t("⏱  Start the timer when I open the Quran: ", "⏱  تشغيل المؤقت عند فتح المصحف: ") + onOff(prefs.getBoolean(k("autoTimer"), false)) to {
            prefs.edit().putBoolean(k("autoTimer"), !prefs.getBoolean(k("autoTimer"), false)).apply() })
        items.add(t("🧘  Pure page (hide timer and notes): ", "🧘  صفحة صافية (إخفاء المؤقت والملاحظات): ") + onOff(pure) to {
            prefs.edit().putBoolean("pure", !pure).apply(); if (mushaf) { if (zoom) showZoom() else showSpread() } })
        items.add(t("🌐  Language: English  (العربية)", "🌐  اللغة: العربية  (English)") to {
            prefs.edit().putString("lang", if (ar) "en" else "ar").apply(); recreate()
        })
        items.add(t("⬆  Check for updates", "⬆  البحث عن تحديثات") to { Updater.check(this, ar) })
        items.add(t("✦  Sahl apps", "✦  تطبيقات سهل") to { com.aly.sahl.common.Family.show(this) })
        items.add(t("ⓘ  About", "ⓘ  حول التطبيق") to { about() })
        val today = todayMs()
        val title = readerName() + "  ·  " + surahLabel(surah) + (if (mushaf) "  ·  " + t("page $curPage", "صفحة ${arabicNum(curPage)}") else "") +
            (if (today >= 60_000) "  ·  " + t("today ${today / 60_000} min", "اليوم ${arabicNum((today / 60_000).toInt())} د") else "")
        dialog().setTitle(title).setItems(items.map { it.first }.toTypedArray()) { _, w -> items[w].second() }.show()
    }

    private fun onOff(b: Boolean) = if (b) t("On", "مُفعّل") else t("Off", "متوقف")

    private fun paperMenu() {
        val ids = listOf("sepia", "light", "night", "dark")
        val labels = ids.map { themeName(it) + if (it == themePref) "  ✓" else "" } + (t("Night paper automatically 7 pm – 5 am: ", "ورق ليلي تلقائيًا من ٧ مساءً إلى ٥ صباحًا: ") + onOff(autoNight))
        dialog().setTitle(t("Paper", "الورق")).setItems(labels.toTypedArray()) { _, w ->
            if (w < ids.size) prefs.edit().putString("theme", ids[w]).apply() else prefs.edit().putBoolean("autoNight", !autoNight).apply()
            styleTheme = ""; applyTheme(); if (mushaf) { if (zoom) showZoom() else showSpread() } else show()
        }.show()
    }

    private fun hizbMenu() {
        val d = data ?: return
        val labels = (0 until 60).map { i -> val st = d.quarterStarts[i * 4]
            t("Hizb ${i + 1}  ·  ${QuranData.NAMES_EN[st.first - 1]} ${st.second}", "الحزب ${arabicNum(i + 1)}  ·  ${QuranData.NAMES_AR[st.first - 1]} ${arabicNum(st.second)}") }
        dialog().setTitle(t("Hizb", "الحزب")).setItems(labels.toTypedArray()) { _, w -> d.quarterStarts[w * 4].let { goTo(it.first, it.second) } }.show()
    }

    private fun goalMenu() {
        val opts = listOf(0, 5, 10, 15, 20, 30, 45, 60)
        val labels = opts.map { (if (it == 0) t("Off", "بدون هدف") else t("$it minutes a day", "${arabicNum(it)} دقيقة يوميًا")) + if (it == goalMin) "  ✓" else "" }
        dialog().setTitle(t("Daily reading goal (time, not a khatmah)", "هدف القراءة اليومي (بالوقت)")).setItems(labels.toTypedArray()) { _, w ->
            prefs.edit().putInt(k("goal"), opts[w]).apply()
            if (opts[w] > 0 && !timerOn) toast(t("Start the timer while you read to count towards it", "شغّل المؤقت أثناء القراءة ليُحسب الوقت"))
        }.show()
    }

    private fun readerMenu() {
        val labels = (0..3).map { readerName(it) + if (it == reader) "  ✓" else "" } + t("✏  Rename ${readerName()}", "✏  تغيير اسم ${readerName()}")
        dialog().setTitle(t("Who is reading?", "من يقرأ؟")).setItems(labels.toTypedArray()) { _, w ->
            if (w == 4) { renameReader(); return@setItems }
            if (w == reader) return@setItems
            if (timerOn) stopTimer(quiet = true)
            prefs.edit().putInt("reader", w).apply()
            mPage = prefs.getInt(k("mp"), 1).coerceIn(1, 604); zoom = false; highlight = null
            applyView()
            if (mushaf) spread.post { sizePages(); showSpread() } else scroll.post { goTo(prefs.getInt(k("s"), 1), prefs.getInt(k("a"), 1)) }
            if (prefs.getBoolean(k("autoTimer"), false)) startTimer()
            toast(t("Welcome, ${readerName()}", "أهلًا ${readerName()}"))
        }.show()
    }

    private fun renameReader() {
        val e = android.widget.EditText(this).apply { setText(readerName()); setSingleLine(); setSelectAllOnFocus(true) }
        dialog().setTitle(t("Name", "الاسم")).setView(e)
            .setPositiveButton(t("Save", "حفظ")) { _, _ -> val n = e.text.toString().trim(); if (n.isNotEmpty()) prefs.edit().putString("rname_$reader", n.take(20)).apply() }
            .setNegativeButton(t("Cancel", "إلغاء"), null).show()
    }

    // ---- your own Mushaf PDF
    private fun myMushafMenu() {
        val doc = pdf
        if (doc == null) { sendFromPhone(); return }
        val items = arrayListOf<Pair<String, () -> Unit>>(
            (if (usePdf) t("Use the built-in pages instead", "استخدام الصفحات المدمجة بدلًا منه") else t("Use my Mushaf", "استخدام مصحفي")) to {
                prefs.edit().putString("src", if (usePdf) "builtin" else "pdf").apply(); if (usePdf && pdfOffset < 0) startAlign() else { applyTheme(); showSpread() } },
            t("Line up the pages again", "إعادة مطابقة الصفحات") to { startAlign() },
            t("Replace with another PDF (e.g. a Tajweed Mushaf)", "استبداله بملف آخر (مثل مصحف التجويد)") to { sendFromPhone() },
            t("Remove my Mushaf from the TV", "حذف مصحفي من التلفاز") to {
                doc.close(); pdf = null; PdfMushaf.file(this).delete(); prefs.edit().remove("pdfOffset").apply(); applyTheme(); showSpread() })
        dialog().setTitle(t("My Mushaf (PDF, ${doc.pageCount} pages)", "مصحفي (PDF، ${arabicNum(doc.pageCount)} صفحة)"))
            .setItems(items.map { it.first }.toTypedArray()) { _, w -> items[w].second() }.show()
    }

    private fun sendFromPhone() {
        val msg = TextView(this).apply { textSize = 17f; setPadding(dp(24), dp(16), dp(24), dp(8)); setTextColor(Color.WHITE) }
        var dlg: android.app.Dialog? = null
        val server = UploadServer(PdfMushaf.file(this), { done, total ->
            msg.text = t("Receiving… ${done * 100 / total}%", "جارٍ الاستلام… ${arabicNum((done * 100 / total).toInt())}٪")
        }) { ok ->
            if (ok) {
                dlg?.dismiss()
                pdf?.close(); pdf = PdfMushaf.open(PdfMushaf.file(this))
                prefs.edit().remove("pdfOffset").putString("src", "pdf").apply()
                toast(t("Received ✓ — now show me where Al-Fatihah is", "تم الاستلام ✓ — حدّد الآن صفحة الفاتحة"))
                startAlign()
            } else msg.text = t("That file isn't a full Mushaf PDF (it needs 600+ pages). Try another file.", "هذا الملف ليس مصحفًا كاملًا بصيغة PDF (يحتاج ٦٠٠ صفحة فأكثر). جرّب ملفًا آخر.")
        }
        msg.text = t("On your phone (same Wi-Fi), open:\n\nhttp://${UploadServer.ip()}:${UploadServer.PORT}\n\nCode: ${server.code}\n\nThen choose your Mushaf PDF and press Send. Keep this window open until it finishes.",
                     "على هاتفك (نفس شبكة Wi-Fi) افتح:\n\nhttp://${UploadServer.ip()}:${UploadServer.PORT}\n\nالرمز: ${server.code}\n\nثم اختر ملف المصحف PDF واضغط إرسال. أبقِ هذه النافذة مفتوحة حتى ينتهي.")
        server.start()
        val dl = dialog().setTitle(t("Send your Mushaf from your phone", "أرسل مصحفك من الهاتف")).setView(msg)
            .setNegativeButton(t("Close", "إغلاق"), null).create()
        dl.setOnDismissListener { server.stop() }
        dlg = dl
        dl.show()
    }

    private fun themeName(th: String) = when (th) { "light" -> t("White", "أبيض"); "sepia" -> t("Cream (like print)", "كريمي (كالمطبوع)"); "night" -> t("Night paper (dim)", "ورق ليلي (خافت)"); else -> t("Dark", "داكن") }

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
