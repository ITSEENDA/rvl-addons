package net.eenda.rvladdons.client.party

import net.eenda.rvladdons.feature.party.PartyAttemptState
import net.eenda.rvladdons.feature.party.PartyJoinExecutionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PartyCommandRouterTest {
    @Test
    fun bareJoinCommandStartsLocalFlowWhenNoPartyClientsAreSelected() {
        var started: Pair<String, PartyJoinExecutionMode>? = null
        val mode = PartyJoinExecutionMode.AUTOMATIC
        val router = PartyCommandRouter(
            roots = setOf("dchien", "tl"),
            isDispatching = { false },
            isCoordinator = { true },
            selectedCount = { 0 },
            state = { PartyAttemptState.IDLE },
            mode = { mode },
            showWaiting = {},
            startJoin = { command, executionMode -> started = command to executionMode },
            cancelJoin = {},
            broadcastCommand = {}
        )

        assertFalse(router.handle("/tl"))
        assertEquals("tl" to mode, started)
    }
}
