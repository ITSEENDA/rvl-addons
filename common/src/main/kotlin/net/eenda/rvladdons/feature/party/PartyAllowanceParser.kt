package net.eenda.rvladdons.feature.party

import net.eenda.rvladdons.core.RvlTextMatcher

object PartyAllowanceParser {
    private val number = Regex("\\d+")

    fun remaining(commandRoot: String, loreLines: Iterable<String>): Int? {
        val root = normalizeRoot(commandRoot) ?: return null
        val label = if (root == "tl") "VE THANH LAN" else "LUOT THAM GIA"
        val lines = loreLines.map(RvlTextMatcher::normalize)

        lines.forEachIndexed { index, line ->
            if (!line.contains(label) || line.contains("TIEU HAO")) return@forEachIndexed
            number.find(line.substringAfter(label))?.value?.toIntOrNull()?.let { return it }

            val remainder = line.substringAfter(label).trim().trim(':', '-').trim()
            if (remainder.isEmpty()) {
                lines.getOrNull(index + 1)
                    ?.trim()
                    ?.toIntOrNull()
                    ?.let { return it }
            }
        }
        return null
    }

    fun cost(commandRoot: String, loreLines: Iterable<String>): Int? {
        val root = normalizeRoot(commandRoot) ?: return null
        val resource = if (root == "tl") "VE THANH LAN" else "LUOT THAM GIA"
        val lines = loreLines.map(RvlTextMatcher::normalize)
        lines.indices.forEach { index ->
            if (!lines[index].contains("TIEU HAO")) return@forEach
            val end = (index + 3).coerceAtMost(lines.size)
            val costText = lines.subList(index, end).joinToString(" ")
            if (!costText.contains(resource)) return@forEach
            number.find(costText.substringAfter("TIEU HAO"))?.value?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?.let { return it }
        }
        return null
    }

    private fun normalizeRoot(commandRoot: String): String? =
        commandRoot.trim().removePrefix("/").substringBefore(' ').lowercase()
            .takeIf { it == "dchien" || it == "tl" }
}
