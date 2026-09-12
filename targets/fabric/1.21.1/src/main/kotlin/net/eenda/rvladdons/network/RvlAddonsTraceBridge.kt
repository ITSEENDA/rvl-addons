package net.eenda.rvladdons.network

import net.eenda.rvladdons.RvlAddonsClient
import net.eenda.rvladdons.client.coma.ComaSwapController
import net.eenda.rvladdons.core.RvlAddonsTrace
import net.eenda.rvladdons.core.GameplayServerState
import net.eenda.rvladdons.core.RvlServerDetector
import net.minecraft.client.MinecraftClient
import net.minecraft.item.ArmorItem
import net.minecraft.item.ItemStack
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket
import net.minecraft.registry.Registries
import net.minecraft.screen.slot.Slot
import net.minecraft.screen.slot.SlotActionType

object RvlAddonsTraceBridge {
    @JvmStatic
    fun inspectIncomingPacket(packet: Any?) {
        logContainerSync(packet)

        if (packet !is GameJoinS2CPacket) return

        val isRvl = RvlServerDetector.isRvlServer(packet.toString())
        val previous = GameplayServerState.isActive()
        RvlAddonsClient.updateRvlServerState(isRvl)
        if (previous != isRvl) {
            RvlAddonsTrace.log(
                "server-detect",
                "rvl=$isRvl signal=${RvlAddonsTrace.describe(packet)}"
            )
        }
    }

    @JvmStatic
    fun logSlotClick(title: String, slot: Slot?, slotId: Int, button: Int, actionType: SlotActionType) {
        ComaSwapController.observeCaptureClick(title, slotId, actionType, slot?.stack)
        if (!RvlAddonsTrace.isEnabled()) return
        val stack = compactStack(slot?.stack)
        RvlAddonsTrace.log(
            "gui-click",
            "title=$title slotId=$slotId button=$button action=$actionType item=$stack"
        )
    }

    private fun logContainerSync(packet: Any?) {
        when (packet) {
            is InventoryS2CPacket -> {
                val syncId = packet.syncId
                val revision = packet.revision
                val syncContents = packet.contents.map(ItemStack::copy)
                MinecraftClient.getInstance().execute {
                    ComaSwapController.observeInventorySync(syncId, revision, syncContents)
                }
                if (!RvlAddonsTrace.isEnabled()) return
                val formattedContents = packet.contents.mapIndexed { index, stack ->
                    "$index=${compactStack(stack)}"
                }.joinToString(",")
                RvlAddonsTrace.log(
                    "container-sync",
                    "type=inventory syncId=${packet.syncId} revision=${packet.revision} " +
                        "cursor=${compactStack(packet.cursorStack)} contents=[$formattedContents]"
                )
            }

            is ScreenHandlerSlotUpdateS2CPacket -> {
                val syncId = packet.syncId
                val revision = packet.revision
                val slot = packet.slot
                val stack = packet.stack.copy()
                MinecraftClient.getInstance().execute {
                    ComaSwapController.observeSlotSync(syncId, revision, slot, stack)
                }
                if (!RvlAddonsTrace.isEnabled()) return
                RvlAddonsTrace.log(
                    "container-slot",
                    "syncId=${packet.syncId} revision=${packet.revision} " +
                        "slot=${packet.slot} item=${compactStack(packet.stack)}"
                )
            }
        }
    }

    private fun compactStack(stack: ItemStack?): String {
        if (stack == null || stack.isEmpty) return "empty"

        val id = Registries.ITEM.getId(stack.item)
        val name = stack.name.string
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('"', '\'')
            .take(80)
        val armor = (stack.item as? ArmorItem)?.let {
            val material = it.material.key
                .map { key -> key.value.toString() }
                .orElse("unknown")
            " armorType=${it.type} material=$material " +
                "protection=${it.protection} toughness=${it.toughness}"
        } ?: ""
        val durability = if (stack.isDamageable) {
            " damage=${stack.damage}/${stack.maxDamage}"
        } else {
            ""
        }
        val componentTypes = stack.components.types
            .map { Registries.DATA_COMPONENT_TYPE.getId(it).toString() }
            .sorted()
            .joinToString("|")
        val fingerprint = Integer.toHexString(
            "$id|$name|${stack.count}|${stack.components}".hashCode()
        )
        val components = if (componentTypes.isEmpty()) "" else " components=$componentTypes"
        return "id=$id count=${stack.count} name=\"$name\"$armor$durability" +
            "$components fingerprint=$fingerprint"
    }
}
