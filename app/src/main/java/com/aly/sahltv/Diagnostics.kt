package com.aly.sahltv

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.ViewTreeObserver
import android.view.Window
import org.json.JSONArray
import org.json.JSONObject

/**
 * Measured, not assumed: how fast Sahl TV starts, how smooth it is, how much memory it uses,
 * and whether "free memory" helps for more than a moment (or apps simply refill the memory a few minutes later).
 * Everything stays on the TV; shown in Settings → Diagnostics.
 */
object Diagnostics {
    private const val MB = 1024L * 1024
    private val main = Handler(Looper.getMainLooper())
    private fun sp(ctx: Context) = ctx.getSharedPreferences("diag", Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- start-up time
    private var createdAt = 0L

    /** Call at the start of MainActivity.onCreate. */
    fun onCreate() { createdAt = SystemClock.uptimeMillis(); readyMarked = false }

    /** Measures until the first frame is drawn. Cold = the app process had just started; warm = the screen was re-created. */
    fun watchFirstFrame(a: Activity) {
        val view = a.window.decorView
        val listener = object : ViewTreeObserver.OnDrawListener {
            var done = false
            override fun onDraw() {
                if (done) return
                done = true
                main.post { view.viewTreeObserver.removeOnDrawListener(this) }
                val now = SystemClock.uptimeMillis()
                val procStart = Process.getStartUptimeMillis()
                val cold = createdAt - procStart < 3_000
                val ms = if (cold) now - procStart else now - createdAt
                add(a, if (cold) "cold" else "warm", ms.toDouble())
            }
        }
        view.viewTreeObserver.addOnDrawListener(listener)
    }

    private fun add(ctx: Context, key: String, v: Double, keep: Int = 10) {
        val a = try { JSONArray(sp(ctx).getString(key, "[]")) } catch (_: Exception) { JSONArray() }
        a.put(v)
        while (a.length() > keep) a.remove(0)
        sp(ctx).edit().putString(key, a.toString()).apply()
    }
    private fun list(ctx: Context, key: String): List<Double> = try {
        val a = JSONArray(sp(ctx).getString(key, "[]")); (0 until a.length()).map { a.getDouble(it) }
    } catch (_: Exception) { emptyList() }
    private fun median(l: List<Double>) = l.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }

    // ---------------------------------------------------------------- time until the apps are on screen
    private var readyMarked = false
    /** Called when the app rows first appear ("cache" = from the saved copy, "scan" = after reading all apps). */
    fun markReady(a: Activity, how: String) {
        if (readyMarked) return
        readyMarked = true
        val now = SystemClock.uptimeMillis()
        val procStart = Process.getStartUptimeMillis()
        val cold = createdAt - procStart < 3_000
        val ms = if (cold) now - procStart else now - createdAt
        android.util.Log.i("SahlPerf", "ready=$ms via=$how cold=$cold")
        add(a, if (cold) "readyCold" else "readyWarm", ms.toDouble())
    }

    // ---------------------------------------------------------------- smoothness (only frames that are really drawn)
    @Volatile private var frames = 0
    @Volatile private var slow = 0        // longer than 33 ms (missed at least one screen refresh)
    @Volatile private var frozen = 0      // longer than 700 ms
    @Volatile private var worst = 0L
    private val frameListener = Window.OnFrameMetricsAvailableListener { _, m, _ ->
        val ms = m.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000
        frames++; if (ms > 33) slow++; if (ms > 700) frozen++; if (ms > worst) worst = ms
    }
    private val frameHandler by lazy { Handler(android.os.HandlerThread("sahl-frames").apply { start() }.looper) }
    fun startFrames(w: Window) { try { w.addOnFrameMetricsAvailableListener(frameListener, frameHandler) } catch (_: Exception) {} }
    fun stopFrames(w: Window) { try { w.removeOnFrameMetricsAvailableListener(frameListener) } catch (_: Exception) {} }

    // ---------------------------------------------------------------- memory
    class Mem(val appMb: Long, val freeMb: Long, val totalMb: Long, val low: Boolean, val lowRamDevice: Boolean, val limitMb: Int)

    fun memory(ctx: Context): Mem {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val pss = try { am.getProcessMemoryInfo(intArrayOf(Process.myPid()))[0].totalPss.toLong() / 1024 } catch (_: Exception) {
            Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss.toLong() / 1024 }
        return Mem(pss, mi.availMem / MB, mi.totalMem / MB, mi.lowMemory, am.isLowRamDevice, am.memoryClass)
    }

    private fun availMb(ctx: Context): Long {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem / MB
    }

    // ---------------------------------------------------------------- does cleaning help?
    /**
     * After a clean: free memory before, right after, then again after 1 and 5 minutes.
     * "Kept" = how much of the freed memory is still free later. Near 0% means apps came straight back
     * (they restarted and refilled it), so cleaning only made them slower to open next time.
     */
    fun recordClean(ctx: Context, kind: String, beforeMb: Long, afterMb: Long) {
        val app = ctx.applicationContext
        val o = JSONObject().put("t", System.currentTimeMillis()).put("kind", kind).put("before", beforeMb).put("after", afterMb)
        fun save() {
            val a = try { JSONArray(sp(app).getString("cleans", "[]")) } catch (_: Exception) { JSONArray() }
            var found = false
            for (i in 0 until a.length()) if (a.getJSONObject(i).optLong("t") == o.optLong("t")) { a.put(i, o); found = true }
            if (!found) a.put(o)
            while (a.length() > 8) a.remove(0)
            sp(app).edit().putString("cleans", a.toString()).apply()
        }
        save()
        main.postDelayed({ o.put("m1", availMb(app)); save() }, 60_000)
        main.postDelayed({ o.put("m5", availMb(app)); save() }, 5 * 60_000)
    }

    private fun cleans(ctx: Context): List<JSONObject> = try {
        val a = JSONArray(sp(ctx).getString("cleans", "[]")); (0 until a.length()).map { a.getJSONObject(it) }
    } catch (_: Exception) { emptyList() }

    private fun kept(o: JSONObject, key: String): Int? {
        if (!o.has(key)) return null
        val freed = o.getLong("after") - o.getLong("before")
        if (freed <= 0) return null
        return (((o.getLong(key) - o.getLong("before")).coerceAtLeast(0) * 100) / freed).toInt().coerceIn(0, 100)
    }

    // ---------------------------------------------------------------- report
    fun report(ctx: Context, ar: Boolean): String {
        fun t(en: String, a: String) = if (ar) a else en
        val cold = list(ctx, "cold"); val warm = list(ctx, "warm")
        val m = memory(ctx)
        val sb = StringBuilder()
        sb.append(t("START-UP", "سرعة الفتح")).append('\n')
        sb.append(if (cold.isEmpty()) t("  Cold start: not measured yet (after the TV restarts)", "  الفتح الأول: لم يُقَس بعد (بعد إعادة تشغيل التلفاز)")
                  else t("  Cold start: ${median(cold).toInt()} ms (median of ${cold.size})", "  الفتح الأول: ${median(cold).toInt()} ملّي ثانية (الوسيط من ${cold.size})")).append('\n')
        if (warm.isNotEmpty()) sb.append(t("  Return to Home: ${median(warm).toInt()} ms (median of ${warm.size})", "  العودة للرئيسية: ${median(warm).toInt()} ملّي ثانية (الوسيط من ${warm.size})")).append('\n')
        val rc = list(ctx, "readyCold")
        if (rc.isNotEmpty()) sb.append(t("  Apps on screen after a cold start: ${median(rc).toInt()} ms", "  ظهور التطبيقات بعد الفتح الأول: ${median(rc).toInt()} ملّي ثانية")).append('\n')
        sb.append('\n').append(t("SMOOTHNESS (since Sahl TV started)", "السلاسة (منذ فتح سهل TV)")).append('\n')
        if (frames == 0) sb.append(t("  No frames measured yet — move around the home screen, then look again", "  لم تُقَس إطارات بعد — تنقّل في الشاشة الرئيسية ثم عُد")).append('\n')
        else sb.append(t("  $frames frames · ${100 - slow * 100 / frames}% on time · $slow slow (>33 ms) · $frozen frozen · worst $worst ms",
                         "  $frames إطار · ${100 - slow * 100 / frames}% في الوقت · $slow بطيء (>33 ملّي ثانية) · $frozen متجمّد · الأسوأ $worst")).append('\n')
        sb.append('\n').append(t("MEMORY", "الذاكرة")).append('\n')
        sb.append(t("  Sahl TV uses ${m.appMb} MB · TV free ${m.freeMb} of ${m.totalMb} MB", "  سهل TV يستخدم ${m.appMb} ميغابايت · المتاح ${m.freeMb} من ${m.totalMb} ميغابايت")).append('\n')
        sb.append(t("  Low-RAM TV: ${if (m.lowRamDevice) "yes" else "no"} · memory low now: ${if (m.low) "yes" else "no"} · limit per app ${m.limitMb} MB",
                    "  تلفاز قليل الذاكرة: ${if (m.lowRamDevice) "نعم" else "لا"} · الذاكرة منخفضة الآن: ${if (m.low) "نعم" else "لا"} · حد كل تطبيق ${m.limitMb} ميغابايت")).append('\n')
        val cl = cleans(ctx)
        sb.append('\n').append(t("DOES MEMORY CLEANING HELP?", "هل يفيد تنظيف الذاكرة؟")).append('\n')
        if (cl.isEmpty()) sb.append(t("  No cleaning measured yet. Press the broom button, then check again after 5 minutes.", "  لم يُقَس أي تنظيف بعد. اضغط زر المكنسة ثم عُد بعد 5 دقائق.")).append('\n')
        for (o in cl.takeLast(5).reversed()) {
            val freed = (o.getLong("after") - o.getLong("before")).coerceAtLeast(0)
            val k1 = kept(o, "m1"); val k5 = kept(o, "m5")
            sb.append(t("  ${if (o.optString("kind") == "auto") "Auto" else "Broom"}: freed $freed MB", "  ${if (o.optString("kind") == "auto") "تلقائي" else "المكنسة"}: حُرِّر $freed ميغابايت"))
            if (k1 != null) sb.append(t(" · still free after 1 min: $k1%", " · باقٍ بعد دقيقة: $k1%"))
            if (k5 != null) sb.append(t(" · after 5 min: $k5%", " · بعد 5 دقائق: $k5%"))
            sb.append('\n')
        }
        val k5s = cl.mapNotNull { kept(it, "m5") }
        if (k5s.size >= 2) {
            val avg = k5s.average().toInt()
            sb.append('\n').append(when {
                avg < 25 -> t("  Verdict: little lasting benefit ($avg% kept after 5 min). Apps refill the memory, then open slower. Consider turning Auto clean off.",
                              "  النتيجة: فائدة قليلة ($avg% باقٍ بعد 5 دقائق). التطبيقات تعيد ملء الذاكرة ثم تفتح أبطأ. يُفضّل إيقاف التنظيف التلقائي.")
                avg < 60 -> t("  Verdict: some benefit ($avg% kept after 5 min). Useful now and then, not every time.",
                              "  النتيجة: فائدة متوسطة ($avg% باقٍ بعد 5 دقائق). مفيد أحيانًا وليس كل مرة.")
                else -> t("  Verdict: real benefit ($avg% kept after 5 min).", "  النتيجة: فائدة حقيقية ($avg% باقٍ بعد 5 دقائق).")
            }).append('\n')
        }
        return sb.toString()
    }
}
