package net.eenda.rvladdons.client.coma

import net.eenda.rvladdons.feature.coma.ComaInventorySnapshot
import net.minecraft.item.ItemStack
import java.util.concurrent.ConcurrentHashMap

data class ComaServerSync(
    val syncId: Int,
    val revision: Int,
    val contents: List<ItemStack>,
    val snapshot: ComaInventorySnapshot = ComaInventorySnapshot(
        syncId,
        revision,
        contents.map { stack -> stack.takeUnless(ItemStack::isEmpty)?.let(FabricComaItemAdapter::snapshot) }
    )
)

/** Owns revision filtering and merging for server container updates. */
class ComaInventorySyncCache {
    private val snapshots = ConcurrentHashMap<Int, ComaServerSync>()

    operator fun get(syncId: Int): ComaServerSync? = snapshots[syncId]

    fun put(snapshot: ComaServerSync) {
        snapshots[snapshot.syncId] = snapshot
    }

    fun clear() {
        snapshots.clear()
    }

    fun observeInventory(syncId: Int, revision: Int, contents: List<ItemStack>): ComaServerSync? {
        val previous = snapshots[syncId]
        if (previous != null && revision <= previous.revision) return null

        return ComaServerSync(syncId, revision, contents.map(ItemStack::copy)).also { snapshots[syncId] = it }
    }

    fun observeSlot(syncId: Int, revision: Int, slot: Int, stack: ItemStack): ComaServerSync? {
        val previous = snapshots[syncId] ?: return null
        if (revision <= previous.revision || slot !in previous.contents.indices) return null

        val contents = previous.contents.map(ItemStack::copy).toMutableList()
        contents[slot] = stack.copy()
        return ComaServerSync(syncId, maxOf(previous.revision, revision), contents)
            .also { snapshots[syncId] = it }
    }
}
