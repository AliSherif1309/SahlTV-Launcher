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
    if (enabled) actions.addAll(listOf("Pair a phone (QR / full code)", "Paired phones / revoke access", "TV navigation setup", "Phone keyboard setup", if (p.getBoolean("files", false)) "Stop receiving files" else "Allow file receiving (8 MB maximum)"))
    dialog().setTitle("Sahl Connect · preview").setMessage(
        "${if (ConnectServer.running) "Encrypted connection ready" else if (enabled) ConnectServer.lastError ?: "Starting… reopen this menu in a moment" else "Off"}\n\n" +
        "Same Wi-Fi only. Each phone pairs using the code shown on this TV. You can revoke it here. File receiving needs separate consent.\n\n" +
        "Navigation needs the optional Accessibility service. Typing needs Sahl Keyboard selected in TV input settings. Some apps may not support remote focus. No screen text is sent to your phone.")
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

private fun MainActivity.connectPair() {
    val code = try { ConnectServer.pairCode() } catch (_: Exception) { toast("Connection not ready. Check Wi-Fi and reopen the menu."); return }
    val bits = MultiFormatWriter().encode(code, BarcodeFormat.QR_CODE, 560, 560, mapOf(EncodeHintType.MARGIN to 4))
    val bmp = Bitmap.createBitmap(560, 560, Bitmap.Config.RGB_565)
    for (y in 0 until 560) for (x in 0 until 560) bmp.setPixel(x, y, if (bits[x, y]) Color.BLACK else Color.WHITE)
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(20), dp(12), dp(20), dp(12)) }
    row.addView(ImageView(this).apply { setImageBitmap(bmp); contentDescription = "Private one-use pairing QR" }, LinearLayout.LayoutParams(dp(250), dp(250)))
    val text = TextView(this).apply {
        text = "On the same Wi-Fi, open Sahl Connect → Scan TV QR.\n\nValid for 3 minutes, one use only. Closing this dialog cancels pairing.\n\nManual alternative: enter this FULL code in the phone app (includes the TV identity):\n\n$code"
        textSize = 14f; setTextColor(this@connectPair.TEXT); setPadding(dp(20), 0, 0, 0); textDirection = android.view.View.TEXT_DIRECTION_LTR
    }
    row.addView(text, LinearLayout.LayoutParams(dp(420), -2))
    val dialog = dialog().setTitle("Pair Sahl Connect").setView(row).setNegativeButton("Cancel", null).create()
    val expire = Runnable { dialog.dismiss() }
    dialog.setOnDismissListener { ConnectServer.closePairing(); handler.removeCallbacks(expire) }
    dialog.show(); dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    handler.postDelayed(expire, 180000)
}
