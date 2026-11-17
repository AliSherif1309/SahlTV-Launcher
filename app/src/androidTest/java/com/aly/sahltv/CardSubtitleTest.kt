package com.aly.sahltv

import android.test.ActivityInstrumentationTestCase2
import android.view.View

class CardSubtitleTest : ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    fun testSubtitleDefaultAndCompactTransitions() {
        val a = activity
        instrumentation.runOnMainSync {
            val key = "islamicSubtitlesAlways"
            val saved = a.prefs.all[key] as? Boolean
            try {
                a.prefs.edit().remove(key).commit()
                a.slimIslamic(true)
                assertEquals(View.VISIBLE, a.ramSub.visibility)
                assertEquals(View.VISIBLE, a.fastSub.visibility)
                a.prefs.edit().putBoolean(key, false).commit()
                a.slimIslamic(true)
                assertEquals(View.GONE, a.ramSub.visibility)
                assertEquals(View.GONE, a.fastSub.visibility)
                a.slimIslamic(false)
                assertEquals(View.VISIBLE, a.ramSub.visibility)
                a.prefs.edit().putBoolean(key, true).commit()
                a.slimIslamic(true)
                assertEquals(View.VISIBLE, a.fastSub.visibility)
                val rows = a.settingsSections().flatMap { it.second }
                assertTrue(rows.any { it.action == 46 })
                assertTrue(rows.any { it.action == 47 })
            } finally {
                a.prefs.edit().apply { if (saved == null) remove(key) else putBoolean(key, saved) }.commit()
                a.updateClock()
            }
        }
    }
}
