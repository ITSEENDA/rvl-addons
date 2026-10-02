package net.eenda.rvladdons.client.coma

import net.eenda.rvladdons.client.item.FabricItemAdapter
import net.eenda.rvladdons.core.RvlTextMatcher
import net.eenda.rvladdons.feature.coma.ComaItemRuleCodec
import net.eenda.rvladdons.feature.coma.ComaItemRuleMatcher
import net.eenda.rvladdons.feature.coma.ComaItemSnapshot
import net.eenda.rvladdons.feature.coma.RegisteredComaItemRule
import net.minecraft.item.ItemStack

/** Coma-specific item rules and set-name extraction. */
object FabricComaItemAdapter {
    fun snapshot(stack: ItemStack): ComaItemSnapshot = FabricItemAdapter.snapshot(stack).let {
        ComaItemSnapshot(it.itemId, it.displayName, it.armorType, it.fingerprint)
    }

    fun encodeRule(stack: ItemStack): String {
        val item = FabricItemAdapter.snapshot(stack)
        return ComaItemRuleCodec.encode(
            RegisteredComaItemRule(
                itemId = item.itemId,
                displayName = item.displayName,
                armorType = item.armorType,
                fingerprint = item.fingerprint,
                fingerprintVersion = 2
            )
        )
    }

    fun extractSetName(stack: ItemStack): String? {
        val lines = FabricItemAdapter.loreLines(stack)
        val activation = lines.firstOrNull { RvlTextMatcher.normalize(it).contains("KICH HOAT BO") }
        if (activation != null) {
            val normalized = RvlTextMatcher.normalize(activation)
            val marker = normalized.indexOf("KICH HOAT BO")
            normalized.substring(marker + "KICH HOAT BO".length).trim().trim('[', ']')
                .takeIf(String::isNotBlank)?.let { return it }
        }
        val armorSet = lines.firstOrNull {
            val normalized = RvlTextMatcher.normalize(it)
            normalized.contains("TRANG SUC") && normalized.contains("-")
        }
        return armorSet?.substringAfterLast('-')?.trim()?.trim('[', ']')?.takeIf(String::isNotBlank)
    }

    fun isDefaultSetName(name: String): Boolean =
        Regex("^SET\\s+\\d+$").matches(RvlTextMatcher.normalize(name))

    fun inferSetName(itemNames: Collection<String>): String? {
        val entries = itemNames.mapNotNull { raw ->
            val rawTokens = raw.trim().split(Regex("[\\s|:/\\-]+"))
                .filter(String::isNotBlank)
            if (rawTokens.isEmpty()) null
            else rawTokens to rawTokens.map(RvlTextMatcher::normalize)
        }
        if (entries.isEmpty()) return null

        val firstTokens = entries.first().second
        var bestStart = -1
        var bestLength = 0
        for (start in firstTokens.indices) {
            for (end in start + 1..firstTokens.size) {
                val length = end - start
                if (length <= bestLength) continue
                val candidate = firstTokens.subList(start, end)
                val presentInAll = entries.drop(1).all { (_, tokens) ->
                    tokens.windowed(length).any { it == candidate }
                }
                if (presentInAll) {
                    bestStart = start
                    bestLength = length
                }
            }
        }
        if (bestStart < 0) return null

        var resultStart = bestStart
        var resultEnd = bestStart + bestLength
        while (resultStart < resultEnd && isRoleToken(firstTokens[resultStart])) resultStart++
        while (resultEnd > resultStart && isRoleToken(firstTokens[resultEnd - 1])) resultEnd--
        if (resultStart >= resultEnd) return null

        return entries.first().first.subList(resultStart, resultEnd)
            .joinToString(" ")
            .trim()
            .takeIf(String::isNotBlank)
    }

    private fun isRoleToken(token: String): Boolean = token in setOf(
        "MU", "NON", "GIAP", "AO", "QUAN", "GIAY",
        "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"
    )

    fun isComaEquipment(stack: ItemStack?, roleIndex: Int): Boolean {
        val item = stack?.takeUnless(ItemStack::isEmpty)?.let(::snapshot)
        return ComaItemRuleMatcher.isComaEquipment(item, roleIndex)
    }

    fun matches(stack: ItemStack, rule: String): Boolean =
        ComaItemRuleMatcher.matches(stack.takeUnless(ItemStack::isEmpty)?.let(::snapshot), rule)
}
