package com.sahl.tv.files

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import java.io.File

/**
 * Sahl Union door of Sahl Files TV (only apps signed with the Sahl Family key can open it):
 *   content://com.sahl.tv.files.union/search?q=<query> -> name, path, size, modified
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
        if (uri.pathSegments.firstOrNull() != "search") return null
        val q = uri.getQueryParameter("q")?.trim()?.lowercase() ?: return null
        val out = MatrixCursor(arrayOf("name", "path", "size", "modified"))
        if (q.isEmpty()) return out
        val root = Environment.getExternalStorageDirectory()
        val roots = listOf("Download", "Documents", "Movies", "Music", "Pictures")
            .map { File(root, it) }
            .filter { it.isDirectory }
        var count = 0
        for (dir in roots) {
            val stack = ArrayDeque<File>()
            stack.add(dir)
            while (stack.isNotEmpty() && count < 50) {
                val cur = stack.removeFirst()
                val children = cur.listFiles() ?: continue
                for (f in children) {
                    if (count >= 50) break
                    if (f.name.startsWith(".")) continue
                    if (f.isDirectory) {
                        if (f.name != "Android") stack.addLast(f)
                    } else if (f.name.lowercase().contains(q)) {
                        out.addRow(arrayOf<Any>(f.name, f.absolutePath, f.length(), f.lastModified()))
                        count++
                    }
                }
            }
        }
        return out
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
