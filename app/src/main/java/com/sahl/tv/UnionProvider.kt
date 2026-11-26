package com.sahl.tv

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import kotlin.math.roundToLong

/**
 * Sahl Union door of Sahl TV Launcher (only apps signed with the Sahl Family key can open it):
 *   content://com.sahl.tv.union/prayers -> index (0 Fajr..4 Isha), name, time (epoch ms) for today and tomorrow
 *   content://com.sahl.tv.union/prefs   -> scale, night, lang, arDigits, city
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
        val path = uri.pathSegments.firstOrNull() ?: return null
        return when (path) {
            "prayers" -> queryPrayers(c)
            "prefs" -> queryPrefs(c)
            else -> null
        }
    }

    private fun queryPrayers(c: Context): Cursor? = try {
        val prefs = c.getSharedPreferences("sahl", Context.MODE_PRIVATE)
        val idx = prefs.getInt("city", 0)
        if (idx !in Alerts.CITIES.indices) return MatrixCursor(arrayOf("index", "name", "time"))
        Prayer.load(c)
        val (_, la, lo) = Alerts.CITIES[idx]
        val zone = ZoneId.systemDefault()
        val tz = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3_600_000.0
        val ar = prefs.getString("lang", null) == "ar" ||
            (prefs.getString("lang", null) == null && c.resources.configuration.locales[0].language == "ar")
        val names = if (ar) arrayOf("الفجر", "الظهر", "العصر", "المغرب", "العشاء")
        else arrayOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")
        val out = MatrixCursor(arrayOf("index", "name", "time"))
        val today = LocalDate.now(zone)
        for (d in listOf(today, today.plusDays(1))) {
            val t = Prayer.compute(d, la, lo, tz)
            val baseMs = d.atStartOfDay(zone).toInstant().toEpochMilli()
            val hours = doubleArrayOf(t.fajr, t.dhuhr, t.asr, t.maghrib, t.isha)
            hours.forEachIndexed { i, h ->
                out.addRow(arrayOf<Any>(i, names[i], baseMs + (h * 3_600_000.0).roundToLong()))
            }
        }
        out
    } catch (_: Exception) {
        null
    }

    private fun queryPrefs(c: Context): Cursor {
        val prefs = c.getSharedPreferences("sahl", Context.MODE_PRIVATE)
        val out = MatrixCursor(arrayOf("scale", "night", "lang", "arDigits", "city"))
        out.addRow(
            arrayOf<Any>(
                prefs.getFloat("fontScale", 1f).coerceIn(1f, 1.25f),
                if (PrefsProvider.isNight(c)) 1 else 0,
                prefs.getString("lang", "") ?: "",
                if (prefs.getBoolean("arDigits", true)) 1 else 0,
                prefs.getInt("city", 0)
            )
        )
        return out
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
