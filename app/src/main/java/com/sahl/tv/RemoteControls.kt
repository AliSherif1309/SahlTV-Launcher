package com.sahl.tv

import android.view.KeyEvent
import android.view.ViewConfiguration
import android.widget.TextView
import com.sahl.tv.common.Fonts

/** Only handles Menu delivered to Home. Dialogs and other apps own their own keys. */
internal class RemoteControls(private val home: MainActivity) {
    private var menuDown: Long? = null
    private var menuHeld = false

    fun cancel() { menuDown = null; menuHeld = false }

    fun dispatch(event: KeyEvent, normal: (KeyEvent) -> Boolean): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_MENU) return normal(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) { menuDown = event.eventTime; menuHeld = false }
            if (menuDown != null && event.isLongPress) menuHeld = true
            return true
        }
        if (event.action != KeyEvent.ACTION_UP) return true
        val start = menuDown
        val held = menuHeld
        cancel()
        if (start == null || event.isCanceled) return true
        if (held || event.eventTime - start >= ViewConfiguration.getLongPressTimeout()) {
            home.runRemoteAction(home.prefs.getString("remoteMenuHold", "panel") ?: "panel")
        } else if (!normal(event)) {
            // App tiles retain their existing short-Menu context/favorite actions.
            home.runRemoteAction(home.prefs.getString("remoteMenuTap", "panel") ?: "panel")
        }
        return true
    }
}

private val remoteActions = listOf("panel", "quran", "timer", "prayer", "recent", "settings", "none")

private fun android.app.AlertDialog.remoteFocus() = apply {
    listView?.selector = android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#D8BD7B"))
}

internal fun MainActivity.remoteActionName(action: String): String = when (action) {
    "quran" -> t("Sahl Quran", "سهل القرآن")
    "timer" -> t("Floating timer", "المؤقت العائم")
    "prayer" -> t("Prayer times", "مواقيت الصلاة")
    "recent" -> t("Recent apps", "التطبيقات الأخيرة")
    "settings" -> t("Settings", "الإعدادات")
    "none" -> t("Do nothing", "بدون إجراء")
    else -> t("Quick panel", "القائمة السريعة")
}

internal fun MainActivity.runRemoteAction(action: String) {
    when (action) {
        "none" -> Unit
        "quran" -> {
            val app = apps["com.sahl.tv.quran"]
            if (app != null) launch(app)
            else toast(t("Install Sahl Quran from Sahl apps first", "ثبّت سهل القرآن من تطبيقات سهل أولًا"))
        }
        "timer" -> timerMenu()
        "prayer" -> prayerDialog()
        "recent" -> {
            val entries = recent.filter { it !in hidden }.mapNotNull { apps[it] }
            if (entries.isEmpty()) toast(t("No recent apps yet", "لا توجد تطبيقات أخيرة بعد"))
            else dialog().setTitle(remoteActionName("recent"))
                .setItems(entries.map { it.label }.toTypedArray()) { _, index -> launch(entries[index]) }.show().remoteFocus()
        }
        "settings" -> showSettings()
        else -> remoteQuickPanel()
    }
}

internal fun MainActivity.remoteQuickPanel(): android.app.AlertDialog = dialog().setTitle(remoteActionName("panel"))
    .setItems(arrayOf(remoteActionName("quran"), remoteActionName("timer"), remoteActionName("prayer"),
        remoteActionName("recent"), remoteActionName("settings"), t("Remote shortcuts", "اختصارات الريموت"))) { _, index ->
        if (index == 5) remoteShortcutsMenu()
        else runRemoteAction(listOf("quran", "timer", "prayer", "recent", "settings")[index])
    }.setNegativeButton(t("Close", "إغلاق"), null).show().remoteFocus()

internal fun MainActivity.remoteShortcutsMenu(): android.app.AlertDialog = dialog().setTitle(t("Remote shortcuts", "اختصارات الريموت"))
    .setItems(arrayOf(
        t("Test my remote", "اختبار الريموت"),
        t("Short Menu outside app tiles: ", "ضغطة القائمة خارج مربعات التطبيقات: ") + remoteActionName(prefs.getString("remoteMenuTap", "panel") ?: "panel"),
        t("Hold Menu, then release: ", "اضغط القائمة مطولًا ثم اتركها: ") + remoteActionName(prefs.getString("remoteMenuHold", "panel") ?: "panel"),
        t("Open quick panel", "فتح القائمة السريعة"),
        t("Reset shortcuts", "إعادة ضبط الاختصارات")
    )) { _, index ->
        when (index) {
            0 -> remoteTester()
            1, 2 -> chooseRemoteAction(if (index == 1) "remoteMenuTap" else "remoteMenuHold")
            3 -> remoteQuickPanel()
            4 -> { prefs.edit().remove("remoteMenuTap").remove("remoteMenuHold").apply(); remoteShortcutsMenu() }
        }
    }.setNegativeButton(t("Close", "إغلاق"), null).show().remoteFocus()

private fun MainActivity.chooseRemoteAction(key: String) {
    dialog().setTitle(t("Choose shortcut", "اختر الاختصار"))
        .setSingleChoiceItems(remoteActions.map { remoteActionName(it) }.toTypedArray(),
            remoteActions.indexOf(prefs.getString(key, "panel")).coerceAtLeast(0)) { d, index ->
            prefs.edit().putString(key, remoteActions[index]).apply()
            d.dismiss(); remoteShortcutsMenu()
        }.setNegativeButton(t("Cancel", "إلغاء"), null).show().remoteFocus()
}

internal fun MainActivity.remoteTester(): android.app.AlertDialog {
    val help = t("Press a button to see what SahlTV receives. Back closes this test. Home, power, voice and branded app buttons may be reserved by your TV; no entry does not mean the button is broken. This test does not remap buttons.",
        "اضغط زرًا لعرض ما يستقبله سهل TV. رجوع يغلق الاختبار. قد يحجز التلفاز أزرار الرئيسية والطاقة والصوت والتطبيقات؛ عدم ظهورها لا يعني عطلًا. هذا الاختبار لا يغيّر وظائف الأزرار.")
    val log = ArrayDeque<String>()
    val label = TextView(this).apply {
        text = help; textSize = 18f; setTextColor(android.graphics.Color.WHITE)
        typeface = Fonts.get(0); setPadding(dp(24), dp(16), dp(24), dp(16))
        minLines = 10
    }
    val test = dialog().setTitle(t("Test my remote", "اختبار الريموت")).setView(label)
        .setNegativeButton(t("Close / Back", "إغلاق / رجوع"), null).create()
    test.setOnKeyListener { _, code, event ->
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) test.dismiss()
            true
        }
        else {
            val phase = if (event.action == KeyEvent.ACTION_UP) t("release", "ترك") else t("press", "ضغط")
            log.addFirst("${KeyEvent.keyCodeToString(code)} ($code) · $phase · ${t("repeat", "تكرار")} ${event.repeatCount}")
            while (log.size > 6) log.removeLast()
            label.text = t("Received buttons · Back closes the test", "الأزرار المستقبلة · رجوع لإغلاق الاختبار") + "\n\n" + log.joinToString("\n")
            lastInput = System.currentTimeMillis()
            true
        }
    }
    test.show()
    return test
}
