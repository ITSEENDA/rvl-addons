package net.eenda.rvladdons.client.coma

import net.eenda.rvladdons.RvlAddonsClient
import net.eenda.rvladdons.core.RvlAddonsConfigStore
import net.eenda.rvladdons.core.RvlAddonsTrace
import net.eenda.rvladdons.feature.coma.ComaSwapPlanner
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.HandledScreen

internal class ComaSwapPlanningState(owner: ComaSwapController) : ComaSwapState(owner) {
    internal var planReady = false
    private var planDeadline = 0L
    private var lastPlanMissing = ""

    override fun onEnter() {
        super.onEnter()
        planReady = false
        planDeadline = System.currentTimeMillis() + ComaSwapController.PLAN_READY_TIMEOUT_MS
        lastPlanMissing = ""
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
            owner.fail(client, "COMA menu closed before swap")
            return
        }
        if (!owner.isComaScreen(screen)) {
            owner.fail(client, "different menu opened")
            return
        }
        val set = RvlAddonsConfigStore.config.comaSets.getOrNull(current.setIndex)
        if (set == null) {
            owner.fail(client, "set no longer exists")
            return
        }
        val serverSnapshot = owner.serverSyncs[handler.syncId]?.snapshot
        if (current.verifyWithServer && serverSnapshot == null) {
            if (System.currentTimeMillis() >= planDeadline) {
                owner.fail(client, "server container snapshot unavailable")
            }
            return
        }

        val plan = ComaSwapPlanner.build(
            owner.handlerSnapshot(handler),
            set,
            if (current.verifyWithServer) serverSnapshot else null
        )
        if (plan.missing.isNotEmpty()) {
            val missing = plan.missing.joinToString(",")
            if (missing != lastPlanMissing) {
                lastPlanMissing = missing
                RvlAddonsTrace.log("coma", "plan-wait missing=$missing")
            }
            if (System.currentTimeMillis() < planDeadline) return
            RvlAddonsTrace.log("coma", "plan-timeout missing=$missing")
            owner.fail(client, "missing=$missing")
            return
        }

        current.steps.addAll(plan.steps)
        current.expectedRules = set.rules()
        current.syncId = handler.syncId
        current.baselineServerRevision = owner.serverSyncs[handler.syncId]?.revision ?: -1
        current.requiresServerSync = current.verifyWithServer && current.steps.isNotEmpty()
        RvlAddonsTrace.log("coma", "plan-ready steps=${current.steps.size}")
        planReady = true
        if (current.steps.isEmpty() && !current.verifyWithServer) {
            owner.localFinishingState.begin(immediate = true)
        }
    }

    internal fun canEnterLocalClearing(): Boolean {
        val current = owner.swapOperation ?: return false
        return planReady && !current.verifyWithServer && current.steps.isNotEmpty()
    }

    internal fun canEnterServerSwapping(): Boolean {
        val current = owner.swapOperation ?: return false
        return planReady && current.verifyWithServer && current.steps.isNotEmpty()
    }

    internal fun canEnterVerifying(): Boolean {
        val current = owner.swapOperation ?: return false
        return planReady && current.verifyWithServer && current.steps.isEmpty()
    }

    internal fun canEnterLocalFinishing(): Boolean {
        val current = owner.swapOperation ?: return false
        return planReady && !current.verifyWithServer && current.steps.isEmpty()
    }
}
