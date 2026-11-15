package com.aly.sahl.common

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.content.res.Configuration
import android.net.Uri

/**
 * "Sahl apps": the same screen across the Sahl family. Shows the family, which ones are on this TV (with version),
 * opens an installed one, and installs a missing one through Sahl TV — the family's single, checked update centre.
 */
object Family {
    const val CONNECT_DOWNLOAD = "https://github.com/AliSherif1309/SahlTV-Launcher/releases/download/latest/SahlConnect.apk"
    const val UPDATES = "com.aly.sahltv.UPDATES"     // asks Sahl TV to check for updates (and offer missing Sahl apps)

    class Member(val pkg: String, val icon: String, val en: String, val ar: String, val whatEn: String, val whatAr: String)

    val members = listOf(
        Member("com.aly.sahlconnect", "📱", "Sahl Connect", "سهل Connect", "Android phone companion for Sahl TV", "تطبيق هاتف Android للتحكّم بسهل TV"),
        Member("com.aly.sahltimer", "⏱", "Sahl Timer", "سهل · المؤقت", "A simple countdown, visible above your apps", "عد تنازلي بسيط يظهر فوق تطبيقاتك"),
        Member("com.aly.sahltv", "🏠", "Sahl TV", "سهل TV", "The home screen: prayer times, your apps, calm design", "الشاشة الرئيسية: مواقيت الصلاة وتطبيقاتك بتصميم هادئ"),
        Member("com.aly.sahlquran", "📖", "Sahl Quran", "سهل · القرآن", "Read the Mushaf, with a reading timer", "قراءة المصحف مع مؤقت القراءة"),
        Member("com.aly.sahlpdf", "📚", "Sahl PDF", "سهل · كتب", "Your books on the TV, sent once from the phone", "كتبك على التلفاز، تُرسل مرة واحدة من الهاتف"),
        Member("com.aly.sahlfiles", "📁", "Sahl Files", "سهل · الملفات", "Files, USB drives, photos and videos", "الملفات وذاكرة USB والصور والفيديو"),
    )

    @Suppress("DEPRECATION")
    private fun version(a: Activity, pkg: String): String? = try {
        a.packageManager.getPackageInfo(pkg, 0).versionName ?: ""
    } catch (_: Exception) { null }

    fun openUpdates(a: Activity) {
        val i = Intent(UPDATES).setPackage("com.aly.sahltv").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { a.startActivity(i) } catch (_: Exception) { a.toast(t("Install Sahl TV first — it keeps all Sahl apps up to date", "ثبّت سهل TV أولًا — فهو يحدّث كل تطبيقات سهل")) }
    }

    fun show(a: Activity, onConnect: (() -> Unit)? = null) {
        val television = a.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
        val items = ArrayList<Pair<String, () -> Unit>>()
        for (m in members) {
            // Connect belongs on a phone; do not offer its APK to the TV installer.
            if (m.pkg == "com.aly.sahlconnect" && television) {
                items += t("📱  Sahl Connect · Android phone app\n      Pair with the main Sahl TV launcher", "📱  سهل Connect · تطبيق هاتف Android\n      اربطه بواجهة سهل TV الرئيسية") to {
                    if (onConnect != null) onConnect()
                    else FontDialog(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Sahl Connect").setMessage(t("Install SahlConnect.apk on your Android phone. On the TV open Sahl TV → Settings → Tools & connection → Sahl Connect.\n\nPhone download: $CONNECT_DOWNLOAD", "ثبّت SahlConnect.apk على هاتف Android. على التلفاز افتح سهل TV ← الإعدادات ← الأدوات والاتصال ← سهل Connect.\n\nتنزيل الهاتف: $CONNECT_DOWNLOAD"))
                        .setPositiveButton(t("Close", "إغلاق"), null).show()
                }
                continue
            }
            val v = version(a, m.pkg)
            val name = if (isAr) m.ar else m.en
            val what = if (isAr) m.whatAr else m.whatEn
            val status = when {
                m.pkg == a.packageName -> t("this app · $v", "هذا التطبيق · $v")
                v != null -> t("installed · $v", "مثبّت · $v")
                else -> t("not installed — press to install", "غير مثبّت — اضغط للتثبيت")
            }
            items += "${m.icon}  $name   ·   $status\n      $what" to {
                when {
                    m.pkg == a.packageName -> {}
                    v != null -> {
                        val pm = a.packageManager
                        val launch = pm.getLeanbackLaunchIntentForPackage(m.pkg) ?: pm.getLaunchIntentForPackage(m.pkg)
                        if (launch != null) a.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    else -> openUpdates(a)
                }
            }
        }
        items += t("⬆  Check for updates for all Sahl apps", "⬆  البحث عن تحديثات لكل تطبيقات سهل") to { openUpdates(a) }
        Dlg.menu(a, t("Sahl apps", "تطبيقات سهل") + "  ·  " + t("one calm family for TV and phone", "عائلة هادئة للتلفاز والهاتف"), items)
    }

    /** Android version of this TV, shown nowhere but useful in reports. */
    val android: String get() = "Android ${Build.VERSION.RELEASE}"
}

