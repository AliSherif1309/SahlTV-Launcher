package com.aly.sahlquran

import org.junit.Assert.*
import org.junit.Test

class PdfPageMappingTest {
    @Test fun freshPdfAlwaysStartsAtItsFirstPage() {
        for (count in listOf(604, 608, 610, 620)) {
            assertEquals(0, PdfPageMapping.alignmentStart(-1, count))
            assertEquals(0, PdfPageMapping.index(PdfPageMapping.alignmentStart(-1, count), 1))
        }
    }

    @Test fun explicitAlignmentIsRetainedAndResetCanReachTheFirstPdfPage() {
        assertEquals(4, PdfPageMapping.alignmentStart(4, 608))
        assertEquals(4, PdfPageMapping.index(4, 1)) // the reported app page 1 / PDF page 5 mismatch
        assertEquals(0, PdfPageMapping.index(0, 1))
        assertEquals(1, PdfPageMapping.page(0, 0))
        val bookmark = PdfPageMapping.index(4, 487)
        assertEquals(491, PdfPageMapping.page(0, bookmark))
        assertEquals(bookmark, PdfPageMapping.index(0, 491))
    }

    @Test fun correctScreenshotOffsetWithoutMovingThePdfLocation() {
        // Menu says 487 while the same PDF page is printed as 491.
        val location = PdfPageMapping.index(6, 487)
        val corrected = PdfPageMapping.correctedOffset(6, 487, 491, 610)!!
        assertEquals(2, corrected)
        assertEquals(location, PdfPageMapping.index(corrected, 491))
        assertEquals(491, PdfPageMapping.page(corrected, location))
    }

    @Test fun exactEvenPageBookmarkSurvivesRecalibration() {
        val saved = PdfPageMapping.index(6, 488)
        assertEquals(488, PdfPageMapping.page(6, saved))
        assertEquals(492, PdfPageMapping.page(2, saved))
        assertEquals(saved, PdfPageMapping.index(2, 492))
    }

    @Test fun rejectOffsetsThatCannotContainACompleteMushaf() {
        assertNull(PdfPageMapping.correctedOffset(0, 487, 491, 604))
        assertNull(PdfPageMapping.correctedOffset(6, 487, 0, 610))
        assertNull(PdfPageMapping.correctedOffset(6, 487, 605, 610))
        assertNull(PdfPageMapping.correctedOffset(6, 487, 480, 610))
        assertEquals(0, PdfPageMapping.correctedOffset(0, 1, 1, 604))
        assertEquals(6, PdfPageMapping.correctedOffset(6, 604, 604, 610))
    }
}
