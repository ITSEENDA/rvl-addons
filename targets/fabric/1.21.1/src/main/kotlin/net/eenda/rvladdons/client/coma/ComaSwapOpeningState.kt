package net.eenda.rvladdons.client.coma

import net.eenda.rvladdons.RvlAddonsClient
import net.eenda.rvladdons.core.RvlAddonsConfigStore
import net.eenda.rvladdons.core.RvlAddonsTrace
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.HandledScreen

internal class ComaSwapOpeningState(owner: ComaSwapController) : ComaSwapState(owner) {
    internal var menuReady = false

    override fun onEnter() {
        super.onEnter()
        menuReady = false
        val current = owner.swapOperation ?: return
        val client = MinecraftClient.getInstance()
        client.player?.networkHandler?.sendChatCommand("tuicoma")
        val set = RvlAddonsConfigStore.config.comaSets.getOrNull(current.setIndex)
        owner.notify(client, "RVL COMA: opening ${set?.let(owner::displayName) ?: "Set ${current.setIndex + 1}"}")
        RvlAddonsTrace.log("coma", "request setIndex=${current.setIndex} name=${set?.let(owner::displayName)}")
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
            if (System.currentTimeMillis() - current.startedAt > ComaSwapController.OPEN_TIMEOUT_MS) {
                owner.fail(client, "COMA menu did not open")
            }
            return
        }
        if (!owner.isComaScreen(screen)) {
            if (System.currentTimeMillis() - current.startedAt > ComaSwapController.OPEN_TIMEOUT_MS) {
                owner.fail(client, "COMA menu title not detected")
            }
            return
        }
        menuReady = true
    }
}
