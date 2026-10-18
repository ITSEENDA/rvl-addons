package net.eenda.rvladdons.feature.party

/** Tracks the promotion handshake and selected clients across reconnects. */
class PartyPrimaryTransferCoordinator {
    companion object {
        private const val RECONNECT_GRACE_MS = 5_000L
    }

    private var handoffId = ""
    private var targetClientId = ""
    private var deadline = 0L
    private var pendingSelection: Set<String>? = null
    private var reconnectSelection = emptySet<String>()
    private var reconnectDeadline = 0L

    val isPending: Boolean
        @Synchronized get() = handoffId.isNotBlank()

    @Synchronized
    fun begin(handoffId: String, targetClientId: String, deadline: Long) {
        this.handoffId = handoffId
        this.targetClientId = targetClientId
        this.deadline = deadline
    }

    @Synchronized
    fun matches(handoffId: String, clientId: String): Boolean =
        this.handoffId == handoffId && targetClientId == clientId

    @Synchronized
    fun isExpired(now: Long): Boolean = handoffId.isNotBlank() && now >= deadline

    @Synchronized
    fun cacheCommittedSelection(clientIds: Set<String>, now: Long) {
        val snapshot = clientIds.toSet()
        pendingSelection = snapshot
        reconnectSelection = snapshot
        reconnectDeadline = now + RECONNECT_GRACE_MS
    }

    @Synchronized
    fun takePendingSelection(): Set<String>? = pendingSelection.also { pendingSelection = null }

    @Synchronized
    fun isReconnecting(clientId: String, now: Long): Boolean =
        now <= reconnectDeadline && clientId in reconnectSelection

    @Synchronized
    fun reconcileSelection(
        selectedClientIds: Set<String>,
        connectedClientIds: Set<String>,
        now: Long
    ): Set<String> {
        val allowedIds = if (now <= reconnectDeadline) {
            reconnectSelection = reconnectSelection - connectedClientIds
            connectedClientIds + reconnectSelection
        } else {
            reconnectSelection = emptySet()
            reconnectDeadline = 0L
            connectedClientIds
        }
        return selectedClientIds.intersect(allowedIds)
    }

    @Synchronized
    fun clearTransfer() {
        handoffId = ""
        targetClientId = ""
        deadline = 0L
    }

    @Synchronized
    fun reset() {
        clearTransfer()
        pendingSelection = null
        reconnectSelection = emptySet()
        reconnectDeadline = 0L
    }
}
