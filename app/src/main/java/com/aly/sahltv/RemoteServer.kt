package com.aly.sahltv

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Phone remote: a tiny web server on the TV (port 8765, home Wi-Fi only).
 * Open http://<tv-ip>:8765 on the phone, enter the 6-digit pairing code shown on the TV once.
 * Off by default; started from the launcher menu.
 */
object RemoteServer {
    const val PORT = 8765
    const val IME_ID = "com.aly.sahltv/.RemoteKeyboard"

    interface Host {
        fun appList(): List<Pair<String, String>>     // package, label (favorites first)
        fun launchPkg(pkg: String)
        fun media(action: String)
        fun nowPlaying(): String
        fun goHome()
        fun arabic(): Boolean
    }

    @Volatile var host: Host? = null
    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)
    private lateinit var app: Context
    private val rnd = SecureRandom()
    @Volatile private var wrongCodes = 0
    @Volatile private var lastText = 0L

    private fun prefs() = app.getSharedPreferences("sahl", Context.MODE_PRIVATE)

    val running get() = server != null

    fun code(ctx: Context): String {
        val p = ctx.getSharedPreferences("sahl", Context.MODE_PRIVATE)
        return p.getString("rcode", null) ?: String.format("%06d", SecureRandom().nextInt(1_000_000)).also {
            p.edit().putString("rcode", it).apply()
        }
    }

    /** New code: every paired phone must pair again. */
    fun resetPairing(ctx: Context) {
        ctx.getSharedPreferences("sahl", Context.MODE_PRIVATE).edit().remove("rcode").remove("rtokens").apply()
    }

    fun ip(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    } catch (_: Exception) { null }

    fun start(ctx: Context) {
        app = ctx.applicationContext
        if (server != null) return
        Thread {
            try {
                val ss = ServerSocket(PORT)
                server = ss
                while (!ss.isClosed) {
                    val s = try { ss.accept() } catch (_: Exception) { break }
                    pool.execute { handle(s) }
                }
            } catch (_: Exception) {
            } finally { server = null }
        }.apply { isDaemon = true; name = "sahl-remote" }.start()
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    // ------------------------------------------------------------------ HTTP

    private fun handle(s: Socket) {
        s.use { sock ->
            try {
                sock.soTimeout = 8000
                val input = BufferedInputStream(sock.getInputStream())
                val head = readHead(input) ?: return
                val lines = head.split("\r\n")
                val parts = lines[0].split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val target = parts[1]
                val path = target.substringBefore("?")
                val query = parseQuery(target.substringAfter("?", ""))
                val headers = lines.drop(1).filter { it.contains(":") }
                    .associate { it.substringBefore(":").trim().lowercase() to it.substringAfter(":").trim() }
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                val body = if (len in 1..20000) ByteArray(len).also { var r = 0; while (r < len) { val n = input.read(it, r, len - r); if (n < 0) break; r += n } }
                    .toString(Charsets.UTF_8) else ""
                val (status, type, out) = route(method, path, query, headers["x-token"], body)
                val bytes = out.toByteArray(Charsets.UTF_8)
                val os = sock.getOutputStream()
                os.write(("HTTP/1.1 $status\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                    "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
                os.write(bytes); os.flush()
            } catch (_: Exception) {}
        }
    }

    private fun readHead(input: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream()
        var last4 = 0
        while (buf.size() < 16000) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return buf.toString("UTF-8").trimEnd()
        }
        return null
    }

    private fun parseQuery(q: String): Map<String, String> = q.split("&").filter { it.contains("=") }
        .associate { URLDecoder.decode(it.substringBefore("="), "UTF-8") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }

    private fun json(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""

    private fun tokens() = (prefs().getString("rtokens", "") ?: "").split("|").filter { it.isNotEmpty() }

    private fun route(method: String, path: String, q: Map<String, String>, token: String?, body: String): Triple<String, String, String> {
        val ok = Triple("200 OK", "application/json", "{\"ok\":true}")
        if (method == "GET" && (path == "/" || path == "/index.html")) return Triple("200 OK", "text/html", RemotePage.HTML)
        if (path == "/api/pair" && method == "POST") {
            if (body.trim() != code(app)) {
                // too many wrong codes: make a new one (shown in Settings → Phone remote), so guessing never works
                if (++wrongCodes >= 10) { wrongCodes = 0; prefs().edit().remove("rcode").apply() }
                Thread.sleep(800); return Triple("403 Forbidden", "application/json", "{\"ok\":false}")
            }
            wrongCodes = 0
            val t = ByteArray(16).also { rnd.nextBytes(it) }.joinToString("") { String.format("%02x", it) }
            prefs().edit().putString("rtokens", (listOf(t) + tokens()).take(10).joinToString("|")).apply()
            return Triple("200 OK", "application/json", "{\"ok\":true,\"token\":${json(t)}}")
        }
        if (token == null || token !in tokens()) return Triple("401 Unauthorized", "application/json", "{\"ok\":false,\"pair\":true}")
        val h = host
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        when (path) {
            "/api/state" -> {
                val apps = h?.appList().orEmpty().joinToString(",") { "{\"p\":${json(it.first)},\"l\":${json(it.second)}}" }
                return Triple("200 OK", "application/json",
                    "{\"ok\":true,\"ar\":${h?.arabic() ?: false},\"now\":${json(h?.nowPlaying() ?: "")},\"kb\":${keyboardReady()},\"apps\":[$apps]}")
            }
            "/api/launch" -> { val p = q["pkg"] ?: return ok; main.post { h?.launchPkg(p) }; return ok }
            "/api/media" -> { val a = q["a"] ?: return ok; main.post { h?.media(a) }; return ok }
            "/api/home" -> { main.post { h?.goHome() }; return ok }
            "/api/vol" -> {
                val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val dir = when (q["d"]) { "up" -> AudioManager.ADJUST_RAISE; "down" -> AudioManager.ADJUST_LOWER; else -> AudioManager.ADJUST_TOGGLE_MUTE }
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
                return ok
            }
            "/api/text" -> return Triple("200 OK", "application/json", "{\"ok\":${typeText(body)}}")
            "/api/key" -> return Triple("200 OK", "application/json", "{\"ok\":${typeKey(q["k"] ?: "")}}")
            "/api/kbdone" -> { restoreKeyboard(); return ok }
        }
        return Triple("404 Not Found", "application/json", "{\"ok\":false}")
    }

    // ------------------------------------------------------------------ typing through the Sahl Keyboard

    private fun canSwitchKeyboard() =
        app.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun currentIme(): String? = Settings.Secure.getString(app.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)

    /** "true" when typing can work right now, "\"switch\"" when the keyboard can be switched automatically, else false. */
    private fun keyboardReady(): String = when {
        RemoteKeyboard.active != null -> "true"
        canSwitchKeyboard() -> "\"switch\""
        else -> "false"
    }

    /** Makes the Sahl Keyboard the active keyboard (remembering the previous one), if allowed. */
    private fun ensureKeyboard(): Boolean {
        if (RemoteKeyboard.active?.hasField() == true) return true
        if (!canSwitchKeyboard()) return RemoteKeyboard.active != null
        val cur = currentIme()
        if (cur != null && cur != IME_ID && !cur.endsWith(".RemoteKeyboard")) prefs().edit().putString("prevIme", cur).apply()
        try { Settings.Secure.putString(app.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, IME_ID) } catch (_: Exception) { return false }
        for (i in 0 until 15) { if (RemoteKeyboard.active?.hasField() == true) return true; Thread.sleep(100) }
        return RemoteKeyboard.active != null
    }

    fun restoreKeyboard() {
        val prev = prefs().getString("prevIme", null) ?: return
        if (!canSwitchKeyboard()) return
        try { Settings.Secure.putString(app.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, prev) } catch (_: Exception) {}
    }

    private fun scheduleRestore() {
        lastText = System.currentTimeMillis()
        val stamp = lastText
        pool.execute {
            Thread.sleep(120_000)
            if (lastText == stamp) restoreKeyboard()        // 2 minutes without typing: give the TV keyboard back
        }
    }

    private fun typeText(text: String): Boolean {
        if (text.isEmpty() || !ensureKeyboard()) return false
        val kb = RemoteKeyboard.active ?: return false
        val done = java.util.concurrent.CountDownLatch(1)
        var ok = false
        android.os.Handler(android.os.Looper.getMainLooper()).post { ok = kb.type(text); done.countDown() }
        done.await(2, java.util.concurrent.TimeUnit.SECONDS)
        scheduleRestore()
        return ok
    }

    private fun typeKey(k: String): Boolean {
        if (!ensureKeyboard()) return false
        val kb = RemoteKeyboard.active ?: return false
        android.os.Handler(android.os.Looper.getMainLooper()).post { kb.key(k) }
        scheduleRestore()
        return true
    }
}
