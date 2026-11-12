package com.aly.sahltv

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/** Lets the other Sahl apps read one setting: the text size chosen in Sahl TV (read only, nothing private). */
class PrefsProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val c = MatrixCursor(arrayOf("scale"))
        c.addRow(arrayOf(context!!.getSharedPreferences("sahl", android.content.Context.MODE_PRIVATE).getFloat("textScale", 1f)))
        return c
    }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
