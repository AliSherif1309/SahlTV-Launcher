package com.aly.sahltv

import android.test.InstrumentationTestCase

/** Regression checks for home row ordering and shared tile geometry. */
class HomeRowRulesTest : InstrumentationTestCase() {
    fun testRecentFirstWhenNoFavorites() {
        assertEquals(listOf("rec"), visibleHomeSections(0, 3, true, true, false))
        assertEquals(listOf("rec"), visibleHomeSections(2, 3, false, true, false))
        assertEquals(listOf("fav", "rec"), visibleHomeSections(2, 3, true, true, false))
        assertEquals(listOf("fav"), visibleHomeSections(2, 0, true, true, false))
        assertEquals(emptyList<String>(), visibleHomeSections(0, 0, true, true, false))
    }

    fun testCombinedRowVisibility() {
        assertEquals(listOf("combined"), visibleHomeSections(0, 3, true, true, true))
        assertEquals(listOf("combined"), visibleHomeSections(2, 0, true, true, true))
        assertEquals(emptyList<String>(), visibleHomeSections(2, 3, false, false, true))
        assertEquals(listOf("combined"), visibleHomeSections(2, 3, false, true, true))
    }

    fun testRowControlsAreBoundedAndDefaultSafe() {
        assertEquals(3, homeRecentLimit("3"))
        assertEquals(6, homeRecentLimit("6"))
        assertEquals(8, homeRecentLimit("8"))
        assertEquals(6, homeRecentLimit("unknown"))
        assertEquals(2, homeTileGap("tight"))
        assertEquals(6, homeTileGap("normal"))
        assertEquals(12, homeTileGap("spacious"))
        assertEquals(6, homeTileGap("invalid"))
    }

    fun testEveryAppRowUsesIdenticalTileProportions() {
        for ((size, width, height) in listOf(
            Triple("compact", 130, 73),
            Triple("normal", 160, 90),
            Triple("large", 200, 112)
        )) {
            val geometry = homeTileDimensions(size)
            assertEquals(width, geometry.widthDp)
            assertEquals(height, geometry.heightDp)
            // Grid, Favorites, Recent and combined all use the same layout helper.
            assertTrue(geometry.widthDp > geometry.heightDp)
        }
        assertEquals(homeTileDimensions("compact"), homeTileDimensions("invalid"))
    }
}
