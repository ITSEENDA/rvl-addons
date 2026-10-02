package net.eenda.rvladdons.client.item

import net.eenda.rvladdons.core.RvlTextMatcher
import net.minecraft.item.ArmorItem
import net.minecraft.item.AxeItem
import net.minecraft.item.BowItem
import net.minecraft.item.CrossbowItem
import net.minecraft.item.ItemStack
import net.minecraft.item.SwordItem
import net.minecraft.item.TridentItem
import net.minecraft.nbt.NbtElement
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Identifier

/** Minecraft 1.20.4 item data access shared by client features. */
object FabricItemAdapter {
    fun itemId(stack: ItemStack): String = Registries.ITEM.getId(stack.item).toString()

    fun displayName(stack: ItemStack): String = RvlTextMatcher.normalize(stack.name.string)

    fun armorType(stack: ItemStack): String = (stack.item as? ArmorItem)?.type?.toString().orEmpty()

    fun snapshot(stack: ItemStack): FabricItemSnapshot = FabricItemSnapshot(
        itemId(stack), displayName(stack), armorType(stack), fingerprint(stack)
    )

    fun fingerprint(stack: ItemStack): String {
        val stableNbt = stack.nbt?.copy()?.also {
            it.remove("Damage")
            it.remove("RepairCost")
        }?.toString().orEmpty()
        return Integer.toHexString("v2|${itemId(stack)}|${displayName(stack)}|${armorType(stack)}|$stableNbt".hashCode())
    }

    fun sameItem(first: ItemStack?, second: ItemStack): Boolean =
        first != null && !first.isEmpty && fingerprint(first) == fingerprint(second)

    fun iconStack(itemId: String): ItemStack? {
        val identifier = Identifier.tryParse(itemId) ?: return null
        val item = Registries.ITEM.getOrEmpty(identifier).orElse(null) ?: return null
        return ItemStack(item)
    }

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
}
