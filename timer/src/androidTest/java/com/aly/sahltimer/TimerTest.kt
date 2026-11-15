package com.aly.sahltimer

import android.test.InstrumentationTestCase
import android.content.Intent
import android.widget.TextView
import com.aly.sahl.common.Family

@Suppress("DEPRECATION")
class TimerTest : InstrumentationTestCase() {
    fun testPauseResumeAndCompletion() {
        val s = TimerState()
        s.select(1); s.start(1000)
        s.pause(11000); assertEquals(50000L, s.left)
        s.start(20000); assertEquals(50000L, s.remaining(20000))
        assertTrue(s.finish(70000)); assertEquals(0L, s.left); assertFalse(s.running)
        s.reset(); assertEquals(60000L, s.left)
    }

    fun testStandaloneScreenWorksWithoutOverlayPermission() {
        val context = instrumentation.targetContext
        context.getSharedPreferences("floating_timer", 0).edit().clear().commit()
        val activity = instrumentation.startActivitySync(Intent(context, TimerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                val focused = activity.currentFocus
                assertTrue(focused is TextView)
                assertTrue(focused!!.performClick())
                assertTrue(TimerStore(context).state.running)
                assertFalse(TimerStore(context).overlay)
                focused.performClick()
                assertFalse(TimerStore(context).state.running)
                assertSame(focused, activity.currentFocus)
                assertTrue(Family.members.any { it.pkg == context.packageName })
            }
        } finally { activity.finish() }
    }
}
