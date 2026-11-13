package com.aly.sahltv

import android.test.InstrumentationTestCase

/** The preview and live panel both consume the same immutable row plan. */
class FlexibleDashboardTest : InstrumentationTestCase() {
    fun testDefaultOrderDoesNotForcePairing() {
        assertEquals(dashboardKeys.map { listOf(it) }, flexibleDashboardRows(dashboardKeys, emptyMap()))
    }

    fun testHalfWidthCardsPairAcrossFormerGroups() {
        val order = listOf("ayah", "ramadan", "prayer", "fasting", "news", "greeting", "adhkar")
        val widths = mapOf("ayah" to "half", "ramadan" to "half", "prayer" to "half",
            "fasting" to "half", "news" to "half", "greeting" to "full", "adhkar" to "half")
        assertEquals(
            listOf(listOf("ayah", "ramadan"), listOf("prayer"),
                listOf("fasting", "news"), listOf("greeting"), listOf("adhkar")),
            flexibleDashboardRows(order, widths)
        )
    }

    fun testUnknownAndRepeatedCardsAreExcluded() {
        val rows = flexibleDashboardRows(listOf("news", "unknown", "news", "ayah"),
            mapOf("news" to "half", "ayah" to "half"))
        assertEquals(listOf(listOf("news", "ayah")), rows)
    }
}
