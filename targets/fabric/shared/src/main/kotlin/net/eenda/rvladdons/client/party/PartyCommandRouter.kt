package net.eenda.rvladdons.client.party

import net.eenda.rvladdons.feature.party.PartyAttemptState
import net.eenda.rvladdons.feature.party.PartyJoinExecutionMode

/** Parses synchronized commands and delegates only party-relevant actions. */
internal class PartyCommandRouter(
    private val roots: Set<String>,
    private val isDispatching: () -> Boolean,
    private val isCoordinator: () -> Boolean,
    private val selectedCount: () -> Int,
    private val state: () -> PartyAttemptState,
    private val mode: () -> PartyJoinExecutionMode,
    private val showWaiting: () -> Unit,
    private val startJoin: (String, PartyJoinExecutionMode) -> Unit,
    private val cancelJoin: (String) -> Unit,
    private val broadcastCommand: (String) -> Unit
) {
    fun handle(rawCommand: String): Boolean {
        if (isDispatching()) return true
        val command = rawCommand.trim().removePrefix("/").trim()
        val parts = command.split(Regex("\\s+"), limit = 2)
        val root = parts.firstOrNull()?.lowercase().orEmpty()
        if (root !in roots) return true

        if (parts.getOrNull(1)?.equals("quit", ignoreCase = true) == true) {
            cancelJoin(command)
            return true
        }

        if (parts.size == 1) {
            if (!isCoordinator()) return true
            if (selectedCount() > 0 && state() != PartyAttemptState.READY) {
                showWaiting()
                return false
            }
            if (selectedCount() == 0) return true
            startJoin(root, mode())
            return false
        }

        if (isCoordinator()) broadcastCommand(command)
        return true
    }
}
