package com.aly.sahl.common

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import java.io.File

/** The "Send from phone" window: shows the address and code, and lists what arrives. Files keep coming until you close it. */
object SendPanel {
    fun show(a: Activity, port: Int, title: String, pdfOnly: Boolean, dir: () -> File, onReceived: (File) -> Unit, onClosed: () -> Unit = {}) {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        val ip = UploadServer.ip()
        box.addView(a.label(t("On your phone (same Wi-Fi), open this address:", "على هاتفك (نفس شبكة الواي فاي) افتح هذا العنوان:"), 16f, C.MUTED))
        box.addView(a.label("http://$ip:$port", 30f, C.TEXT, true).apply { setPadding(0, a.dp(6), 0, a.dp(12)); textDirection = android.view.View.TEXT_DIRECTION_LTR })
        box.addView(a.label(t("Then type this code:", "ثم اكتب هذا الرمز:"), 16f, C.MUTED))
        val codeTv = a.label("", 44f, C.GOLD, true).apply { letterSpacing = 0.25f; setPadding(0, a.dp(2), 0, a.dp(12)); typeface = Typeface.MONOSPACE }
        box.addView(codeTv)
        val status = a.label(t("Waiting for files…", "في انتظار الملفات…"), 16f, C.TEXT)
        box.addView(status)
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = 0; progressTintList = android.content.res.ColorStateList.valueOf(C.GOLD); visibility = android.view.View.INVISIBLE
        }
        box.addView(bar, LinearLayout.LayoutParams(-1, a.dp(10)).apply { topMargin = a.dp(6) })
        val got = a.label("", 15f, C.MUTED).apply { setPadding(0, a.dp(8), 0, a.dp(10)); maxLines = 6 }
        box.addView(got)
        val received = ArrayList<String>()

        lateinit var server: UploadServer
        server = UploadServer(port, title, pdfOnly, dir,
            onProgress = { name, done, total ->
                bar.visibility = android.view.View.VISIBLE; bar.progress = (done * 1000 / total).toInt()
                status.text = t("Receiving: ", "جارٍ الاستلام: ") + name + "  ·  " + (done * 100 / total) + "%"
            },
            onFile = { f, name, err ->
                bar.visibility = android.view.View.INVISIBLE
                if (f != null) {
                    received.add(0, "✓  " + f.name); onReceived(f)
                    status.text = t("Received ✓  Send more, or press Done.", "تم الاستلام ✓  أرسل المزيد أو اضغط تم.")
                } else status.text = "✗  " + name + "  ·  " + when (err) {
                    "pdf" -> t("not a PDF file", "ليس ملف PDF")
                    "space" -> t("not enough space on the TV", "لا توجد مساحة كافية على التلفاز")
                    else -> t("did not arrive completely, try again", "لم يصل كاملًا، حاول مرة أخرى")
                }
                got.text = received.take(6).joinToString("\n")
            })
        codeTv.text = server.code.chunked(3).joinToString(" ")
        server.start()

        lateinit var d: android.app.Dialog
        val done = a.pill(t("Done", "تم")) { d.dismiss() }
        box.addView(done, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
        d = Dlg.base(a, title, box, wide = true)
        d.setOnDismissListener { server.stop(); onClosed() }
        d.setOnShowListener {
            done.requestFocus()
            if (ip == "?") status.text = t("The TV is not connected to Wi-Fi or a network.", "التلفاز غير متصل بالشبكة.")
            else box.postDelayed({ if (server.failed) status.text = t("Could not start. Close and open this window again.", "تعذّر التشغيل. أغلق هذه النافذة وافتحها مرة أخرى.") }, 600)
        }
        d.show()
    }
}
