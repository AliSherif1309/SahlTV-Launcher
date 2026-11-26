package com.sahl.tv

import android.test.InstrumentationTestCase
import com.sahl.tv.common.C
import com.sahl.tv.common.R

class DialogTextTest : InstrumentationTestCase() {
    fun testDialogTextRemainsWhiteInRemoteAndTouchStates() {
        val ctx = instrumentation.targetContext
        val colors = ctx.resources.getColorStateList(R.color.sahl_dialog_text, ctx.theme)
        val buttons = ctx.resources.getColorStateList(R.color.sahl_dialog_button_text, ctx.theme)
        for (state in listOf(android.R.attr.state_focused, android.R.attr.state_pressed, android.R.attr.state_selected)) {
            val states = intArrayOf(android.R.attr.state_enabled, state)
            assertEquals(C.TEXT, colors.getColorForState(states, 0))
            if (state != android.R.attr.state_selected) assertEquals(C.TEXT, buttons.getColorForState(states, 0))
        }
        assertEquals(C.TEXT, colors.defaultColor)
        assertEquals(C.MUTED, colors.getColorForState(intArrayOf(-android.R.attr.state_enabled), 0))
    }
}
