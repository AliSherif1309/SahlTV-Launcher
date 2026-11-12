package com.aly.sahltv

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.aly.sahl.common.Fonts
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Morning / evening adhkar, one at a time, calm and large.
 *  OK = count one (moves on by itself when the count is reached) · ◀ ▶ previous / next · Back = leave (progress is kept for today)
 */
class AdhkarActivity : Activity() {
    companion object {
        fun load(a: Activity): JSONObject? = try { JSONObject(a.assets.open("adhkar.json").bufferedReader().use { it.readText() }) } catch (_: Exception) { null }
        fun progressKey(kind: String) = "adhkar_${kind}_${LocalDate.now()}"
    }

    private lateinit var items: JSONArray
    private lateinit var kind: String
    private var index = 0
    private var count = 0
    private val ar get() = getSharedPreferences("sahl", MODE_PRIVATE).getString("lang", null)?.let { it == "ar" }
        ?: (resources.configuration.locales[0].language == "ar")
    private fun t(en: String, a: String) = if (ar) a else en
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val prefs by lazy { getSharedPreferences("sahl", MODE_PRIVATE) }

    private lateinit var title: TextView
    private lateinit var text: TextView
    private lateinit var counter: TextView
    private lateinit var source: TextView
    private lateinit var progress: TextView
    private val quranFont: Typeface? by lazy { try { Typeface.createFromAsset(assets, "AmiriQuran.ttf") } catch (_: Exception) { null } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        kind = if (intent.getStringExtra("kind") == "e") "e" else "m"
        items = load(this)?.optJSONArray(kind) ?: run { finish(); return }
        val gold = Color.parseColor("#E3B85C")
        val root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#0B1A17"), Color.parseColor("#0E241D")))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(80), dp(36), dp(80), dp(30)) }
        title = TextView(this).apply { textSize = 30f; setTextColor(gold); typeface = Fonts.display(true); gravity = Gravity.CENTER
            text = if (kind == "m") t("Morning adhkar", "أذكار الصباح") else t("Evening adhkar", "أذكار المساء"); Fonts.heading(this) }
        progress = TextView(this).apply { textSize = 15f; setTextColor(Color.parseColor("#8FA39C")); gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(18)) }
        text = TextView(this).apply { setTextColor(Color.parseColor("#F3EBDA")); gravity = Gravity.CENTER; setLineSpacing(0f, 1.45f); textDirection = View.TEXT_DIRECTION_RTL }
        counter = TextView(this).apply {
            textSize = 30f; setTextColor(Color.parseColor("#0B1E1A")); typeface = Fonts.get(2); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(gold) }
        }
        source = TextView(this).apply { textSize = 14f; setTextColor(Color.parseColor("#8FA39C")); gravity = Gravity.CENTER; typeface = Fonts.text(); setPadding(0, dp(14), 0, 0) }
        val hint = TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#6E7C76")); gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0)
            text = t("OK = count  ·  ◀ ▶ previous / next  ·  Back = later", "OK = عدّ  ·  ◀ ▶ السابق / التالي  ·  رجوع = لاحقًا") }
        col.addView(title); col.addView(progress)
        col.addView(text, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(counter, LinearLayout.LayoutParams(dp(86), dp(86)).apply { topMargin = dp(10) })
        col.addView(source); col.addView(hint)
        root.addView(col)
        setContentView(root)
        // continue where you stopped today
        val saved = prefs.getString(progressKey(kind), "0:0")!!.split(":")
        index = saved.getOrNull(0)?.toIntOrNull()?.coerceIn(0, items.length() - 1) ?: 0
        count = saved.getOrNull(1)?.toIntOrNull() ?: 0
        show()
    }

    private fun digits(n: Int) = if (ar) n.toString().map { '٠' + (it - '0') }.joinToString("") else n.toString()

    private fun show() {
        val o = items.getJSONObject(index)
        val quran = o.optBoolean("quran")
        val body = o.getString("text")
        text.text = body
        text.typeface = if (quran) quranFont ?: Fonts.text() else Fonts.text()
        // long texts get a smaller size so everything fits on one screen
        text.textSize = when { body.length > 420 -> 22f; body.length > 260 -> 25f; body.length > 140 -> 28f; else -> 32f }
        val n = o.getInt("n")
        counter.text = if (n == 1) "✓" else digits(n - count)
        counter.alpha = if (n == 1 && count == 0) 0.55f else 1f
        source.text = o.optString("src")
        progress.text = t("${index + 1} of ${items.length()}", "${digits(index + 1)} من ${digits(items.length())}") + if (n > 1) t("  ·  repeat $n times", "  ·  تُكرّر ${digits(n)} مرات") else ""
        prefs.edit().putString(progressKey(kind), "$index:$count").apply()
    }

    private fun countOne() {
        val n = items.getJSONObject(index).getInt("n")
        count++
        if (count >= n) next() else show()
    }

    private fun next() {
        if (index >= items.length() - 1) { done(); return }
        index++; count = 0; show()
    }

    private fun prev() { if (index > 0) { index--; count = 0; show() } }

    private fun done() {
        prefs.edit().putString(progressKey(kind), "done").apply()
        text.typeface = Fonts.display(true); text.textSize = 40f
        text.text = t("May Allah accept from you ✓", "تقبّل الله منك ✓")
        counter.text = "✓"; counter.alpha = 1f; source.text = ""; progress.text = ""
        text.postDelayed({ finish() }, 1800)
    }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        if (prefs.getString(progressKey(kind), "") == "done") return super.onKeyDown(code, e)
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { if (e.repeatCount == 0) countOne(); return true }
            // right-to-left: ◀ goes forward
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { next(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_REWIND -> { prev(); return true }
        }
        return super.onKeyDown(code, e)
    }
}
