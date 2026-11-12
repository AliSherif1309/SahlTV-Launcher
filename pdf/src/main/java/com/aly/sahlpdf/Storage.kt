package com.aly.sahlpdf

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/** Access to the TV's shared storage (Download, Movies, USB…). Android 10 asks with a normal Allow window. */
object Storage {
    const val REQ = 41

    fun granted(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun ask(a: Activity) {
        if (Build.VERSION.SDK_INT >= 30) {
            val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + a.packageName))
            try { a.startActivity(i) } catch (_: Exception) {
                Dlg.message(a, t("Allow file access", "السماح بالوصول إلى الملفات"),
                    t("This TV has no settings screen for it. On your PC run:\n\nadb shell appops set ${a.packageName} MANAGE_EXTERNAL_STORAGE allow",
                      "لا توجد شاشة إعدادات لذلك على هذا التلفاز. على الكمبيوتر شغّل:\n\nadb shell appops set ${a.packageName} MANAGE_EXTERNAL_STORAGE allow"))
            }
        } else a.requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ)
    }

    @Suppress("DEPRECATION")
    val root: File get() = Environment.getExternalStorageDirectory()
    @Suppress("DEPRECATION")
    fun pub(type: String): File = Environment.getExternalStoragePublicDirectory(type)

    /** USB drives and SD cards plugged into the TV. */
    fun removable(): List<File> = (File("/storage").listFiles() ?: emptyArray())
        .filter { it.isDirectory && it.name != "emulated" && it.name != "self" && it.canRead() }
        .sortedBy { it.name }

    fun size(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024L * 1024 -> "${b / 1024} KB"
        b < 1024L * 1024 * 1024 -> String.format("%.1f MB", b / 1048576.0)
        else -> String.format("%.2f GB", b / 1073741824.0)
    }
}
