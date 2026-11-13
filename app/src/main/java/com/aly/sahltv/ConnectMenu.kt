package com.aly.sahltv

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.EncodeHintType

internal fun MainActivity.connectMenu() {
    val p = getSharedPreferences("connect", 0)
    val enabled = p.getBoolean("enabled", false)
    val actions = mutableListOf(if (enabled) "Turn Sahl Connect off" else "Turn Sahl Connect on")
    if (enabled) actions.addAll(listOf("Pair a phone (QR / full code)", "Paired phones / revoke access", "Permissions: TV navigation (${if (ConnectAccess.active != null) "ready" else "setup needed"})", "Permissions: Phone keyboard", if (p.getBoolean("files", false)) "Stop receiving files" else "Allow file receiving (8 MB maximum)"))
    dialog().setTitle("Sahl Connect · preview").setMessage(
        "${if (ConnectServer.running) "Encrypted connection ready" else if (enabled) ConnectServer.lastError ?: "Starting… reopen this menu in a moment" else "Off"}\n\n" +
        "Same Wi-Fi only. Each phone pairs using the code shown on this TV. You can revoke it here. File receiving needs separate consent.\n\n" +
        "Permissions & setup: arrows, OK and Back need Sahl Connect navigation enabled in TV Accessibility. Choose Options → Permissions: TV navigation to set it up. Typing needs Sahl Keyboard selected in TV input settings. File receiving needs separate consent. The phone app shows these steps in Permissions & setup. No screen text is sent to your phone.")
        .setPositiveButton("Options") { _, _ ->
            dialog().setTitle("Sahl Connect").setItems(actions.toTypedArray()) { _, which -> guarded {
                when (which) {
                    0 -> {
                        p.edit().putBoolean("enabled", !enabled).putBoolean("files", false).apply()
                        if (enabled) ConnectServer.stop() else ConnectServer.start(this)
                        handler.postDelayed({ connectMenu() }, 700)
                    }
                    1 -> connectPair()
                    2 -> {
                        val phones = ConnectServer.phones()
                        val items = phones.map { it.second }.toTypedArray() + "Revoke all phones"
                        dialog().setTitle("Select a phone to revoke").setItems(items) { _, i -> guarded {
                            try { ConnectServer.revoke(if (i == phones.size) null else phones[i].first); toast("Access revoked") }
                            catch (_: Exception) { toast("Could not revoke access. Turn Sahl Connect off now.") }
                        } }.setNegativeButton("Close", null).show()
                    }
                    3 -> {
                        dialog().setTitle("Optional navigation permission").setMessage("Enable Sahl Connect navigation in Accessibility settings to let paired phones move focus, press OK, Home and Back across apps. Android grants access to the active window for focus navigation; Sahl Connect does not transmit its text. Disable it whenever you want.")
                            .setPositiveButton("Open Accessibility") { _, _ -> try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) { toast("Open TV Settings → Accessibility") } }
                            .setNegativeButton("Cancel", null).show()
                    }
                    4 -> {
                        dialog().setTitle("Phone keyboard").setMessage("In TV Settings → Keyboard, enable and select Sahl Keyboard. Open a text field in the TV app, then send text from Sahl Connect. Switch back to your usual keyboard manually when finished. No ADB or privileged settings permission is required.")
                            .setPositiveButton("Keyboard settings") { _, _ -> try { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) } catch (_: Exception) { toast("Open TV Settings → Keyboard") } }
                            .setNegativeButton("Close", null).show()
                    }
                    5 -> { p.edit().putBoolean("files", !p.getBoolean("files", false)).apply(); connectMenu() }
                }
            } }.setNegativeButton("Close", null).show()
        }.setNegativeButton("Close", null).show()
}

internal fun MainActivity.connectPair(): android.app.Dialog? {
    val code = try { ConnectServer.pairCode() } catch (_: Exception) { toast("Connection not ready. Check Wi-Fi and reopen the menu."); return null }
    val bits = MultiFormatWriter().encode(code, BarcodeFormat.QR_CODE, 560, 560, mapOf(EncodeHintType.MARGIN to 4))
    val bmp = Bitmap.createBitmap(560, 560, Bitmap.Config.RGB_565)
    for (y in 0 until 560) for (x in 0 until 560) bmp.setPixel(x, y, if (bits[x, y]) Color.BLACK else Color.WHITE)
    // A separate full-screen window, not the launcher's narrow standard AlertDialog.
    // The QR is measured against the available row height and width, including font scaling.
    val dialog = android.app.Dialog(this)
    dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_FULLSCREEN)
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(36), dp(24), dp(36), dp(24))
        setBackgroundColor(android.graphics.Color.parseColor("#10261F"))
    }
    root.addView(TextView(this).apply { text = "Pair Sahl Connect"; textSize = 26f; setTextColor(GOLD); setPadding(0, 0, 0, dp(12)) })
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    root.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
    val qr = ImageView(this).apply {
        tag = "connect-qr"; setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "Private one-use pairing QR"
    }
    row.addView(qr, LinearLayout.LayoutParams(1, 1))
    val instructions = TextView(this).apply {
        tag = "connect-instructions"
        text = "On the same Wi-Fi, open Sahl Connect on your phone and press Scan TV QR.\n\nScan the complete square shown here.\n\nValid for 3 minutes, one use only. Closing this screen cancels pairing.\n\nFor manual entry, choose Full pairing code below."
        textSize = 18f; setTextColor(this@connectPair.TEXT); setPadding(dp(28), 0, 0, 0)
    }
    val scroll = android.widget.ScrollView(this).apply { addView(instructions); isFillViewport = true; isFocusable = true }
    row.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
    row.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
        val size = minOf(((right - left) * 0.44f).toInt(), bottom - top, dp(340)).coerceAtLeast(1)
        if (qr.layoutParams.width != size || qr.layoutParams.height != size) {
            qr.layoutParams = (qr.layoutParams as LinearLayout.LayoutParams).apply { width = size; height = size }
        }
    }
    var manual: android.app.Dialog? = null
    val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
    root.addView(buttons)
    buttons.addView(android.widget.Button(this).apply {
        text = "Full pairing code"
        setOnClickListener {
            manual = android.app.Dialog(this@connectPair).also { full ->
                full.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
                full.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_FULLSCREEN)
                val box = LinearLayout(this@connectPair).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(36), dp(24), dp(36), dp(24))
                    setBackgroundColor(android.graphics.Color.parseColor("#10261F"))
                }
                val codeText = TextView(this@connectPair).apply {
                    text = "Enter this FULL code in Sahl Connect on your phone:\n\n$code"
                    textSize = 18f; setTextColor(this@connectPair.TEXT); textDirection = android.view.View.TEXT_DIRECTION_LTR
                }
                box.addView(android.widget.ScrollView(this@connectPair).apply { addView(codeText); isFocusable = true }, LinearLayout.LayoutParams(-1, 0, 1f))
                box.addView(android.widget.Button(this@connectPair).apply { text = "Back to QR"; setOnClickListener { full.dismiss() } })
                full.setContentView(box); full.show()
                full.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            }
        }
    }, LinearLayout.LayoutParams(0, -2, 1f))
    buttons.addView(android.widget.Button(this).apply { text = "Cancel pairing"; setOnClickListener { dialog.dismiss() } }, LinearLayout.LayoutParams(0, -2, 1f))
    dialog.setContentView(root)
    val expire = Runnable { dialog.dismiss() }
    dialog.setOnDismissListener { manual?.dismiss(); ConnectServer.closePairing(); handler.removeCallbacks(expire) }
    dialog.show()
    dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    handler.postDelayed(expire, 180000)
    return dialog
}
