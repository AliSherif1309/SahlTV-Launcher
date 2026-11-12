package com.aly.sahltv

import com.aly.sahl.common.Fonts
import com.aly.sahl.common.FontDialog
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import com.aly.sahltv.MainActivity.*

// Backup and restore of the launcher's settings.  (part of the Sahl TV home screen, split out of MainActivity)

internal fun MainActivity.backupMenu() {
    dialog().setTitle(t("Backup & restore", "النسخ الاحتياطي والاستعادة"))
        .setItems(arrayOf(
            t("💾  Back up settings now", "💾  نسخ الإعدادات احتياطيًا الآن"),
            t("♻  Restore settings from backup", "♻  استعادة الإعدادات من النسخة"),
        )) { _, which ->
            if (which == 0) { if (storageOk(true)) doBackup() else askStorage(8) }
            else guarded { if (storageOk(false)) doRestore() else askStorage(9) }
        }.show()
}

internal fun MainActivity.storageOk(write: Boolean): Boolean {
    if (Build.VERSION.SDK_INT >= 30) return true          // MediaStore: no permission needed for our own file
    val perm = if (write) "android.permission.WRITE_EXTERNAL_STORAGE" else "android.permission.READ_EXTERNAL_STORAGE"
    return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
}

internal fun MainActivity.askStorage(code: Int) =
    requestPermissions(arrayOf("android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.READ_EXTERNAL_STORAGE"), code)

internal fun MainActivity.storageHelp() {
    dialog().setTitle(t("Storage permission needed", "يلزم إذن التخزين"))
        .setMessage(t("If no prompt appeared on the TV, run once from your PC:\n\nadb shell pm grant com.aly.sahltv android.permission.WRITE_EXTERNAL_STORAGE\nadb shell pm grant com.aly.sahltv android.permission.READ_EXTERNAL_STORAGE",
                      "إذا لم تظهر نافذة طلب الإذن على التلفاز، نفّذ هذا مرة واحدة من الكمبيوتر:\n\nadb shell pm grant com.aly.sahltv android.permission.WRITE_EXTERNAL_STORAGE\nadb shell pm grant com.aly.sahltv android.permission.READ_EXTERNAL_STORAGE"))
        .setPositiveButton(t("OK", "حسنًا"), null).show()
}

internal fun MainActivity.settingsJson(): String {
    val o = org.json.JSONObject()
    o.put("app", "SahlTV"); o.put("version", 1)
    val data = org.json.JSONObject()
    for ((k, v) in prefs.all) {
        if (k.startsWith("n_")) { data.put(k, org.json.JSONObject().put("t", "i").put("v", v)); continue }
        when (v) {
            is Boolean -> data.put(k, org.json.JSONObject().put("t", "b").put("v", v))
            is Int -> data.put(k, org.json.JSONObject().put("t", "i").put("v", v))
            is String -> data.put(k, org.json.JSONObject().put("t", "s").put("v", v))
        }
    }
    o.put("settings", data)
    return o.toString(2)
}

internal fun MainActivity.doBackup() {
    worker.execute {
        val ok = try {
            val text = settingsJson().toByteArray()
            if (Build.VERSION.SDK_INT >= 30) {
                val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                contentResolver.delete(uri, MediaStore.MediaColumns.DISPLAY_NAME + "=?", arrayOf(backupName))
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, backupName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val item = contentResolver.insert(uri, cv) ?: throw IllegalStateException()
                contentResolver.openOutputStream(item)?.use { it.write(text) }
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                java.io.File(dir, backupName).writeBytes(text)
            }
            true
        } catch (_: Exception) { false }
        handler.post {
            if (!ok) { storageHelp(); return@post }
            dialog().setTitle(t("✓ Backup saved", "✓ تم حفظ النسخة الاحتياطية"))
                .setMessage(t("Saved to Download/$backupName on the TV.\n\nTo keep a copy on your PC:\nadb pull /sdcard/Download/$backupName\n\nTo restore after a reinstall, first copy it back:\nadb push $backupName /sdcard/Download/",
                              "تم الحفظ في Download/$backupName على التلفاز.\n\nللاحتفاظ بنسخة على الكمبيوتر:\nadb pull /sdcard/Download/$backupName\n\nللاستعادة بعد إعادة التثبيت، انسخها أولًا إلى التلفاز:\nadb push $backupName /sdcard/Download/"))
                .setPositiveButton(t("OK", "حسنًا"), null).show()
        }
    }
}

internal fun MainActivity.doRestore() {
    worker.execute {
        val text: String? = try {
            if (Build.VERSION.SDK_INT >= 30) {
                val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                contentResolver.query(uri, arrayOf("_id"), MediaStore.MediaColumns.DISPLAY_NAME + "=?", arrayOf(backupName), null)?.use { c ->
                    if (c.moveToNext()) contentResolver.openInputStream(android.content.ContentUris.withAppendedId(uri, c.getLong(0)))
                        ?.use { it.readBytes().toString(Charsets.UTF_8) } else null
                }
            } else {
                @Suppress("DEPRECATION")
                val f = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), backupName)
                if (f.exists()) f.readText() else null
            }
        } catch (_: Exception) { null }
        handler.post {
            if (text == null) {
                dialog().setTitle(t("No backup found", "لم يتم العثور على نسخة"))
                    .setMessage(t("Put $backupName in the TV's Download folder first:\nadb push $backupName /sdcard/Download/",
                                  "ضع الملف $backupName في مجلد Download على التلفاز أولًا:\nadb push $backupName /sdcard/Download/"))
                    .setPositiveButton(t("OK", "حسنًا"), null).show()
                return@post
            }
            try {
                val data = org.json.JSONObject(text).getJSONObject("settings")
                val e = prefs.edit().clear()
                for (k in data.keys()) {
                    val o = data.getJSONObject(k)
                    when (o.getString("t")) {
                        "b" -> e.putBoolean(k, o.getBoolean("v"))
                        "i" -> e.putInt(k, o.getInt("v"))
                        else -> e.putString(k, o.getString("v"))
                    }
                }
                e.apply()
                toast(t("✓ Settings restored", "✓ تمت استعادة الإعدادات"))
                recreate()
            } catch (_: Exception) { toast(t("That backup file can't be read", "تعذّرت قراءة ملف النسخة")) }
        }
    }
}
