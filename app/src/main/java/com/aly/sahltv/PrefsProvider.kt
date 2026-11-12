package com.aly.sahltv

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/** Lets the other Sahl apps read two settings: the text size, and whether night style is on now (read only, nothing private). */
class PrefsProvider : ContentProvider() {
    companion object {
        /** Night style is on and it is between Isha and Fajr (times of day kept by the home screen). */
        fun isNight(ctx: android.content.Context): Boolean {
            val p = ctx.getSharedPreferences("sahl", android.content.Context.MODE_PRIVATE)
            if (!p.getBoolean("nightStyle", true)) return false
            val isha = p.getInt("ishaMin", 19 * 60); val fajr = p.getInt("fajrMin", 4 * 60 + 30)
            val now = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
            return now >= isha || now < fajr
        }
    }

    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val ctx = context!!
        val c = MatrixCursor(arrayOf("scale", "night"))
        c.addRow(arrayOf(ctx.getSharedPreferences("sahl", android.content.Context.MODE_PRIVATE).getFloat("textScale", 1f), if (isNight(ctx)) 1 else 0))
        return c
    }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
