package com.aly.sahlpdf

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.io.File
import java.util.concurrent.Executors

/**
 * The reader. Remote:
 *  ◀ ▶  turn pages (in the book's direction; Arabic books: ◀ = next)     ▲ ▼  previous / next page
 *  OK or Menu  the reading menu      ⏪ ⏩ / channel ± also turn pages      Back  library
 * When zoomed in, the arrows move around the page; at the edge they turn the page.
 */
class ReaderActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private var doc: PdfDoc? = null
    private var book: Book? = null           // null when opened from another app without a real file path
    private lateinit var view: BookView
    private lateinit var info: android.widget.TextView
    private lateinit var bar: View
    private lateinit var barFill: View

    private var page = 0
    private var dual = true
    private var rtl = isAr
    private var shift = false                // two-page mode: show the first page alone (like a printed book's cover)
    private var paper = "white"
    private var zoom = 1f
    private var gen = 0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.setBackgroundColor(Color.parseColor("#0C1512"))
        view = BookView(this)
        val root = FrameLayout(this)
        root.addView(view)
        info = label("", 16f, C.TEXT).apply {
            background = rounded(Color.parseColor("#CC0B1E1A"), 16); setPadding(dp(16), dp(7), dp(16), dp(7)); alpha = 0f
        }
        root.addView(info, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(22) })
        bar = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#33FFFFFF")); alpha = 0f }
        barFill = View(this).apply { setBackgroundColor(C.GOLD) }
        (bar as FrameLayout).addView(barFill, FrameLayout.LayoutParams(0, -1))
        root.addView(bar, FrameLayout.LayoutParams(-1, dp(4), Gravity.BOTTOM))
        setContentView(root)
        if (!openFromIntent()) { toast(t("Could not open this PDF", "تعذّر فتح ملف PDF هذا")); finish(); return }
        view.post { show(first = true) }
    }

    private fun openFromIntent(): Boolean {
        val i = intent
        var path = i.getStringExtra("path")
        val uri: Uri? = i.data
        if (path == null && uri?.scheme == "file") path = uri.path
        if (path != null && File(path).canRead()) {
            doc = PdfDoc.open(File(path)) ?: return false
            book = Books.get(this, path)
        } else if (uri != null) {
            val fd = try { contentResolver.openFileDescriptor(uri, "r") } catch (_: Exception) { null } ?: return false
            doc = PdfDoc.open(fd) ?: return false
            // a file path sent along by Sahl Files lets us remember the page even without file access
            if (path != null) book = Books.get(this, path)
        } else return false
        val d = doc!!
        book?.let { bk ->
            page = bk.page.coerceIn(0, d.count - 1)
            val (w, h) = d.pageSize(0)
            dual = bk.opt("dual", if (h > w) "1" else "0") == "1"      // portrait pages: two side by side on the wide TV
            rtl = bk.opt("rtl", if (isAr) "1" else "0") == "1"
            shift = bk.opt("shift", "0") == "1"
            bk.count = d.count; bk.opened = System.currentTimeMillis(); Books.save(this, bk)
        } ?: run { val (w, h) = d.pageSize(0); dual = h > w }
        paper = getSharedPreferences("app", MODE_PRIVATE).getString("paper", "white")!!
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        exec.execute { doc?.close() }
        exec.shutdown()
    }

    // ------------------------------------------------------------------ pages

    private val count get() = doc?.count ?: 0
    private fun start(p: Int): Int = if (!dual) p else if (shift) (if (p <= 0) 0 else ((p - 1) / 2) * 2 + 1) else (p / 2) * 2
    private fun spread(p: Int): List<Int> {
        val s = start(p)
        if (!dual || (shift && s == 0) || s + 1 >= count) return listOf(s)
        return listOf(s, s + 1)
    }

    private fun go(to: Int, fromEnd: Boolean = false) {
        val p = to.coerceIn(0, count - 1)
        if (start(p) == start(page) && view.hasPages) { if (to < 0 || to >= count) note(if (to < 0) t("This is the first page", "هذه أول صفحة") else t("This is the last page", "هذه آخر صفحة")); return }
        page = start(p)
        view.panToStart(fromEnd)
        show()
    }

    private fun next() = go(spread(page).last() + 1)
    private fun prev(fromEnd: Boolean = false) = go(start(page) - 1, fromEnd)

    /** Draws the current page(s) sharp for the screen and zoom, on a background thread. */
    private fun show(first: Boolean = false) {
        val d = doc ?: return
        val pages = spread(page)
        val my = ++gen
        val vw = view.width.coerceAtLeast(1); val vh = view.height.coerceAtLeast(1)
        val z = zoom
        exec.execute {
            if (my != gen) return@execute
            val sizes = pages.map { d.pageSize(it) }
            val gap = if (pages.size > 1) dp(6) else 0
            val boxW = (vw - gap) / pages.size.toFloat()
            // same height for both pages of a spread; fit inside the screen
            val fit = sizes.minOf { (w, h) -> minOf(boxW / w, vh.toFloat() / h) }
            var scale = fit * z
            val maxSide = sizes.maxOf { (w, h) -> maxOf(w, h) } * scale
            if (maxSide > 4096) scale *= 4096f / maxSide
            val bmps = pages.map { d.render(it, scale) }
            if (my != gen) return@execute
            main.post {
                if (my != gen) return@post
                view.set(bmps, if (rtl) -1 else 1, gap, PdfDoc.paper(paper), z)
                save()
                note(pageText(), progress = true, long = first)
                // get the next spread ready in the background is not needed: pages draw fast; keep memory low on the TV
            }
        }
    }

    private fun pageText(): String {
        val p = spread(page)
        val n = if (p.size > 1) "${p[0] + 1}–${p[1] + 1}" else "${p[0] + 1}"
        val mark = if (book?.marks?.any { it in p } == true) "  🔖" else ""
        return t("Page $n of $count", "صفحة $n من $count") + mark
    }

    private fun save() {
        val bk = book ?: return
        bk.page = page; bk.count = count; bk.opened = System.currentTimeMillis()
        if (spread(page).last() >= count - 1) bk.finished = true
        Books.save(this, bk)
    }

    private val hideInfo = Runnable { info.animate().alpha(0f).setDuration(400).start(); bar.animate().alpha(0f).setDuration(400).start() }
    private fun note(s: String, progress: Boolean = false, long: Boolean = false) {
        info.text = s; info.animate().alpha(1f).setDuration(150).start()
        if (progress && count > 0) {
            bar.animate().alpha(1f).setDuration(150).start()
            barFill.layoutParams = (barFill.layoutParams as FrameLayout.LayoutParams).apply {
                width = (bar.width * (spread(page).last() + 1f) / count).toInt()
                gravity = if (rtl) Gravity.END else Gravity.START
            }
            barFill.requestLayout()
        }
        main.removeCallbacks(hideInfo); main.postDelayed(hideInfo, if (long) 3500 else 1800)
    }

    // ------------------------------------------------------------------ remote

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        val forwardKey = if (rtl) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        val backKey = if (rtl) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        val zoomed = zoom > 1.01f
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU -> { if (e.repeatCount == 0) menu(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT -> { next(); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { prev(); return true }
        }
        if (code == forwardKey || code == backKey) {
            val fwd = code == forwardKey
            if (zoomed) {
                // move in screen direction; at the edge, turn the page
                val dx = if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                if (view.pan(dx, 0)) return true
            }
            if (fwd) next() else prev()
            return true
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_UP) {
            val down = code == KeyEvent.KEYCODE_DPAD_DOWN
            if (zoomed && view.pan(0, if (down) 1 else -1)) return true
            if (down) next() else prev(fromEnd = zoomed)
            return true
        }
        return super.onKeyDown(code, e)
    }

    // ------------------------------------------------------------------ menu

    private fun menu() {
        val bk = book
        val here = spread(page)
        val marked = bk?.marks?.any { it in here } == true
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += t("#   Go to page…", "#   الانتقال إلى صفحة…") to { goTo() }
        if (bk != null) {
            items += (if (marked) t("🔖  Remove bookmark from this page", "🔖  إزالة العلامة من هذه الصفحة") else t("🔖  Bookmark this page", "🔖  وضع علامة على هذه الصفحة")) to {
                val m = bk.marks; if (marked) m.removeAll { it in here } else m.add(here.first()); bk.setMarks(m); Books.save(this, bk)
                note(if (marked) t("Bookmark removed", "أُزيلت العلامة") else t("Bookmarked 🔖", "تم وضع العلامة 🔖"))
            }
            if (bk.marks.isNotEmpty()) items += t("📑  My bookmarks (${bk.marks.size})", "📑  علاماتي (${bk.marks.size})") to { marks() }
        }
        items += (if (dual) t("▭   One page at a time", "▭   صفحة واحدة") else t("▭▭  Two pages side by side", "▭▭  صفحتان متجاورتان")) to {
            dual = !dual; page = start(page); zoom = 1f; bk?.set("dual", if (dual) "1" else "0"); show()
        }
        if (dual) items += t("⇆   Pair the pages differently", "⇆   تغيير ترتيب الصفحات المتجاورة") to {
            shift = !shift; page = start(page); bk?.set("shift", if (shift) "1" else "0"); show()
        }
        items += t("☀   Paper: ", "☀   لون الورق: ") + paperName(paper) to { paperMenu() }
        items += t("＋  Zoom in", "＋  تكبير") to { zoom = (zoom * 1.5f).coerceAtMost(4f); show(); hint() }
        if (zoom > 1.01f) {
            items += t("－  Zoom out", "－  تصغير") to { zoom = (zoom / 1.5f).coerceAtLeast(1f); show() }
            items += t("⤢   Whole page", "⤢   الصفحة كاملة") to { zoom = 1f; show() }
        }
        items += (if (rtl) t("⇄   Book direction: right to left (Arabic)", "⇄   اتجاه الكتاب: من اليمين لليسار (عربي)")
                  else t("⇄   Book direction: left to right", "⇄   اتجاه الكتاب: من اليسار لليمين")) to {
            rtl = !rtl; bk?.set("rtl", if (rtl) "1" else "0"); show()
            note(if (rtl) t("◀ now goes to the next page", "◀ ينتقل الآن إلى الصفحة التالية") else t("▶ now goes to the next page", "▶ ينتقل الآن إلى الصفحة التالية"), long = true)
        }
        items += t("📚  Back to the library", "📚  العودة إلى المكتبة") to { finish() }
        Dlg.menu(this, (bk?.title ?: t("Book", "الكتاب")) + "  ·  " + pageText(), items)
    }

    private fun hint() = note(t("Use the arrows to move around the page", "استخدم الأسهم للتنقل داخل الصفحة"), long = true)

    private fun paperName(p: String) = when (p) { "warm" -> t("warm", "دافئ"); "dark" -> t("dark", "داكن"); else -> t("white", "أبيض") }

    private fun paperMenu() {
        val kinds = listOf("white", "warm", "dark")
        Dlg.menu(this, t("Paper colour", "لون الورق"), listOf(
            t("☀  White (as printed)", "☀  أبيض (كما طُبع)") to { setPaper("white") },
            t("🌅  Warm (softer for the eyes)", "🌅  دافئ (أريح للعين)") to { setPaper("warm") },
            t("🌙  Dark (for night)", "🌙  داكن (للّيل)") to { setPaper("dark") }), focus = kinds.indexOf(paper))
    }

    private fun setPaper(p: String) { paper = p; getSharedPreferences("app", MODE_PRIVATE).edit().putString("paper", p).apply(); view.setPaper(PdfDoc.paper(p)) }

    private fun goTo() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += t("⌨  Type a page number", "⌨  اكتب رقم الصفحة") to {
            Dlg.input(this, t("Go to page (1–$count)", "الانتقال إلى صفحة (1–$count)"), "", numeric = true) { s -> s.toIntOrNull()?.let { go(it - 1) } }
        }
        items += t("⏮  First page", "⏮  أول صفحة") to { go(0) }
        for (pc in listOf(25, 50, 75)) items += t("$pc% of the book", "$pc% من الكتاب") to { go((count - 1) * pc / 100) }
        items += t("⏭  Last page", "⏭  آخر صفحة") to { go(count - 1) }
        Dlg.menu(this, t("Go to page", "الانتقال إلى صفحة"), items)
    }

    private fun marks() {
        val bk = book ?: return
        Dlg.menu(this, t("My bookmarks", "علاماتي"), bk.marks.map { p -> t("🔖  Page ${p + 1}", "🔖  صفحة ${p + 1}") to { go(p) } })
    }
}

/** Draws one or two page pictures, centred, with zoom and moving around. */
class BookView(ctx: Context) : View(ctx) {
    private var bmps: List<Bitmap?> = emptyList()
    private var dir = 1
    private var gap = 0
    private var zoomed = false
    private var offX = 0f; private var offY = 0f
    private var startAtEnd = false
    private var reset = true
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint().apply { color = Color.parseColor("#55000000") }
    val hasPages get() = bmps.isNotEmpty()

    private val contentW get() = bmps.sumOf { it?.width ?: 0 } + gap * (bmps.size - 1).coerceAtLeast(0)
    private val contentH get() = bmps.maxOfOrNull { it?.height ?: 0 } ?: 0

    fun set(b: List<Bitmap?>, direction: Int, gap: Int, filter: android.graphics.ColorFilter?, zoom: Float) {
        bmps = b; dir = direction; this.gap = gap; paint.colorFilter = filter
        val wasZoomed = zoomed; zoomed = zoom > 1.01f
        if (reset || !zoomed || !wasZoomed) {
            offX = if (dir < 0) (width - contentW).toFloat().coerceAtMost(0f) else 0f   // start reading at the book's first side
            offY = if (startAtEnd) (height - contentH).toFloat().coerceAtMost(0f) else 0f
            startAtEnd = false; reset = false
        }
        clamp(); invalidate()
    }

    fun setPaper(f: android.graphics.ColorFilter?) { paint.colorFilter = f; invalidate() }
    fun panToStart(fromEnd: Boolean) { startAtEnd = fromEnd; reset = true }

    /** Moves by a third of the screen. Returns false when already at that edge (then the reader turns the page). */
    fun pan(dx: Int, dy: Int): Boolean {
        val ox = offX; val oy = offY
        offX -= dx * width / 3f; offY -= dy * height / 3f
        clamp()
        if (ox == offX && oy == offY) return false
        invalidate(); return true
    }

    private fun clamp() {
        val cw = contentW; val ch = contentH
        offX = if (cw <= width) 0f else offX.coerceIn((width - cw).toFloat(), 0f)
        offY = if (ch <= height) 0f else offY.coerceIn((height - ch).toFloat(), 0f)
    }

    override fun onDraw(c: Canvas) {
        if (bmps.isEmpty()) return
        val cw = contentW; val ch = contentH
        val x0 = if (cw <= width) (width - cw) / 2f else offX
        val y0 = if (ch <= height) (height - ch) / 2f else offY
        // in a right-to-left book the first page of a spread is on the right
        val order = if (dir < 0) bmps.reversed() else bmps
        var x = x0
        for (b in order) {
            if (b != null) {
                val y = y0 + (ch - b.height) / 2f
                c.drawRect(RectF(x + 4, y + 6, x + b.width + 4, y + b.height + 6), shadow)
                c.drawBitmap(b, x, y, paint)
                x += b.width + gap
            }
        }
    }
}
