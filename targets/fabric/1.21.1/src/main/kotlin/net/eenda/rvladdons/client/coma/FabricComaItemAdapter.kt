package net.eenda.rvladdons.client.coma

import net.eenda.rvladdons.core.RvlTextMatcher
import net.eenda.rvladdons.feature.coma.ComaItemRuleCodec
import net.eenda.rvladdons.feature.coma.ComaItemRuleMatcher
import net.eenda.rvladdons.feature.coma.ComaItemSnapshot
import net.eenda.rvladdons.feature.coma.RegisteredComaItemRule
import net.minecraft.component.DataComponentTypes
import net.minecraft.item.ArmorItem
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries

/** Fabric 1.21.1 item identity and data-component extraction. */
object FabricComaItemAdapter {
    fun snapshot(stack: ItemStack): ComaItemSnapshot = ComaItemSnapshot(
        itemId = Registries.ITEM.getId(stack.item).toString(),
        displayName = RvlTextMatcher.normalize(stack.name.string),
        armorType = (stack.item as? ArmorItem)?.type?.toString().orEmpty(),
        fingerprint = itemFingerprint(stack)
    )

    fun encodeRule(stack: ItemStack): String {
        val itemId = Registries.ITEM.getId(stack.item).toString()
        val displayName = RvlTextMatcher.normalize(stack.name.string)
        val armorType = (stack.item as? ArmorItem)?.type?.toString().orEmpty()
        return ComaItemRuleCodec.encode(
            RegisteredComaItemRule(
                itemId = itemId,
                displayName = displayName,
                armorType = armorType,
                fingerprint = itemFingerprint(stack),
                fingerprintVersion = 2
            )
        )
    }

    fun extractSetName(stack: ItemStack): String? {
        val lines = itemLore(stack)
        val activation = lines.firstOrNull { RvlTextMatcher.normalize(it).contains("KICH HOAT BO") }
        if (activation != null) {
            val marker = activation.indexOf("bộ", ignoreCase = true)
            if (marker >= 0) {
                activation.substring(marker + 2).trim().trim('[', ']')
                    .takeIf(String::isNotBlank)?.let { return it }
            }
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

    fun sameItem(first: ItemStack?, second: ItemStack): Boolean =
        first != null && !first.isEmpty && snapshot(first).fingerprint == snapshot(second).fingerprint

    fun isComaEquipment(stack: ItemStack?, roleIndex: Int): Boolean {
        val item = stack?.takeUnless(ItemStack::isEmpty)?.let(::snapshot)
        return ComaItemRuleMatcher.isComaEquipment(item, roleIndex)
    }

    fun matches(stack: ItemStack, rule: String): Boolean =
        ComaItemRuleMatcher.matches(stack.takeUnless(ItemStack::isEmpty)?.let(::snapshot), rule)

    private fun itemLore(stack: ItemStack): List<String> =
        stack.get(DataComponentTypes.LORE)?.lines?.map { it.string }.orEmpty()

    private fun itemFingerprint(stack: ItemStack): String {
        val id = Registries.ITEM.getId(stack.item)
        val name = RvlTextMatcher.normalize(stack.name.string)
        val armorType = (stack.item as? ArmorItem)?.type?.toString().orEmpty()
        val dynamicComponents = setOf("minecraft:damage", "minecraft:repair_cost")
        val stableComponents = stack.components.types
            .mapNotNull { type ->
                val componentId = Registries.DATA_COMPONENT_TYPE.getId(type).toString()
                if (componentId in dynamicComponents) null
                else "$componentId=${stack.components.get(type)}"
            }
            .sorted()
            .joinToString("|")
        return Integer.toHexString("v2|$id|$name|$armorType|$stableComponents".hashCode())
    }
}
