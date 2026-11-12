package com.aly.sahlfiles

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Lets another app (Sahl PDF, a video player, the app installer) open ONE file you chose, read-only.
 * Not public: an app can only read a file when Sahl Files hands it that file's link.
 */
class FilesProvider : ContentProvider() {
    companion object {
        const val AUTH = "com.aly.sahlfiles.files"
        fun uri(f: File): Uri = Uri.Builder().scheme("content").authority(AUTH).path(f.absolutePath).build()

        fun mime(f: File): String {
            val ext = f.extension.lowercase()
            return when (ext) {
                "apk" -> "application/vnd.android.package-archive"
                "mkv" -> "video/x-matroska"
                "srt" -> "text/plain"
                else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            }
        }
    }

    private fun file(uri: Uri): File {
        val f = File(uri.path ?: throw FileNotFoundException()).canonicalFile
        val p = f.path
        // only shared storage (internal storage, USB); never the apps' private folders
        if (!(p.startsWith("/storage/") || p.startsWith("/sdcard/") || p.startsWith("/mnt/media_rw/")) || p.contains("/Android/data/") || p.contains("/Android/obb/"))
            throw FileNotFoundException("not shared")
        if (!f.isFile) throw FileNotFoundException()
        return f
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = try { mime(file(uri)) } catch (_: Exception) { null }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = file(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map { when (it) { OpenableColumns.DISPLAY_NAME -> f.name; OpenableColumns.SIZE -> f.length(); else -> null } }.toTypedArray())
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
