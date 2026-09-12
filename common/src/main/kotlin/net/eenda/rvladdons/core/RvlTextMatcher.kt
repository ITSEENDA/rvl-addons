package net.eenda.rvladdons.core

import java.text.Normalizer
import java.util.Locale

object RvlTextMatcher {
    fun normalize(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{M}".toRegex(), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .uppercase(Locale.ROOT)
    }

    fun contains(haystack: String?, needle: String?): Boolean {
        val normalizedNeedle = normalize(needle)
        return normalizedNeedle.isNotEmpty() && normalize(haystack).contains(normalizedNeedle)
    }
}
