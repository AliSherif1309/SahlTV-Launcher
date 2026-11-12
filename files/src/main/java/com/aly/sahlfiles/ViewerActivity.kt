package com.aly.sahlfiles

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.media.ExifInterface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/**
 * Built-in viewers:
 *  Pictures — ◀ ▶ previous / next picture in the folder, OK = menu (slideshow, rotate). Photos are turned the right way up.
 *  Text     — ▲ ▼ scroll, ◀ ▶ a page at a time.
 */
class ViewerActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var img: ImageView
    private lateinit var caption: TextView
    private var pics: List<File> = emptyList()
    private var index = 0
    private var rotation = 0
    private var slideshow = false
    private var gen = 0
    private var textScroll: ScrollView? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val f = File(intent.getStringExtra("path") ?: run { finish(); return })
        window.decorView.setBackgroundColor(Color.BLACK)
        if (intent.getStringExtra("mode") == "text") showText(f) else showImages(f)
    }

    override fun onDestroy() { super.onDestroy(); main.removeCallbacksAndMessages(null); exec.shutdownNow() }

    // ------------------------------------------------------------------ text

    private fun showText(f: File) {
        window.decorView.background = backdrop()
        val tv = label(t("Loading…", "جارٍ التحميل…"), 20f, C.TEXT).apply { typeface = Fonts.text();
            setPadding(dp(56), dp(30), dp(56), dp(40)); setLineSpacing(0f, 1.3f); setTextIsSelectable(false)
        }
        val sc = ScrollView(this).apply { isFocusable = true; addView(tv) }
        textScroll = sc
        val root = FrameLayout(this)
        root.addView(sc)
        root.addView(label(f.name, 14f, C.MUTED).apply { setPadding(dp(56), dp(8), dp(56), 0) }, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        setContentView(root)
        sc.requestFocus()
        exec.execute {
            val s = try { f.readText(Charsets.UTF_8) } catch (_: Exception) { null }
            main.post { tv.text = s ?: t("This file cannot be read.", "لا يمكن قراءة هذا الملف."); if (s != null && looksMono(s)) tv.typeface = Typeface.MONOSPACE }
        }
    }

    private fun looksMono(s: String) = s.lineSequence().take(40).count { it.startsWith("  ") || it.contains('\t') } > 8

    // ------------------------------------------------------------------ pictures

    private fun showImages(f: File) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        caption = label("", 16f, C.TEXT).apply {
            background = rounded(Color.parseColor("#CC0B1E1A"), 16); setPadding(dp(16), dp(7), dp(16), dp(7)); alpha = 0f
        }
        val root = FrameLayout(this)
        root.addView(img)
        root.addView(caption, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(24) })
        setContentView(root)
        val exts = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
        val all = (f.parentFile?.listFiles() ?: arrayOf(f)).filter { it.isFile && it.extension.lowercase() in exts && !it.name.startsWith(".") }
        pics = if (intent.getIntExtra("sort", 0) == 1) all.sortedByDescending { it.lastModified() } else all.sortedBy { it.name.lowercase() }
        index = pics.indexOfFirst { it.path == f.path }.coerceAtLeast(0)
        img.post { showPic() }
    }

    private fun showPic() {
        val f = pics.getOrNull(index) ?: return
        val my = ++gen
        val w = img.width.coerceAtLeast(1280); val h = img.height.coerceAtLeast(720)
        val rot = rotation
        exec.execute {
            val bmp = decode(f, w, h, rot)
            main.post {
                if (my != gen) return@post
                if (bmp != null) img.setImageBitmap(bmp) else img.setImageDrawable(null)
                note((if (bmp == null) t("Cannot show this picture  ·  ", "لا يمكن عرض هذه الصورة  ·  ") else "") + f.name + "   ${index + 1} / ${pics.size}")
            }
        }
    }

    /** Reads the picture at screen size (saves memory) and turns phone photos the right way up. */
    private fun decode(f: File, w: Int, h: Int, extraRot: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
        val exif = try {
            when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90; ExifInterface.ORIENTATION_ROTATE_180 -> 180; ExifInterface.ORIENTATION_ROTATE_270 -> 270; else -> 0
            }
        } catch (_: Exception) { 0 }
        val r = (exif + extraRot) % 360
        if (bmp != null && r != 0) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(r.toFloat()) }, true) else bmp
    } catch (_: Throwable) { null }

    private val hide = Runnable { caption.animate().alpha(0f).setDuration(400).start() }
    private fun note(s: String) {
        caption.text = s; caption.animate().alpha(1f).setDuration(150).start()
        main.removeCallbacks(hide); main.postDelayed(hide, 2500)
    }

    private fun step(d: Int) {
        if (pics.isEmpty()) return
        index = (index + d + pics.size) % pics.size; rotation = 0; showPic()
    }

    private val tick = object : Runnable { override fun run() { if (slideshow) { step(1); main.postDelayed(this, 6000) } } }

    private fun menu() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += (if (slideshow) t("⏸  Stop slideshow", "⏸  إيقاف العرض") else t("▶  Slideshow (every 6 seconds)", "▶  عرض تلقائي (كل 6 ثوانٍ)")) to {
            slideshow = !slideshow; main.removeCallbacks(tick); if (slideshow) main.postDelayed(tick, 6000)
            note(if (slideshow) t("Slideshow on", "العرض التلقائي يعمل") else t("Slideshow off", "توقف العرض التلقائي"))
        }
        items += t("↻  Rotate", "↻  تدوير") to { rotation = (rotation + 90) % 360; showPic() }
        items += t("ℹ  Name", "ℹ  الاسم") to { pics.getOrNull(index)?.let { note(it.name + "   ${index + 1} / ${pics.size}") } }
        Dlg.menu(this, pics.getOrNull(index)?.name, items)
    }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        textScroll?.let { sc ->
            when (code) {
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { sc.pageScroll(android.view.View.FOCUS_DOWN); return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { sc.pageScroll(android.view.View.FOCUS_UP); return true }
            }
            return super.onKeyDown(code, e)
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_CHANNEL_DOWN -> { step(1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_CHANNEL_UP -> { step(-1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU -> { if (e.repeatCount == 0) menu(); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { pics.getOrNull(index)?.let { note(it.name + "   ${index + 1} / ${pics.size}") }; return true }
        }
        return super.onKeyDown(code, e)
    }
}
