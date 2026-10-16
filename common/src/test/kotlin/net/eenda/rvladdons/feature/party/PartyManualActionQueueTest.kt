package net.eenda.rvladdons.feature.party

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartyManualActionQueueTest {
    private fun action(
        sequence: Int,
        type: PartyManualActionType,
        intent: Int = 1
    ) = PartyManualAction(sequence, 4, 0, "PICKUP", intent, type, "target")

    @Test
    fun newerTypeIntentDropsQueuedRoomActionsAndRequestsSettleDelay() {
        val queue = PartyManualActionQueue()
        assertTrue(queue.enqueue(action(1, PartyManualActionType.SELECT_ROOM), null, false).queued)

        val nextType = queue.enqueue(
            action(2, PartyManualActionType.SELECT_TYPE, intent = 2),
            PartyManualActionType.SELECT_ROOM,
            awaitingTransition = true
        )

        assertTrue(nextType.accepted)
        assertTrue(nextType.intentChanged)
        assertTrue(nextType.shouldDelayType)
        assertEquals(PartyManualActionType.SELECT_TYPE, queue.first()?.type)
    }

    @Test
    fun rejectsDuplicateRoomClicksForAnIntentAndStaleActions() {
        val queue = PartyManualActionQueue()
        assertTrue(queue.enqueue(action(1, PartyManualActionType.SELECT_ROOM), null, false).accepted)
        assertFalse(queue.enqueue(action(2, PartyManualActionType.SELECT_ROOM), null, false).accepted)

        queue.markApplied(3)
        assertFalse(queue.enqueue(action(3, PartyManualActionType.SELECT_TYPE, intent = 2), null, false).accepted)
    }

    @Test
    fun recoveryTypePreemptsRoomActionAndReplayResetKeepsIntent() {
        val queue = PartyManualActionQueue()
        queue.enqueue(action(1, PartyManualActionType.SELECT_ROOM), null, false)
        queue.enqueueRecoveryType(action(2, PartyManualActionType.SELECT_TYPE, intent = 2))

        assertEquals(PartyManualActionType.SELECT_TYPE, queue.first()?.type)
        assertEquals(2, queue.intentVersion)

        queue.markApplied(2)
        queue.resetReplay()
        assertEquals(0, queue.lastAppliedSequence)
        assertEquals(2, queue.intentVersion)
        assertTrue(queue.isEmpty())
    }
}
