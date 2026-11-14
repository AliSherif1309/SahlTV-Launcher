package com.aly.sahltv

import java.text.Normalizer
import java.util.Locale

/** Search the visible labels and descriptions; ignore Arabic vowel marks and alef variants. */
internal object SettingsDiscovery {
    private fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace("ـ", "")
        .replace('ى', 'ي')
        .lowercase(Locale.ROOT)

    fun matches(query: String, vararg labels: String): Boolean {
        val haystack = normalize(labels.joinToString(" "))
        return normalize(query).trim().split(Regex("\\s+")).all { it in haystack }
    }

    // Register new options here with a monotonically increasing discovery revision.
    // Opening an option acknowledges only that revision, persisted across app updates.
    fun revision(action: Int): Int? = when (action) {
        40, 41, 42, 43 -> 1
        else -> null
    }
}
