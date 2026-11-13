@file:Suppress("DEPRECATION")
package com.aly.sahltv

import android.app.AlertDialog
import android.content.Intent
import android.test.InstrumentationTestCase
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.aly.sahl.common.LanGuard
import com.aly.sahl.common.UpdateCheck
import java.io.File
import java.net.InetAddress

/** Real framework views and D-pad events on a TV emulator; no physical TV claim. */
class HardeningTest : InstrumentationTestCase() {
    private var activity: MainActivity? = null
    private var dialog: AlertDialog? = null

    private fun ui(action: () -> Unit) {
        instrumentation.runOnMainSync(action)
        instrumentation.waitForIdleSync()
    }

    private fun launch(): MainActivity {
        instrumentation.targetContext.getSharedPreferences("sahl", 0).edit().clear()
            .putString("lang", "en").putInt("autoUpd", 0).commit()
        val a = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        activity = a
        instrumentation.waitForIdleSync()
        return a
    }

    private fun text(root: View, value: String): TextView? {
        if (root is TextView && root.text.toString() == value) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) {
            text(root.getChildAt(i), value)?.let { return it }
        }
        return null
    }

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        instrumentation.waitForIdleSync()
    }

    fun testDashboardRemoteFocusSurvivesRedrawAndCancel() {
        val a = launch()
        ui { dialog = a.dashboardEditor() }
        val d = dialog!!
        ui { assertTrue(text(d.window!!.decorView, "M")!!.requestFocus()) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        ui {
            assertEquals("L", (d.currentFocus as TextView).text.toString())
            assertFalse(a.prefs.contains("dashboardSize_prayer"))
        }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        ui { assertEquals("↑", (d.currentFocus as TextView).text.toString()) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        ui { assertEquals("↑", (d.currentFocus as TextView).text.toString()) }
        ui { d.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus() }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        ui {
            assertFalse(d.isShowing)
            assertFalse(a.prefs.contains("dashboardSize_prayer"))
        }
    }

    fun testVisualEditorRemoteApplyAndCancel() {
        val a = launch()
        ui { dialog = a.homeVisualEditor() }
        val d = dialog!!
        ui { text(d.window!!.decorView, "Change tile size")!!.requestFocus() }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        ui {
            assertEquals("Change tile size", (d.currentFocus as TextView).text.toString())
            assertFalse(a.prefs.contains("homeTileSize"))
        }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        ui { assertEquals("Toggle combined / separate rows", (d.currentFocus as TextView).text.toString()) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        ui { d.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus() }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        ui { assertFalse(a.prefs.contains("homeTileSize")); dialog = a.homeVisualEditor() }
        val applied = dialog!!
        ui { text(applied.window!!.decorView, "Change tile size")!!.requestFocus() }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        ui { applied.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus() }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("normal", a.prefs.getString("homeTileSize", null))
    }

    fun testLanGuardRejectsPublicPeersAndRebindingHosts() {
        assertFalse(LanGuard.allowedPeer(null))
        for (ip in listOf("127.0.0.1", "192.168.1.2", "10.0.0.1", "172.16.0.1", "172.31.255.254", "::1", "fd00::1")) {
            assertTrue(ip, LanGuard.allowedPeer(InetAddress.getByName(ip)))
        }
        for (ip in listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "2001:4860:4860::8888")) {
            assertFalse(ip, LanGuard.allowedPeer(InetAddress.getByName(ip)))
        }
        for (host in listOf("localhost:8080", "192.168.1.2:8080", "[fd00::1]:8080")) assertTrue(host, LanGuard.allowedHost(host))
        for (host in listOf("evil.example:8080", "192.168.1.2.evil.example", "8.8.8.8:8080", "999.1.1.1", "", "user@192.168.1.2")) assertFalse(host, LanGuard.allowedHost(host))
        assertFalse(LanGuard.allowedHost(null))
        assertTrue(LanGuard.same("paired-token", "paired-token"))
        assertFalse(LanGuard.same("paired-token", "paired-tokeN"))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", LanGuard.sha256("abc"))
    }

    fun testUpdateVerificationRejectsTamperingAndWrongPackage() {
        val ctx = instrumentation.targetContext
        val apk = File(ctx.applicationInfo.sourceDir)
        val hash = UpdateCheck.sha256(apk)
        assertNull(UpdateCheck.verify(ctx, apk, hash, ctx.packageName))
        assertEquals("fingerprint does not match", UpdateCheck.verify(ctx, apk, "0".repeat(64), ctx.packageName))
        assertTrue(UpdateCheck.verify(ctx, apk, hash, "com.aly.wrong")!!.startsWith("unexpected app"))
        val invalid = File(ctx.cacheDir, "invalid-update.apk")
        try {
            invalid.writeText("")
            assertEquals("empty download", UpdateCheck.verify(ctx, invalid, "", ctx.packageName))
            invalid.writeText("not an APK")
            assertEquals("not an app file", UpdateCheck.verify(ctx, invalid, UpdateCheck.sha256(invalid), ctx.packageName))
        } finally { invalid.delete() }
    }

    override fun tearDown() {
        try { ui { dialog?.dismiss(); activity?.finish() } } finally { super.tearDown() }
    }
}
