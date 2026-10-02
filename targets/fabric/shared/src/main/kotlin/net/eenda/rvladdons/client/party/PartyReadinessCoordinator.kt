package net.eenda.rvladdons.client.party

import net.eenda.rvladdons.feature.party.PartyAttemptState
import net.eenda.rvladdons.feature.party.PartyClientSnapshot
import net.eenda.rvladdons.feature.party.PartyPresenceState
import net.minecraft.client.MinecraftClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Owns the PREPARING/READY handshake before a join attempt starts. */
internal class PartyReadinessCoordinator(
    private val selectedClients: () -> List<PartyClientSnapshot>,
    private val isCoordinator: () -> Boolean,
    private val transport: () -> LocalPartyTransport?,
    private val currentServerKey: (MinecraftClient) -> String,
    private val setAttemptState: (PartyAttemptState) -> Unit,
    private val showStatus: (MinecraftClient, PartyAttemptState, Int, Int, String?) -> Unit
) {
    private val readyClientIds = ConcurrentHashMap.newKeySet<String>()
    private val notReadyReasons = ConcurrentHashMap<String, String>()
    private var phase = PartyAttemptState.IDLE
    private var deadline = 0L
    private var remoteAttemptId = ""

    var attemptId: String = ""
        private set

    var participantIds: Set<String> = emptySet()
        private set

    fun start(client: MinecraftClient): Boolean {
        if (!isCoordinator()) return false
        val selected = selectedClients()
        if (selected.isEmpty()) return false

        attemptId = UUID.randomUUID().toString()
        participantIds = selected.mapTo(LinkedHashSet()) { it.clientId }
        readyClientIds.clear()
        notReadyReasons.clear()
        deadline = System.currentTimeMillis() + 2_000L
        phase = PartyAttemptState.PREPARING
        setAttemptState(PartyAttemptState.PREPARING)
        transport()?.prepare(participantIds, attemptId, currentServerKey(client))
        update(client)
        return true
    }

    fun tick(client: MinecraftClient) {
        if (phase == PartyAttemptState.PREPARING || phase == PartyAttemptState.READY) {
            update(client)
        }
    }

    fun handlePrepareRequest(attemptId: String, expectedServerKey: String) {
        val client = MinecraftClient.getInstance()
        val ready = client.world != null &&
                client.player != null &&
                currentServerKey(client) == expectedServerKey
        remoteAttemptId = attemptId
        transport()?.sendReady(attemptId, ready, if (ready) "" else "not on target server")
    }

    fun cancelAttempt(attemptId: String): Boolean {
        if (attemptId.isBlank()) return false
        if (this.attemptId == attemptId) {
            clear()
            return true
        }
        if (remoteAttemptId == attemptId) {
            remoteAttemptId = ""
            return true
        }
        return false
    }

    fun resumeReady() {
        if (attemptId.isNotBlank()) phase = PartyAttemptState.READY
        remoteAttemptId = ""
    }

    fun handleReadyResponse(
        clientId: String,
        responseAttemptId: String,
        ready: Boolean,
        reason: String,
        client: MinecraftClient
    ) {
        if (responseAttemptId != attemptId || clientId !in participantIds) return
        if (ready) {
            readyClientIds.add(clientId)
        } else {
            notReadyReasons[clientId] = reason.ifBlank { "not ready" }
        }
        update(client)
    }

    fun readyCount(selected: List<PartyClientSnapshot>, client: MinecraftClient): Int {
        if (attemptId.isBlank()) return selected.count {
            it.state == PartyPresenceState.IN_SERVER && it.serverKey == currentServerKey(client)
        }
        return selected.count { it.clientId in readyClientIds }
    }

    fun pauseForJoin() {
        phase = PartyAttemptState.JOINING
    }

    fun reconcileParticipants(availableClientIds: Set<String>) {
        participantIds = participantIds.intersect(availableClientIds)
        readyClientIds.retainAll(participantIds)
        notReadyReasons.keys.retainAll(participantIds)
    }

    fun removeParticipant(clientId: String) {
        participantIds = participantIds - clientId
        readyClientIds.remove(clientId)
        notReadyReasons.remove(clientId)
    }

    fun clear() {
        attemptId = ""
        remoteAttemptId = ""
        participantIds = emptySet()
        readyClientIds.clear()
        notReadyReasons.clear()
        deadline = 0L
        phase = PartyAttemptState.IDLE
    }

    fun failureReason(): String? = notReadyReasons.values.firstOrNull()

    private fun update(client: MinecraftClient) {
        val selected = selectedClients()
        if (selected.isEmpty()) {
            clear()
            setAttemptState(PartyAttemptState.IDLE)
            return
        }

        val selectedIds = selected.mapTo(HashSet()) { it.clientId }
        participantIds = participantIds.intersect(selectedIds)
        val nextState = when {
            notReadyReasons.isNotEmpty() -> PartyAttemptState.FAILED
            participantIds.isNotEmpty() && readyClientIds.containsAll(participantIds) -> PartyAttemptState.READY
            System.currentTimeMillis() >= deadline -> PartyAttemptState.FAILED
            else -> PartyAttemptState.PREPARING
        }
        phase = nextState
        setAttemptState(nextState)
        if (nextState == PartyAttemptState.FAILED || nextState == PartyAttemptState.READY) {
            showStatus(client, nextState, readyCount(selected, client), selected.size, failureReason())
        }
    }
}
