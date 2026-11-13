package com.aly.sahltv

import android.accessibilityservice.AccessibilityService
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Optional, user-enabled TV navigation. Does not read or send screen text to the phone. */
class ConnectAccess : AccessibilityService() {
    companion object { @Volatile var active: ConnectAccess? = null }
    override fun onServiceConnected() { active = this }
    override fun onDestroy() { if (active === this) active = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    fun key(name: String): Boolean {
        if (name == "home") return performGlobalAction(GLOBAL_ACTION_HOME)
        if (name == "back") return performGlobalAction(GLOBAL_ACTION_BACK)
        val root = rootInActiveWindow ?: return false
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return when (name) {
            "ok" -> focus.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            else -> {
                val direction = when (name) { "up" -> View.FOCUS_UP; "down" -> View.FOCUS_DOWN; "left" -> View.FOCUS_LEFT; "right" -> View.FOCUS_RIGHT; else -> return false }
                focus.focusSearch(direction)?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ?: false
            }
        }
    }
}
