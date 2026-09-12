package net.eenda.rvladdons.client.coma

import net.eenda.rvladdons.RvlAddonsClient
import net.eenda.rvladdons.core.RvlAddonsTrace
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.HandledScreen

internal class ComaLocalClearingState(owner: ComaSwapController) : ComaSwapState(owner) {
    internal var readyForMove = false
    internal var waitForMove = false
    internal var readyForFinish = false
    private var nextStepAt = 0L
    private var clearClicks = 0

    override fun onEnter() {
        super.onEnter()
        readyForMove = false
        waitForMove = false
        readyForFinish = false
        clearClicks = 0
        nextStepAt = System.currentTimeMillis() + owner.localOpenDelayMs()
    }

    override fun onUpdate() {
        val current = owner.swapOperation ?: return
        val client = MinecraftClient.getInstance()
        if (!RvlAddonsClient.isGameplayServerActive()) {
            owner.fail(client, "inactive")
            return
        }
        val screen = client.currentScreen as? HandledScreen<*>
        val handler = screen?.let(owner::screenHandler)
        if (screen == null || handler == null) {
            owner.fail(client, "COMA menu closed before local swap")
            return
        }
        if (!owner.isComaScreen(screen)) {
            owner.fail(client, "different menu opened")
            return
        }
        val now = System.currentTimeMillis()
        if (now < nextStepAt) return
        if (current.steps.isEmpty()) {
            owner.localFinishingState.begin(immediate = false)
            readyForFinish = true
            return
        }
        val player = client.player ?: run {
            owner.fail(client, "player unavailable")
            return
        }
        val interactionManager = client.interactionManager ?: run {
            owner.fail(client, "interaction manager unavailable")
            return
        }

        while (current.steps.peekFirst()?.role?.startsWith("remove-") == true) {
            val step = current.steps.pollFirst()
            interactionManager.clickSlot(handler.syncId, step.slot, 0, step.action.toSlotActionType(), player)
            clearClicks++
            RvlAddonsTrace.log(
                "coma",
                "local-clear-click syncId=${handler.syncId} slot=${step.slot} action=${step.action} role=${step.role}"
            )
            val interval = owner.localClearClickIntervalMs()
            if (interval > 0L && current.steps.peekFirst()?.role?.startsWith("remove-") == true) {
                nextStepAt = now + interval
                return
            }
        }

        if (current.steps.peekFirst()?.role?.startsWith("move-") == true) {
            val delay = owner.localClearToMoveDelayMs()
            if (clearClicks > 0 && delay > 0L) {
                waitForMove = true
                owner.localWaitingForMoveState.begin(now + delay)
                RvlAddonsTrace.log(
                    "coma",
                    "local-clear-burst-submitted syncId=${handler.syncId} clicks=$clearClicks"
                )
            } else {
                waitForMove = false
                if (clearClicks > 0) {
                    RvlAddonsTrace.log(
                        "coma",
                        "local-clear-burst-submitted syncId=${handler.syncId} clicks=$clearClicks"
                    )
                }
            }
            readyForMove = true
        } else if (current.steps.isEmpty()) {
            owner.localFinishingState.begin(immediate = false)
            readyForFinish = true
        }
    }
}
