package com.sahl.tv.common

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Sahl Union (TV edition) — shared signature-protected messaging, data doors, and crash reporting
 * across Sahl TV apps (`com.sahl.tv.*`) signed with the Sahl Family key.
 */
object SahlUnion {
    /** Signature permission: only apps signed with the Sahl Family key hold it. */
    const val PERMISSION = "com.sahl.permission.UNION"

    /** Broadcast every Sahl app can listen to. Extras: [EXTRA_EVENT], [EXTRA_FROM]. */
    const val ACTION_EVENT = "com.sahl.action.EVENT"
    const val EXTRA_EVENT = "event"
    const val EXTRA_FROM = "from"

    const val EVENT_PREFS_CHANGED = "prefs_changed"
    const val EVENT_THEME_CHANGED = "theme_changed"
    const val EVENT_PRAYER_CHANGED = "prayer_changed"
    const val EVENT_TIMER_CHANGED = "timer_changed"

    // TV-scoped Sahl Union data doors (only Sahl-signed apps can open them)
    const val TV_UNION = "content://com.sahl.tv.union"
    const val TV_FILES_UNION = "content://com.sahl.tv.files.union"
    const val TV_TIMER_UNION = "content://com.sahl.tv.timer.union"

    /** Announce an event to all Sahl apps holding [PERMISSION]. */
    fun announce(c: Context, event: String, extras: Bundle? = null) {
        val i = Intent(ACTION_EVENT)
            .putExtra(EXTRA_EVENT, event)
            .putExtra(EXTRA_FROM, c.packageName)
        if (extras != null) i.putExtras(extras)
        c.sendBroadcast(i, PERMISSION)
    }

    /** Saves any uncaught crash and opens a plain report screen. */
    fun catchCrashes(c: Context) {
        val app = c.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is CrashHandler) return
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(app, prev))
    }

    private class CrashHandler(
        private val app: Context,
        private val prev: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                val v = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName } catch (_: Exception) { "?" }
                val log = "${app.packageName} v$v\n" + android.util.Log.getStackTraceString(e).take(6000)
                app.startActivity(
                    Intent(app, SahlCrashActivity::class.java)
                        .putExtra("log", log)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            } catch (_: Exception) {
                prev?.uncaughtException(t, e)
            }
        }
    }
}

/** Plain crash-report screen (no Sahl UI code, so it cannot crash itself). */
class SahlCrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val log = intent.getStringExtra("log") ?: "No report"
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        box.addView(TextView(this).apply { text = "Sahl TV crash report"; textSize = 22f })
        box.addView(Button(this).apply {
            text = "Copy report"
            setOnClickListener {
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("crash", log))
                Toast.makeText(this@SahlCrashActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        })
        box.addView(
            ScrollView(this).apply {
                addView(TextView(this@SahlCrashActivity).apply {
                    text = log
                    textSize = 11f
                    setTextIsSelectable(true)
                })
            },
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
        setContentView(box)
    }
}
