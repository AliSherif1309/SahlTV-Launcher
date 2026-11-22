package com.aly.sahltimer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aly.sahl.common.*

/** A single screen; timer ticks never rebuild controls or move remote focus. */
class TimerActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var clock: TextView
    private lateinit var status: TextView
    private lateinit var start: TextView
    private lateinit var overlay: TextView
    private lateinit var position: TextView
    private val tick = object : Runnable {
        override fun run() { if (refresh()) handler.postDelayed(this, 250) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false; clipToPadding = false
            setPadding(dp(40), dp(20), dp(40), dp(24)); background = backdrop()
            layoutDirection = if (isAr) android.view.View.LAYOUT_DIRECTION_RTL else android.view.View.LAYOUT_DIRECTION_LTR
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(content) })
        content.addView(label(t("Sahl Timer", "سهل · المؤقت"), 28f, C.GOLD, true))
        content.addView(label(t("Choose your time. Press Start.", "اختر المدة واضغط بدء."), 16f, C.MUTED))
        clock = label("", 76f, C.TEXT, true).apply {
            gravity = Gravity.CENTER; textDirection = android.view.View.TEXT_DIRECTION_LTR
            fontFeatureSettings = "tnum"
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        content.addView(clock)
        status = label("", 16f, C.MUTED); content.addView(status)
        fun row(): LinearLayout = LinearLayout(this).apply {
            gravity = Gravity.CENTER; clipChildren = false; clipToPadding = false
            setPadding(0, dp(10), 0, dp(6)); content.addView(this)
        }
        fun LinearLayout.button(text: String, action: () -> Unit): TextView = pill(text, action).also {
            addView(it, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(6), 0, dp(6), 0) })
        }
        val presets = row()
        for (m in intArrayOf(5, 10, 15, 25, 30)) presets.button(t("$m min", "$m د")) { chooseDuration(m) }
        presets.button(t("More…", "المزيد…")) {
            val minutes = intArrayOf(1, 5, 10, 15, 25, 30, 45, 60, 90, 120, 180)
            FontDialog(this).setTitle(t("Duration", "المدة"))
                .setItems(minutes.map { t("$it minutes", "$it دقيقة") }.toTypedArray()) { _, i -> chooseDuration(minutes[i]) }.show()
        }
        val controls = row()
        start = controls.button(t("Start", "بدء")) {
            change { val now = SystemClock.elapsedRealtime()
                if (state.running) state.pause(now) else { if (state.left == 0L) state.reset(); state.start(now); visible = true }
            }
        }
        controls.button(t("Reset", "إعادة ضبط")) { change { state.reset() } }
        val options = row()
        overlay = options.button("") { toggleOverlay() }
        position = options.button("") {
            val names = cornerNames()
            FontDialog(this).setTitle(t("Timer corner", "موضع المؤقت"))
                .setSingleChoiceItems(names, TimerStore(this).position) { d, i ->
                    change { position = i }; d.dismiss()
                }.show()
        }
        row().button(t("Sahl apps", "تطبيقات سهل")) { Family.show(this) }
        content.addView(label(t("Visual countdown • No alarm or TV shutdown", "عد تنازلي مرئي • بدون صوت أو إيقاف التلفاز"), 14f, C.MUTED))
        refresh()
        start.requestFocus()
    }

    private fun cornerNames() = arrayOf(t("Top left", "أعلى اليسار"), t("Top right", "أعلى اليمين"),
        t("Bottom left", "أسفل اليسار"), t("Bottom right", "أسفل اليمين"))

    private fun chooseDuration(minutes: Int) {
        fun apply() { change { state.select(minutes) } }
        val store = TimerStore(this)
        if (store.state.running || store.state.left != store.state.duration && store.state.left > 0)
            FontDialog(this).setTitle(t("Replace this countdown?", "استبدال العد التنازلي؟"))
                .setMessage(t("The new duration will be ready to start.", "ستكون المدة الجديدة جاهزة للبدء."))
                .setPositiveButton(t("Replace", "استبدال")) { _, _ -> apply() }
                .setNegativeButton(t("Cancel", "إلغاء"), null).show()
        else apply()
    }

    private fun change(action: TimerStore.() -> Unit) {
        TimerStore(this).apply { state.finish(SystemClock.elapsedRealtime()); action(); save() }
        syncTimerOverlay(this)
        handler.removeCallbacks(tick)
        if (refresh()) handler.postDelayed(tick, 250)
    }

    private fun toggleOverlay() {
        if (TimerStore(this).overlay) { change { overlay = false }; return }
        if (Settings.canDrawOverlays(this)) { change { overlay = true }; return }
        FontDialog(this).setTitle(t("Show above other apps", "إظهار فوق التطبيقات"))
            .setMessage(t("Allow Sahl Timer to display over other apps. Then return here and turn this option on. Some TVs and protected videos block overlays.",
                "اسمح لسهل المؤقت بالظهور فوق التطبيقات، ثم ارجع وفعّل هذا الخيار. قد تمنع بعض أجهزة التلفاز ومقاطع الفيديو المحمية ظهوره."))
            .setPositiveButton(t("Open settings", "فتح الإعدادات")) { _, _ ->
                try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                catch (_: RuntimeException) { toast(t("This TV does not offer overlay permission.", "هذا التلفاز لا يوفر إذن الظهور فوق التطبيقات.")) }
            }.setNegativeButton(t("Cancel", "إلغاء"), null).show()
    }

    private fun setIfChanged(tv: TextView, text: String) {
        if (tv.text?.toString() != text) tv.text = text
    }

    private fun refresh(): Boolean {
        val store = TimerStore(this)
        val now = SystemClock.elapsedRealtime()
        if (store.state.finish(now)) store.save()
        setIfChanged(clock, timerText(store.state.remaining(now)))
        setIfChanged(start, if (store.state.running) t("Pause", "إيقاف مؤقت") else if (store.state.left == 0L) t("Start again", "بدء مجددًا")
            else if (store.state.left < store.state.duration) t("Resume", "استئناف") else t("Start", "بدء"))
        setIfChanged(status, when {
            store.state.left == 0L -> t("Time is up", "انتهى الوقت")
            store.state.running -> t("Counting down", "العد التنازلي جارٍ")
            store.state.left < store.state.duration -> t("Paused", "متوقف مؤقتًا")
            else -> t("Ready", "جاهز")
        })
        setIfChanged(overlay, t("Above apps: ", "فوق التطبيقات: ") + if (store.overlay && Settings.canDrawOverlays(this)) t("On", "مفعّل") else t("Off", "متوقف"))
        setIfChanged(position, cornerNames()[store.position])
        return store.state.running
    }
    override fun onResume() {
        super.onResume()
        syncTimerOverlay(this)
        handler.removeCallbacks(tick)
        if (refresh()) handler.postDelayed(tick, 250)
    }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }
}
