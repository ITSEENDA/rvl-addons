package net.eenda.rvladdons.client.coma

import net.eenda.rvladdons.core.RvlAddonsConfigStore
import net.eenda.rvladdons.core.RvlAddonsTrace
import net.minecraft.client.MinecraftClient

internal class ComaLocalFinishingState(owner: ComaSwapController) : ComaSwapState(owner) {
    internal var completed = false
    private var finishAt = 0L
    private var finishImmediately = false

    internal fun begin(immediate: Boolean) {
        finishImmediately = immediate
    }

    override fun onEnter() {
        super.onEnter()
        completed = false
        val immediate = finishImmediately
        finishImmediately = false
        finishAt = System.currentTimeMillis() + if (immediate) 0L else owner.localCloseDelayMs()
    }

    override fun onExit() {
        if (completed) {
            owner.swapOperation?.let { complete(MinecraftClient.getInstance(), it) }
        }
        finishAt = 0L
        completed = false
        super.onExit()
    }

    override fun onUpdate() {
        if (owner.swapOperation == null) return
        if (System.currentTimeMillis() < finishAt) return
        completed = true
    }

    private fun complete(client: MinecraftClient, current: SwapRequest) {
        owner.pendingHudSetIndex = current.setIndex
        owner.pendingHudSyncId = current.syncId
        owner.pendingHudRules = current.expectedRules.toList()
        owner.serverSyncs[current.syncId]?.contents?.let {
            owner.tryConfirmPendingHud(current.syncId, it)
        }

        val set = RvlAddonsConfigStore.config.comaSets.getOrNull(current.setIndex)
        val name = set?.let(owner::displayName) ?: "Set ${current.setIndex + 1}"
        owner.notify(client, "RVL COMA: swap sent $name")
        RvlAddonsTrace.log("coma", "local-swap-submitted syncId=${current.syncId} setIndex=${current.setIndex}")
        client.player?.closeHandledScreen()
        client.setScreen(null)
        owner.nextSwapAllowedAt = System.currentTimeMillis() + ComaSwapController.SWAP_REARM_MS
    }
}
