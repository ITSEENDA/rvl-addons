package net.eenda.rvladdons.network

import net.eenda.rvladdons.RvlAddonsClient
import net.eenda.rvladdons.client.coma.ComaSwapController
import net.eenda.rvladdons.client.cooldown.CooldownController
import net.eenda.rvladdons.core.GameplayServerState
import net.eenda.rvladdons.core.RvlAddonsTrace
import net.eenda.rvladdons.core.RvlServerDetector
import net.eenda.rvladdons.feature.cooldown.SkillTrigger
import net.minecraft.client.MinecraftClient
import net.minecraft.item.ItemStack
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.util.Hand

/** Packet-independent observations emitted by each Minecraft-version target. */
sealed interface RvlTraceEvent {
    data class SkillAction(val trigger: SkillTrigger, val hand: Hand, val detail: String? = null) : RvlTraceEvent
    data class ServerMessage(val text: String, val actionBar: Boolean) : RvlTraceEvent
    data class ServerJoin(val detectionSignal: String, val traceSignal: () -> String) : RvlTraceEvent
    data class TraceLine(val category: String, val message: String) : RvlTraceEvent
    data class InventorySync(
        val syncId: Int,
        val revision: Int,
        val contents: List<ItemStack>,
        val traceMessage: String?
    ) : RvlTraceEvent
    data class SlotSync(
        val syncId: Int,
        val revision: Int,
        val slot: Int,
        val stack: ItemStack,
        val traceMessage: String?
    ) : RvlTraceEvent
    data class SlotClick(
        val title: String,
        val slotId: Int,
        val button: Int,
        val actionType: SlotActionType,
        val stack: ItemStack?,
        val traceItem: String?
    ) : RvlTraceEvent
}

/** Routes normalized observations to shared feature controllers and trace logging. */
object RvlTraceEventDispatcher {
    private val cooldownPattern = Regex("(?i)\\bCD\\b\\s*[:：]?\\s*(\\d+(?:[.,]\\d+)?)\\s*s?")

    fun dispatch(event: RvlTraceEvent) {
        when (event) {
            is RvlTraceEvent.SkillAction -> {
                CooldownController.observeAction(event.trigger, event.hand)
                event.detail?.let { RvlAddonsTrace.log("skill-action", it) }
            }
            is RvlTraceEvent.ServerMessage -> {
                MinecraftClient.getInstance().execute {
                    CooldownController.observeServerMessage(event.text, event.actionBar)
                }
                val cd = cooldownPattern.find(event.text)?.groupValues?.getOrNull(1)
                RvlAddonsTrace.log(
                    "skill-signal",
                    "type=game-message overlay=${event.actionBar} cd=${cd ?: "none"} text=${event.text}"
                )
            }
            is RvlTraceEvent.ServerJoin -> {
                val isRvl = RvlServerDetector.isRvlServer(event.detectionSignal)
                val previous = GameplayServerState.isActive()
                RvlAddonsClient.updateRvlServerState(isRvl)
                if (previous != isRvl) {
                    RvlAddonsTrace.log("server-detect", "rvl=$isRvl signal=${event.traceSignal()}")
                }
            }
            is RvlTraceEvent.TraceLine -> RvlAddonsTrace.log(event.category, event.message)
            is RvlTraceEvent.InventorySync -> {
                val contents = event.contents.map(ItemStack::copy)
                MinecraftClient.getInstance().execute {
                    ComaSwapController.observeInventorySync(event.syncId, event.revision, contents)
                }
                event.traceMessage?.let { RvlAddonsTrace.log("container-sync", it) }
            }
            is RvlTraceEvent.SlotSync -> {
                val stack = event.stack.copy()
                MinecraftClient.getInstance().execute {
                    ComaSwapController.observeSlotSync(event.syncId, event.revision, event.slot, stack)
                }
                event.traceMessage?.let { RvlAddonsTrace.log("container-slot", it) }
            }
            is RvlTraceEvent.SlotClick -> {
                ComaSwapController.observeCaptureClick(event.title, event.slotId, event.actionType, event.stack)
                event.traceItem?.let {
                    RvlAddonsTrace.log(
                        "gui-click",
                        "title=${event.title} slotId=${event.slotId} button=${event.button} " +
                            "action=${event.actionType} item=$it"
                    )
                }
            }
        }
    }
}
