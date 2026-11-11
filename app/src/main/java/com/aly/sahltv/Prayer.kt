package com.aly.sahltv

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Offline prayer times (PrayTimes.org algorithm), Qatar method:
 * Fajr: sun 18° below the horizon · Asr: shadow = object length (Shafi'i) ·
 * Maghrib: sunset · Isha: 90 minutes after Maghrib.
 */
object Prayer {
    private const val D2R = Math.PI / 180.0
    private const val R2D = 180.0 / Math.PI

    /** Times in hours (local, may exceed 24): fajr, sunrise, dhuhr, asr, maghrib, isha. */
    class Times(val fajr: Double, val sunrise: Double, val dhuhr: Double, val asr: Double, val maghrib: Double, val isha: Double)

    private fun fixHour(h: Double) = h - 24.0 * floor(h / 24.0)
    private fun fixAngle(a: Double) = a - 360.0 * floor(a / 360.0)

    private fun julian(d: LocalDate): Double {
        var y = d.year; var m = d.monthValue
        if (m <= 2) { y -= 1; m += 12 }
        val a = floor(y / 100.0); val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + d.dayOfMonth + b - 1524.5
    }

    /** returns (declination°, equation of time in hours) */
    private fun sunPos(jd: Double): Pair<Double, Double> {
        val d = jd - 2451545.0
        val g = fixAngle(357.529 + 0.98560028 * d)
        val q = fixAngle(280.459 + 0.98564736 * d)
        val l = fixAngle(q + 1.915 * sin(g * D2R) + 0.020 * sin(2 * g * D2R))
        val e = 23.439 - 0.00000036 * d
        val ra = atan2(cos(e * D2R) * sin(l * D2R), cos(l * D2R)) * R2D / 15.0
        val eqt = q / 15.0 - fixHour(ra)
        val decl = asin(sin(e * D2R) * sin(l * D2R)) * R2D
        return Pair(decl, eqt)
    }

    fun compute(date: LocalDate, lat: Double, lon: Double, tzHours: Double): Times {
        val jd = julian(date) - lon / (15.0 * 24.0)
        fun midDay(t: Double) = fixHour(12.0 - sunPos(jd + t).second)
        fun sunAngleTime(angle: Double, t: Double, ccw: Boolean): Double {
            val decl = sunPos(jd + t).first
            val noon = midDay(t)
            val x = (-sin(angle * D2R) - sin(decl * D2R) * sin(lat * D2R)) / (cos(decl * D2R) * cos(lat * D2R))
            val tt = acos(x.coerceIn(-1.0, 1.0)) * R2D / 15.0
            return noon + if (ccw) -tt else tt
        }
        fun asrTime(factor: Double, t: Double): Double {
            val decl = sunPos(jd + t).first
            val angle = -atan(1.0 / (factor + tan(abs(lat - decl) * D2R))) * R2D
            return sunAngleTime(angle, t, false)
        }
        // first pass with rough guesses, second pass refined with the first results
        var f = 5.0; var sr = 6.0; var dh = 12.0; var asr = 13.0; var ss = 18.0
        repeat(2) {
            val nf = sunAngleTime(18.0, f / 24.0, true)
            val nsr = sunAngleTime(0.833, sr / 24.0, true)
            val ndh = midDay(dh / 24.0)
            val nasr = asrTime(1.0, asr / 24.0)
            val nss = sunAngleTime(0.833, ss / 24.0, false)
            f = nf; sr = nsr; dh = ndh; asr = nasr; ss = nss
        }
        val adj = tzHours - lon / 15.0
        val mg = ss + adj
        return Times(f + adj, sr + adj, dh + adj, asr + adj, mg, mg + 1.5)
    }

    /** "HH:mm", rounded to the nearest minute. */
    fun hm(h: Double): String {
        val mins = Math.round(fixHour(h) * 60.0).toInt() % (24 * 60)
        return String.format(java.util.Locale.US, "%02d:%02d", mins / 60, mins % 60)
    }
}
