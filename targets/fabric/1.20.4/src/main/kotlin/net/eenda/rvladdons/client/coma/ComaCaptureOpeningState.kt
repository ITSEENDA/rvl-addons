package net.eenda.rvladdons.client.coma

import net.eenda.rvladdons.RvlAddonsClient
import net.eenda.rvladdons.core.RvlAddonsTrace
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.item.ItemStack

internal class ComaCaptureOpeningState(owner: ComaSwapController) : ComaCaptureState(owner) {
    internal var menuReady = false
    internal var needsClearing = false

    override fun onEnter() {
        super.onEnter()
        menuReady = false
        needsClearing = false
        val current = owner.captureOperation ?: return
        val client = MinecraftClient.getInstance()
        client.player?.networkHandler?.sendChatCommand("tuicoma")
        owner.notify(client, "RVL COMA: capture mode - shift-click or pick up and place items")
        RvlAddonsTrace.log("coma", "capture-request setIndex=${current.setIndex}")
    }

    override fun onUpdate() {
        val current = owner.captureOperation ?: return
        val client = MinecraftClient.getInstance()
        val now = System.currentTimeMillis()
        if (!RvlAddonsClient.isGameplayServerActive()) {
            owner.failCapture(client, "inactive")
            return
        }

        val screen = client.currentScreen as? HandledScreen<*>
        val handler = screen?.let(owner::screenHandler)
        if (screen == null || handler == null) {
            if (now - current.startedAt > ComaSwapController.OPEN_TIMEOUT_MS) {
                owner.failCapture(client, "COMA menu did not open")
            }
            return
        }
        if (!owner.isComaScreen(screen)) {
            if (now - current.startedAt > ComaSwapController.OPEN_TIMEOUT_MS) {
                owner.failCapture(client, "COMA menu title not detected")
            }
            return
        }

        val snapshot = owner.serverSyncs[handler.syncId]
        current.syncId = handler.syncId
        current.lastRevision = snapshot?.revision ?: -1
        val liveContents = handler.slots.map { it.stack.copy() }
        current.previousContents = snapshot?.contents?.map(ItemStack::copy) ?: liveContents
        if (snapshot == null) {
            owner.serverSyncs.put(ComaServerSync(handler.syncId, -1, liveContents))
        }

        current.clearSlots.clear()
        owner.comaSlots.forEachIndexed { index, slot ->
            val stack = snapshot?.contents?.getOrNull(slot) ?: liveContents.getOrNull(slot)
            if (FabricComaItemAdapter.isComaEquipment(stack, index)) current.clearSlots.add(slot)
        }
        val clientBaseline = owner.comaSlots.joinToString(",") { slot ->
            "$slot=${current.previousContents.getOrNull(slot)?.isEmpty != false}"
        }
        val serverBaseline = owner.comaSlots.joinToString(",") { slot ->
            "$slot=${snapshot?.contents?.getOrNull(slot)?.isEmpty != false}"
        }
        RvlAddonsTrace.log(
            "coma",
            "capture-baseline syncId=${handler.syncId} client=[$clientBaseline] server=[$serverBaseline] clearSlots=${current.clearSlots}"
        )

        menuReady = true
        needsClearing = current.clearSlots.isNotEmpty()
    }
}
