package net.eenda.rvladdons.client.item

import net.minecraft.item.ItemStack

/** Shared adapter contract; each Minecraft target supplies its item-data access. */
abstract class FabricItemAdapterBase {
    abstract fun itemId(stack: ItemStack): String

    abstract fun displayName(stack: ItemStack): String

    abstract fun armorType(stack: ItemStack): String

    abstract fun fingerprint(stack: ItemStack): String

    fun snapshot(stack: ItemStack): FabricItemSnapshot = FabricItemSnapshot(
        itemId(stack), displayName(stack), armorType(stack), fingerprint(stack)
    )

    fun sameItem(first: ItemStack?, second: ItemStack): Boolean =
        first != null && !first.isEmpty && fingerprint(first) == fingerprint(second)

    abstract fun iconStack(itemId: String): ItemStack?

    abstract fun isWeapon(stack: ItemStack): Boolean

    abstract fun isArmor(stack: ItemStack): Boolean

    abstract fun loreLines(stack: ItemStack): List<String>
}
