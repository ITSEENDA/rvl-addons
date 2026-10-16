package net.eenda.rvladdons.network

import net.eenda.rvladdons.core.RvlAddonsTrace
import net.eenda.rvladdons.feature.cooldown.SkillTrigger
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
import net.eenda.rvladdons.network.RvlTraceEvent.*

object RvlAddonsTraceBridge {
    @JvmStatic
    fun inspectIncomingPacket(packet: Any?) {
        logContainerSync(packet)
        logTypedIncomingPacket(packet)
        if (packet is GameJoinS2CPacket) {
            RvlTraceEventDispatcher.dispatch(ServerJoin(packet.toString()) { RvlAddonsTrace.describe(packet) })
        }
    }

    @JvmStatic
    fun inspectOutgoingPacket(packet: Any?) {
        when (packet) {
            is HandSwingC2SPacket -> {
                RvlTraceEventDispatcher.dispatch(
                    SkillAction(
                        SkillTrigger.LEFT_CLICK,
                        packet.hand,
                        "type=hand-swing hand=${packet.hand} item=${heldItem(packet.hand)}"
                    )
                )
            }
            is PlayerInteractItemC2SPacket -> {
                RvlTraceEventDispatcher.dispatch(
                    SkillAction(
                        SkillTrigger.RIGHT_CLICK,
                        packet.hand,
                        "type=item-use hand=${packet.hand} sequence=${packet.sequence} item=${heldItem(packet.hand)}"
                    )
                )
            }
            is PlayerInteractBlockC2SPacket -> RvlTraceEventDispatcher.dispatch(
                SkillAction(SkillTrigger.RIGHT_CLICK, packet.hand)
            )
            is PlayerInteractEntityC2SPacket -> {
                RvlTraceEventDispatcher.dispatch(
                    SkillAction(SkillTrigger.RIGHT_CLICK, net.minecraft.util.Hand.MAIN_HAND)
                )
            }
            is PlayerActionC2SPacket -> RvlTraceEventDispatcher.dispatch(
                TraceLine(
                    "skill-action",
                    "type=player-action action=${packet.action} sequence=${packet.sequence} pos=${packet.pos}"
                )
            )
        }
    }


    private fun logTypedIncomingPacket(packet: Any?) {
        when (packet) {
            is GameMessageS2CPacket -> {
                RvlTraceEventDispatcher.dispatch(ServerMessage(packet.content.string, packet.overlay))
            }
            is ParticleS2CPacket -> RvlTraceEventDispatcher.dispatch(
                TraceLine(
                    "skill-signal",
                    "type=particle effect=${packet.parameters} x=${packet.x} y=${packet.y} z=${packet.z} " +
                        "count=${packet.count} speed=${packet.speed}"
                )
            )
            is EntityStatusEffectS2CPacket -> RvlTraceEventDispatcher.dispatch(
                TraceLine(
                    "skill-signal",
                    "type=status-effect entity=${packet.entityId} effect=${packet.effectId} " +
                        "amplifier=${packet.amplifier} duration=${packet.duration}"
                )
            )
            is EntityAttributesS2CPacket -> RvlTraceEventDispatcher.dispatch(
                TraceLine("skill-signal", "type=attributes entity=${packet.entityId} entries=${packet.entries}")
            )
            is PlaySoundS2CPacket -> RvlTraceEventDispatcher.dispatch(
                TraceLine(
                    "skill-signal",
                    "type=sound sound=${packet.sound} category=${packet.category} " +
                        "x=${packet.x} y=${packet.y} z=${packet.z} volume=${packet.volume} pitch=${packet.pitch}"
                )
            )
        }
    }

    private fun heldItem(hand: net.minecraft.util.Hand): String {
        val player = MinecraftClient.getInstance().player ?: return "empty"
        return compactStack(if (hand == net.minecraft.util.Hand.OFF_HAND) player.offHandStack else player.mainHandStack)
    }

    @JvmStatic
    fun logSlotClick(title: String, slot: Slot?, slotId: Int, button: Int, actionType: SlotActionType) {
        RvlTraceEventDispatcher.dispatch(
            SlotClick(
                title,
                slotId,
                button,
                actionType,
                slot?.stack,
                if (RvlAddonsTrace.isEnabled()) compactStack(slot?.stack) else null
            )
        )
    }

    private fun logContainerSync(packet: Any?) {
        when (packet) {
            is InventoryS2CPacket -> {
                val traceMessage = if (RvlAddonsTrace.isEnabled()) {
                    val formattedContents = packet.contents.mapIndexed { index, stack ->
                        "$index=${compactStack(stack)}"
                    }.joinToString(",")
                    "type=inventory syncId=${packet.syncId} revision=${packet.revision} " +
                        "cursor=${compactStack(packet.cursorStack)} contents=[$formattedContents]"
                } else {
                    null
                }
                RvlTraceEventDispatcher.dispatch(
                    InventorySync(packet.syncId, packet.revision, packet.contents, traceMessage)
                )
            }

            is ScreenHandlerSlotUpdateS2CPacket -> {
                val traceMessage = if (RvlAddonsTrace.isEnabled()) {
                    "syncId=${packet.syncId} revision=${packet.revision} " +
                        "slot=${packet.slot} item=${compactStack(packet.stack)}"
                } else {
                    null
                }
                RvlTraceEventDispatcher.dispatch(
                    SlotSync(packet.syncId, packet.revision, packet.slot, packet.stack, traceMessage)
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
            " armorType=${it.type} material=${it.material.name} " +
                "protection=${it.protection} toughness=${it.toughness}"
        } ?: ""
        val durability = if (stack.isDamageable) {
            " damage=${stack.damage}/${stack.maxDamage}"
        } else {
            ""
        }
        val nbtKeys = if (stack.hasNbt()) {
            stack.nbt?.keys?.sorted()?.joinToString("|")?.let { " nbtKeys=$it" } ?: ""
        } else {
            ""
        }
        val fingerprint = Integer.toHexString(
            "$id|$name|${stack.count}|${stack.nbt ?: ""}".hashCode()
        )
        return "id=$id count=${stack.count} name=\"$name\"$armor$durability" +
            "$nbtKeys fingerprint=$fingerprint"
    }
}
