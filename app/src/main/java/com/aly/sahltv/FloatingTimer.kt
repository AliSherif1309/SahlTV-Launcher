package com.aly.sahltv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/** Shared, persisted state. All access is on the main thread. Never stores Activity references. */
internal class TimerStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("floating_timer", Context.MODE_PRIVATE)
    private val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    val state = TimerState(prefs.getLong("duration", 300_000L).coerceIn(60_000L, 10_800_000L),
        prefs.getLong("left", 300_000L).coerceIn(0L, 10_800_000L),
        if (boot >= 0 && boot == prefs.getInt("boot", -2)) prefs.getLong("deadline", 0) else 0)
    var visible: Boolean
        get() = prefs.getBoolean("visible", false)
        set(value) { prefs.edit().putBoolean("visible", value).apply() }
    var overlay: Boolean
        get() = prefs.getBoolean("overlay", false)
        set(value) { prefs.edit().putBoolean("overlay", value).apply() }
    var position: Int
        get() = prefs.getInt("position", 1).coerceIn(0, 3)
        set(value) { prefs.edit().putInt("position", value.coerceIn(0, 3)).apply() }
    fun save() { prefs.edit().putLong("duration", state.duration).putLong("left", state.remaining(SystemClock.elapsedRealtime()))
        .putLong("deadline", state.deadline).putInt("boot", boot).apply() }
}
internal fun timerGravity(position: Int) = when (position) {
    0 -> Gravity.TOP or Gravity.LEFT
    1 -> Gravity.TOP or Gravity.RIGHT
    2 -> Gravity.BOTTOM or Gravity.LEFT
    else -> Gravity.BOTTOM or Gravity.RIGHT
}
internal fun timerLabel(context: Context) = TextView(context).apply {
    textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
    val d = resources.displayMetrics.density
    setPadding((16*d).toInt(), (8*d).toInt(), (16*d).toInt(), (8*d).toInt())
    background = GradientDrawable().apply { setColor(Color.rgb(11,30,26)); cornerRadius = 12*d; setStroke((2*d).toInt(), Color.rgb(227,184,92)) }
    isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}
internal class HomeTimer(private val activity: MainActivity) {
    private var label: TextView? = null
    private val tick = object : Runnable {
        override fun run() {
            val store = TimerStore(activity)
            if (store.state.finish(SystemClock.elapsedRealtime())) store.save()
            if (store.visible) {
                val view = label ?: timerLabel(activity).also { label = it; activity.rootFrame.addView(it) }
                view.text = activity.digits(timerText(store.state.remaining(SystemClock.elapsedRealtime())))
                view.layoutParams = FrameLayout.LayoutParams(-2, -2, timerGravity(store.position)).apply { setMargins(activity.dp(32), activity.dp(24), activity.dp(32), activity.dp(24)) }
                view.visibility = if (activity.settingsView == null && activity.saver == null && !(store.overlay && store.state.remaining(SystemClock.elapsedRealtime()) > 0 && Settings.canDrawOverlays(activity))) View.VISIBLE else View.GONE
            } else { label?.let { activity.rootFrame.removeView(it) }; label = null }
            activity.handler.postDelayed(this, 250)
        }
    }
    fun resume() { activity.handler.removeCallbacks(tick); activity.handler.post(tick)
        syncTimerOverlay(activity) }
    fun pause() { activity.handler.removeCallbacks(tick); label?.visibility = View.GONE }
}
internal fun syncTimerOverlay(context: Context) {
    val store = TimerStore(context)
    val intent = Intent(context, TimerOverlayService::class.java)
    if (store.visible && store.overlay && store.state.remaining(SystemClock.elapsedRealtime()) > 0 && Settings.canDrawOverlays(context)) {
        try { context.startForegroundService(intent) } catch (_: RuntimeException) {
            store.overlay = false
            android.widget.Toast.makeText(context, R.string.timer_overlay_failed, android.widget.Toast.LENGTH_LONG).show()
        }
    } else context.stopService(intent)
}
internal fun MainActivity.timerMenu(): android.app.AlertDialog {
    val store = TimerStore(this)
    val state = store.state
    state.finish(SystemClock.elapsedRealtime()); store.save()
    val actions = arrayOf(
        t(if (state.running) "Pause" else "Start / resume", if (state.running) "إيقاف مؤقت" else "بدء / استئناف"),
        t("Reset", "إعادة ضبط"), t("Duration", "المدة"),
        t("Show timer: ", "إظهار المؤقت: ") + onOff(store.visible),
        t("Position", "الموضع"), t("Above other apps: ", "فوق التطبيقات الأخرى: ") + onOff(store.overlay),
        t("Overlay permission & limitations", "إذن الظهور والقيود"))
    return dialog().setTitle(t("Floating timer", "المؤقت العائم") + " · " + digits(timerText(state.remaining(SystemClock.elapsedRealtime()))))
        .setItems(actions) { _, which ->
            fun changed() { store.save(); syncTimerOverlay(this); timerMenu() }
            when (which) {
                0 -> { if (state.running) state.pause(SystemClock.elapsedRealtime()) else { state.start(SystemClock.elapsedRealtime()); store.visible = true }; changed() }
                1 -> { state.reset(); changed() }
                2 -> { val minutes = intArrayOf(1, 5, 10, 15, 25, 30, 45, 60, 90, 120, 180)
                    dialog().setTitle(t("Minutes", "دقائق")).setItems(minutes.map { digits(it.toString()) }.toTypedArray()) { _, i -> state.select(minutes[i]); changed() }.show() }
                3 -> { store.visible = !store.visible; changed() }
                4 -> dialog().setTitle(t("Position", "الموضع")).setSingleChoiceItems(arrayOf(t("Top left", "أعلى اليسار"), t("Top right", "أعلى اليمين"), t("Bottom left", "أسفل اليسار"), t("Bottom right", "أسفل اليمين")), store.position) { d, i -> store.position = i; d.dismiss(); changed() }.show()
                5 -> { if (store.overlay) { store.overlay = false; changed() }
                    else if (Settings.canDrawOverlays(this)) { store.overlay = true; changed() }
                    else timerPermissionHelp() }
                6 -> timerPermissionHelp()
            }
        }.setNegativeButton(t("Close", "إغلاق"), null).show()
}
private fun MainActivity.timerPermissionHelp() {
    dialog().setTitle(t("Above other apps", "فوق التطبيقات الأخرى"))
        .setMessage(getString(R.string.timer_overlay_help))
        .setPositiveButton(t("Open permission settings", "فتح إعدادات الإذن")) { _, _ ->
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: RuntimeException) { toast(getString(R.string.timer_overlay_failed)) }
        }.setNegativeButton(t("Close", "إغلاق"), null).show()
}
