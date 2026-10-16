package net.eenda.rvladdons.client.item

import net.eenda.rvladdons.core.RvlTextMatcher
import net.minecraft.component.DataComponentTypes
import net.minecraft.item.ArmorItem
import net.minecraft.item.AxeItem
import net.minecraft.item.BowItem
import net.minecraft.item.CrossbowItem
import net.minecraft.item.ItemStack
import net.minecraft.item.SwordItem
import net.minecraft.item.TridentItem
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier

/** Minecraft 1.21.1 item data access shared by client features. */
object FabricItemAdapter : FabricItemAdapterBase() {
    override fun itemId(stack: ItemStack): String = Registries.ITEM.getId(stack.item).toString()

    override fun displayName(stack: ItemStack): String = RvlTextMatcher.normalize(stack.name.string)

    override fun armorType(stack: ItemStack): String = (stack.item as? ArmorItem)?.type?.toString().orEmpty()

    override fun fingerprint(stack: ItemStack): String {
        val dynamicComponents = setOf("minecraft:damage", "minecraft:repair_cost")
        val stableComponents = stack.components.types
            .mapNotNull { type ->
                val componentId = Registries.DATA_COMPONENT_TYPE.getId(type).toString()
                if (componentId in dynamicComponents) null
                else "$componentId=${stack.components.get(type)}"
            }
            .sorted()
            .joinToString("|")
        return Integer.toHexString(
            "v2|${itemId(stack)}|${displayName(stack)}|${armorType(stack)}|$stableComponents".hashCode()
        )
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

    override fun loreLines(stack: ItemStack): List<String> =
        stack.get(DataComponentTypes.LORE)?.lines?.map { it.string }.orEmpty()
}
