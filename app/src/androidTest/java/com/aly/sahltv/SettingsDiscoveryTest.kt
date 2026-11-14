package com.aly.sahltv

import android.test.ActivityInstrumentationTestCase2
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView

/** Exercise search, category completeness, Back navigation and durable discovery state. */
class SettingsDiscoveryTest : ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    private fun texts(view: View): List<String> {
        val own = if (view is TextView) listOf(view.text.toString()) else emptyList()
        return own + if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList()
    }

    fun testSearchNormalizesArabicAndMatchesMultipleWords() {
        assertTrue(SettingsDiscovery.matches("اعدادات", "إِعْدَادَات"))
        assertTrue(SettingsDiscovery.matches("العربي", "العربى"))
        assertTrue(SettingsDiscovery.matches("  TIMER   floating ", "Floating timer"))
        assertTrue(SettingsDiscovery.matches("countdown", "Floating timer", "Countdown above apps"))
        assertFalse(SettingsDiscovery.matches("timer missing", "Floating timer"))
    }

    fun testEverySettingAppearsExactlyOnce() {
        val a = activity
        instrumentation.runOnMainSync {
            val actions = a.settingsSections().flatMap { it.second }.map { it.action }
            assertEquals(actions.size, actions.toSet().size)
            assertEquals((0..43).filter { it != 16 }.toSet(), actions.toSet())
        }
    }

    fun testGlobalSearchAndBackNavigation() {
        val a = activity
        instrumentation.runOnMainSync {
            a.showSettings()
            try {
                val search = a.settingsList!!.getChildAt(1) as EditText
                search.setText(if (a.ar) "المؤقت" else "timer")
                assertNotNull(a.settingsResults!!.findViewWithTag<View>("setting:43"))
                search.setText("zzzznonexistent")
                assertTrue(texts(a.settingsResults!!).any { it.contains(if (a.ar) "لا توجد" else "No settings") })
                a.settingsBack()
                assertEquals("", a.settingsQuery)
                a.settingsCategory = 0
                a.fillSettings()
                a.settingsBack()
                assertEquals(-1, a.settingsCategory)
                assertNotNull(a.settingsView)
                a.settingsBack()
                assertNull(a.settingsView)
            } finally { a.hideSettings() }
        }
    }

    fun testBadgeAcknowledgesOnlyOpenedSetting() {
        val a = activity
        instrumentation.runOnMainSync {
            val key = "settingsSeen:43"
            val existed = a.prefs.contains(key)
            val previous = a.prefs.getInt(key, 0)
            try {
                a.prefs.edit().remove(key).commit()
                assertTrue(a.isNewSetting(43))
                a.showSettings()
                a.settingsQuery = if (a.ar) "المؤقت" else "timer"
                a.fillSettingsResults()
                a.settingsResults!!.findViewWithTag<View>("setting:43").performClick()
                assertFalse(a.isNewSetting(43))
                assertFalse(a.isNewSetting(0))
                a.fillSettingsResults()
                assertFalse(a.isNewSetting(43))
            } finally {
                // Close the timer dialog without changing its settings.
                a.hideSettings()
                a.prefs.edit().apply { if (existed) putInt(key, previous) else remove(key) }.commit()
            }
        }
        // Activity teardown dismisses any dialog opened by this test.
        activity.finish()
    }
}
