package com.aly.sahlquran

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates from the public GitHub release "latest".
 * The release carries version.json + the APKs. Android only accepts an update signed with the same key
 * as the installed app, so a tampered APK can never replace Sahl TV or Sahl Quran.
 */
object Updater {
    private const val BASE = "https://github.com/AliSherif1309/SahlTV-Launcher/releases/download/latest/"

    class Info(val code: Long, val tv: String, val quran: String, val notes: String)
    private class Item(val pkg: String, val file: String, val name: String, val from: String, val to: String)

    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayList<Pair<File, String>>()

    private fun get(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000; c.instanceFollowRedirects = true
        c.setRequestProperty("Cache-Control", "no-cache")
        return c
    }

    fun fetch(): Info? = try {
        val c = get(BASE + "version.json")
        val o = org.json.JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        Info(o.getLong("code"), o.optString("sahltv"), o.optString("quran"), o.optString("notes"))
    } catch (_: Exception) { null }

    @Suppress("DEPRECATION")
    private fun installed(ctx: Context, pkg: String): Pair<Long, String>? = try {
        val pi = ctx.packageManager.getPackageInfo(pkg, 0)
        Pair(if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong(), pi.versionName ?: "")
    } catch (_: Exception) { null }

    private fun items(ctx: Context, info: Info, ar: Boolean): List<Item> {
        val out = ArrayList<Item>()
        // other apps first; this app last, because installing it restarts it
        val all = listOf(Triple("com.aly.sahlquran", "SahlQuran.apk", if (ar) "سهل · القرآن" to info.quran else "Sahl Quran" to info.quran),
                         Triple("com.aly.sahltv", "SahlTV.apk", if (ar) "سهل TV" to info.tv else "Sahl TV" to info.tv))
        for ((pkg, file, nv) in all) {
            val have = installed(ctx, pkg)
            if (pkg != ctx.packageName && pkg == "com.aly.sahltv") continue      // Sahl Quran only updates itself
            if (have == null) { if (pkg != ctx.packageName) out.add(Item(pkg, file, nv.first, "", nv.second)) }
            else if (have.first < info.code) out.add(Item(pkg, file, nv.first, have.second, nv.second))
        }
        return out
    }

    /** Quiet background check (no dialogs). Calls back on the main thread with true if an update is waiting. */
    fun checkQuietly(ctx: Context, ar: Boolean, result: (Boolean) -> Unit) {
        Thread {
            val info = fetch()
            val has = info != null && items(ctx, info, ar).any { it.from.isNotEmpty() }
            main.post { result(has) }
        }.start()
    }

    private fun dialog(a: Activity) = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    /** "Check for updates": tells you what's new and installs with one press. */
    fun check(a: Activity, ar: Boolean) {
        fun t(en: String, x: String) = if (ar) x else en
        val wait = dialog(a).setMessage(t("Checking for updates…", "جارٍ البحث عن تحديثات…")).show()
        Thread {
            val info = fetch()
            main.post {
                wait.dismiss()
                if (info == null) {
                    dialog(a).setTitle(t("Couldn't check", "تعذّر البحث")).setMessage(t("Check the TV's internet connection and try again.", "تحقّق من اتصال التلفاز بالإنترنت وحاول مجددًا."))
                        .setPositiveButton(t("OK", "حسنًا"), null).show(); return@post
                }
                val list = items(a, info, ar)
                val mine = installed(a, a.packageName)
                if (list.isEmpty()) {
                    dialog(a).setTitle(t("You're up to date ✓", "لديك أحدث إصدار ✓"))
                        .setMessage(t("Version ${mine?.second} (build ${mine?.first})", "الإصدار ${mine?.second} (رقم ${mine?.first})"))
                        .setPositiveButton(t("OK", "حسنًا"), null).show(); return@post
                }
                val msg = StringBuilder()
                for (it in list) msg.append("• ").append(it.name).append("   ")
                    .append(if (it.from.isEmpty()) t("not installed → ${it.to}", "غير مثبّت ← ${it.to}") else "${it.from} → ${it.to}").append("\n")
                if (info.notes.isNotBlank()) msg.append("\n").append(t("What's new: ", "الجديد: ")).append(info.notes)
                dialog(a).setTitle(t("Update available", "يتوفّر تحديث")).setMessage(msg.toString())
                    .setPositiveButton(t("Update now", "تحديث الآن")) { _, _ -> start(a, list, ar) }
                    .setNegativeButton(t("Later", "لاحقًا"), null).show()
            }
        }.start()
    }

    private fun start(a: Activity, list: List<Item>, ar: Boolean) {
        fun t(en: String, x: String) = if (ar) x else en
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            dialog(a).setTitle(t("Allow updates (one time)", "السماح بالتحديث (مرة واحدة)"))
                .setMessage(t("Android needs your OK for this app to install updates.\n\nPress “Open setting” and switch it on. If the TV has no such screen, run once on your PC:\n\nadb shell appops set ${a.packageName} REQUEST_INSTALL_PACKAGES allow",
                              "يحتاج Android إلى موافقتك ليتمكّن التطبيق من تثبيت التحديثات.\n\nاضغط «فتح الإعداد» وفعّله. إذا لم تتوفّر هذه الشاشة في التلفاز، شغّل مرة واحدة على الكمبيوتر:\n\nadb shell appops set ${a.packageName} REQUEST_INSTALL_PACKAGES allow"))
                .setPositiveButton(t("Open setting", "فتح الإعداد")) { _, _ ->
                    try { a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName))) }
                    catch (_: Exception) { Toast.makeText(a, t("Not available on this TV: use the adb command", "غير متاح على هذا التلفاز: استخدم أمر adb"), Toast.LENGTH_LONG).show() }
                }.setNegativeButton(t("Close", "إغلاق"), null).show()
            return
        }
        val tv = TextView(a).apply { textSize = 18f; setPadding(60, 40, 60, 40) }
        val prog = dialog(a).setTitle(t("Updating", "جارٍ التحديث")).setView(tv).setCancelable(false).show()
        Thread {
            val dir = File(a.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val files = ArrayList<Pair<File, String>>()
            try {
                for (it in list) {
                    val f = File(dir, it.file)
                    val c = get(BASE + it.file)
                    val total = c.contentLength.toLong()
                    c.inputStream.use { inp -> f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024); var done = 0L; var last = -1
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else -1
                            if (pct != last) { last = pct; main.post { tv.text = t("Downloading ${it.name}… ", "تنزيل ${it.name}… ") + if (pct >= 0) "$pct%" else "" } }
                        }
                    } }
                    if (total > 0 && f.length() != total) throw java.io.IOException("incomplete")
                    files.add(f to it.pkg)
                }
            } catch (e: Exception) {
                main.post { prog.dismiss(); dialog(a).setTitle(t("Download failed", "فشل التنزيل")).setMessage(t("Please try again.", "يُرجى المحاولة مرة أخرى."))
                    .setPositiveButton(t("OK", "حسنًا"), null).show() }
                return@Thread
            }
            main.post {
                prog.dismiss()
                synchronized(queue) { queue.clear(); queue.addAll(files) }
                installNext(a.applicationContext)
            }
        }.start()
    }

    /** Installs the next downloaded APK; Android shows its own "Install" confirmation. */
    fun installNext(ctx: Context) {
        val next = synchronized(queue) { if (queue.isEmpty()) null else queue.removeAt(0) } ?: return
        val (f, pkg) = next
        try {
            val pi = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(pkg)
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                s.openWrite("app", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) 0x02000000 /* FLAG_MUTABLE */ else 0)
                val cb = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java), flags)
                s.commit(cb.intentSender)
            }
        } catch (e: Exception) {
            Toast.makeText(ctx, "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
            installNext(ctx)
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(ctx, "✓ " + (intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: "") + " updated", Toast.LENGTH_SHORT).show()
                Updater.installNext(ctx)
            }
            else -> {
                val m = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                if (m != null && !m.contains("abort", true)) Toast.makeText(ctx, "Update not installed: $m", Toast.LENGTH_LONG).show()
                Updater.installNext(ctx)
            }
        }
    }
}
