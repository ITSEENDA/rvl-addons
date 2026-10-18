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
object FabricItemAdapter : FabricItemAdapterBase() {
    override fun itemId(stack: ItemStack): String = Registries.ITEM.getId(stack.item).toString()

    override fun displayName(stack: ItemStack): String = RvlTextMatcher.normalize(stack.name.string)

    override fun armorType(stack: ItemStack): String = (stack.item as? ArmorItem)?.type?.toString().orEmpty()

    override fun fingerprint(stack: ItemStack): String {
        val stableNbt = stack.nbt?.copy()?.also {
            it.remove("Damage")
            it.remove("RepairCost")
        }?.toString().orEmpty()
        return Integer.toHexString("v2|${itemId(stack)}|${displayName(stack)}|${armorType(stack)}|$stableNbt".hashCode())
    }

    override fun iconStack(itemId: String): ItemStack? {
        val identifier = Identifier.tryParse(itemId) ?: return null
        val item = Registries.ITEM.getOrEmpty(identifier).orElse(null) ?: return null
        return ItemStack(item)
    }

    override fun isWeapon(stack: ItemStack): Boolean =
        stack.item is SwordItem || stack.item is AxeItem || stack.item is BowItem ||
            stack.item is CrossbowItem || stack.item is TridentItem

    override fun isArmor(stack: ItemStack): Boolean = stack.item is ArmorItem

    override fun loreLines(stack: ItemStack): List<String> {
        val display = stack.nbt?.getCompound("display") ?: return emptyList()
        val lore = display.getList("Lore", NbtElement.STRING_TYPE.toInt())
        return lore.indices.mapNotNull { index ->
            val raw = lore.getString(index)
            runCatching { Text.Serialization.fromJson(raw)?.string ?: raw }.getOrNull()
        }
    }
}
