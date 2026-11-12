package com.aly.sahlfiles

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Sahl Files — a simple file manager for the TV.
 * Left: places (Internal storage, Download, Movies, USB…). Right: the folder. OK opens, Back goes up,
 * hold OK (or Menu) for Copy / Move / Rename / Delete. "Receive from phone" saves files straight into the folder you are in.
 */
class FilesActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }
    private lateinit var places: LinearLayout
    private lateinit var pathTv: TextView
    private lateinit var infoTv: TextView
    private lateinit var actions: LinearLayout
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private val adapter = Adapter()

    private var dir: File? = null
    private var placeRoot: File? = null
    private var placeName = ""
    private var items: List<File> = emptyList()
    private val positions = HashMap<String, Int>()        // where you were in each folder, to come back to it
    private val dirCounts = HashMap<String, Int>()
    private var clip: List<File> = emptyList()
    private var clipMove = false
    private var sort: Int
        get() = prefs.getInt("sort", 0)                  // 0 name, 1 newest, 2 largest
        set(v) { prefs.edit().putInt("sort", v).apply() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.decorView.background = backdrop()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(40), dp(24), dp(40), dp(20))
            layoutDirection = if (isAr) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            clipChildren = false; clipToPadding = false
        }
        // ---- places
        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        side.addView(label(t("Sahl Files", "سهل · الملفات"), 26f, C.TEXT, true).apply { setPadding(dp(8), 0, 0, dp(14)) })
        places = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(6), dp(4), dp(6), dp(4)) }
        side.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(places) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(side, LinearLayout.LayoutParams(dp(250), MATCH_PARENT))
        // ---- folder
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), 0, 0, 0); clipChildren = false; clipToPadding = false }
        pathTv = label("", 20f, C.TEXT, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.START }
        infoTv = label("", 14f, C.MUTED).apply { setPadding(0, dp(2), 0, dp(10)) }
        right.addView(pathTv); right.addView(infoTv)
        actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; setPadding(dp(4), dp(4), dp(4), dp(10)) }
        right.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(actions) })
        val listBox = android.widget.FrameLayout(this).apply { background = rounded(Color.parseColor("#14FFFFFF"), 16); setPadding(dp(8), dp(8), dp(8), dp(8)) }
        list = ListView(this).apply {
            adapter = this@FilesActivity.adapter
            divider = null; selector = ColorDrawable(Color.TRANSPARENT); isVerticalScrollBarEnabled = true
            itemsCanFocus = false; isFocusable = true
        }
        empty = label("", 17f, C.MUTED).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        listBox.addView(list); listBox.addView(empty, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        right.addView(listBox, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(right, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        setContentView(root)

        list.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ -> items.getOrNull(pos)?.let { open(it) } }
        list.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, pos, _ -> items.getOrNull(pos)?.let { itemMenu(it) }; true }
        list.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_MENU && ev.action == KeyEvent.ACTION_UP) { items.getOrNull(list.selectedItemPosition)?.let { itemMenu(it) }; true } else false
        }
        list.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { dir?.let { positions[it.path] = pos } }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        if (!Storage.granted(this)) Storage.ask(this)
        buildPlaces()
        val last = prefs.getString("dir", null)?.let { File(it) }
        if (last != null && last.isDirectory && Storage.granted(this)) { placeRoot = placeFor(last); placeName = nameFor(placeRoot!!); load(last) }
        else openPlace(Storage.root, t("Internal storage", "الذاكرة الداخلية"))
        main.post { list.requestFocus() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        buildPlaces(); dir?.let { load(it) } ?: openPlace(Storage.root, t("Internal storage", "الذاكرة الداخلية"))
    }

    override fun onResume() { super.onResume(); dir?.let { if (items.isNotEmpty() || it.exists()) load(it, keep = true) } }

    // ------------------------------------------------------------------ places

    private fun placeList(): List<Triple<String, String, File>> {
        val l = ArrayList<Triple<String, String, File>>()
        l += Triple("🏠", t("Internal storage", "الذاكرة الداخلية"), Storage.root)
        l += Triple("⬇", t("Download", "التنزيلات"), Storage.pub(Environment.DIRECTORY_DOWNLOADS))
        l += Triple("🎬", t("Movies", "الأفلام"), Storage.pub(Environment.DIRECTORY_MOVIES))
        l += Triple("🖼", t("Pictures", "الصور"), Storage.pub(Environment.DIRECTORY_PICTURES))
        l += Triple("🎵", t("Music", "الصوتيات"), Storage.pub(Environment.DIRECTORY_MUSIC))
        l += Triple("📄", t("Documents", "المستندات"), Storage.pub(Environment.DIRECTORY_DOCUMENTS))
        File(Storage.pub(Environment.DIRECTORY_DOWNLOADS), "Sahl PDF").let { if (it.isDirectory) l += Triple("📚", t("Books (Sahl PDF)", "الكتب (سهل PDF)"), it) }
        for (u in Storage.removable()) l += Triple("🔌", "USB · " + u.name, u)
        return l
    }

    private fun placeFor(f: File): File = placeList().map { it.third }.filter { f.path == it.path || f.path.startsWith(it.path + "/") }
        .maxByOrNull { it.path.length } ?: Storage.root
    private fun nameFor(f: File) = placeList().firstOrNull { it.third.path == f.path }?.second ?: f.name

    private fun buildPlaces() {
        places.removeAllViews()
        if (!Storage.granted(this)) places.addView(placeRow("🔓", t("Allow access to files", "السماح بالوصول إلى الملفات"), null) { Storage.ask(this) })
        for ((icon, name, f) in placeList()) places.addView(placeRow(icon, name, f) { openPlace(f, name); list.requestFocus() })
        val st = try { StatFs(Storage.root.path) } catch (_: Exception) { null }
        if (st != null) places.addView(label(t("Free: ", "المتاح: ") + Storage.size(st.availableBytes) + t(" of ", " من ") + Storage.size(st.totalBytes), 13f, C.MUTED).apply { setPadding(dp(10), dp(16), 0, 0) })
    }

    private fun placeRow(icon: String, name: String, f: File?, onClick: () -> Unit) = Dlg.row(this, "$icon   $name", onClick).apply {
        tag = f?.path; textSize = 16f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(2) }
    }

    private fun openPlace(f: File, name: String) {
        placeRoot = f; placeName = name
        f.mkdirs()
        load(f)
    }

    // ------------------------------------------------------------------ folder

    private fun load(d: File, keep: Boolean = false, select: String? = null) {
        val previous = dir
        dir = d
        prefs.edit().putString("dir", d.path).apply()
        Thread {
            val all = (d.listFiles() ?: emptyArray()).filter { !it.name.startsWith(".") }
            val folders = all.filter { it.isDirectory }; val filesOnly = all.filter { !it.isDirectory }
            val sorted = when (sort) {
                1 -> folders.sortedByDescending { it.lastModified() } + filesOnly.sortedByDescending { it.lastModified() }
                2 -> folders.sortedBy { it.name.lowercase() } + filesOnly.sortedByDescending { it.length() }
                else -> folders.sortedBy { it.name.lowercase() } + filesOnly.sortedBy { it.name.lowercase() }
            }
            main.post {
                if (dir != d) return@post
                items = sorted; dirCounts.clear()
                adapter.notifyDataSetChanged()
                val rel = placeRoot?.let { r -> d.path.removePrefix(r.path).trim('/').split('/').filter { it.isNotEmpty() } } ?: emptyList()
                pathTv.text = (listOf(placeName) + rel).joinToString("  ›  ")
                infoTv.text = when {
                    !d.canRead() -> t("This folder cannot be opened", "لا يمكن فتح هذا المجلد")
                    else -> t("${folders.size} folders · ${filesOnly.size} files", "${folders.size} مجلد · ${filesOnly.size} ملف")
                }
                empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                empty.text = if (!Storage.granted(this)) t("Allow access to files (left) to see this folder.", "اسمح بالوصول إلى الملفات (القائمة الجانبية) لرؤية هذا المجلد.")
                             else t("This folder is empty.\nUse “Receive from phone” to put files here.", "هذا المجلد فارغ.\nاستخدم «استلام من الهاتف» لوضع ملفات هنا.")
                buildActions()
                markPlace()
                val pos = when {
                    select != null -> items.indexOfFirst { it.path == select }
                    keep || previous == null || previous.path.startsWith(d.path + "/") ->
                        previous?.takeIf { it.path.startsWith(d.path + "/") }?.let { p -> items.indexOfFirst { p.path.startsWith(it.path) } }?.takeIf { it >= 0 }
                            ?: positions[d.path] ?: 0
                    else -> 0
                }
                if (items.isNotEmpty()) list.setSelection(pos.coerceIn(0, items.size - 1))
            }
        }.start()
    }

    private fun markPlace() {
        for (i in 0 until places.childCount) {
            val v = places.getChildAt(i) as? TextView ?: continue
            val on = v.tag == placeRoot?.path
            if (!v.hasFocus()) v.setTextColor(if (on) C.GOLD else C.TEXT)
        }
    }

    private fun buildActions() {
        actions.removeAllViews()
        fun add(text: String, run: () -> Unit) = actions.addView(pill(text, run), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(12) })
        add(t("📲  Receive from phone", "📲  استلام من الهاتف")) { receive() }
        if (clip.isNotEmpty()) add((if (clipMove) t("📋  Move here", "📋  نقل هنا") else t("📋  Paste here", "📋  لصق هنا")) + " (${clip.size})") { paste() }
        add(t("＋  New folder", "＋  مجلد جديد")) {
            Dlg.input(this, t("New folder name", "اسم المجلد الجديد"), "", numeric = false) { n ->
                val f = File(dir, UploadServer.cleanName(n))
                if (f.exists() || f.mkdirs()) load(dir!!, select = f.path) else toast(t("Cannot create a folder here", "لا يمكن إنشاء مجلد هنا"))
            }
        }
        add(when (sort) { 1 -> t("⇅  Newest first", "⇅  الأحدث أولًا"); 2 -> t("⇅  Largest first", "⇅  الأكبر أولًا"); else -> t("⇅  By name", "⇅  حسب الاسم") }) {
            sort = (sort + 1) % 3; load(dir!!); main.post { actions.getChildAt(actions.childCount - 1)?.requestFocus() }
        }
        if (clip.isNotEmpty()) add(t("✕  Clear", "✕  إلغاء النسخ")) { clip = emptyList(); buildActions(); list.requestFocus() }
    }

    private fun up(): Boolean {
        val d = dir ?: return false
        val r = placeRoot
        if (r != null && d.path != r.path && d.parentFile != null) { load(d.parentFile!!); return true }
        return false
    }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        if (code == KeyEvent.KEYCODE_BACK) {
            if (list.hasFocus() && up()) return true
            if (!places.hasFocus()) { places.findViewWithTag<View>(placeRoot?.path)?.requestFocus() ?: places.getChildAt(0)?.requestFocus(); return true }
        }
        return super.onKeyDown(code, e)
    }

    // ------------------------------------------------------------------ open

    private fun kind(f: File): String {
        if (f.isDirectory) return "dir"
        return when (f.extension.lowercase()) {
            "pdf" -> "pdf"
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" -> "image"
            "mp4", "mkv", "avi", "mov", "webm", "m4v", "3gp", "ts", "wmv", "flv" -> "video"
            "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma" -> "audio"
            "apk" -> "apk"
            "txt", "md", "json", "xml", "csv", "log", "srt", "vtt", "ini", "conf", "cfg", "properties", "html", "htm", "m3u", "m3u8" -> "text"
            "zip", "rar", "7z", "tar", "gz" -> "zip"
            else -> "other"
        }
    }

    private fun icon(k: String) = when (k) {
        "dir" -> "📁"; "pdf" -> "📕"; "image" -> "🖼"; "video" -> "🎬"; "audio" -> "🎵"; "apk" -> "📦"; "text" -> "📄"; "zip" -> "🗜"; else -> "📃"
    }

    private fun open(f: File) {
        when (kind(f)) {
            "dir" -> { if (f.canRead()) load(f) else toast(t("This folder cannot be opened", "لا يمكن فتح هذا المجلد")) }
            "image" -> startActivity(Intent(this, ViewerActivity::class.java).putExtra("path", f.path).putExtra("mode", "image").putExtra("sort", sort))
            "text" -> if (f.length() <= 2_000_000) startActivity(Intent(this, ViewerActivity::class.java).putExtra("path", f.path).putExtra("mode", "text")) else openWith(f, false)
            "pdf" -> {
                val i = view(f).setPackage("com.aly.sahlpdf").putExtra("path", f.path)
                try { startActivity(i) } catch (_: ActivityNotFoundException) { openWith(f, false) }
            }
            else -> openWith(f, false)
        }
    }

    private fun view(f: File) = Intent(Intent.ACTION_VIEW).setDataAndType(FilesProvider.uri(f), FilesProvider.mime(f))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private fun openWith(f: File, choose: Boolean) {
        val i = view(f)
        try { startActivity(if (choose) Intent.createChooser(i, t("Open with", "فتح باستخدام")) else i) }
        catch (_: ActivityNotFoundException) {
            Dlg.message(this, f.name, if (kind(f) == "zip") t("Compressed files cannot be opened on the TV. Open it on your phone or PC.", "لا يمكن فتح الملفات المضغوطة على التلفاز. افتحه على الهاتف أو الكمبيوتر.")
                else t("No app on this TV can open this kind of file.", "لا يوجد تطبيق على هذا التلفاز يفتح هذا النوع من الملفات."))
        } catch (_: SecurityException) { toast(t("This file cannot be shared with other apps", "لا يمكن مشاركة هذا الملف مع تطبيقات أخرى")) }
    }

    // ------------------------------------------------------------------ item menu

    private fun itemMenu(f: File) {
        val dirItem = f.isDirectory
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += t("▶  Open", "▶  فتح") to { open(f) }
        if (!dirItem) items += t("↗  Open with another app", "↗  فتح بتطبيق آخر") to { openWith(f, true) }
        items += t("📋  Copy", "📋  نسخ") to { clip = listOf(f); clipMove = false; buildActions(); toast(t("Now go to a folder and choose “Paste here”", "اذهب الآن إلى مجلد واختر «لصق هنا»")) }
        items += t("✂  Move", "✂  نقل") to { clip = listOf(f); clipMove = true; buildActions(); toast(t("Now go to a folder and choose “Move here”", "اذهب الآن إلى مجلد واختر «نقل هنا»")) }
        items += t("✎  Rename", "✎  إعادة التسمية") to {
            Dlg.input(this, t("New name", "الاسم الجديد"), f.name, numeric = false) { n ->
                val to = File(f.parentFile, UploadServer.cleanName(n))
                if (to.exists()) toast(t("That name is already used", "هذا الاسم مستخدم"))
                else if (f.renameTo(to)) load(dir!!, select = to.path) else toast(t("Could not rename", "تعذّرت إعادة التسمية"))
            }
        }
        items += t("🗑  Delete", "🗑  حذف") to {
            val what = if (dirItem) t("The folder and everything inside it will be deleted.", "سيُحذف المجلد وكل ما بداخله.") else t("The file will be deleted from the TV.", "سيُحذف الملف من التلفاز.")
            Dlg.confirm(this, t("Delete?", "حذف؟"), f.name + "\n\n" + what, t("Delete", "حذف")) {
                val pos = list.selectedItemPosition
                Thread {
                    val ok = f.deleteRecursively()
                    main.post { toast(if (ok) t("Deleted", "تم الحذف") else t("Some items could not be deleted", "تعذّر حذف بعض العناصر")); dir?.let { positions[it.path] = pos }; load(dir!!, keep = true) }
                }.start()
            }
        }
        items += t("ℹ  Details", "ℹ  التفاصيل") to { details(f) }
        Dlg.menu(this, f.name, items)
    }

    private fun details(f: File) {
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        Thread {
            val size = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()
            val n = if (f.isDirectory) f.walkTopDown().count { it.isFile } else 0
            main.post {
                Dlg.message(this, f.name, buildString {
                    append(t("Size: ", "الحجم: ")).append(Storage.size(size)).append('\n')
                    if (f.isDirectory) append(t("Files inside: ", "عدد الملفات: ")).append(n).append('\n')
                    append(t("Changed: ", "آخر تعديل: ")).append(df.format(Date(f.lastModified()))).append('\n')
                    append(t("Place: ", "المكان: ")).append(f.parent ?: "")
                })
            }
        }.start()
    }

    // ------------------------------------------------------------------ copy / move

    private fun paste() {
        val to = dir ?: return
        val src = clip; val move = clipMove
        if (src.any { to.path == it.path || to.path.startsWith(it.path + "/") }) { toast(t("Cannot put a folder inside itself", "لا يمكن وضع مجلد داخل نفسه")); return }
        val total = src.sumOf { s -> if (s.isDirectory) s.walkTopDown().filter { it.isFile }.sumOf { it.length() } else s.length() }
        val stat = try { StatFs(to.path) } catch (_: Exception) { null }
        if (stat != null && !move && total > stat.availableBytes) { toast(t("Not enough space", "لا توجد مساحة كافية")); return }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val msg = label(t("Working…", "جارٍ العمل…"), 16f, C.TEXT)
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progressTintList = ColorStateList.valueOf(C.GOLD) }
        box.addView(msg); box.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, dp(10)).apply { topMargin = dp(10) })
        val d = Dlg.base(this, if (move) t("Moving", "جارٍ النقل") else t("Copying", "جارٍ النسخ"), box)
        d.setCancelable(false); d.show()
        Thread {
            var done = 0L; var ok = true; var last: File? = null
            val buf = ByteArray(512 * 1024)
            fun copyFile(a: File, b: File) {
                a.inputStream().use { i -> b.outputStream().use { o ->
                    while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n
                        val p = if (total > 0) (done * 1000 / total).toInt() else 0; main.post { bar.progress = p; msg.text = a.name } }
                } }
                b.setLastModified(a.lastModified())
            }
            fun copyAny(a: File, b: File) {
                if (a.isDirectory) { b.mkdirs(); (a.listFiles() ?: emptyArray()).forEach { copyAny(it, File(b, it.name)) } } else copyFile(a, b)
            }
            for (s in src) {
                try {
                    val target = UploadServer.freeName(to, s.name); last = target
                    if (move && s.renameTo(target)) continue                 // same drive: instant
                    copyAny(s, target)
                    if (move) s.deleteRecursively()
                } catch (_: Exception) { ok = false }
            }
            main.post {
                d.dismiss(); clip = emptyList()
                toast(if (!ok) t("Some items could not be copied", "تعذّر نسخ بعض العناصر") else if (move) t("Moved ✓", "تم النقل ✓") else t("Copied ✓", "تم النسخ ✓"))
                load(to, select = last?.path); list.requestFocus()
            }
        }.start()
    }

    // ------------------------------------------------------------------ phone

    private fun receive() {
        val target = { dir?.takeIf { it.canWrite() } ?: Storage.pub(Environment.DIRECTORY_DOWNLOADS) }
        var lastFile: File? = null
        SendPanel.show(this, PORT, t("Sahl Files · Receive from phone", "سهل · الملفات · استلام من الهاتف"), pdfOnly = false, dir = target,
            onReceived = { f -> lastFile = f; load(f.parentFile ?: target(), select = f.path) },
            onClosed = { list.requestFocus(); lastFile?.let { load(it.parentFile!!, select = it.path) } })
    }

    // ------------------------------------------------------------------ list rows

    private inner class Adapter : BaseAdapter() {
        private val df = DateFormat.getDateInstance(DateFormat.MEDIUM)
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val row = (convert as? LinearLayout) ?: LinearLayout(this@FilesActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_selected), rounded(C.GOLD, 10))
                    addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
                }
                val colors = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()), intArrayOf(C.DARK, C.TEXT))
                val sub = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()), intArrayOf(Color.parseColor("#3A3020"), C.MUTED))
                addView(label("", 24f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(44), WRAP_CONTENT))
                val texts = LinearLayout(this@FilesActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0); isDuplicateParentStateEnabled = true }
                texts.addView(label("", 17f).apply { setTextColor(colors); maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE; isDuplicateParentStateEnabled = true })
                texts.addView(label("", 13f).apply { setTextColor(sub); maxLines = 1; isDuplicateParentStateEnabled = true })
                addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
            val f = items[p]
            val k = kind(f)
            (row.getChildAt(0) as TextView).text = icon(k)
            val texts = row.getChildAt(1) as LinearLayout
            (texts.getChildAt(0) as TextView).text = f.name
            (texts.getChildAt(1) as TextView).text = if (k == "dir") {
                val n = dirCounts.getOrPut(f.path) { f.list()?.count { !it.startsWith(".") } ?: 0 }
                t("$n items", "$n عنصر") + "  ·  " + df.format(Date(f.lastModified()))
            } else Storage.size(f.length()) + "  ·  " + df.format(Date(f.lastModified())) + (if (clip.any { it.path == f.path }) "  ·  📋" else "")
            return row
        }
    }

    companion object { const val PORT = 8768 }
}
