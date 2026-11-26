package com.sahl.tv.timer

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.SystemClock

/**
 * Sahl Union door of Sahl Timer TV (only apps signed with the Sahl Family key can open it):
 *   content://com.sahl.tv.timer.union/state -> duration, remaining, running, stopwatchMode, elapsed
 */
class UnionProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        args: Array<out String>?,
        sort: String?
    ): Cursor? {
        val c = context ?: return null
        if (uri.pathSegments.firstOrNull() != "state") return null
        val store = TimerStore(c)
        val now = SystemClock.elapsedRealtime()
        val out = MatrixCursor(arrayOf("duration", "remaining", "running", "stopwatchMode", "elapsed"))
        out.addRow(
            arrayOf<Any>(
                store.state.duration,
                store.state.remaining(now),
                if (store.state.running || store.stopwatch.running) 1 else 0,
                if (store.stopwatchMode) 1 else 0,
                store.stopwatch.elapsed(now)
            )
        )
        return out
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
