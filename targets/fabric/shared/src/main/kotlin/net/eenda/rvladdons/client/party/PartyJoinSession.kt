package net.eenda.rvladdons.client.party

import net.eenda.rvladdons.feature.party.PartyMenuStage

/** Single source of truth for one synchronized join attempt. */
internal class PartyJoinSession {
    var attemptId: String = ""
        private set
    var commandRoot: String = ""
        private set
    var deadline: Long = 0L
        private set
    val expectedClientIds = LinkedHashSet<String>()
    val results = LinkedHashSet<String>()
    val failures = LinkedHashMap<String, String>()
    val readyMenuClientIds = LinkedHashSet<String>()
    val menuStages = LinkedHashMap<String, PartyMenuStage>()

    val isActive: Boolean
        get() = attemptId.isNotBlank()

    fun begin(
        attemptId: String,
        commandRoot: String,
        expectedClientIds: Set<String>,
        deadline: Long
    ) {
        this.attemptId = attemptId
        this.commandRoot = commandRoot
        this.deadline = deadline
        this.expectedClientIds.clear()
        this.expectedClientIds.addAll(expectedClientIds)
        results.clear()
        failures.clear()
        readyMenuClientIds.clear()
        menuStages.clear()
    }

    fun isExpired(now: Long): Boolean = deadline in 1..now

    fun stopDeadline() {
        deadline = 0L
    }

    fun reset() {
        attemptId = ""
        commandRoot = ""
        deadline = 0L
        expectedClientIds.clear()
        results.clear()
        failures.clear()
        readyMenuClientIds.clear()
        menuStages.clear()
    }
}
