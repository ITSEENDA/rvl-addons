package net.eenda.rvladdons.client.party

import net.eenda.rvladdons.core.RvlAddonsConfigStore
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.item.Items
import net.minecraft.screen.slot.SlotActionType
import java.util.Locale

/** Owns the type-then-room slot-capture flow for automatic party joins. */
internal class PartySlotCaptureController(
    private val isCoordinator: () -> Boolean,
    private val isJoinActive: () -> Boolean,
    private val stopAllowanceScan: () -> Unit,
    private val sendCommand: (String) -> Boolean,
    private val notify: (String) -> Unit
) {
    private enum class Stage { IDLE, WAITING_TYPE_MENU, TYPE, WAITING_ROOM, ROOM }

    private data class MenuSnapshot(
        val syncId: Int,
        val fingerprint: String,
        val itemName: String = ""
    )

    companion object {
        private const val TIMEOUT_MS = 15_000L
    }

    private var stage = Stage.IDLE
    private var initialTypeMenuSyncId = -1
    private var commandRoot = "dchien"
    private var typeMenuBeforeCapture: MenuSnapshot? = null
    private var deadline = 0L

    val isActive: Boolean
        get() = stage != Stage.IDLE

    fun start(commandRoot: String) {
        if (!isCoordinator()) {
            notify("Only the coordinator can configure automatic slots")
            return
        }
        val root = commandRoot.trim().removePrefix("/").lowercase(Locale.ROOT)
        if (root != "dchien" && root != "tl") return
        if (isJoinActive()) {
            notify("Finish the current join before capturing slots")
            return
        }

        stopAllowanceScan()
        stage = Stage.WAITING_TYPE_MENU
        this.commandRoot = root
        initialTypeMenuSyncId = currentMenuSnapshot()?.syncId ?: -1
        typeMenuBeforeCapture = null
        deadline = System.currentTimeMillis() + TIMEOUT_MS
        notify("Opening /$root; click the type item when the menu appears")
        if (!sendCommand(root)) {
            reset()
            notify("Could not open /$root")
        }
    }

    fun onSlotAction(syncId: Int, slot: Int, button: Int, action: SlotActionType): Boolean {
        updateMenu()
        if (stage == Stage.IDLE) return false
        if (stage == Stage.WAITING_TYPE_MENU || stage == Stage.WAITING_ROOM) return true
        if (button != 0 || action != SlotActionType.PICKUP) {
            notify("Use a left click on a menu item")
            return true
        }

        val snapshot = captureSnapshot(syncId, slot) ?: run {
            notify("Click a filled menu slot, not your inventory")
            return true
        }
        val config = RvlAddonsConfigStore.config
        if (stage == Stage.TYPE) {
            if (commandRoot == "tl") config.partyTlAutomaticTypeSlot = slot
            else config.partyAutomaticTypeSlot = slot
            RvlAddonsConfigStore.save()
            typeMenuBeforeCapture = snapshot
            stage = Stage.WAITING_ROOM
            deadline = System.currentTimeMillis() + TIMEOUT_MS
            notify("/$commandRoot type #$slot captured (${snapshot.itemName.take(24)}); waiting for room menu")
            return false
        }

        if (commandRoot == "tl") {
            config.partyTlAutomaticRoomSlot = slot
            config.partyTlAutomaticRoomName = snapshot.itemName
        } else {
            config.partyAutomaticRoomSlot = slot
            config.partyAutomaticRoomName = snapshot.itemName
        }
        RvlAddonsConfigStore.save()
        val capturedCommand = commandRoot
        reset()
        MinecraftClient.getInstance().player?.closeHandledScreen()
        notify("/$capturedCommand room #$slot captured (${snapshot.itemName.take(24)}); click blocked")
        return true
    }

    fun onMenuOpened() = updateMenu(menuOpenedPacket = true)

    fun tick(now: Long) {
        updateMenu()
        if (isActive && now >= deadline) {
            reset()
            notify("Slot capture timed out")
        }
    }

    fun cancel(): Boolean {
        if (!isActive) return false
        reset()
        return true
    }

    fun reset() {
        stage = Stage.IDLE
        commandRoot = "dchien"
        initialTypeMenuSyncId = -1
        typeMenuBeforeCapture = null
        deadline = 0L
    }

    private fun captureSnapshot(syncId: Int, slot: Int): MenuSnapshot? {
        val client = MinecraftClient.getInstance()
        val screen = client.currentScreen as? HandledScreen<*> ?: return null
        val handler = client.player?.currentScreenHandler ?: return null
        if (handler.syncId != syncId) return null

        val menuSlots = if (handler.slots.size > 36) handler.slots.dropLast(36) else handler.slots
        val stack = menuSlots.getOrNull(slot)?.stack ?: return null
        if (stack.isEmpty || stack.item == Items.BLACK_STAINED_GLASS_PANE || stack.item == Items.BOOK) return null

        return MenuSnapshot(syncId, fingerprint(screen.title.string, menuSlots), stack.name.string)
    }

    private fun updateMenu(menuOpenedPacket: Boolean = false) {
        if (stage == Stage.WAITING_TYPE_MENU) {
            val current = currentMenuSnapshot() ?: return
            if (!menuOpenedPacket && initialTypeMenuSyncId >= 0 && current.syncId == initialTypeMenuSyncId) return
            initialTypeMenuSyncId = -1
            stage = Stage.TYPE
            deadline = System.currentTimeMillis() + TIMEOUT_MS
            notify("Type menu ready; click its item to capture the type slot")
            return
        }
        if (stage != Stage.WAITING_ROOM) return

        val current = currentMenuSnapshot() ?: return
        val previous = typeMenuBeforeCapture ?: return reset()
        if (!menuOpenedPacket && current.syncId == previous.syncId && current.fingerprint == previous.fingerprint) return

        stage = Stage.ROOM
        deadline = System.currentTimeMillis() + TIMEOUT_MS
        notify("Room menu ready; click its item to capture the room slot")
    }

    private fun currentMenuSnapshot(): MenuSnapshot? {
        val client = MinecraftClient.getInstance()
        val screen = client.currentScreen as? HandledScreen<*> ?: return null
        val handler = client.player?.currentScreenHandler ?: return null
        val menuSlots = if (handler.slots.size > 36) handler.slots.dropLast(36) else handler.slots
        return MenuSnapshot(handler.syncId, fingerprint(screen.title.string, menuSlots))
    }

    private fun fingerprint(title: String, slots: List<net.minecraft.screen.slot.Slot>): String =
        "$title|${slots.size}|" + slots.joinToString("|") { slot ->
            val stack = slot.stack
            if (stack.isEmpty) "empty" else "${stack.name.string}:${stack.count}"
        }
}
