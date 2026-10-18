package net.eenda.rvladdons.feature.party

import java.util.ArrayDeque

data class PartyManualActionQueueUpdate(
    val accepted: Boolean,
    val intentChanged: Boolean = false,
    val queued: Boolean = false,
    val shouldDelayType: Boolean = false,
    val clearsRoomWait: Boolean = false
)

class PartyManualActionQueue {
    private val pending = ArrayDeque<PartyManualAction>()

    var lastAppliedSequence: Int = 0
        private set
    var intentVersion: Int = 0
        private set

    fun enqueue(
        action: PartyManualAction,
        lastActionType: PartyManualActionType?,
        awaitingTransition: Boolean
    ): PartyManualActionQueueUpdate {
        if (action.sequence <= lastAppliedSequence || action.intentVersion < intentVersion) {
            return PartyManualActionQueueUpdate(accepted = false)
        }

        val intentChanged = action.intentVersion > intentVersion
        if (intentChanged) {
            pending.clear()
            intentVersion = action.intentVersion
        }

        val clearsRoomWait = action.type == PartyManualActionType.SELECT_TYPE
        if (clearsRoomWait) {
            pending.removeIf {
                it.type == PartyManualActionType.SELECT_ROOM && it.intentVersion <= action.intentVersion
            }
        }

        val awaitingCurrentAction = awaitingTransition && !intentChanged
        if (action.type == PartyManualActionType.SELECT_ROOM &&
            (pending.any { it.type == PartyManualActionType.SELECT_ROOM && it.intentVersion == action.intentVersion } ||
                (lastActionType == PartyManualActionType.SELECT_ROOM && awaitingCurrentAction &&
                    intentVersion == action.intentVersion))
        ) {
            return PartyManualActionQueueUpdate(accepted = false, intentChanged = intentChanged)
        }

        val queued = pending.none { it.sequence == action.sequence }
        if (queued) pending.addLast(action)
        return PartyManualActionQueueUpdate(
            accepted = true,
            intentChanged = intentChanged,
            queued = queued,
            shouldDelayType = queued && clearsRoomWait && lastActionType == PartyManualActionType.SELECT_ROOM,
            clearsRoomWait = clearsRoomWait
        )
    }

    fun enqueueRecoveryType(action: PartyManualAction): Boolean {
        if (action.intentVersion < intentVersion) return false
        intentVersion = maxOf(intentVersion, action.intentVersion)
        pending.removeIf { it.type == PartyManualActionType.SELECT_ROOM }
        pending.removeIf { it.sequence == action.sequence && it.type == PartyManualActionType.SELECT_TYPE }
        pending.addFirst(action)
        return true
    }

    fun markCoordinatorAction(sequence: Int, intentVersion: Int) {
        this.intentVersion = intentVersion
        lastAppliedSequence = sequence
    }

    fun markApplied(sequence: Int) {
        lastAppliedSequence = sequence
    }

    fun first(): PartyManualAction? = pending.peekFirst()

    fun removeFirst(): PartyManualAction? = pending.pollFirst()

    fun any(predicate: (PartyManualAction) -> Boolean): Boolean = pending.any(predicate)

    fun isEmpty(): Boolean = pending.isEmpty()

    fun clearPending() = pending.clear()

    fun resetReplay() {
        pending.clear()
        lastAppliedSequence = 0
    }

    fun reset() {
        resetReplay()
        intentVersion = 0
    }
}
