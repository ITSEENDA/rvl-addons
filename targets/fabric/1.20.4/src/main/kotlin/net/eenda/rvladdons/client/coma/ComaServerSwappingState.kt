package net.eenda.rvladdons.client.coma

import net.eenda.rvladdons.RvlAddonsClient
import net.eenda.rvladdons.core.RvlAddonsTrace
import net.eenda.rvladdons.feature.coma.ComaClickStep
import net.eenda.rvladdons.feature.coma.ComaRole
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.HandledScreen

internal class ComaServerSwappingState(owner: ComaSwapController) : ComaSwapState(owner) {
    internal var readyForVerification = false
    private var nextStepAt = 0L
    private var stepStartedAt = 0L
    private var lastStepRole = "step"
    private var serverStep: ComaClickStep? = null
    private var waitingForServerSync = false

    override fun onEnter() {
        super.onEnter()
        readyForVerification = false
        nextStepAt = 0L
        stepStartedAt = 0L
        lastStepRole = "step"
        serverStep = null
        waitingForServerSync = false
    }

    override fun onExit() {
        readyForVerification = false
        serverStep = null
        waitingForServerSync = false
        super.onExit()
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
            owner.fail(client, "COMA menu closed before verification")
            return
        }
        if (!owner.isComaScreen(screen)) {
            owner.fail(client, "different menu opened")
            return
        }
        if (handler.syncId != current.syncId) {
            owner.fail(client, "syncId changed")
            return
        }

        val now = System.currentTimeMillis()
        if (waitingForServerSync) {
            val sync = owner.serverSyncs[current.syncId]
            if (sync == null || sync.revision <= current.expectedServerRevision) {
                if (now - stepStartedAt > ComaSwapController.STEP_SYNC_TIMEOUT_MS) {
                    owner.fail(client, "server did not confirm $lastStepRole")
                }
                return
            }
            val step = serverStep
            if (step != null && !serverStepSettled(sync, current, step)) {
                if (now - stepStartedAt > ComaSwapController.STEP_SYNC_TIMEOUT_MS) {
                    owner.fail(client, "server state did not settle ${step.role}")
                }
                return
            }
            waitingForServerSync = false
            serverStep = null
        }
        if (now < nextStepAt) return

        val step = current.steps.pollFirst()
        if (step == null) {
            readyForVerification = true
            return
        }
        val player = client.player ?: run {
            owner.fail(client, "player unavailable")
            return
        }
        current.expectedServerRevision = owner.serverSyncs[current.syncId]?.revision ?: current.baselineServerRevision
        stepStartedAt = now
        lastStepRole = step.role
        serverStep = step
        waitingForServerSync = true
        client.interactionManager?.clickSlot(handler.syncId, step.slot, 0, step.action.toSlotActionType(), player)
        nextStepAt = now + ComaSwapController.CLICK_INTERVAL_MS
        RvlAddonsTrace.log(
            "coma",
            "click syncId=${handler.syncId} slot=${step.slot} action=${step.action} role=${step.role}"
        )
    }

    private fun serverStepSettled(
        sync: ComaServerSync,
        current: SwapRequest,
        step: ComaClickStep
    ): Boolean {
        val roleIndex = ComaRole.ordered.indexOfFirst { it.key == step.role.substringAfter('-') }
        val targetSlot = owner.comaSlots.getOrNull(roleIndex) ?: return false
        val stack = sync.contents.getOrNull(targetSlot) ?: return false
        return if (step.role.startsWith("remove-")) {
            !FabricComaItemAdapter.isComaEquipment(stack, roleIndex)
        } else {
            current.expectedRules.getOrNull(roleIndex)?.let { rule ->
                FabricComaItemAdapter.matches(stack, rule)
            } == true
        }
    }
}
