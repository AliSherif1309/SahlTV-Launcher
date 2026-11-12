package com.aly.sahlpdf

import android.os.Handler
import android.os.Looper
import java.io.BufferedInputStream
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * Send files from the phone to the TV over home Wi-Fi.
 * Runs only while the "Send from phone" window is open on the TV, and only accepts files with the 6-digit code shown there.
 * After 10 wrong codes it closes until the window is opened again.
 *
 * [pdfOnly] = only PDF files are kept (checked by their first bytes, not just the name).
 * [dir] = where received files go (asked again for every file, so it can follow the folder you are in).
 */
class UploadServer(
    private val port: Int,
    private val title: String,
    private val pdfOnly: Boolean,
    private val dir: () -> File,
    private val onProgress: (name: String, done: Long, total: Long) -> Unit,
    private val onFile: (file: File?, name: String, error: String?) -> Unit,
) {
    companion object {
        fun ip(): String = try {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress ?: "?"
        } catch (_: Exception) { "?" }

        /** A safe file name: no folders, no hidden or control characters. */
        fun cleanName(raw: String): String {
            var n = raw.substringAfterLast('/').substringAfterLast('\\').filter { it >= ' ' && it !in "<>:\"|?*" }.trim().trimStart('.')
            if (n.length > 120) { val ext = n.substringAfterLast('.', ""); n = n.take(110) + if (ext.isNotEmpty() && ext.length < 8) ".$ext" else "" }
            return n.ifEmpty { "file" }
        }

        /** "Book.pdf" → "Book (2).pdf" when the name is taken, so nothing is ever overwritten. */
        fun freeName(dir: File, name: String): File {
            var f = File(dir, name); var i = 2
            val base = name.substringBeforeLast('.', name); val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
            while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
            return f
        }
    }

    val code: String = String.format("%06d", SecureRandom().nextInt(1_000_000))
    private var server: ServerSocket? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var wrong = 0
    @Volatile var failed = false; private set

    fun start() {
        Thread {
            try {
                val s = ServerSocket(port); server = s
                while (!s.isClosed) { val c = s.accept(); Thread { handle(c) }.start() }
            } catch (_: Exception) { if (server == null) failed = true }
        }.start()
    }

    fun stop() { try { server?.close() } catch (_: Exception) {} }

    private fun send(c: Socket, status: String, type: String, body: String) {
        val b = body.toByteArray(Charsets.UTF_8)
        c.getOutputStream().apply {
            write("HTTP/1.1 $status\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${b.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray()); write(b); flush()
        }
    }

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 60_000
            val inp = BufferedInputStream(c.getInputStream(), 256 * 1024)
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) { val ch = inp.read(); if (ch < 0) return; head.append(ch.toChar()); if (head.length > 16_000) return }
            val lines = head.lines()
            val (method, path) = lines[0].split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
            val headers = lines.drop(1).filter { it.contains(":") }.associate { it.substringBefore(":").trim().lowercase() to it.substringAfter(":").trim() }
            if (method == "GET") { send(c, "200 OK", "text/html", page()); return }
            if (method != "POST" || !path.startsWith("/upload")) { send(c, "404 Not Found", "text/plain", ""); return }
            if (headers["x-code"] != code) {
                if (++wrong >= 10) stop()
                Thread.sleep(800); send(c, "403 Forbidden", "text/plain", "wrong code"); return
            }
            val name = cleanName(try { URLDecoder.decode(headers["x-name"] ?: "file", "UTF-8") } catch (_: Exception) { "file" })
            val len = headers["content-length"]?.toLongOrNull() ?: -1L
            if (len <= 0) { send(c, "400 Bad Request", "text/plain", "empty"); return }
            if (pdfOnly && !name.lowercase().endsWith(".pdf")) { send(c, "415 Unsupported", "text/plain", "PDF only"); main.post { onFile(null, name, "pdf") }; return }
            val d = dir(); d.mkdirs()
            if (d.usableSpace in 1 until len + 50L * 1024 * 1024) { send(c, "507 Insufficient Storage", "text/plain", "no space on the TV"); main.post { onFile(null, name, "space") }; return }
            val tmp = File(d, ".$name.part")
            var done = 0L
            tmp.outputStream().use { out ->
                val buf = ByteArray(256 * 1024); var last = 0L
                while (done < len) {
                    val n = inp.read(buf, 0, minOf(buf.size.toLong(), len - done).toInt()); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    if (done - last > 1_500_000 || done == len) { last = done; val x = done; main.post { onProgress(name, x, len) } }
                }
            }
            if (done != len) { tmp.delete(); send(c, "400 Bad Request", "text/plain", "incomplete"); main.post { onFile(null, name, "incomplete") }; return }
            if (pdfOnly) {
                val sig = ByteArray(5); tmp.inputStream().use { it.read(sig) }
                if (String(sig, Charsets.ISO_8859_1) != "%PDF-") { tmp.delete(); send(c, "415 Unsupported", "text/plain", "not a PDF"); main.post { onFile(null, name, "pdf") }; return }
            }
            val dest = freeName(d, name)
            if (!tmp.renameTo(dest)) { tmp.delete(); send(c, "500 Error", "text/plain", "could not save"); main.post { onFile(null, name, "save") }; return }
            send(c, "200 OK", "text/plain", "ok")
            main.post { onFile(dest, dest.name, null) }
        } catch (_: Exception) {
        } finally { try { c.close() } catch (_: Exception) {} }
    }

    private fun page() = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><meta charset="utf-8">
<title>$title</title><style>body{font-family:system-ui,sans-serif;background:#0e1a24;background:linear-gradient(#0e1a24,#10261f);color:#eef3f1;margin:0;padding:22px;min-height:100vh;box-sizing:border-box}
h1{color:#e3b85c;font-size:22px;margin:4px 0 2px}.s{color:#8fa39c;margin:0 0 16px}
input,button,label.f{display:block;font-size:19px;padding:13px;border-radius:14px;border:1px solid #e3b85c;margin:10px 0;width:100%;box-sizing:border-box;background:#16231e;color:#eef3f1;text-align:center}
label.f{cursor:pointer}button{background:#e3b85c;color:#0b1e1a;font-weight:bold}button:disabled{opacity:.5}
.it{background:#16231e;border-radius:12px;padding:10px 12px;margin:8px 0;text-align:start}.n{font-size:15px;word-break:break-all}
.bar{height:8px;background:#0b1e1a;border-radius:4px;overflow:hidden;margin-top:6px}.fill{height:100%;width:0;background:#e3b85c}.st{font-size:13px;color:#8fa39c;margin-top:4px}
</style></head><body><h1>$title</h1><p class="s">أرسل الملفات إلى التلفاز · Send files to the TV</p>
<input id="code" inputmode="numeric" maxlength="6" placeholder="الرمز الظاهر على التلفاز · code on the TV" autocomplete="off">
<label class="f">📂 اختر الملفات · Choose files<input id="f" type="file" multiple ${if (pdfOnly) "accept=\"application/pdf,.pdf\"" else ""} style="display:none"></label>
<button id="go" onclick="go()">إرسال · Send</button><div id="list"></div>
<script>
var F=document.getElementById('f'),L=document.getElementById('list');
F.onchange=function(){L.innerHTML='';for(var i=0;i<F.files.length;i++){L.innerHTML+='<div class="it"><div class="n"></div><div class="bar"><div class="fill"></div></div><div class="st">…</div></div>';L.lastChild.querySelector('.n').textContent=F.files[i].name}};
function go(){var c=document.getElementById('code').value.trim();if(!F.files.length){alert('اختر ملفًا · choose a file');return}
document.getElementById('go').disabled=true;one(0,c)}
function one(i,c){if(i>=F.files.length){document.getElementById('go').disabled=false;return}
var f=F.files[i],row=L.children[i],fill=row.querySelector('.fill'),st=row.querySelector('.st');
var x=new XMLHttpRequest();x.open('POST','/upload');x.setRequestHeader('X-Code',c);x.setRequestHeader('X-Name',encodeURIComponent(f.name));
x.upload.onprogress=function(e){if(e.lengthComputable){fill.style.width=(e.loaded*100/e.total)+'%';st.textContent=Math.round(e.loaded*100/e.total)+'%'}};
x.onload=function(){if(x.status==200){fill.style.width='100%';st.textContent='✓ تم · Done';one(i+1,c)}else{st.textContent=(x.status==403?'✗ الرمز غير صحيح · wrong code':'✗ '+x.responseText);document.getElementById('go').disabled=false}};
x.onerror=function(){st.textContent='✗ تعذّر الاتصال · connection failed';document.getElementById('go').disabled=false};x.send(f)}
</script></body></html>"""
}
