package com.aly.sahltv

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.aly.sahl.common.ConnectTls
import com.aly.sahl.common.ConnectVault
import com.aly.sahl.common.LanGuard
import com.aly.sahl.connect.Protocol
import org.json.JSONArray
import org.json.JSONObject
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** Dedicated TLS-only v1 endpoint. Disabled until opened on the TV; independent of the old browser remote. */
object ConnectServer {
    private val gate = Protocol.PairingGate()
    private val main = Handler(Looper.getMainLooper())
    private val pool = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(4))
    @Volatile private var server: SSLServerSocket? = null
    @Volatile private var pin: String? = null
    private var generation = 0L
    private var starting = false
    private lateinit var app: Context
    private lateinit var vault: ConnectVault
    private var identities = JSONArray()
    @Volatile var lastError: String? = null
        private set
    val running get() = server != null
    private val clients = java.util.Collections.synchronizedSet(mutableSetOf<Socket>())

    @Synchronized fun start(ctx: Context) {
        if (starting || running) return
        app = ctx.applicationContext; vault = ConnectVault(app, "tv-identities")
        try { identities = JSONArray(vault.read() ?: "[]") }
        catch (_: Exception) { identities = JSONArray(); lastError = "Paired-device storage unavailable. Revoke all phones here, then turn off and on to pair again."; return }
        starting = true; lastError = null; val epoch = ++generation
        Thread {
            var ss: SSLServerSocket? = null
            try {
                val identity = ConnectTls.server(); val listener = identity.first; ss = listener
                synchronized(this) {
                    if (generation != epoch) { listener.close(); return@Thread }
                    server = listener; pin = identity.second; starting = false
                }
                while (!listener.isClosed) {
                    val s = listener.accept()
                    if (!LanGuard.allowedPeer(s.inetAddress)) { s.close(); continue }
                    clients.add(s)
                    try { pool.execute { handle(s, epoch) } }
                    catch (_: java.util.concurrent.RejectedExecutionException) { clients.remove(s); s.close() }
                }
            } catch (e: Exception) {
                synchronized(this) { if (generation == epoch) lastError = "Connection service unavailable: ${e.javaClass.simpleName}" }
            } finally {
                try { ss?.close() } catch (_: Exception) {}
                synchronized(this) { if (generation == epoch) { server = null; pin = null; starting = false } }
            }
        }.apply { name = "sahl-connect"; isDaemon = true }.start()
    }
    @Synchronized fun stop() {
        generation++; starting = false; gate.close(); pin = null
        try { server?.close() } catch (_: Exception) {}; server = null
        synchronized(clients) { clients.toList().forEach { try { it.close() } catch (_: Exception) {} } }
        pool.queue.clear()
    }
    @Synchronized fun pairCode(): String {
        check(running); val ip = RemoteServer.ip() ?: error("Connect the TV to Wi-Fi first")
        require(Protocol.privateV4(ip))
        return "sahl-connect://$ip:${Protocol.PORT}/v1?pin=$pin&secret=${gate.open(SystemClock.elapsedRealtime())}"
    }
    fun closePairing() { gate.close() }
    @Synchronized fun phones(): List<Pair<String, String>> = (0 until identities.length()).map {
        identities.getJSONObject(it).let { o -> o.getString("hash") to o.getString("name") }
    }
    @Synchronized fun revoke(hash: String?) {
        val keep = JSONArray()
        for (i in 0 until identities.length()) identities.getJSONObject(i).let { if (hash != null && it.getString("hash") != hash) keep.put(it) }
        vault.write(keep.toString()); identities = keep
    }
    @Synchronized private fun authenticate(token: String): Boolean {
        if (!token.matches(Regex("[a-f0-9]{64}"))) return false
        val hash = Protocol.hash(token.toByteArray())
        val now = System.currentTimeMillis()
        for (i in 0 until identities.length()) {
            val o = identities.getJSONObject(i)
            if (Protocol.same(hash, o.getString("hash")) && now - o.getLong("seen") < 90L * 86400000 && now >= o.getLong("seen")) {
                if (now - o.getLong("seen") > 600000) { o.put("seen", now); vault.write(identities.toString()) }
                return true
            }
        }
        return false
    }
    private fun reply(s: Socket, obj: JSONObject) = Protocol.write(s.getOutputStream(), obj.toString().toByteArray())
    private fun result(ok: Boolean, message: String = "") = JSONObject().put("ok", ok).put("message", message)
    private fun handle(s: Socket, epoch: Long) {
        s.use {
            try {
                s.soTimeout = 15000; (s as SSLSocket).startHandshake()
                val request = JSONObject(Protocol.read(s.getInputStream()).toString(Charsets.UTF_8))
                if (request.optInt("v") != 1) { reply(s, result(false, "Unsupported protocol")); return }
                synchronized(this) {
                    if (epoch != generation || !running) return
                    if (request.optString("op") == "pair") {
                        if (!gate.accept(request.optString("secret"), SystemClock.elapsedRealtime())) {
                            reply(s, result(false, "Pairing closed, expired or incorrect")); return
                        }
                        if (identities.length() >= 10) { reply(s, result(false, "Remove a paired phone first (limit 10)")); return }
                        val token = Protocol.random()
                        val name = request.optString("name", "Phone").filter { !it.isISOControl() }.take(60).ifBlank { "Phone" }
                        val entry = JSONObject().put("hash", Protocol.hash(token.toByteArray())).put("name", name).put("seen", System.currentTimeMillis())
                        val updated = JSONArray(identities.toString()).put(entry)
                        vault.write(updated.toString()); identities = updated
                        reply(s, result(true, "Paired").put("token", token))
                        main.post { android.widget.Toast.makeText(app, "Sahl Connect paired: $name", android.widget.Toast.LENGTH_LONG).show() }
                        return
                    }
                    if (!authenticate(request.optString("token"))) { reply(s, result(false, "Pair again: access revoked or expired").put("unauthorized", true)); return }
                }
                if (request.optString("op") == "upload") {
                    receiveFile(s, request, epoch); return
                }
                // Re-check authorization on the UI thread immediately before the action, so revocation is effective for queued requests.
                val task = FutureTask<JSONObject> {
                    synchronized(this) {
                        if (epoch != generation || !running || !authenticate(request.optString("token"))) result(false, "Access revoked")
                        else command(request)
                    }
                }
                main.post(task)
                val response = try { task.get(4, TimeUnit.SECONDS) } catch (e: Exception) { task.cancel(false); throw e }
                reply(s, response)
            } catch (_: Exception) { /* Invalid/partial requests close without executing. Never log keys or text. */ }
            finally { clients.remove(s) }
        }
    }
    private fun command(q: JSONObject): JSONObject {
        val host = RemoteServer.host as? MainActivity
        when (q.optString("op")) {
            "state" -> {
                val list = JSONArray()
                host?.let { h -> h.appList().forEach { (pkg, label) -> list.put(JSONObject().put("package", pkg).put("label", label).put("favourite", pkg in h.favorites)) } }
                val info = android.app.ActivityManager.MemoryInfo().also { (app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it) }
                val ime = app.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                val selected = android.provider.Settings.Secure.getString(app.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
                return result(true).put("apps", list).put("navigation", ConnectAccess.active != null)
                    .put("keyboardEnabled", ime.enabledInputMethodList.any { it.packageName == app.packageName && it.serviceName.endsWith("RemoteKeyboard") })
                    .put("keyboardSelected", selected.startsWith("${app.packageName}/") && selected.contains("RemoteKeyboard"))
                    .put("filesSupported", android.os.Build.VERSION.SDK_INT >= 29)
                    .put("keyboard", RemoteKeyboard.active?.hasField() == true).put("freeMb", info.availMem / 1048576)
                    .put("totalMb", info.totalMem / 1048576).put("android", android.os.Build.VERSION.RELEASE)
                    .put("version", app.packageManager.getPackageInfo(app.packageName, 0).versionName)
                    .put("filesEnabled", app.getSharedPreferences("connect", 0).getBoolean("files", false))
            }
            "key" -> {
                val key = q.optString("key")
                if (key == "home" && ConnectAccess.active == null && host != null) { host.goHome(); return result(true) }
                if (ConnectAccess.active == null) return result(false, "Enable Sahl Connect navigation on TV using Permissions & setup").put("setup", "navigation")
                return result(ConnectAccess.active?.key(key) == true, "This TV screen does not expose a usable focus control")
            }
            "setup" -> {
                if (host == null || host.pinSet || host.lockSettings) return result(false, "Open setup using the TV remote; TV PIN protection must be respected")
                val action = when (q.optString("section")) {
                    "navigation" -> android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
                    "keyboard" -> android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS
                    else -> return result(false, "Unknown setup screen")
                }
                return try {
                    app.startActivity(android.content.Intent(action).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    result(true, "Continue on the TV, then check permissions again")
                } catch (_: android.content.ActivityNotFoundException) { result(false, "Open TV Settings → Device Preferences → Accessibility or Keyboard using your TV remote") }
            }
            "text" -> {
                val text = q.optString("text")
                if (text.length !in 1..4000) return result(false, "Text must be 1–4000 characters")
                val kb = RemoteKeyboard.active
                return result(kb?.hasField() == true && kb.type(text), "Select Sahl Keyboard and open a text field on the TV first").put("setup", "keyboard")
            }
            "edit" -> {
                val key = q.optString("key"); val kb = RemoteKeyboard.active
                if (key !in listOf("del", "enter") || kb?.hasField() != true) return result(false, "No active TV text field")
                kb.key(key); return result(true)
            }
            "launch" -> {
                val pkg = q.optString("package")
                if (host == null || host.appList().none { it.first == pkg }) return result(false, "App unavailable or locked")
                host.launchPkg(pkg); return result(true)
            }
            "favourite" -> {
                val pkg = q.optString("package")
                if (host == null || host.pinSet || host.lockSettings || host.appList().none { it.first == pkg }) return result(false, "Change favourites on the TV when PIN protection is enabled")
                val fav = host.favorites.toMutableList()
                if (q.optBoolean("enabled")) { if (pkg !in fav) fav.add(pkg) } else fav.remove(pkg)
                host.saveList("fav", fav); host.render(true); return result(true)
            }
            "media" -> {
                val code = when (q.optString("key")) { "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; else -> return result(false, "Unknown media action") }
                val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); return result(true)
            }
            "volume" -> {
                val dir = when (q.optString("key")) { "up" -> AudioManager.ADJUST_RAISE; "down" -> AudioManager.ADJUST_LOWER; "mute" -> AudioManager.ADJUST_TOGGLE_MUTE; else -> return result(false, "Unknown volume action") }
                val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (am.isVolumeFixed) return result(false, "This TV uses fixed audio output; use the TV or receiver remote for volume")
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI); return result(true)
            }
        }
        return result(false, "Unsupported command")
    }
    private fun receiveFile(s: Socket, q: JSONObject, epoch: Long) {
        if (!app.getSharedPreferences("connect", 0).getBoolean("files", false)) { reply(s, result(false, "Allow file receiving on the TV first")); return }
        val size = q.optLong("size", -1)
        val name = q.optString("name").substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._ -]"), "_").take(100)
        val mime = when (name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)) {
            "pdf" -> "application/pdf"; "txt" -> "text/plain"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "mp3" -> "audio/mpeg"; "mp4" -> "video/mp4"; else -> null
        }
        if (size !in 1..Protocol.MAX_FILE.toLong() || mime == null) { reply(s, result(false, "Use PDF, text, images, MP3 or MP4, up to 8 MB. Fonts and APKs are excluded.")); return }
        if (android.os.Build.VERSION.SDK_INT < 29) { reply(s, result(false, "File receiving v1 requires Android 10 or later")); return }
        val resolver = app.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "Sahl-${System.currentTimeMillis()}-${Protocol.random().take(8)}-$name")
            put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/Sahl Connect")
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Cannot create download")
        var complete = false
        try {
            reply(s, result(true, "ready"))
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            resolver.openOutputStream(uri, "w")!!.use { out ->
                val buf = ByteArray(32768); var left = size; val deadline = SystemClock.elapsedRealtime() + 60000
                while (left > 0) {
                    synchronized(this) {
                        check(epoch == generation && authenticate(q.optString("token")) && app.getSharedPreferences("connect", 0).getBoolean("files", false))
                    }
                    check(SystemClock.elapsedRealtime() < deadline)
                    val n = s.getInputStream().read(buf, 0, minOf(buf.size.toLong(), left).toInt()); check(n > 0)
                    out.write(buf, 0, n); digest.update(buf, 0, n); left -= n
                }
            }
            check(Protocol.same(q.optString("sha256"), Protocol.hex(digest.digest())))
            synchronized(this) {
                check(epoch == generation && authenticate(q.optString("token")) && app.getSharedPreferences("connect", 0).getBoolean("files", false))
                resolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                complete = true
            }
            reply(s, result(true, "Saved in Download/Sahl Connect"))
        } finally { if (!complete) resolver.delete(uri, null, null) }
    }
}
