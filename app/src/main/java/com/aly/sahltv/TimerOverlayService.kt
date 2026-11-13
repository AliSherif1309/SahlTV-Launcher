package com.aly.sahltv

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
    private var label: TextView? = null
    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private val tick = object : Runnable {
        override fun run() {
            val store = TimerStore(this@TimerOverlayService)
            if (store.state.finish(SystemClock.elapsedRealtime())) store.save()
            if (!store.visible || !store.overlay || !Settings.canDrawOverlays(this@TimerOverlayService) || store.state.left == 0L) { stopSelf(); return }
            try {
                val params = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT).apply {
                    gravity = timerGravity(store.position); x = (32 * resources.displayMetrics.density).toInt(); y = (24 * resources.displayMetrics.density).toInt()
                    alpha = 0.8f
                }
                val view = label ?: timerLabel(this@TimerOverlayService).also { wm.addView(it, params); label = it }
                view.text = timerText(store.state.remaining(SystemClock.elapsedRealtime()))
                wm.updateViewLayout(view, params)
            } catch (_: RuntimeException) { store.overlay = false; stopSelf(); return }
            handler.postDelayed(this, 250)
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("timer", getString(R.string.timer_name), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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
