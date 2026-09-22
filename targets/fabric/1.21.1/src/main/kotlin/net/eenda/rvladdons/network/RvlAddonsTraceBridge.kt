package net.eenda.rvladdons.network

import net.eenda.rvladdons.RvlAddonsClient
import net.eenda.rvladdons.client.coma.ComaSwapController
import net.eenda.rvladdons.client.cooldown.CooldownController
import net.eenda.rvladdons.core.RvlAddonsTrace
import net.eenda.rvladdons.core.GameplayServerState
import net.eenda.rvladdons.core.RvlServerDetector
import net.minecraft.client.MinecraftClient
import net.minecraft.item.ArmorItem
import net.minecraft.item.ItemStack
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket
import net.minecraft.network.packet.s2c.play.EntityStatusEffectS2CPacket
import net.minecraft.network.packet.s2c.play.EntityAttributesS2CPacket
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket
import net.minecraft.registry.Registries
import net.minecraft.screen.slot.Slot
import net.minecraft.screen.slot.SlotActionType

object RvlAddonsTraceBridge {
    @JvmStatic
    fun inspectIncomingPacket(packet: Any?) {
        logContainerSync(packet)
        logTypedIncomingPacket(packet)
        if (packet is GameMessageS2CPacket) {
            val message = packet.content.string
            MinecraftClient.getInstance().execute {
                CooldownController.observeServerMessage(message, packet.overlay)
            }
        }

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
    fun inspectOutgoingPacket(packet: Any?) {
        when (packet) {
            is HandSwingC2SPacket -> {
                CooldownController.observeAction(
                    net.eenda.rvladdons.feature.cooldown.SkillTrigger.LEFT_CLICK,
                    packet.hand
                )
                RvlAddonsTrace.log(
                    "skill-action",
                    "type=hand-swing hand=${packet.hand} item=${heldItem(packet.hand)}"
                )
            }
            is PlayerInteractItemC2SPacket -> {
                CooldownController.observeAction(
                    net.eenda.rvladdons.feature.cooldown.SkillTrigger.RIGHT_CLICK,
                    packet.hand
                )
                RvlAddonsTrace.log(
                    "skill-action",
                    "type=item-use hand=${packet.hand} sequence=${packet.sequence} item=${heldItem(packet.hand)}"
                )
            }
            is PlayerInteractBlockC2SPacket -> CooldownController.observeAction(
                net.eenda.rvladdons.feature.cooldown.SkillTrigger.RIGHT_CLICK,
                packet.hand
            )
            is PlayerInteractEntityC2SPacket -> {
                CooldownController.observeAction(
                    net.eenda.rvladdons.feature.cooldown.SkillTrigger.RIGHT_CLICK,
                    net.minecraft.util.Hand.MAIN_HAND
                )
            }
            is PlayerActionC2SPacket -> RvlAddonsTrace.log(
                "skill-action",
                "type=player-action action=${packet.action} sequence=${packet.sequence} pos=${packet.pos}"
            )
        }
    }


    private fun logTypedIncomingPacket(packet: Any?) {
        when (packet) {
            is GameMessageS2CPacket -> {
                val text = packet.content.string
                val cd = Regex("(?i)\\bCD\\b\\s*[:：]?\\s*(\\d+(?:[.,]\\d+)?)\\s*s?")
                    .find(text)?.groupValues?.getOrNull(1)
                RvlAddonsTrace.log(
                    "skill-signal",
                    "type=game-message overlay=${packet.overlay} cd=${cd ?: "none"} text=$text"
                )
            }
            is ParticleS2CPacket -> RvlAddonsTrace.log(
                "skill-signal",
                "type=particle effect=${packet.parameters} x=${packet.x} y=${packet.y} z=${packet.z} " +
                    "count=${packet.count} speed=${packet.speed}"
            )
            is EntityStatusEffectS2CPacket -> RvlAddonsTrace.log(
                "skill-signal",
                "type=status-effect entity=${packet.entityId} effect=${packet.effectId} " +
                    "amplifier=${packet.amplifier} duration=${packet.duration}"
            )
            is EntityAttributesS2CPacket -> RvlAddonsTrace.log(
                "skill-signal",
                "type=attributes entity=${packet.entityId} entries=${packet.entries}"
            )
            is PlaySoundS2CPacket -> RvlAddonsTrace.log(
                "skill-signal",
                "type=sound sound=${packet.sound} category=${packet.category} " +
                    "x=${packet.x} y=${packet.y} z=${packet.z} volume=${packet.volume} pitch=${packet.pitch}"
            )
        }
    }

    private fun heldItem(hand: net.minecraft.util.Hand): String {
        val player = MinecraftClient.getInstance().player ?: return "empty"
        return compactStack(if (hand == net.minecraft.util.Hand.OFF_HAND) player.offHandStack else player.mainHandStack)
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
