package net.eenda.rvladdons.client.coma

import net.eenda.rvladdons.RvlAddonsClient
import net.eenda.rvladdons.core.RvlAddonsConfigStore
import net.eenda.rvladdons.core.RvlAddonsTrace
import net.eenda.rvladdons.core.RvlTextMatcher
import net.eenda.rvladdons.feature.coma.ComaRole
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.item.ItemStack
import net.minecraft.screen.slot.SlotActionType

internal class ComaCaptureActiveState(owner: ComaSwapController) : ComaCaptureState(owner) {
    internal var completed = false
    private val pendingClicks = mutableListOf<PendingCaptureClick>()

    override fun onEnter() {
        super.onEnter()
        completed = false
        pendingClicks.clear()
        val current = owner.captureOperation ?: return
        val client = MinecraftClient.getInstance()
        owner.notify(client, "RVL COMA: capture active - shift-click or pick up and place helmet, chest, legs, boots")
        RvlAddonsTrace.log("coma", "capture-active syncId=${current.syncId} setIndex=${current.setIndex}")
    }

    override fun onExit() {
        val current = owner.captureOperation
        if (completed && current != null) {
            val client = MinecraftClient.getInstance()
            val set = RvlAddonsConfigStore.config.comaSets.getOrNull(current.setIndex)
            if (set != null && FabricComaItemAdapter.isDefaultSetName(set.name)) {
                val inferredName = FabricComaItemAdapter.inferSetName(current.capturedItemNames.values)
                    ?: current.detectedSetName
                if (!inferredName.isNullOrBlank()) {
                    set.name = inferredName
                    RvlAddonsConfigStore.save()
                }
            }
            owner.notify(client, "RVL COMA: capture complete (4/4)")
            RvlAddonsTrace.log("coma", "capture-finished setIndex=${current.setIndex}")
            current.returnScreen?.let { returnScreen ->
                if (returnScreen is ComaSetEditorScreen) returnScreen.refreshNameFromConfig()
            }
            client.setScreen(current.returnScreen)
        }
        pendingClicks.clear()
        super.onExit()
    }

    override fun onUpdate() {
        val current = owner.captureOperation ?: return
        val client = MinecraftClient.getInstance()
        if (!RvlAddonsClient.isGameplayServerActive()) {
            owner.failCapture(client, "inactive")
            return
        }

        val screen = client.currentScreen as? HandledScreen<*>
        if (screen == null || owner.screenHandler(screen) == null) {
            if (current.capturedRoles.size == 4) {
                completed = true
            } else {
                owner.failCapture(client, "menu closed while capturing (${current.capturedRoles.size}/4)")
            }
            return
        }
        if (!owner.isComaScreen(screen) &&
            System.currentTimeMillis() - current.startedAt > ComaSwapController.OPEN_TIMEOUT_MS
        ) {
            owner.failCapture(client, "COMA menu title not detected")
        }
    }

    override fun onInventorySync(sync: ComaServerSync) {
        val current = owner.captureOperation ?: return
        val set = RvlAddonsConfigStore.config.comaSets.getOrNull(current.setIndex) ?: return
        owner.comaSlots.forEachIndexed { index, slot ->
            val next = sync.contents.getOrNull(slot) ?: return@forEachIndexed
            val previous = current.previousContents.getOrNull(slot)
            if (!FabricComaItemAdapter.isComaEquipment(next, index) ||
                FabricComaItemAdapter.sameItem(previous, next)
            ) return@forEachIndexed

            val pending = pendingClicks.firstOrNull { FabricComaItemAdapter.sameItem(it.stack, next) }
                ?: return@forEachIndexed
            pendingClicks.remove(pending)
            val encoded = FabricComaItemAdapter.encodeRule(next)
            current.capturedItemNames[index] = next.name.string
            if (current.detectedSetName == null) {
                current.detectedSetName = FabricComaItemAdapter.extractSetName(next)
            }
            val role = ComaRole.fromIndex(index) ?: return@forEachIndexed
            set.setRule(role, encoded)
            owner.currentComaStacks = owner.currentComaStacks.toMutableList().also { it[index] = next.copy() }
            RvlAddonsConfigStore.save()
            if (current.capturedRoles.add(index)) {
                owner.enqueueNotification("RVL COMA: saved ${owner.roleName(index)} (${current.capturedRoles.size}/4)")
            }
            RvlAddonsTrace.log("coma", "captured role=${owner.roleName(index)} rule=$encoded")
        }
    }

    override fun onCaptureClick(title: String, slotId: Int, actionType: SlotActionType, stack: ItemStack?) {
        if (owner.captureOperation == null) return
        if (!RvlTextMatcher.normalize(title).contains("CO MA")) return
        if (actionType != SlotActionType.QUICK_MOVE && actionType != SlotActionType.PICKUP) return
        if (stack == null || stack.isEmpty || slotId in owner.comaSlots) return
        pendingClicks += PendingCaptureClick(stack.copy())
        RvlAddonsTrace.log("coma", "capture-click sourceSlot=$slotId action=$actionType item=${stack.item}")
    }
}

private data class PendingCaptureClick(
    val stack: ItemStack
)
