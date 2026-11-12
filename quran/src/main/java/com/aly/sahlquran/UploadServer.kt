package com.aly.sahlquran

import android.os.Handler
import android.os.Looper
import java.io.BufferedInputStream
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * Tiny one-off web page for sending a Mushaf PDF from the phone to the TV over home Wi-Fi.
 * It only runs while the "Send from phone" window is open, and only accepts uploads with the 6-digit code shown on the TV.
 */
class UploadServer(private val dest: File, private val onProgress: (Long, Long) -> Unit, private val onDone: (Boolean) -> Unit) {
    companion object {
        const val PORT = 8766
        fun ip(): String = try {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }?.hostAddress ?: "?"
        } catch (_: Exception) { "?" }
    }

    val code: String = String.format("%06d", SecureRandom().nextInt(1_000_000))
    private var server: ServerSocket? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var wrong = 0

    fun start() {
        Thread {
            try {
                val s = ServerSocket(PORT); server = s
                while (!s.isClosed) { val c = s.accept(); Thread { handle(c) }.start() }
            } catch (_: Exception) {}
        }.start()
    }

    fun stop() { try { server?.close() } catch (_: Exception) {} }

    private fun send(c: Socket, status: String, type: String, body: String) {
        val b = body.toByteArray(Charsets.UTF_8)
        c.getOutputStream().apply {
            write(("HTTP/1.1 $status\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${b.size}\r\nCache-Control: no-store\r\n" + com.aly.sahl.common.LanGuard.HEADERS + "Connection: close\r\n\r\n").toByteArray()); write(b); flush()
        }
    }

    private fun handle(c: Socket) {
        try {
            if (!com.aly.sahl.common.LanGuard.allowedPeer(c.inetAddress)) return     // home network only
            c.soTimeout = 60_000
            val inp = BufferedInputStream(c.getInputStream())
            // read the request head
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) { val ch = inp.read(); if (ch < 0) return; head.append(ch.toChar()); if (head.length > 16_000) return }
            val lines = head.lines()
            val (method, path) = lines[0].split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
            val headers = lines.drop(1).filter { it.contains(":") }.associate { it.substringBefore(":").trim().lowercase() to it.substringAfter(":").trim() }
            if (!com.aly.sahl.common.LanGuard.allowedHost(headers["host"])) { send(c, "421 Misdirected Request", "text/plain", "Open the TV by its IP address"); return }
            if (method == "GET") { send(c, "200 OK", "text/html", PAGE); return }
            if (method == "POST" && path.startsWith("/upload")) {
                if (!com.aly.sahl.common.LanGuard.same(headers["x-code"] ?: "", code)) {
                    if (++wrong >= 10) stop()                 // too many wrong codes: close until the window is opened again
                    Thread.sleep(800); send(c, "403 Forbidden", "text/plain", "wrong code"); return
                }
                val len = headers["content-length"]?.toLongOrNull() ?: -1L
                if (len <= 0 || len > 600L * 1024 * 1024) { send(c, "400 Bad Request", "text/plain", "size"); return }
                val tmp = File(dest.parentFile, dest.name + ".part")
                tmp.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024); var done = 0L; var last = 0L
                    while (done < len) {
                        val n = inp.read(buf, 0, minOf(buf.size.toLong(), len - done).toInt()); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (done - last > 2_000_000 || done == len) { last = done; val d = done; main.post { onProgress(d, len) } }
                    }
                    if (done != len) { send(c, "400 Bad Request", "text/plain", "incomplete"); tmp.delete(); main.post { onDone(false) }; return }
                }
                val ok = PdfMushaf.open(tmp)?.let { val n = it.pageCount; it.close(); n >= 600 } ?: false
                if (ok) { dest.delete(); tmp.renameTo(dest) } else tmp.delete()
                send(c, if (ok) "200 OK" else "415 Unsupported", "text/plain", if (ok) "ok" else "not a full Mushaf PDF")
                main.post { onDone(ok) }
                return
            }
            send(c, "404 Not Found", "text/plain", "")
        } catch (_: Exception) {
        } finally { try { c.close() } catch (_: Exception) {} }
    }

    private val PAGE = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><meta charset="utf-8">
<title>Sahl Quran</title><style>body{font-family:system-ui,sans-serif;background:#0e1714;color:#eee;margin:0;padding:24px;text-align:center}
h1{color:#d4b15a;font-size:22px}input,button{font-size:20px;padding:12px;border-radius:12px;border:1px solid #d4b15a;margin:8px 0;width:100%;box-sizing:border-box;background:#16231e;color:#eee}
button{background:#d4b15a;color:#111;font-weight:bold}#bar{height:14px;background:#16231e;border-radius:7px;overflow:hidden;margin-top:16px}#fill{height:100%;width:0;background:#d4b15a}
p{color:#aab}</style></head><body><h1>سهل · القرآن — Send your Mushaf</h1>
<p>أرسل ملف المصحف (PDF) إلى التلفاز<br>Send your Mushaf PDF to the TV</p>
<input id="code" inputmode="numeric" maxlength="6" placeholder="الرمز الظاهر على التلفاز · code on the TV">
<input id="f" type="file" accept="application/pdf">
<button onclick="go()">إرسال · Send</button>
<div id="bar"><div id="fill"></div></div><p id="msg"></p>
<script>
function go(){var f=document.getElementById('f').files[0];var c=document.getElementById('code').value.trim();var m=document.getElementById('msg');
if(!f){m.textContent='اختر الملف · choose the file';return}
var x=new XMLHttpRequest();x.open('POST','/upload');x.setRequestHeader('X-Code',c);
x.upload.onprogress=function(e){if(e.lengthComputable)document.getElementById('fill').style.width=(e.loaded*100/e.total)+'%'};
x.onload=function(){m.textContent=x.status==200?'✓ تم — انظر إلى التلفاز · Done, look at the TV':(x.status==403?'الرمز غير صحيح · wrong code':'تعذّر: '+x.responseText)};
x.onerror=function(){m.textContent='تعذّر الاتصال · connection failed'};m.textContent='…';x.send(f)}
</script></body></html>"""
}
