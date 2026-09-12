package net.eenda.rvladdons.client.coma

import net.eenda.rvladdons.RvlAddonsClient
import net.eenda.rvladdons.core.RvlAddonsConfigStore
import net.eenda.rvladdons.core.RvlAddonsTrace
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.HandledScreen

internal class ComaSwapVerifyingState(owner: ComaSwapController) : ComaSwapState(owner) {
    internal var verified = false
    private var verifyAt = 0L
    private var verifyDeadline = 0L

    override fun onEnter() {
        super.onEnter()
        verified = false
        val now = System.currentTimeMillis()
        verifyAt = now + ComaSwapController.VERIFY_DELAY_MS
        verifyDeadline = now + ComaSwapController.VERIFY_TIMEOUT_MS
    }

    override fun onExit() {
        verified = false
        verifyAt = 0L
        verifyDeadline = 0L
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
        val now = System.currentTimeMillis()
        if (now < verifyAt) return

        val serverSync = owner.serverSyncs[current.syncId]
        val hasFreshServerSync = !current.requiresServerSync ||
            (serverSync != null && serverSync.revision > current.expectedServerRevision)
        if (!hasFreshServerSync) {
            if (now < verifyDeadline) {
                verifyAt = now + 50L
            } else {
                owner.fail(client, "server did not confirm container update")
            }
            return
        }

        val failed = current.expectedRules.mapIndexedNotNull { index, rule ->
            if (rule.isBlank()) return@mapIndexedNotNull null
            val serverStack = serverSync?.contents?.getOrNull(owner.comaSlots[index])
            val stack = serverStack ?: handler.getSlot(owner.comaSlots[index]).stack
            if (FabricComaItemAdapter.matches(stack, rule)) null else owner.roleName(index)
        }
        if (failed.isNotEmpty()) {
            owner.fail(client, "server state mismatch=${failed.joinToString(",")}")
            return
        }

        owner.currentComaStacks = current.expectedRules.mapIndexed { index, rule ->
            if (rule.isBlank()) null
            else (serverSync?.contents?.getOrNull(owner.comaSlots[index])
                ?: handler.getSlot(owner.comaSlots[index]).stack).copy()
        }
        owner.clearPendingHud()
        val set = RvlAddonsConfigStore.config.comaSets.getOrNull(current.setIndex)
        owner.notify(client, "RVL COMA: equipped ${set?.let(owner::displayName) ?: "Set ${current.setIndex + 1}"}")
        RvlAddonsTrace.log("coma", "verified syncId=${handler.syncId} setIndex=${current.setIndex}")
        client.player?.closeHandledScreen()
        client.setScreen(null)
        owner.nextSwapAllowedAt = now + ComaSwapController.SWAP_REARM_MS
        verified = true
    }
}
