package net.eenda.rvladdons.feature.party

import net.eenda.rvladdons.core.RvlTextMatcher
import java.util.Locale

data class FinishedPartyRoom(val commandRoot: String, val wasAutomatic: Boolean)

class PartyRoomRunMonitor {
    private var commandRoot: String? = null
    private var automatic = false

    fun start(commandRoot: String, automatic: Boolean) {
        this.commandRoot = commandRoot.trim().removePrefix("/").lowercase(Locale.ROOT)
        this.automatic = automatic
    }

    fun stop() {
        commandRoot = null
        automatic = false
    }

    fun completeIfMatched(message: String): FinishedPartyRoom? {
        val root = commandRoot ?: return null
        val finished = when (root) {
            "dchien" -> RvlTextMatcher.contains(message, "VƯỢT THÀNH CÔNG ĐOÀN CHIẾN") ||
                RvlTextMatcher.contains(message, "THẢO PHẠT ĐOÀN CHIẾN THÀNH CÔNG")
            "tl" -> RvlTextMatcher.contains(message, "VƯỢT THANH LÂN THÀNH CÔNG")
            else -> false
        }
        if (!finished) return null

        val completed = FinishedPartyRoom(root, automatic)
        stop()
        return completed
    }
}
