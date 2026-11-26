package com.sahl.tv

import android.content.Intent
import android.test.InstrumentationTestCase
import android.view.KeyEvent
import android.view.ViewConfiguration

class RemoteControlsTest : InstrumentationTestCase() {
    fun testMenuTapHoldCancellationAndTesterIsolation() {
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val previous = activity.prefs.getString("remoteMenuHold", null)
            try {
                activity.prefs.edit().putString("remoteMenuHold", "none").commit()
                val controls = RemoteControls(activity)
                var normalCalls = 0
                val normal: (KeyEvent) -> Boolean = { normalCalls++; true }
                fun key(action: Int, time: Long, flags: Int = 0, repeat: Int = 0) =
                    KeyEvent(100, time, action, KeyEvent.KEYCODE_MENU, repeat, 0, 0, 0, flags)
                controls.dispatch(key(KeyEvent.ACTION_DOWN, 100), normal)
                controls.dispatch(key(KeyEvent.ACTION_UP, 120), normal)
                assertEquals("Short Menu must reach the existing context action once", 1, normalCalls)
                controls.dispatch(key(KeyEvent.ACTION_DOWN, 100), normal)
                controls.dispatch(key(KeyEvent.ACTION_DOWN, 200, repeat = 1), normal)
                controls.dispatch(key(KeyEvent.ACTION_UP, 100L + ViewConfiguration.getLongPressTimeout()), normal)
                assertEquals("Hold must not also activate the context action", 1, normalCalls)
                controls.dispatch(key(KeyEvent.ACTION_DOWN, 100), normal)
                controls.dispatch(key(KeyEvent.ACTION_DOWN, 100, KeyEvent.FLAG_LONG_PRESS, 1), normal)
                controls.dispatch(key(KeyEvent.ACTION_UP, 100), normal)
                assertEquals("Explicit long-press flags must work even with unchanged event times", 1, normalCalls)
                controls.dispatch(key(KeyEvent.ACTION_DOWN, 100), normal)
                controls.cancel()
                controls.dispatch(key(KeyEvent.ACTION_UP, 120), normal)
                controls.dispatch(key(KeyEvent.ACTION_DOWN, 100), normal)
                controls.dispatch(key(KeyEvent.ACTION_UP, 120, KeyEvent.FLAG_CANCELED), normal)
                assertEquals("Canceled presses must not perform actions", 1, normalCalls)
                val test = activity.remoteTester()
                try {
                    test.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, 100))
                    test.dispatchKeyEvent(key(KeyEvent.ACTION_UP, 120))
                    assertTrue("Menu must remain inside the tester", test.isShowing)
                    test.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
                    test.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
                    assertFalse("Back must close the tester", test.isShowing)
                } finally { test.dismiss() }
            } finally {
                activity.prefs.edit().putString("remoteMenuHold", previous).commit()
                activity.finish()
            }
        }
    }
}
