package net.eenda.rvladdons.client.party

/** Keeps primary-transfer state separate from the join session. */
internal class PartyPrimaryTransferCoordinator {
    var handoffId: String = ""
        private set
    var targetClientId: String = ""
        private set
    var deadline: Long = 0L
        private set

    val isPending: Boolean
        get() = handoffId.isNotBlank()

    fun begin(handoffId: String, targetClientId: String, deadline: Long) {
        this.handoffId = handoffId
        this.targetClientId = targetClientId
        this.deadline = deadline
    }

    fun matches(handoffId: String, clientId: String): Boolean =
        this.handoffId == handoffId && this.targetClientId == clientId

    fun isExpired(now: Long): Boolean = isPending && now >= deadline

    fun clear() {
        handoffId = ""
        targetClientId = ""
        deadline = 0L
    }
}
