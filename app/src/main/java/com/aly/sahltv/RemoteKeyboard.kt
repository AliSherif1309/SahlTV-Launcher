package com.aly.sahltv

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView

/**
 * "Sahl Keyboard": receives text typed on the phone remote and puts it into the text box on the TV.
 * It has no keys of its own — just a slim bar saying where to type. The phone remote switches to it
 * automatically while you type (when allowed once with ADB) and gives the normal TV keyboard back afterwards.
 */
class RemoteKeyboard : InputMethodService() {

    companion object {
        @Volatile var active: RemoteKeyboard? = null
    }

    override fun onCreate() { super.onCreate(); active = this }
    override fun onDestroy() { if (active === this) active = null; super.onDestroy() }

    fun hasField() = currentInputConnection != null && currentInputEditorInfo?.inputType != EditorInfo.TYPE_NULL

    override fun onCreateInputView(): View {
        val ar = getSharedPreferences("sahl", MODE_PRIVATE).getString("lang", null).let {
            if (it != null) it == "ar" else resources.configuration.locales[0].language == "ar"
        }
        val ip = RemoteServer.ip() ?: "…"
        return TextView(this).apply {
            text = if (ar) "📱  اكتب من هاتفك:  http://$ip:${RemoteServer.PORT}   ·   زر الرجوع للإغلاق"
                   else "📱  Type on your phone:  http://$ip:${RemoteServer.PORT}   ·   Back to close"
            textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#E60E1A24"))
            val p = (14 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
    }

    /** Insert text typed on the phone. */
    fun type(text: String): Boolean {
        val ic = currentInputConnection ?: return false
        return ic.commitText(text, 1)
    }

    /** enter / search / del / clear */
    fun key(k: String) {
        val ic = currentInputConnection ?: return
        when (k) {
            "del" -> ic.deleteSurroundingText(1, 0)
            "clear" -> {
                val before = ic.getTextBeforeCursor(10000, 0)?.length ?: 0
                val after = ic.getTextAfterCursor(10000, 0)?.length ?: 0
                ic.deleteSurroundingText(before, after)
            }
            else -> {
                val action = (currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
                if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) ic.performEditorAction(action)
                else {
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                }
            }
        }
    }
}
