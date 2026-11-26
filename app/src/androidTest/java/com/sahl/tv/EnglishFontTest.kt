package com.sahl.tv

import android.graphics.Typeface
import android.test.InstrumentationTestCase
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.widget.TextView
import com.sahl.tv.common.Fonts

class EnglishFontTest : InstrumentationTestCase() {
    fun testMixedTextKeepsArabicOutsideEnglishFont() {
        instrumentation.runOnMainSync {
            val tv = TextView(instrumentation.targetContext).apply {
                typeface = Typeface.DEFAULT_BOLD
                text = "سهل"
            }
            Fonts.applyTree(tv)
            val arabicFace = tv.typeface
            tv.text = "Sahl سهل ١٢"
            Fonts.applyTree(tv)
            assertEquals(arabicFace, tv.typeface)
            assertEquals("Sahl سهل ١٢", tv.text.toString())
            val text = tv.text as Spanned
            val spans = text.getSpans(0, 4, MetricAffectingSpan::class.java)
            assertTrue(spans.isNotEmpty())
            val paint = TextPaint().apply { typeface = arabicFace }
            spans.forEach { it.updateMeasureState(paint) }
            assertEquals(Typeface.create("sans-serif", Typeface.BOLD), paint.typeface)
            assertTrue(text.getSpans(5, 8, MetricAffectingSpan::class.java).isEmpty())
            assertTrue(text.getSpans(9, 11, MetricAffectingSpan::class.java).isEmpty())
            val before = tv.text
            Fonts.applyTree(tv)
            assertSame(before, tv.text)
            tv.text = "العربية"
            Fonts.applyTree(tv)
            assertEquals("العربية", tv.text.toString())
        }
    }

    fun testDedicatedReadingAndCodeFontsArePreserved() {
        instrumentation.runOnMainSync {
            for (face in listOf(Typeface.MONOSPACE, Typeface.SERIF)) {
                val tv = TextView(instrumentation.targetContext).apply {
                    typeface = face
                    text = "Read 123 اقرأ"
                }
                Fonts.applyTree(tv)
                assertEquals(face, tv.typeface)
                val text = tv.text
                assertTrue(text !is Spanned || text.getSpans(0, text.length, MetricAffectingSpan::class.java).isEmpty())
            }
        }
    }
}
