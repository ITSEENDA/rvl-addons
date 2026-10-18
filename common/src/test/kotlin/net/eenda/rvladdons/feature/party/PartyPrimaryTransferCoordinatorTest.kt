package net.eenda.rvladdons.feature.party

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartyPrimaryTransferCoordinatorTest {
    @Test
    fun tracksHandoffUntilExpiredOrCleared() {
        val transfer = PartyPrimaryTransferCoordinator()
        transfer.begin("handoff", "client-b", deadline = 2_000L)

        assertTrue(transfer.isPending)
        assertTrue(transfer.matches("handoff", "client-b"))
        assertFalse(transfer.matches("other", "client-b"))
        assertFalse(transfer.isExpired(1_999L))
        assertTrue(transfer.isExpired(2_000L))

        transfer.clearTransfer()
        assertFalse(transfer.isPending)
    }

    @Test
    fun preservesCommittedSelectionDuringReconnectGraceThenDropsMissingClients() {
        val transfer = PartyPrimaryTransferCoordinator()
        transfer.cacheCommittedSelection(setOf("local", "peer"), now = 1_000L)

        assertEquals(setOf("local", "peer"), transfer.takePendingSelection())
        assertNull(transfer.takePendingSelection())
        assertTrue(transfer.isReconnecting("peer", now = 2_000L))
        assertEquals(
            setOf("local", "peer"),
            transfer.reconcileSelection(setOf("local", "peer"), setOf("local"), now = 2_000L)
        )
        assertEquals(
            setOf("local"),
            transfer.reconcileSelection(setOf("local", "peer"), setOf("local"), now = 6_001L)
        )
        assertFalse(transfer.isReconnecting("peer", now = 6_001L))
    }

    @Test
    fun resetClearsHandshakeAndCachedSelection() {
        val transfer = PartyPrimaryTransferCoordinator()
        transfer.begin("handoff", "client-b", deadline = 5_000L)
        transfer.cacheCommittedSelection(setOf("client-a"), now = 1_000L)

        transfer.reset()

        assertFalse(transfer.isPending)
        assertNull(transfer.takePendingSelection())
        assertFalse(transfer.isReconnecting("client-a", now = 1_001L))
    }
}
