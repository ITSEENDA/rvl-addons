package net.eenda.rvladdons.client.party

import net.eenda.rvladdons.client.item.FabricItemAdapter
import net.eenda.rvladdons.core.RvlAddonsTrace
import net.eenda.rvladdons.core.RvlTextMatcher
import net.eenda.rvladdons.feature.party.PartyAllowanceParser
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.item.ItemStack
import net.minecraft.screen.ScreenHandler

internal data class PartyAllowanceCost(val commandRoot: String, val amount: Int)

/** Reads the account book in each gameplay menu and commits room costs only after join confirmation. */
internal class PartyAllowanceTracker(private val showStatus: (String) -> Unit = {}) {
    private enum class ScanStage { IDLE, WAIT_DCHIEN, WAIT_TL, START_TL }

    private var stage = ScanStage.IDLE
    private var deadline = 0L

    var dchienRemaining: Int? = null
        private set
    var tlTicketsRemaining: Int? = null
        private set

    fun remaining(commandRoot: String): Int? =
        if (commandRoot.trim().removePrefix("/").substringBefore(' ').equals("tl", ignoreCase = true)) {
            tlTicketsRemaining
        } else {
            dchienRemaining
        }

    fun commit(cost: PartyAllowanceCost?) {
        cost ?: return
        when (cost.commandRoot) {
            "dchien" -> dchienRemaining = dchienRemaining?.minus(cost.amount)?.coerceAtLeast(0)
            "tl" -> tlTicketsRemaining = tlTicketsRemaining?.minus(cost.amount)?.coerceAtLeast(0)
        }
    }

    fun start(client: MinecraftClient, sendCommand: (String) -> Boolean): Boolean {
        if (stage != ScanStage.IDLE || client.player == null || client.world == null ||
            client.currentScreen != null
        ) return false

        dchienRemaining = null
        tlTicketsRemaining = null
        return openMenu("dchien", ScanStage.WAIT_DCHIEN, sendCommand)
    }

    fun isScanning(): Boolean = stage != ScanStage.IDLE

    fun tick(client: MinecraftClient, sendCommand: (String) -> Boolean) {
        if (stage == ScanStage.START_TL) {
            openMenu("tl", ScanStage.WAIT_TL, sendCommand)
            return
        }
        if (stage != ScanStage.WAIT_DCHIEN && stage != ScanStage.WAIT_TL) return

        val root = if (stage == ScanStage.WAIT_DCHIEN) "dchien" else "tl"
        val handler = menuHandler(client, root)
        if (handler == null) {
            if (System.currentTimeMillis() >= deadline) onTimeout(client, root)
            return
        }
        val slots = menuSlots(handler)
        val count = parseBalance(root, slots)
        if (count != null) {
            if (root == "dchien") dchienRemaining = count else tlTicketsRemaining = count
            // Close the matching type menu as soon as its allowance has been read.
            client.player?.closeHandledScreen()
            showStatus("Party: /$root allowance = $count")
            RvlAddonsTrace.log("party-allowance", "read root=$root count=$count")
            if (root == "dchien") {
                stage = ScanStage.START_TL
            } else {
                stage = ScanStage.IDLE
            }
            return
        }

        if (System.currentTimeMillis() >= deadline) onTimeout(client, root)
    }

    fun reset() {
        stopScan()
        dchienRemaining = null
        tlTicketsRemaining = null
    }

    fun stopScan() {
        stage = ScanStage.IDLE
        deadline = 0L
    }

    private fun openMenu(
        root: String,
        nextStage: ScanStage,
        sendCommand: (String) -> Boolean
    ): Boolean {
        deadline = System.currentTimeMillis() + MENU_TIMEOUT_MS
        stage = nextStage
        if (sendCommand(root)) {
            showStatus("Party: reading /$root allowance")
            RvlAddonsTrace.log("party-allowance", "open root=$root")
            return true
        }
        stopScan()
        return false
    }

    private fun onTimeout(client: MinecraftClient, root: String) {
        val title = (client.currentScreen as? HandledScreen<*>)?.title?.string.orEmpty()
        val handler = client.player?.currentScreenHandler
        val titleMatches = hasExpectedTitle(client, root)
        val label = if (root == "tl") "VE THANH LAN" else "LUOT THAM GIA"
        val candidates = if (handler != null && titleMatches) {
            menuSlots(handler).asSequence()
                .flatMap { FabricItemAdapter.loreLines(it.stack).asSequence() }
                .map(RvlTextMatcher::normalize)
                .filter { it.contains(label) || it.contains("TIEU HAO") }
                .take(8)
                .toList()
        } else {
            emptyList()
        }
        if (handler != null && titleMatches) client.player?.closeHandledScreen()
        RvlAddonsTrace.log(
            "party-allowance",
            "read-failed root=$root title=$title candidates=$candidates"
        )
        showStatus("Party: couldn't read /$root allowance")

        if (root == "dchien") {
            stage = ScanStage.START_TL
        } else {
            stage = ScanStage.IDLE
        }
    }

    private fun menuHandler(client: MinecraftClient, root: String): ScreenHandler? {
        if (!hasExpectedTitle(client, root)) return null
        return client.player?.currentScreenHandler
    }

    private fun hasExpectedTitle(client: MinecraftClient, root: String): Boolean {
        val expectedTitle = if (root == "tl") "THANH LAN" else "DOAN CHIEN"
        return (client.currentScreen as? HandledScreen<*>)
            ?.title?.string?.let {
                RvlTextMatcher.contains(it.replace('Đ', 'D').replace('đ', 'd'), expectedTitle)
            } == true
    }

    private fun menuSlots(handler: ScreenHandler): List<net.minecraft.screen.slot.Slot> =
        if (handler.slots.size > 36) handler.slots.dropLast(36) else handler.slots

    private fun parseBalance(root: String, slots: List<net.minecraft.screen.slot.Slot>): Int? {
        return slots.asSequence()
            .map { FabricItemAdapter.loreLines(it.stack) }
            .mapNotNull { PartyAllowanceParser.remaining(root, it) }
            .firstOrNull()
    }

    companion object {
        const val MENU_TIMEOUT_MS = 2_500L

        fun roomCost(commandRoot: String, stack: ItemStack): PartyAllowanceCost? {
            val root = commandRoot.trim().removePrefix("/").substringBefore(' ').lowercase()
            val amount = PartyAllowanceParser.cost(commandRoot, FabricItemAdapter.loreLines(stack))
                ?: return null
            return PartyAllowanceCost(root, amount)
        }
    }
}
