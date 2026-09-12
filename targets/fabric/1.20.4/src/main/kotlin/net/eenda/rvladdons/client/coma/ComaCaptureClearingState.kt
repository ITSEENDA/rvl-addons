package net.eenda.rvladdons.client.coma

import net.eenda.rvladdons.RvlAddonsClient
import net.eenda.rvladdons.core.RvlAddonsTrace
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.item.ItemStack
import net.minecraft.screen.slot.SlotActionType

internal class ComaCaptureClearingState(owner: ComaSwapController) : ComaCaptureState(owner) {
    internal var clearFinished = false

    override fun onEnter() {
        super.onEnter()
        clearFinished = false
        val current = owner.captureOperation ?: return
        current.clearNextAttemptAt = System.currentTimeMillis() + ComaSwapController.CAPTURE_CLEAR_INITIAL_DELAY_MS
        owner.notify(MinecraftClient.getInstance(), "RVL COMA: clearing existing items")
        RvlAddonsTrace.log(
            "coma",
            "capture-menu-open syncId=${current.syncId} setIndex=${current.setIndex} clearing=${current.clearSlots.size}"
        )
    }

    override fun onUpdate() {
        val current = owner.captureOperation ?: return
        val client = MinecraftClient.getInstance()
        if (!RvlAddonsClient.isGameplayServerActive()) {
            owner.failCapture(client, "inactive")
            return
        }
        val screen = client.currentScreen as? HandledScreen<*>
        val handler = screen?.let(owner::screenHandler)
        if (screen == null || handler == null) {
            owner.failCapture(client, "menu closed while clearing")
            return
        }
        if (!owner.isComaScreen(screen)) {
            if (System.currentTimeMillis() - current.startedAt > ComaSwapController.OPEN_TIMEOUT_MS) {
                owner.failCapture(client, "COMA menu title not detected")
            }
            return
        }

        val now = System.currentTimeMillis()
        val sync = owner.serverSyncs[current.syncId]
        if (current.clearWaitingForServerSync) {
            if (sync == null || sync.revision <= current.clearExpectedRevision) {
                if (now - current.clearStepStartedAt > ComaSwapController.STEP_SYNC_TIMEOUT_MS) {
                    owner.failCapture(client, "server did not clear COMA slots")
                }
                return
            }

            val slot = current.clearSlots.firstOrNull()
            val serverStack = slot?.let { sync.contents.getOrNull(it) }
            if (slot != null && serverStack == null) {
                if (now - current.clearStepStartedAt > ComaSwapController.STEP_SYNC_TIMEOUT_MS) {
                    owner.failCapture(client, "server returned incomplete COMA state")
                }
                return
            }
            val roleIndex = slot?.let(owner.comaSlots::indexOf) ?: -1
            if (slot != null && serverStack != null &&
                FabricComaItemAdapter.isComaEquipment(serverStack, roleIndex)
            ) {
                if (now - current.clearStepStartedAt > ComaSwapController.STEP_SYNC_TIMEOUT_MS) {
                    owner.failCapture(client, "server did not clear ${current.clearRole}")
                    return
                }
                current.clearWaitingForServerSync = false
                current.clearBatchSent = false
                current.clearNextAttemptAt = now + ComaSwapController.CAPTURE_CLEAR_RETRY_DELAY_MS
                RvlAddonsTrace.log(
                    "coma",
                    "clear-retry syncId=${current.syncId} slot=$slot role=${current.clearRole}"
                )
                return
            }
            if (slot != null) current.clearSlots.removeFirst()
            current.clearAttempts = 0
            current.previousContents = sync.contents.map(ItemStack::copy)
            current.lastRevision = sync.revision
            current.clearWaitingForServerSync = false
            current.clearBatchSent = false
            current.clearNextAttemptAt = now + ComaSwapController.CAPTURE_CLEAR_NEXT_SLOT_DELAY_MS
        }

        while (current.clearSlots.isNotEmpty()) {
            val slot = current.clearSlots.first()
            val serverStack = owner.serverSyncs[current.syncId]?.contents?.getOrNull(slot)
            val roleIndex = owner.comaSlots.indexOf(slot)
            if (serverStack == null || FabricComaItemAdapter.isComaEquipment(serverStack, roleIndex)) break
            current.clearSlots.removeFirst()
            current.clearWaitingForServerSync = false
            current.clearBatchSent = false
            current.clearAttempts = 0
            RvlAddonsTrace.log("coma", "clear-skip-empty syncId=${current.syncId} slot=$slot")
        }

        if (current.clearSlots.isEmpty()) {
            clearFinished = true
            return
        }
        if (now < current.clearNextAttemptAt) return

        val player = client.player ?: run {
            owner.failCapture(client, "player unavailable while clearing")
            return
        }
        if (!current.clearBatchSent) {
            if (current.clearAttempts >= 6) {
                owner.failCapture(client, "clear retry limit reached for ${current.clearRole}")
                return
            }
            current.clearAttempts++
            current.clearExpectedRevision = owner.serverSyncs[current.syncId]?.revision ?: current.lastRevision
            current.clearStepStartedAt = now
            current.clearBatchSent = true
            val slot = current.clearSlots.first()
            val role = owner.roleName(owner.comaSlots.indexOf(slot).coerceAtLeast(0))
            current.clearRole = role
            client.interactionManager?.clickSlot(
                handler.syncId,
                slot,
                0,
                SlotActionType.QUICK_MOVE,
                player
            )
            RvlAddonsTrace.log("coma", "clear-click syncId=${handler.syncId} slot=$slot role=$role")
            current.clearWaitingForServerSync = true
        }
    }
}
