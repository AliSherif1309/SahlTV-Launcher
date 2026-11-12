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
 * Phone remote: a tiny web server on the TV (port 8765, home network only).
 *
 * Security:
 *  • Pairing works only while "Pair a phone" is open on the TV (3 minutes), with a new one-time code each time.
 *    5 wrong codes close the window. Outside that window no code is accepted at all, so guessing is impossible.
 *  • Each paired phone gets its own random key. The TV keeps only a fingerprint (SHA-256) of it, with the phone's
 *    name and when it was last used. Phones unused for 90 days are forgotten. You can remove any phone in Settings.
 *  • Only devices on the home network may connect, and only by the TV's IP address (blocks websites — "DNS rebinding").
 *  • The TV shows a message each time a phone is paired.
 *  • Off by default; started from Settings → Phone remote.
 * Note: it is plain HTTP inside your home Wi-Fi (a browser cannot use HTTPS with a TV that has no certificate), so it
 * relies on your Wi-Fi password, like other TV remotes on the home network.
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
        fun paired(name: String)                      // shown on the TV
    }

    /** A paired phone: fingerprint of its key, a readable name, when it was paired and last used. */
    class Phone(val hash: String, val name: String, val since: Long, val seen: Long)

    @Volatile var host: Host? = null
    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)
    private lateinit var app: Context
    private val rnd = SecureRandom()
    @Volatile private var wrongCodes = 0
    @Volatile private var lastText = 0L

    private fun prefs() = app.getSharedPreferences("sahl", Context.MODE_PRIVATE)

    val running get() = server != null

    // ---------------------------------------------------------------- pairing window
    @Volatile private var pairCode: String? = null
    @Volatile private var pairUntil = 0L
    const val PAIR_MS = 3 * 60_000L

    /** Opens pairing for 3 minutes with a fresh one-time code (shown on the TV). */
    fun openPairing(): String {
        val c = String.format("%06d", rnd.nextInt(1_000_000))
        pairCode = c; pairUntil = System.currentTimeMillis() + PAIR_MS; wrongCodes = 0
        return c
    }
    fun closePairing() { pairCode = null; pairUntil = 0L }
    fun pairingOpen() = pairCode != null && System.currentTimeMillis() < pairUntil

    // ---------------------------------------------------------------- paired phones
    private fun sp(ctx: Context) = ctx.getSharedPreferences("sahl", Context.MODE_PRIVATE)

    fun phones(ctx: Context): List<Phone> {
        val p = sp(ctx)
        // older versions kept the keys themselves: keep those phones paired, but store only fingerprints from now on
        val old = (p.getString("rtokens", "") ?: "").split("|").filter { it.isNotEmpty() }
        if (old.isNotEmpty() || p.contains("rcode")) {
            val now = System.currentTimeMillis()
            savePhones(ctx, readPhones(ctx) + old.map { Phone(com.aly.sahl.common.LanGuard.sha256(it), "Phone", now, now) })
            p.edit().remove("rtokens").remove("rcode").apply()
        }
        val cutoff = System.currentTimeMillis() - 90L * 24 * 3_600_000L
        val l = readPhones(ctx)
        val keep = l.filter { it.seen > cutoff }
        if (keep.size != l.size) savePhones(ctx, keep)
        return keep
    }
    private fun readPhones(ctx: Context): List<Phone> = try {
        val a = org.json.JSONArray(sp(ctx).getString("rphones", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Phone(o.getString("h"), o.optString("n", "Phone"), o.optLong("s"), o.optLong("u")) } }
    } catch (_: Exception) { emptyList() }
    private fun savePhones(ctx: Context, l: List<Phone>) {
        val a = org.json.JSONArray()
        l.distinctBy { it.hash }.take(10).forEach { a.put(org.json.JSONObject().put("h", it.hash).put("n", it.name).put("s", it.since).put("u", it.seen)) }
        sp(ctx).edit().putString("rphones", a.toString()).apply()
    }
    fun removePhone(ctx: Context, hash: String) = savePhones(ctx, readPhones(ctx).filter { it.hash != hash })
    fun removeAll(ctx: Context) = sp(ctx).edit().remove("rphones").remove("rtokens").remove("rcode").apply()

    /** "Honor ALI-NX1", "iPhone", "Android phone" or "Computer", from the browser's description. */
    private fun deviceName(ua: String?): String {
        val u = ua ?: return "Phone"
        Regex("""Android [\d.]+; ([^;)]+?)(?: Build/[^;)]*)?[;)]""").find(u)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && it != "K" }?.let { return it }
        return when { "iPhone" in u -> "iPhone"; "iPad" in u -> "iPad"; "Android" in u -> "Android phone"; else -> "Computer" }
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
                if (!com.aly.sahl.common.LanGuard.allowedPeer(sock.inetAddress)) return     // home network only
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
                val (status, type, out) =
                    if (!com.aly.sahl.common.LanGuard.allowedHost(headers["host"])) Triple("421 Misdirected Request", "text/plain", "Open the TV by its IP address")
                    else route(method, path, query, headers["x-token"], body, headers["user-agent"])
                val bytes = out.toByteArray(Charsets.UTF_8)
                val os = sock.getOutputStream()
                os.write(("HTTP/1.1 $status\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                    "Cache-Control: no-store\r\n" + com.aly.sahl.common.LanGuard.HEADERS + "Connection: close\r\n\r\n").toByteArray())
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

    /** Checks a phone's key against the stored fingerprints, and notes when it was last used (at most every 10 minutes). */
    private fun known(token: String?): Boolean {
        if (token.isNullOrEmpty() || token.length > 128) return false
        val h = com.aly.sahl.common.LanGuard.sha256(token)
        val l = phones(app)
        val p = l.firstOrNull { com.aly.sahl.common.LanGuard.same(it.hash, h) } ?: return false
        val now = System.currentTimeMillis()
        if (now - p.seen > 10 * 60_000L) savePhones(app, l.map { if (it.hash == p.hash) Phone(it.hash, it.name, it.since, now) else it })
        return true
    }

    private fun route(method: String, path: String, q: Map<String, String>, token: String?, body: String, ua: String?): Triple<String, String, String> {
        val ok = Triple("200 OK", "application/json", "{\"ok\":true}")
        if (method == "GET" && (path == "/" || path == "/index.html")) return Triple("200 OK", "text/html", RemotePage.HTML)
        if (path == "/api/pair" && method == "POST") {
            synchronized(this) {
                val c = pairCode
                if (c == null || !pairingOpen()) { Thread.sleep(500); return Triple("403 Forbidden", "application/json", "{\"ok\":false,\"closed\":true}") }
                if (!com.aly.sahl.common.LanGuard.same(body.trim(), c)) {
                    if (++wrongCodes >= 5) closePairing()          // 5 wrong codes: pairing closes until opened again on the TV
                    Thread.sleep(800); return Triple("403 Forbidden", "application/json", "{\"ok\":false}")
                }
                closePairing()                                     // one phone per code
                val t = ByteArray(32).also { rnd.nextBytes(it) }.joinToString("") { String.format("%02x", it) }
                val now = System.currentTimeMillis()
                val name = deviceName(ua)
                savePhones(app, listOf(Phone(com.aly.sahl.common.LanGuard.sha256(t), name, now, now)) + phones(app))
                android.os.Handler(android.os.Looper.getMainLooper()).post { host?.paired(name) }
                return Triple("200 OK", "application/json", "{\"ok\":true,\"token\":${json(t)}}")
            }
        }
        if (!known(token)) return Triple("401 Unauthorized", "application/json", "{\"ok\":false,\"pair\":true}")
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
