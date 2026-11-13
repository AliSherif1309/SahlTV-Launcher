package com.aly.sahlconnect

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import com.aly.sahl.common.*
import com.aly.sahl.connect.Protocol
import com.google.zxing.integration.android.IntentIntegrator
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A single-TV, LAN-only companion. Standard system fonts; no Sahl font assets are included. */
class ConnectActivity : Activity() {
    private lateinit var vault: ConnectVault
    private var identity: JSONObject? = null
    private lateinit var column: LinearLayout
    private lateinit var status: TextView
    private var apps: LinearLayout? = null
    private var tvPermissions: JSONObject? = null
    private var setupPromptShown = false
    private val permissionSummaries = mutableSetOf<TextView>()
    internal var setupDialog: AlertDialog? = null
        private set
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(12))
    private var generation = 0
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        vault = ConnectVault(this, "phone")
        try { identity = vault.read()?.let(::JSONObject) } catch (_: Exception) { vault.clear() }
        screen()
    }
    override fun onDestroy() { generation++; worker.shutdownNow(); super.onDestroy() }
    override fun onResume() { super.onResume(); if (::status.isInitialized) updatePermissionSummaries() }
    private fun text(value: String, size: Float = 16f) = label(value, size).apply { setPadding(0, dp(8), 0, dp(8)) }
    private fun button(title: String, parent: LinearLayout = column, action: () -> Unit) {
        parent.addView(pill(title, action), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(4) })
    }
    private fun screen() {
        permissionSummaries.clear()
        tvPermissions = null
        val scroll = ScrollView(this).apply { background = backdrop(); isFillViewport = true }
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(26), dp(22), dp(32)) }
        scroll.addView(column); setContentView(scroll)
        column.addView(text("Sahl Connect", 30f).apply { setTextColor(C.GOLD) })
        status = text(if (identity == null) "Your phone, connected to your Sahl TV." else "TV ${identity!!.getString("host")} · tap Refresh to connect")
        column.addView(status)
        column.addView(text("Permissions & setup", 21f).apply { setTextColor(C.GOLD) })
        column.addView(text("").apply { tag = "permissions-summary"; permissionSummaries.add(this) })
        button("Permissions & setup") { showPermissions() }
        updatePermissionSummaries()
        if (identity == null) {
            column.addView(text("On the TV: Settings → Sahl Connect → Turn on → Pair a phone. Keep that QR open and join the same Wi-Fi. Pairing expires after 3 minutes. No account or internet service is needed."))
            button("Scan TV QR") { scanTvQr() }
            val code = EditText(this).apply {
                hint = "Or enter the full sahl-connect:// code"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                isSaveEnabled = false; setTextColor(C.TEXT); setHintTextColor(C.MUTED); minLines = 2
            }
            column.addView(code)
            button("Pair with full code") { val value = code.text.toString(); code.text.clear(); pair(value) }
            column.addView(text("Only scan a code displayed by your own TV. It contains the TV certificate fingerprint and a one-use secret. Keep it private. This preview uses the phone’s standard fonts."))
            return
        }
        button("Refresh apps & diagnostics") { refresh() }
        column.addView(text("Arrows, OK and Back need TV remote navigation access. Set it up using Permissions & setup above."))
        val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        column.addView(pad)
        for (row in listOf(listOf("↑" to "up"), listOf("←" to "left", "OK" to "ok", "→" to "right"), listOf("↓" to "down"), listOf("Home" to "home", "Back" to "back"))) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            pad.addView(line)
            row.forEach { (label, key) -> line.addView(pill(label) { send("key", "key", key) }, LinearLayout.LayoutParams(0, dp(58), 1f).apply { setMargins(dp(3), dp(4), dp(3), 0) }) }
        }
        val media = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(media)
        listOf("Previous" to "prev", "Play / pause" to "toggle", "Next" to "next").forEach { (label, key) ->
            media.addView(pill(label) { send("media", "key", key) }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(8), dp(3), 0) })
        }
        val volume = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(volume)
        listOf("Vol −" to "down", "Mute" to "mute", "Vol +" to "up").forEach { (label, key) ->
            volume.addView(pill(label) { send("volume", "key", key) }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(8), dp(3), 0) })
        }
        column.addView(text("Phone keyboard", 21f))
        val input = EditText(this).apply { hint = "Text for the active TV field"; setTextColor(C.TEXT); setHintTextColor(C.MUTED); isSaveEnabled = false; maxLines = 4 }
        column.addView(input)
        button("Send text") {
            if (!keyboardSelected()) { showPermissions(); return@button }
            val value = input.text.toString()
            if (value.length !in 1..4000) { status.text = "Enter 1–4000 characters"; return@button }
            request(JSONObject().put("op", "text").put("text", value)) { if (it.optBoolean("ok")) input.text.clear() }
        }
        button("TV Backspace") { send("edit", "key", "del") }
        button("TV Enter / Search") { send("edit", "key", "enter") }
        column.addView(text("Select Sahl Keyboard in TV input settings and open a TV text field before typing. Select your usual keyboard again when finished."))
        button("Send a file to TV") {
            if (tvPermissions?.optBoolean("filesEnabled") != true) { showPermissions(); return@button }
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 73)
        }
        column.addView(text("File receiving must be enabled on the TV. PDF, TXT, PNG/JPEG, MP3 and MP4 only, up to 8 MB; saved in TV Download/Sahl Connect (Android 10+). Files are never opened or installed automatically."))
        column.addView(text("Apps & favourites", 21f))
        apps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { column.addView(it) }
        button("Forget this TV") {
            AlertDialog.Builder(this).setTitle("Forget TV?").setMessage("This removes the key from this phone. Revoke this phone on the TV too if you no longer want it to have access.")
                .setPositiveButton("Forget") { _, _ -> generation++; vault.clear(); identity = null; setupPromptShown = false; screen() }.setNegativeButton("Cancel", null).show()
        }
        refresh()
    }
    private fun keyboardSelected(): Boolean = tvPermissions?.let {
        if (it.has("keyboardSelected")) it.optBoolean("keyboardSelected") else it.optBoolean("keyboard")
    } ?: false
    internal fun applyTvPermissions(state: JSONObject) {
        tvPermissions = JSONObject(state.toString())
        updatePermissionSummaries()
    }
    private fun updatePermissionSummaries() {
        val camera = if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) "Allowed" else "Not allowed — optional for QR scanning"
        val navigation = when {
            identity == null -> "Pair a TV first"
            tvPermissions == null -> "Not checked — refresh TV status"
            tvPermissions!!.optBoolean("navigation") -> "Ready"
            else -> "Needs setup on TV — required for arrows, OK and Back"
        }
        val keyboard = when {
            identity == null -> "Pair a TV first"
            tvPermissions == null -> "Not checked"
            keyboardSelected() -> if (tvPermissions!!.optBoolean("keyboard")) "Ready to type" else "Selected — open a TV text field"
            tvPermissions!!.optBoolean("keyboardEnabled") -> "Enabled — select Sahl Keyboard on TV"
            else -> "Enable and select Sahl Keyboard on TV — optional for typing"
        }
        val files = when {
            identity == null -> "Pair a TV first"
            tvPermissions == null -> "Not checked"
            tvPermissions!!.has("filesSupported") && !tvPermissions!!.optBoolean("filesSupported") -> "Requires Android 10 or later on TV"
            tvPermissions!!.optBoolean("filesEnabled") -> "Allowed"
            else -> "Off — allow file receiving on TV only if wanted"
        }
        val summary = "TV remote navigation: $navigation\n\nPhone keyboard: $keyboard\n\nTV file receiving: $files\n\nPhone camera: $camera"
        permissionSummaries.forEach { it.text = if (it.tag == "permissions-summary") summary.replace("\n\n", "\n") else summary }
    }
    private fun showPermissions() {
        if (setupDialog?.isShowing == true) return
        setupPromptShown = true
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, dp(18), dp(12)) }
        val summary = text("").also { permissionSummaries.add(it); box.addView(it) }
        box.addView(text("TV remote navigation\nArrows, OK and Back require the optional Sahl Connect navigation service on the TV. Open TV Accessibility, choose Sahl Connect navigation under Services / Downloaded services, then turn it on and accept the TV notice. This lets a paired phone move focus and press controls. Screen text is not sent to your phone."))
        if (identity != null) button("Open TV Accessibility", box) { openTvSetup("navigation") }
        box.addView(text("Phone keyboard\nFor typing only: enable and select Sahl Keyboard in TV Keyboard settings, then open a TV text field. Select your usual keyboard again when finished."))
        if (identity != null) button("Open TV Keyboard settings", box) { openTvSetup("keyboard") }
        box.addView(text("File receiving\nOptional and off by default. On TV: Sahl Connect → Options → Allow file receiving. This is separate consent; pairing alone does not enable uploads."))
        box.addView(text("Phone camera\nUsed only to scan the TV pairing QR. Allow Camera when scanning, or enter the full code manually."))
        button("Phone app permissions", box) {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        if (identity != null) button("Check TV permissions again", box) { refresh() }
        else box.addView(text("Pair your TV first to check its permission status and open its setup screens from here."))
        setupDialog = AlertDialog.Builder(this).setTitle("Permissions & setup")
            .setView(ScrollView(this).apply { addView(box) }).setPositiveButton("Done", null).create().also { dialog ->
                dialog.setOnDismissListener { permissionSummaries.remove(summary); setupDialog = null }
                dialog.show()
            }
        updatePermissionSummaries()
        if (identity != null) refresh()
    }
    private fun openTvSetup(section: String) {
        request(JSONObject().put("op", "setup").put("section", section)) { response ->
            if (response.optBoolean("ok")) toast("Continue on the TV, then tap Check TV permissions again")
        }
    }
    private fun scanTvQr() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            status.text = "No camera available. Enter the full TV pairing code instead."; return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 74); return
        }
        try {
            IntentIntegrator(this).setCaptureActivity(ConnectScannerActivity::class.java)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE).setPrompt("Scan the QR on your own TV")
                .setBeepEnabled(false).setOrientationLocked(false).initiateScan()
        } catch (_: RuntimeException) {
            status.text = "Could not open the camera. You can enter the full TV pairing code instead."
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 74) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) scanTvQr()
            else status.text = "Camera access was declined. Allow Camera in phone app settings to scan, or enter the full TV pairing code."
        }
        updatePermissionSummaries()
    }
    private fun background(action: () -> Unit) {
        val epoch = generation
        try { worker.execute {
            try { if (epoch == generation) action() }
            catch (e: Exception) { runOnUiThread { if (epoch == generation && !isFinishing && !isDestroyed) status.text = "Could not connect securely. Check TV pairing, Wi-Fi and permissions. ${e.javaClass.simpleName}" } }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { status.text = "Please wait for the current command" }
    }
    private fun pair(code: String) {
        val parsed = try { Protocol.PairCode(code) } catch (_: Exception) { status.text = "Invalid full code. Scan the QR displayed on your own TV."; return }
        status.text = "Pairing securely…"
        val epoch = generation
        background {
            val response = ConnectTls.client(parsed.host, parsed.pin).use { socket ->
                val q = JSONObject().put("v", 1).put("op", "pair").put("secret", parsed.secret).put("name", android.os.Build.MODEL.take(60))
                Protocol.write(socket.getOutputStream(), q.toString().toByteArray())
                JSONObject(Protocol.read(socket.getInputStream()).toString(Charsets.UTF_8))
            }
            runOnUiThread {
                if (epoch != generation || isDestroyed) return@runOnUiThread
                if (!response.optBoolean("ok")) { status.text = response.optString("message"); return@runOnUiThread }
                val token = response.getString("token")
                if (!token.matches(Regex("[a-f0-9]{64}"))) { status.text = "Invalid TV response"; return@runOnUiThread }
                val saved = JSONObject().put("host", parsed.host).put("pin", parsed.pin).put("token", token)
                try { vault.write(saved.toString()); identity = saved; screen(); showPermissions() }
                catch (_: Exception) { status.text = "Could not save credentials. Revoke this phone on the TV and try again." }
            }
        }
    }
    private fun request(q: JSONObject, after: (JSONObject) -> Unit = {}) {
        val saved = identity?.let { JSONObject(it.toString()) } ?: return
        val epoch = generation
        background {
            val response = ConnectTls.client(saved.getString("host"), saved.getString("pin")).use { socket ->
                q.put("v", 1).put("token", saved.getString("token"))
                Protocol.write(socket.getOutputStream(), q.toString().toByteArray())
                JSONObject(Protocol.read(socket.getInputStream()).toString(Charsets.UTF_8))
            }
            runOnUiThread {
                if (epoch != generation || isDestroyed) return@runOnUiThread
                status.text = if (response.optBoolean("ok")) "Connected to TV" else response.optString("message", "Command unavailable")
                after(response)
                if (!response.optBoolean("ok") && response.optString("setup") in listOf("navigation", "keyboard")) showPermissions()
            }
        }
    }
    private fun send(op: String, field: String, value: String) {
        if (op == "key" && value != "home" && tvPermissions?.optBoolean("navigation") != true) { showPermissions(); return }
        if (op == "edit" && !keyboardSelected()) { showPermissions(); return }
        request(JSONObject().put("op", op).put(field, value))
    }
    private fun refresh(): Unit = request(JSONObject().put("op", "state")) { response ->
        if (!response.optBoolean("ok")) return@request
        applyTvPermissions(response)
        if (!response.optBoolean("navigation") && !setupPromptShown) showPermissions()
        status.text = "Sahl TV ${response.optString("version")} · Android ${response.optString("android")}\n" +
            "Free RAM: ${response.optLong("freeMb")} / ${response.optLong("totalMb")} MB\n" +
            "Navigation: ${if (response.optBoolean("navigation")) "enabled" else "enable on TV"} · Keyboard field: ${if (response.optBoolean("keyboard")) "ready" else "not active"}\n" +
            "File receiving: ${if (response.optBoolean("filesEnabled")) "allowed" else "off"}"
        apps?.removeAllViews()
        val entries = response.optJSONArray("apps") ?: return@request
        for (i in 0 until entries.length()) {
            val a = entries.getJSONObject(i); val pkg = a.getString("package")
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            apps?.addView(row)
            row.addView(pill(a.getString("label")) { send("launch", "package", pkg) }, LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(6) })
            row.addView(pill(if (a.optBoolean("favourite")) "★" else "☆") {
                request(JSONObject().put("op", "favourite").put("package", pkg).put("enabled", !a.optBoolean("favourite"))) { if (it.optBoolean("ok")) refresh() }
            }, LinearLayout.LayoutParams(dp(64), -2).apply { topMargin = dp(6); marginStart = dp(6) })
        }
    }
    private fun upload(uri: Uri) {
        val saved = identity?.let { JSONObject(it.toString()) } ?: return
        val epoch = generation
        status.text = "Preparing file…"
        background {
            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else "file" } ?: "file"
            val bytes = contentResolver.openInputStream(uri)!!.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(32768)
                while (true) { val n = input.read(buf); if (n < 0) break; require(out.size() + n <= Protocol.MAX_FILE) { "File exceeds 8 MB" }; out.write(buf, 0, n) }
                out.toByteArray()
            }
            val response = ConnectTls.client(saved.getString("host"), saved.getString("pin")).use { socket ->
                val q = JSONObject().put("v", 1).put("op", "upload").put("token", saved.getString("token"))
                    .put("name", name).put("size", bytes.size).put("sha256", Protocol.hash(bytes))
                Protocol.write(socket.getOutputStream(), q.toString().toByteArray())
                val ready = JSONObject(Protocol.read(socket.getInputStream()).toString(Charsets.UTF_8))
                if (!ready.optBoolean("ok")) ready else {
                    socket.getOutputStream().write(bytes); socket.getOutputStream().flush()
                    JSONObject(Protocol.read(socket.getInputStream()).toString(Charsets.UTF_8))
                }
            }
            runOnUiThread { if (epoch == generation && !isDestroyed) status.text = response.optString("message") }
        }
    }
    @Deprecated("Legacy scanner and file picker result bridge")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scan != null) { scan.contents?.let { pair(it) }; return }
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 73 && resultCode == RESULT_OK) data?.data?.let { upload(it) }
    }
}
