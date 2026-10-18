package net.eenda.rvladdons.client.party

internal data class RoomCreatorCandidate(
    val clientId: String,
    val accountKey: String,
    val name: String,
    val dchienRunsRemaining: Int?,
    val tlTicketsRemaining: Int?
) {
    fun remainingFor(commandRoot: String): Int? =
        if (commandRoot.trim().removePrefix("/").substringBefore(' ').equals("tl", ignoreCase = true)) {
            tlTicketsRemaining
        } else {
            dchienRunsRemaining
        }
}

/** Prefers more remaining runs, then LRU, while honoring the server cooldown. */
internal class RoomCreatorRotation(
    private val cooldownMs: Long = 60_000L
) {
    data class Selection(val candidate: RoomCreatorCandidate, val availableAt: Long)

    private val lastCreatedAt = HashMap<String, Long>()

    fun choose(
        candidates: List<RoomCreatorCandidate>,
        now: Long,
        excludedClientIds: Set<String> = emptySet(),
        commandRoot: String
    ): Selection? {
        val unique = candidates
            .filterNot { it.clientId in excludedClientIds }
            .distinctBy { it.accountKey }
            .filter { it.remainingFor(commandRoot) != 0 }
        if (unique.isEmpty()) return null

        fun availableAt(candidate: RoomCreatorCandidate): Long =
            lastCreatedAt[candidate.accountKey]?.plus(cooldownMs) ?: Long.MIN_VALUE

        val ready = unique.filter { availableAt(it) <= now }
        val pool = if (ready.isNotEmpty()) ready else {
            val earliest = unique.minOf(::availableAt)
            unique.filter { availableAt(it) == earliest }
        }
        val candidate = pool.sortedWith(
            compareByDescending<RoomCreatorCandidate> { it.remainingFor(commandRoot) ?: -1 }
                .thenBy { lastCreatedAt[it.accountKey] ?: Long.MIN_VALUE }
        ).firstOrNull() ?: return null

        return Selection(candidate, maxOf(now, availableAt(candidate)))
    }

    fun markCreated(accountKey: String, now: Long) {
        lastCreatedAt[accountKey] = now
    }
}
