package net.eenda.rvladdons.client.coma

import net.eenda.rvladdons.core.RvlTextMatcher
import net.eenda.rvladdons.feature.coma.ComaItemRuleCodec
import net.eenda.rvladdons.feature.coma.ComaItemRuleMatcher
import net.eenda.rvladdons.feature.coma.ComaItemSnapshot
import net.eenda.rvladdons.feature.coma.RegisteredComaItemRule
import net.minecraft.item.ArmorItem
import net.minecraft.item.AxeItem
import net.minecraft.item.BowItem
import net.minecraft.item.CrossbowItem
import net.minecraft.item.ItemStack
import net.minecraft.item.SwordItem
import net.minecraft.item.TridentItem
import net.minecraft.nbt.NbtElement
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier
import net.minecraft.text.Text

/** Fabric 1.20.4 item identity and NBT extraction. */
object FabricComaItemAdapter {
    fun snapshot(stack: ItemStack): ComaItemSnapshot = ComaItemSnapshot(
        itemId = Registries.ITEM.getId(stack.item).toString(),
        displayName = RvlTextMatcher.normalize(stack.name.string),
        armorType = (stack.item as? ArmorItem)?.type?.toString().orEmpty(),
        fingerprint = itemFingerprint(stack)
    )

    fun iconStack(itemId: String): ItemStack? {
        val identifier = Identifier.tryParse(itemId) ?: return null
        val item = Registries.ITEM.getOrEmpty(identifier).orElse(null) ?: return null
        return ItemStack(item)
    }

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
        val lines = loreLines(stack)
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

    fun sameItem(first: ItemStack?, second: ItemStack): Boolean =
        first != null && !first.isEmpty && snapshot(first).fingerprint == snapshot(second).fingerprint

    fun isComaEquipment(stack: ItemStack?, roleIndex: Int): Boolean {
        val item = stack?.takeUnless(ItemStack::isEmpty)?.let(::snapshot)
        return ComaItemRuleMatcher.isComaEquipment(item, roleIndex)
    }

    fun matches(stack: ItemStack, rule: String): Boolean =
        ComaItemRuleMatcher.matches(stack.takeUnless(ItemStack::isEmpty)?.let(::snapshot), rule)

    fun isWeapon(stack: ItemStack): Boolean =
        stack.item is SwordItem || stack.item is AxeItem || stack.item is BowItem ||
            stack.item is CrossbowItem || stack.item is TridentItem

    fun isArmor(stack: ItemStack): Boolean = stack.item is ArmorItem

    fun loreLines(stack: ItemStack): List<String> {
        val display = stack.nbt?.getCompound("display") ?: return emptyList()
        val lore = display.getList("Lore", NbtElement.STRING_TYPE.toInt())
        return (0 until lore.size).mapNotNull { index ->
            val raw = lore.getString(index)
            runCatching { Text.Serialization.fromJson(raw)?.string ?: raw }.getOrNull()
        }
    }

    private fun itemFingerprint(stack: ItemStack): String {
        val id = Registries.ITEM.getId(stack.item)
        val name = RvlTextMatcher.normalize(stack.name.string)
        val armorType = (stack.item as? ArmorItem)?.type?.toString().orEmpty()
        val stableNbt = stack.nbt?.copy()?.also {
            it.remove("Damage")
            it.remove("RepairCost")
        }?.toString().orEmpty()
        return Integer.toHexString("v2|$id|$name|$armorType|$stableNbt".hashCode())
    }
}
