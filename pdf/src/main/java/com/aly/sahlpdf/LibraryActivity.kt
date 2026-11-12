package com.aly.sahlpdf

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * Sahl PDF — the library. "Continue reading" first (the books you are in the middle of), then all your books with big covers.
 * Everything works with the remote: arrows, OK, Back. Hold OK (or press Menu) on a book for more.
 */
class LibraryActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var col: LinearLayout
    private lateinit var scroll: ScrollView
    private var files: List<File> = emptyList()
    private var lastFocus: String? = null
    private var scanning = false
    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }
    private var sortRecent: Boolean
        get() = prefs.getBoolean("sortRecent", false)
        set(v) { prefs.edit().putBoolean("sortRecent", v).apply() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.decorView.background = backdrop()
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(26), dp(48), dp(40))           // TV safe area
            clipChildren = false; clipToPadding = false
            layoutDirection = if (isAr) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; clipChildren = false; isFillViewport = true; addView(col) }
        setContentView(scroll)
        if (!Storage.granted(this) && !prefs.getBoolean("askedFiles", false)) {
            prefs.edit().putBoolean("askedFiles", true).apply(); Storage.ask(this)
        }
    }

    override fun onResume() { super.onResume(); refresh() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { refresh() }

    private fun refresh() {
        if (scanning) return
        scanning = true
        if (col.childCount == 0) { header(); col.addView(label(t("Looking for your books…", "جارٍ البحث عن كتبك…"), 18f, C.MUTED).apply { setPadding(0, dp(40), 0, 0) }) }
        Thread {
            val found = try { Books.scan(this) } catch (_: Exception) { emptyList() }
            main.post { scanning = false; files = found; build() }
        }.start()
    }

    // ------------------------------------------------------------------ screen

    private fun header() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false; setPadding(0, 0, 0, dp(6)) }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label(t("Sahl PDF", "سهل · كتب"), 32f, C.TEXT, true).apply { typeface = Fonts.display(true); Fonts.heading(this) })
        titles.addView(label(if (files.isEmpty()) t("Your books on the TV", "كتبك على التلفاز") else t("${files.size} books", "${files.size} كتاب"), 15f, C.MUTED))
        row.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val send = pill(t("📲  Send from phone", "📲  إرسال من الهاتف")) { sendFromPhone() }.apply { tag = "btn/send" }
        val sort = pill(if (sortRecent) t("⇅  Recent first", "⇅  الأحدث أولًا") else t("⇅  By name", "⇅  حسب الاسم")) {
            sortRecent = !sortRecent; lastFocus = "btn/sort"; build()
        }.apply { tag = "btn/sort" }
        row.addView(send); row.addView(sort, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) })
        col.addView(row)
    }

    private fun section(text: String) = col.addView(label(text, 20f, C.GOLD).apply { typeface = Fonts.display(false); Fonts.heading(this); setPadding(dp(2), dp(22), 0, dp(4)) })

    private fun build() {
        col.removeAllViews()
        header()
        if (!Storage.granted(this)) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false
                background = rounded(C.CARD, 14); setPadding(dp(18), dp(12), dp(14), dp(12))
            }
            box.addView(label(t("Allow access to files to also show the PDFs in Download, Documents and USB drives.",
                "اسمح بالوصول إلى الملفات لعرض ملفات PDF الموجودة في التنزيلات والمستندات وذاكرة USB أيضًا."), 15f, C.TEXT), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            box.addView(pill(t("Allow", "السماح")) { Storage.ask(this) }.apply { tag = "btn/allow" })
            col.addView(box, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        }

        val books = files.map { Books.get(this, it.absolutePath) }
        val reading = books.filter { it.reading }.sortedByDescending { it.opened }.take(10)
        if (reading.isNotEmpty()) {
            section(t("Continue reading", "متابعة القراءة"))
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(12), dp(10), dp(12)); clipChildren = false; clipToPadding = false }
            reading.forEach { line.addView(continueCard(it)) }
            col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(line) })
        }

        if (books.isEmpty()) {
            col.addView(label(t("No books yet.\n\nPress “Send from phone” above, then choose PDF files on your phone. They are saved on the TV, so you send each book only once.",
                "لا توجد كتب بعد.\n\nاضغط «إرسال من الهاتف» في الأعلى، ثم اختر ملفات PDF من هاتفك. تُحفظ على التلفاز، فترسل كل كتاب مرة واحدة فقط."), 19f, C.TEXT).apply { typeface = Fonts.text(); setPadding(dp(4), dp(40), dp(4), 0); setLineSpacing(0f, 1.3f) })
        } else {
            section(t("All books  ·  ${books.size}", "كل الكتب  ·  ${books.size}"))
            val sorted = if (sortRecent) books.sortedWith(compareByDescending<Book> { maxOf(it.opened, it.file.lastModified()) })
                         else books.sortedBy { it.title.lowercase() }
            val cellW = 150 + 22
            val cols = maxOf(3, (resources.configuration.screenWidthDp - 96 - 20) / cellW)
            val grid = GridLayout(this).apply { columnCount = cols; clipChildren = false; clipToPadding = false; setPadding(dp(10), dp(8), dp(10), dp(8)) }
            sorted.forEach { grid.addView(coverCard(it)) }
            col.addView(grid)
        }
        restoreFocus(reading.firstOrNull()?.path, books.isEmpty())
    }

    private fun restoreFocus(firstReading: String?, empty: Boolean) {
        main.post {
            val v = lastFocus?.let { col.findViewWithTag<View>(it) ?: col.findViewWithTag<View>("all/" + it.substringAfter("/")) }
                ?: firstReading?.let { col.findViewWithTag<View>("cont/$it") }
                ?: firstBook(col)
                ?: col.findViewWithTag<View>(if (empty) "btn/send" else "btn/sort")
            v?.requestFocus()
        }
    }

    private fun firstBook(v: View): View? {
        if ((v.tag as? String)?.startsWith("all/") == true) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) firstBook(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun cover(b: Book, w: Int, h: Int): FrameLayout {
        val f = FrameLayout(this).apply { background = rounded(Color.parseColor("#22343C"), 8); clipToOutline = true }
        f.addView(label(b.title, 13f, C.MUTED).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(8)) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        f.addView(img, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        img.tag = b.path
        Covers.load(this, b.file) { bmp -> if (img.tag == b.path && bmp != null) img.setImageBitmap(bmp) }
        if (b.count > 1 && (b.page > 0 || b.finished)) {
            val track = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#99000000")) }
            track.addView(View(this).apply { setBackgroundColor(C.GOLD) },
                FrameLayout.LayoutParams((dp(w) * (if (b.finished) 1f else b.progress)).toInt(), MATCH_PARENT))
            f.addView(track, FrameLayout.LayoutParams(MATCH_PARENT, dp(5), Gravity.BOTTOM))
        }
        if (b.finished) f.addView(label("✓", 15f, C.DARK, true).apply {
            gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C.GOLD) }
        }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
        return f
    }

    private fun coverCard(b: Book): View {
        val (card, ring) = card(10, Color.TRANSPARENT)
        card.tag = "all/${b.path}"
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(cover(b, 150, 210), LinearLayout.LayoutParams(dp(150), dp(210)))
        val name = label(b.title, 14f, C.TEXT).apply { typeface = Fonts.text(); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(2), dp(6), dp(2), 0); gravity = Gravity.CENTER_HORIZONTAL }
        inner.addView(name, LinearLayout.LayoutParams(dp(150), dp(46)))
        card.addView(inner)
        card.addView(ring, FrameLayout.LayoutParams(dp(150), dp(210)))
        card.goldFocus(ring, 1.07f) { has -> name.setTextColor(if (has) C.GOLD else C.TEXT); if (has) lastFocus = card.tag as String }
        wire(card, b)
        card.layoutParams = GridLayout.LayoutParams().apply { width = dp(150); height = dp(256); setMargins(dp(11), dp(10), dp(11), dp(10)) }
        return card
    }

    private fun continueCard(b: Book): View {
        val (card, ring) = card(14, C.CARD)
        card.tag = "cont/${b.path}"
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(10), dp(14), dp(10)) }
        row.addView(cover(b, 120, 168), LinearLayout.LayoutParams(dp(120), dp(168)))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(4), 0, 0) }
        info.addView(label(b.title, 18f, C.TEXT, true).apply { typeface = Fonts.text(true); maxLines = 3; ellipsize = TextUtils.TruncateAt.END })
        info.addView(label(t("Page ${b.page + 1} of ${b.count}", "صفحة ${b.page + 1} من ${b.count}"), 14f, C.MUTED).apply { setPadding(0, dp(8), 0, dp(6)) })
        val track = FrameLayout(this).apply { background = rounded(Color.parseColor("#33FFFFFF"), 3) }
        track.addView(View(this).apply { background = rounded(C.GOLD, 3) }, FrameLayout.LayoutParams((dp(200) * b.progress).toInt().coerceAtLeast(dp(6)), MATCH_PARENT))
        info.addView(track, LinearLayout.LayoutParams(dp(200), dp(6)))
        info.addView(label("${(b.progress * 100).toInt()}%", 13f, C.GOLD).apply { setPadding(0, dp(6), 0, 0) })
        row.addView(info, LinearLayout.LayoutParams(dp(230), WRAP_CONTENT))
        card.addView(row)
        card.addView(ring, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        card.goldFocus(ring, 1.05f) { has -> if (has) lastFocus = card.tag as String }
        wire(card, b)
        card.layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(20) }
        return card
    }

    private fun wire(card: View, b: Book) {
        card.setOnClickListener { open(b.path) }
        card.setOnLongClickListener { bookMenu(b); true }
        card.setOnKeyListener { _, code, ev -> if (code == KeyEvent.KEYCODE_MENU && ev.action == KeyEvent.ACTION_UP) { bookMenu(b); true } else false }
    }

    // ------------------------------------------------------------------ actions

    private fun open(path: String) = startActivity(Intent(this, ReaderActivity::class.java).putExtra("path", path))

    private fun bookMenu(b: Book) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += t("📖  Open", "📖  فتح") to { open(b.path) }
        if (b.page > 0) items += t("⏮  Read from the beginning", "⏮  القراءة من البداية") to { b.page = 0; b.finished = false; Books.save(this, b); open(b.path) }
        items += (if (b.finished) t("↺  Mark as not finished", "↺  لم أُنهِه بعد") else t("✓  Mark as finished", "✓  تمّت قراءته")) to {
            b.finished = !b.finished; Books.save(this, b); build()
        }
        if (b.reading) items += t("✕  Remove from Continue reading", "✕  إزالة من متابعة القراءة") to { b.opened = 0; Books.save(this, b); build() }
        items += t("🗑  Delete this book from the TV", "🗑  حذف هذا الكتاب من التلفاز") to {
            Dlg.confirm(this, t("Delete the book?", "حذف الكتاب؟"), b.title + "\n\n" + t("The file will be removed from the TV.", "سيُحذف الملف من التلفاز."), t("Delete", "حذف")) {
                if (b.file.delete() || !b.file.exists()) { Books.forget(this, b.path); toast(t("Deleted", "تم الحذف")); lastFocus = null; refresh() }
                else toast(t("Could not delete this file", "تعذّر حذف هذا الملف"))
            }
        }
        Dlg.menu(this, b.title, items)
    }

    private fun sendFromPhone() {
        SendPanel.show(this, PORT, t("Sahl PDF · Send books", "سهل · إرسال الكتب"), pdfOnly = true, dir = { Books.inbox(this) },
            onReceived = { f -> lastFocus = "all/${f.absolutePath}" },
            onClosed = { lastFocus = lastFocus ?: "btn/send"; refresh() })
    }

    companion object { const val PORT = 8767 }
}
