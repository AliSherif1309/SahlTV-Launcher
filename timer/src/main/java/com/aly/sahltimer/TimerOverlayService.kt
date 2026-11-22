package com.aly.sahltimer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import android.widget.TextView

/** Read-only overlay: never takes D-pad focus from the foreground app. Started only from visible Home. */
class TimerOverlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastSave = 0L
    private var label: TextView? = null
    private var lastPosition = -1
    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private val tick = object : Runnable {
        override fun run() {
            val store = TimerStore(this@TimerOverlayService)
            val now = SystemClock.elapsedRealtime()
            if (store.state.finish(now) || now - lastSave >= 1000) { store.save(); lastSave = now }
            if (!store.visible || !store.overlay || !Settings.canDrawOverlays(this@TimerOverlayService) || store.state.left == 0L) { stopSelf(); return }
            try {
                val pos = store.position
                val existing = label
                if (existing == null) {
                    val params = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT).apply {
                        gravity = timerGravity(pos); x = (32 * resources.displayMetrics.density).toInt(); y = (24 * resources.displayMetrics.density).toInt()
                        alpha = 0.8f
                    }
                    val created = timerLabel(this@TimerOverlayService)
                    created.text = timerText(store.state.remaining(now))
                    wm.addView(created, params)
                    label = created
                    lastPosition = pos
                } else {
                    val text = timerText(store.state.remaining(now))
                    if (existing.text?.toString() != text) existing.text = text
                    if (pos != lastPosition) {
                        val params = (existing.layoutParams as WindowManager.LayoutParams).apply { gravity = timerGravity(pos) }
                        wm.updateViewLayout(existing, params)
                        lastPosition = pos
                    }
                }
            } catch (_: RuntimeException) { store.overlay = false; stopSelf(); return }
            handler.postDelayed(this, 250)
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("timer", getString(R.string.timer_name), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, TimerActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            startForeground(7401, Notification.Builder(this, "timer").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(getString(R.string.timer_name)).setContentText(getString(R.string.timer_notification))
                .setContentIntent(open).setOngoing(true).build())
            handler.removeCallbacks(tick); handler.post(tick)
        } catch (_: RuntimeException) { TimerStore(this).overlay = false; stopSelf() }
        return START_NOT_STICKY
    }
    override fun onDestroy() { handler.removeCallbacks(tick); label?.let { try { wm.removeView(it) } catch (_: RuntimeException) {} }; label = null; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}

