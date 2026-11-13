package com.aly.sahlconnect

import android.Manifest
import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import com.aly.sahl.common.ConnectVault
import com.aly.sahl.connect.Protocol
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class ScannerTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private fun resumed(type: Class<*>): Boolean {
        var found = false
        i.runOnMainSync { found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { type.isInstance(it) } }
        return found
    }
    private fun waitFor(type: Class<*>) {
        val until = System.currentTimeMillis() + 10000
        while (!resumed(type) && System.currentTimeMillis() < until) Thread.sleep(100)
        assertTrue("Expected resumed ${type.simpleName}", resumed(type))
    }
    @Test fun scanButtonOpensCameraAndBackReturnsWithoutCrashing() {
        val ctx = i.targetContext
        ConnectVault(ctx, "phone").clear()
        i.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.CAMERA)
        val activity = i.startActivitySync(Intent(ctx, ConnectActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            i.runOnMainSync {
                val views = ArrayList<View>()
                activity.window.decorView.findViewsWithText(views, "Scan TV QR", View.FIND_VIEWS_WITH_TEXT)
                val scan = views.filterIsInstance<TextView>().single { it.text.toString() == "Scan TV QR" }
                assertTrue(scan.performClick())
            }
            waitFor(ConnectScannerActivity::class.java)
            Thread.sleep(1500) // exercise asynchronous camera opening, not only intent construction
            assertTrue(resumed(ConnectScannerActivity::class.java))
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor(ConnectActivity::class.java)
        } finally { i.runOnMainSync { activity.finish() } }
    }
    @Test fun missingNavigationOpensSetupAndKeyboardStatusDistinguishesSelectionFromActiveField() {
        val ctx = i.targetContext
        val vault = ConnectVault(ctx, "phone")
        vault.write(JSONObject().put("host", "192.0.2.1").put("pin", Protocol.random()).put("token", Protocol.random()).toString())
        val activity = i.startActivitySync(Intent(ctx, ConnectActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ConnectActivity
        try {
            i.runOnMainSync {
                activity.applyTvPermissions(JSONObject().put("navigation", false).put("keyboardEnabled", false).put("keyboardSelected", false))
                val views = ArrayList<View>()
                activity.window.decorView.findViewsWithText(views, "↑", View.FIND_VIEWS_WITH_TEXT)
                views.filterIsInstance<TextView>().single { it.text.toString() == "↑" }.performClick()
                assertTrue("A blocked arrow should explain setup, not leave the user guessing", activity.setupDialog?.isShowing == true)
                val instructions = ArrayList<View>()
                activity.setupDialog!!.window!!.decorView.findViewsWithText(instructions, "Open TV Accessibility", View.FIND_VIEWS_WITH_TEXT)
                assertTrue(instructions.isNotEmpty())
                activity.setupDialog!!.dismiss()
                activity.applyTvPermissions(JSONObject().put("navigation", true).put("keyboardEnabled", true).put("keyboardSelected", false))
                val summary = activity.window.decorView.findViewWithTag<TextView>("permissions-summary")
                assertTrue(summary.text.contains("TV remote navigation: Ready"))
                assertTrue(summary.text.contains("Enabled — select Sahl Keyboard"))
                activity.applyTvPermissions(JSONObject().put("navigation", true).put("keyboardEnabled", true).put("keyboardSelected", true).put("keyboard", false))
                assertTrue(summary.text.contains("Selected — open a TV text field"))
            }
        } finally { i.runOnMainSync { activity.setupDialog?.dismiss(); activity.finish() }; vault.clear() }
    }
}
