package net.eenda.rvladdons.core

import java.text.Normalizer
import java.nio.charset.Charset
import java.util.Locale

object RvlTextMatcher {
    fun normalize(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return Normalizer.normalize(repairMojibake(value), Normalizer.Form.NFD)
            .replace("\\p{M}".toRegex(), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .uppercase(Locale.ROOT)
    }

    fun contains(haystack: String?, needle: String?): Boolean {
        val normalizedNeedle = normalize(needle)
        return normalizedNeedle.isNotEmpty() && normalize(haystack).contains(normalizedNeedle)
    }

    private fun repairMojibake(value: String): String {
        if (!(value.contains('Ã') || value.contains('Â') || value.contains('â') ||
                value.contains("á»") || value.contains("áº"))) return value
        return runCatching {
            String(value.toByteArray(Charset.forName("windows-1252")), Charsets.UTF_8)
                .takeUnless { it.contains('\uFFFD') }
                ?: value
        }.getOrDefault(value)
    }
}
