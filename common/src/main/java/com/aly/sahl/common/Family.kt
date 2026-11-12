package com.aly.sahl.common

import android.app.Activity
import android.content.Intent
import android.os.Build

/**
 * "Sahl apps": the same screen in all four apps. Shows the family, which ones are on this TV (with version),
 * opens an installed one, and installs a missing one through Sahl TV — the family's single, checked update centre.
 */
object Family {
    const val UPDATES = "com.aly.sahltv.UPDATES"     // asks Sahl TV to check for updates (and offer missing Sahl apps)

    class Member(val pkg: String, val icon: String, val en: String, val ar: String, val whatEn: String, val whatAr: String)

    val members = listOf(
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

    fun show(a: Activity) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        for (m in members) {
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
        Dlg.menu(a, t("Sahl apps", "تطبيقات سهل") + "  ·  " + t("one calm family for the TV", "عائلة هادئة واحدة للتلفاز"), items)
    }

    /** Android version of this TV, shown nowhere but useful in reports. */
    val android: String get() = "Android ${Build.VERSION.RELEASE}"
}
