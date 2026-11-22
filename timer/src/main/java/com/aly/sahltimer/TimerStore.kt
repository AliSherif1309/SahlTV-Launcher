package com.aly.sahltimer

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
    val stopwatch = StopwatchState(prefs.getLong("sw_acc", 0L).coerceAtLeast(0L),
        if (boot >= 0 && boot == prefs.getInt("boot", -2)) prefs.getLong("sw_start", 0L) else 0L)
    var stopwatchMode: Boolean
        get() = prefs.getBoolean("sw_mode", false)
        set(value) { prefs.edit().putBoolean("sw_mode", value).apply() }
    var visible: Boolean
        get() = prefs.getBoolean("visible", false)
        set(value) { prefs.edit().putBoolean("visible", value).apply() }
    var overlay: Boolean
        get() = prefs.getBoolean("overlay", false)
        set(value) { prefs.edit().putBoolean("overlay", value).apply() }
    var position: Int
        get() = prefs.getInt("position", 1).coerceIn(0, 3)
        set(value) { prefs.edit().putInt("position", value.coerceIn(0, 3)).apply() }
    fun save() {
        val now = SystemClock.elapsedRealtime()
        prefs.edit().putLong("duration", state.duration).putLong("left", state.remaining(now))
            .putLong("deadline", state.deadline)
            .putLong("sw_acc", stopwatch.elapsed(now)).putLong("sw_start", stopwatch.startedAt)
            .putInt("boot", boot).apply()
    }
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
internal fun syncTimerOverlay(context: Context) {
    val store = TimerStore(context)
    val intent = Intent(context, TimerOverlayService::class.java)
    val active = store.stopwatchMode || store.state.remaining(SystemClock.elapsedRealtime()) > 0
    if (store.visible && store.overlay && active && Settings.canDrawOverlays(context)) {
        try { context.startForegroundService(intent) } catch (_: RuntimeException) {
            store.overlay = false
            android.widget.Toast.makeText(context, R.string.timer_overlay_failed, android.widget.Toast.LENGTH_LONG).show()
        }
    } else context.stopService(intent)
}
