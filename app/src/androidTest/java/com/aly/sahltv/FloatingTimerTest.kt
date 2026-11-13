package com.aly.sahltv

import android.test.InstrumentationTestCase
import android.os.SystemClock
import android.view.Gravity

class FloatingTimerTest : InstrumentationTestCase() {
    fun testCountdownPauseResumeAndReset() {
        val timer = TimerState()
        timer.start(1_000)
        assertEquals(240_000L, timer.remaining(61_000))
        timer.pause(61_000)
        assertEquals(240_000L, timer.remaining(999_000))
        timer.start(1_000_000)
        timer.start(1_100_000) // repeated OK cannot extend the deadline
        assertEquals(180_000L, timer.remaining(1_060_000))
        timer.reset()
        assertFalse(timer.running)
        assertEquals(300_000L, timer.left)
    }
    fun testCompletionIsClampedAndReportedOnce() {
        val timer = TimerState(60_000, 60_000)
        timer.start(100)
        assertEquals(0L, timer.remaining(100_000))
        assertTrue(timer.finish(100_000))
        assertFalse(timer.finish(100_001))
        timer.start(200_000)
        assertFalse(timer.running)
        timer.reset(); timer.start(200_000)
        assertTrue(timer.running)
    }
    fun testDurationAndDisplayBoundaries() {
        val timer = TimerState()
        timer.select(-1); assertEquals(60_000L, timer.duration)
        timer.select(999); assertEquals(10_800_000L, timer.duration)
        assertEquals("00:00", timerText(-1))
        assertEquals("00:01", timerText(1))
        assertEquals("01:00", timerText(60_000))
        assertEquals("180:00", timerText(10_800_000))
    }
    fun testPersistenceAndSeparateHomePreferences() {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("floating_timer", 0)
        val backup = HashMap(prefs.all)
        try {
            prefs.edit().clear().commit()
            val store = TimerStore(context)
            assertFalse(store.visible); assertFalse(store.overlay)
            store.state.select(25); store.state.start(SystemClock.elapsedRealtime())
            store.visible = true; store.position = 3; store.save()
            val restored = TimerStore(context)
            assertTrue(restored.state.running)
            assertTrue(restored.visible)
            assertEquals(3, restored.position)
            assertTrue(restored.state.remaining(SystemClock.elapsedRealtime()) in 1_490_000L..1_500_000L)
            prefs.edit().putInt("boot", -99).commit()
            assertFalse(TimerStore(context).state.running)
            assertEquals(Gravity.BOTTOM or Gravity.RIGHT, timerGravity(3))
            instrumentation.runOnMainSync {
                val label = timerLabel(context)
                assertFalse(label.isFocusable)
            }
        } finally {
            val editor = prefs.edit().clear()
            for ((key, value) in backup) when (value) {
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
            }
            editor.commit()
        }
    }
}
