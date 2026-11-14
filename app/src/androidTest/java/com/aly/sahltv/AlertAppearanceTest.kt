package com.aly.sahltv

import android.content.Context
import android.test.InstrumentationTestCase
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.aly.sahl.common.Fonts

/** Exercise real overlay views without requiring display-over-other-apps permission. */
class AlertAppearanceTest : InstrumentationTestCase() {
    private fun checkCard(language: String?, numberStyle: Boolean?, expectedTitle: String, expectedSub: String, rtl: Boolean) {
        val ctx = instrumentation.targetContext
        val prefs = ctx.getSharedPreferences("sahl", Context.MODE_PRIVATE)
        val oldLanguage = prefs.getString("lang", null)
        val hadNumberStyle = prefs.contains("arDigits")
        val oldNumberStyle = prefs.getBoolean("arDigits", false)
        try {
            prefs.edit().apply {
                if (language == null) remove("lang") else putString("lang", language)
                if (numberStyle == null) remove("arDigits") else putBoolean("arDigits", numberStyle)
            }.commit()
            instrumentation.runOnMainSync {
                val card = Alerts.createCard(ctx, "تذكير 10", "11:19 · ١٢")
                val texts = card.getChildAt(1) as LinearLayout
                val title = texts.getChildAt(0) as TextView
                val sub = texts.getChildAt(1) as TextView
                assertEquals(expectedTitle, title.text.toString())
                assertEquals(expectedSub, sub.text.toString())
                assertEquals(if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR, card.layoutDirection)
                assertEquals(Fonts.display(true), title.typeface)
                assertEquals(Fonts.get(), sub.typeface)
                assertEquals("tnum", sub.fontFeatureSettings)
            }
        } finally {
            prefs.edit().apply {
                if (oldLanguage == null) remove("lang") else putString("lang", oldLanguage)
                if (hadNumberStyle) putBoolean("arDigits", oldNumberStyle) else remove("arDigits")
            }.commit()
        }
    }

    fun testArabicDefaultsToArabicNumerals() = checkCard("ar", null, "تذكير ١٠", "١١:١٩ · ١٢", true)
    fun testArabicRespectsWesternNumberOverride() = checkCard("ar", false, "تذكير 10", "11:19 · 12", true)
    fun testEnglishDefaultsToWesternNumerals() = checkCard("en", null, "تذكير 10", "11:19 · 12", false)
    fun testEnglishRespectsArabicNumberOverride() = checkCard("en", true, "تذكير ١٠", "١١:١٩ · ١٢", false)
    fun testUnsetLanguageUsesDeviceLocale() {
        val ar = instrumentation.targetContext.resources.configuration.locales[0].language == "ar"
        checkCard(null, null, if (ar) "تذكير ١٠" else "تذكير 10", if (ar) "١١:١٩ · ١٢" else "11:19 · 12", ar)
    }
}
