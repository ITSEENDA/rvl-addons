package net.eenda.rvladdons.client.coma

import net.eenda.rvladdons.RvlAddonsClient
import net.eenda.rvladdons.core.RvlAddonsTrace
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.HandledScreen

internal class ComaLocalMovingState(owner: ComaSwapController) : ComaSwapState(owner) {
    internal var readyForFinish = false
    private var nextStepAt = 0L
    private var moveClicks = 0

    override fun onEnter() {
        super.onEnter()
        readyForFinish = false
        moveClicks = 0
        nextStepAt = System.currentTimeMillis()
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
            owner.fail(client, "COMA menu closed before local move")
            return
        }
        if (!owner.isComaScreen(screen)) {
            owner.fail(client, "different menu opened")
            return
        }
        val now = System.currentTimeMillis()
        if (now < nextStepAt) return
        val player = client.player ?: run {
            owner.fail(client, "player unavailable")
            return
        }
        val interactionManager = client.interactionManager ?: run {
            owner.fail(client, "interaction manager unavailable")
            return
        }
        if (current.steps.isEmpty()) {
            owner.localFinishingState.begin(immediate = false)
            readyForFinish = true
            return
        }

        while (current.steps.isNotEmpty()) {
            val step = current.steps.pollFirst()
            interactionManager.clickSlot(handler.syncId, step.slot, 0, step.action.toSlotActionType(), player)
            moveClicks++
            RvlAddonsTrace.log(
                "coma",
                "local-move-click syncId=${handler.syncId} slot=${step.slot} action=${step.action} role=${step.role}"
            )
            val interval = owner.localMoveClickIntervalMs()
            if (interval > 0L && current.steps.isNotEmpty()) {
                nextStepAt = now + interval
                return
            }
        }

        owner.localFinishingState.begin(immediate = false)
        readyForFinish = true
    }
}
