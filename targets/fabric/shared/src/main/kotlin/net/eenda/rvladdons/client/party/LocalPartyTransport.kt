package net.eenda.rvladdons.client.party

import net.eenda.rvladdons.feature.party.PartyClientSnapshot
import net.eenda.rvladdons.feature.party.PartyManualAction
import net.eenda.rvladdons.feature.party.PartyMenuStage
import net.eenda.rvladdons.feature.party.PartyPresenceState
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private object PartyProtocol {
    fun encode(type: String, values: List<String> = emptyList()): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return (listOf(type) + values.map { encoder.encodeToString(it.toByteArray(StandardCharsets.UTF_8)) })
            .joinToString("|") + "\n"
    }

    fun decode(line: String): Pair<String, List<String>>? {
        val parts = line.trimEnd('\r', '\n').split('|')
        if (parts.isEmpty() || parts.first().isBlank()) return null
        val decoder = Base64.getUrlDecoder()
        return parts.first() to parts.drop(1).mapNotNull { value ->
            runCatching { String(decoder.decode(value), StandardCharsets.UTF_8) }.getOrNull()
        }
    }
}

class LocalPartyTransport(
    private val onSnapshot: (List<PartyClientSnapshot>) -> Unit,
    private val onPrepare: (String, String) -> Unit,
    private val onReady: (String, String, Boolean, String) -> Unit,
    private val onPromoteRequest: (String) -> Unit,
    private val onPromotionReady: (String, String, Boolean, String) -> Unit,
    private val onJoinGo: (String, String, Int, Int) -> Unit,
    private val onJoinResult: (String, String, Boolean, String) -> Unit,
    private val onPartyCommand: (String) -> Unit,
    private val onManualCommand: (String, String) -> Unit,
    private val onManualMenuReady: (String, String) -> Unit,
    private val onManualMenuState: (String, String, PartyMenuStage, Int) -> Unit,
    private val onManualClick: (String, Int, Int, Int, String, Int, String, String) -> Unit,
    private val onManualRoomAccepted: (String, Int, Int, Int, String, Int, String, String) -> Unit,
    private val onManualReplayBegin: (String) -> Unit,
    private val onManualAck: (String, String, Int) -> Unit,
    private val onManualResync: (String, String, Int) -> Unit,
    private val onJoinCancel: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        const val PORT = 28765
        private const val CONNECT_TIMEOUT_MS = 500
        private const val RETRY_DELAY_MS = 1_000L
        private const val PROMOTION_DELAY_MS = 100L
        private const val HANDOFF_CLIENT_WAIT_MS = 3_000L
    }

    private data class Peer(
        val socket: Socket,
        val writer: BufferedWriter,
        var clientId: String? = null
    ) {
        @Synchronized
        fun send(type: String, values: List<String> = emptyList()) {
            writer.write(PartyProtocol.encode(type, values))
            writer.flush()
        }

        fun close() {
            runCatching { socket.close() }
        }
    }

    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "rvl-party-transport").apply { isDaemon = true }
    }
    private val peers = ConcurrentHashMap<String, Peer>()
    private val remoteClients = ConcurrentHashMap<String, PartyClientSnapshot>()
    private var serverSocket: ServerSocket? = null
    private var clientPeer: Peer? = null
    private var localSnapshot: PartyClientSnapshot? = null
    private var lastSentSnapshot: PartyClientSnapshot? = null
    private var lastSentAt = 0L
    private var promoteRequested = false
    private var forceClientUntil = 0L

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var isCoordinator: Boolean = false
        private set

    fun start() {
        if (running) return
        running = true
        if (!tryBecomeCoordinator()) executor.execute { connectLoop() }
    }

    fun updateLocal(snapshot: PartyClientSnapshot) {
        localSnapshot = snapshot
        val now = System.currentTimeMillis()
        val changed = snapshot != lastSentSnapshot
        if (!changed && now - lastSentAt < 1_000L) return
        lastSentSnapshot = snapshot
        lastSentAt = now
        if (isCoordinator) {
            publishSnapshot()
            broadcastSnapshot()
        } else {
            clientPeer?.send("PRESENCE", snapshotFields(snapshot))
        }
    }

    fun prepare(clientIds: Set<String>, attemptId: String, serverKey: String) {
        if (!isCoordinator) return
        clientIds.forEach { clientId ->
            peers[clientId]?.send("PREPARE", listOf(attemptId, serverKey))
        }
    }

    fun sendReady(attemptId: String, ready: Boolean, reason: String = "") {
        if (isCoordinator) return
        clientPeer?.send("READY", listOf(attemptId, ready.toString(), reason))
    }

    fun sendJoinGo(
        clientIds: Set<String>,
        attemptId: String,
        command: String,
        gamemodeSlot: Int,
        difficultySlot: Int
    ): Int {
        if (!isCoordinator) return 0
        var sent = 0
        clientIds.forEach { clientId ->
            val peer = peers[clientId] ?: return@forEach
            runCatching {
                peer.send(
                    "JOIN_GO",
                    listOf(attemptId, command, gamemodeSlot.toString(), difficultySlot.toString())
                )
                sent++
            }
        }
        onStatus("join-go:$sent/${clientIds.size}")
        return sent
    }

    fun sendJoinResult(attemptId: String, success: Boolean, reason: String = "") {
        if (isCoordinator) return
        clientPeer?.send("JOIN_RESULT", listOf(attemptId, success.toString(), reason))
    }

    fun sendJoinCancel(clientIds: Set<String>, attemptId: String) {
        if (!isCoordinator) return
        clientIds.forEach { clientId ->
            peers[clientId]?.let { peer ->
                runCatching { peer.send("JOIN_CANCEL", listOf(attemptId)) }
            }
        }
    }

    fun sendManualCommand(clientIds: Set<String>, attemptId: String, command: String): Int {
        if (!isCoordinator) return 0
        var sent = 0
        clientIds.forEach { clientId ->
            val peer = peers[clientId] ?: return@forEach
            runCatching {
                peer.send("MANUAL_COMMAND", listOf(attemptId, command))
                sent++
            }
        }
        onStatus("manual-command:$sent/${clientIds.size}")
        return sent
    }

    fun sendPartyCommand(clientIds: Set<String>, command: String): Int {
        if (!isCoordinator) return 0
        var sent = 0
        clientIds.forEach { clientId ->
            val peer = peers[clientId] ?: return@forEach
            runCatching {
                peer.send("PARTY_COMMAND", listOf(command))
                sent++
            }
        }
        onStatus("party-command:$sent/${clientIds.size}")
        return sent
    }

    fun sendManualClick(attemptId: String, manualAction: PartyManualAction) {
        if (!isCoordinator) return
        peers.values.forEach { peer ->
            runCatching {
                peer.send(
                    "MANUAL_CLICK",
                    listOf(
                        attemptId,
                        manualAction.sequence.toString(),
                        manualAction.slot.toString(),
                        manualAction.button.toString(),
                        manualAction.action,
                        manualAction.intentVersion.toString(),
                        manualAction.type.name,
                        manualAction.targetSignature
                    )
                )
            }
        }
    }

    fun sendManualAck(attemptId: String, sequence: Int) {
        if (isCoordinator) return
        clientPeer?.send("MANUAL_ACK", listOf(attemptId, sequence.toString()))
    }

    fun sendManualMenuReady(attemptId: String) {
        if (isCoordinator) return
        clientPeer?.send("MANUAL_MENU_READY", listOf(attemptId))
    }

    fun sendManualMenuState(attemptId: String, stage: PartyMenuStage, intentVersion: Int) {
        if (isCoordinator) return
        clientPeer?.send("MANUAL_MENU_STATE", listOf(attemptId, stage.name, intentVersion.toString()))
    }

    fun sendManualRoomAccepted(
        clientIds: Set<String>,
        attemptId: String,
        action: PartyManualAction
    ) {
        if (!isCoordinator) return
        clientIds.forEach { clientId ->
            peers[clientId]?.let { peer ->
                runCatching {
                    peer.send(
                        "MANUAL_ROOM_ACCEPTED",
                        listOf(
                            attemptId,
                            action.sequence.toString(),
                            action.slot.toString(),
                            action.button.toString(),
                            action.action,
                            action.intentVersion.toString(),
                            action.type.name,
                            action.targetSignature
                        )
                    )
                }
            }
        }
    }

    fun requestManualResync(attemptId: String, lastSequence: Int) {
        if (isCoordinator) return
        clientPeer?.send("MANUAL_RESYNC", listOf(attemptId, lastSequence.toString()))
    }

    fun sendManualHistory(clientId: String, attemptId: String, actions: List<PartyManualAction>) {
        if (!isCoordinator) return
        val peer = peers[clientId] ?: return
        runCatching {
            peer.send("MANUAL_REPLAY_BEGIN", listOf(attemptId))
            actions.forEach { action ->
                peer.send(
                    "MANUAL_CLICK",
                    listOf(
                        attemptId,
                        action.sequence.toString(),
                        action.slot.toString(),
                        action.button.toString(),
                        action.action,
                        action.intentVersion.toString(),
                        action.type.name,
                        action.targetSignature
                    )
                )
            }
        }
    }

    fun requestPromotion(clientId: String, handoffId: String): Boolean {
        if (!isCoordinator) return false
        val peer = peers[clientId] ?: return false
        runCatching { peer.send("PROMOTE", listOf(handoffId)) }.getOrElse { return false }
        return true
    }

    fun sendPromotionReady(handoffId: String, ready: Boolean, reason: String = "") {
        if (isCoordinator) return
        clientPeer?.send("PROMOTION_READY", listOf(handoffId, ready.toString(), reason))
    }

    fun commitPromotion(clientId: String, handoffId: String): Boolean {
        if (!isCoordinator || !peers.containsKey(clientId)) return false
        peers.forEach { (peerId, peer) ->
            runCatching {
                if (peerId == clientId) {
                    peer.send("PROMOTE_COMMIT", listOf(handoffId))
                } else {
                    peer.send("PROMOTE_WAIT", listOf(handoffId, clientId))
                }
            }
        }
        executor.execute {
            Thread.sleep(PROMOTION_DELAY_MS)
            demoteToClient()
        }
        return true
    }

    fun stop() {
        running = false
        isCoordinator = false
        serverSocket?.close()
        clientPeer?.close()
        peers.values.forEach(Peer::close)
        peers.clear()
        remoteClients.clear()
        executor.shutdownNow()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running && isCoordinator && serverSocket === socket) {
            try {
                val peerSocket = socket.accept()
                val peer = Peer(peerSocket, peerSocket.getOutputStream().bufferedWriter())
                executor.execute { readCoordinatorPeer(peer) }
            } catch (_: IOException) {
                if (running && isCoordinator) Thread.sleep(RETRY_DELAY_MS)
            }
        }
    }

    private fun readCoordinatorPeer(peer: Peer) {
        try {
            peer.socket.getInputStream().bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val decoded = PartyProtocol.decode(line) ?: return@forEach
                    when (decoded.first) {
                        "HELLO", "PRESENCE" -> {
                            val snapshot = snapshotFrom(decoded.second) ?: return@forEach
                            peer.clientId?.let { previous -> peers.remove(previous) }
                            peer.clientId = snapshot.clientId
                            val wasKnown = remoteClients.containsKey(snapshot.clientId)
                            peers[snapshot.clientId] = peer
                            remoteClients[snapshot.clientId] = snapshot
                            if (!wasKnown) onStatus("peer-connected:${snapshot.name}")
                            publishSnapshot()
                            broadcastSnapshot()
                        }
                        "READY" -> {
                            val clientId = peer.clientId ?: return@forEach
                            val attemptId = decoded.second.getOrNull(0) ?: return@forEach
                            val ready = decoded.second.getOrNull(1)?.toBooleanStrictOrNull() ?: false
                            val reason = decoded.second.getOrNull(2).orEmpty()
                            onReady(clientId, attemptId, ready, reason)
                        }
                        "PROMOTION_READY" -> {
                            val clientId = peer.clientId ?: return@forEach
                            val handoffId = decoded.second.getOrNull(0) ?: return@forEach
                            val ready = decoded.second.getOrNull(1)?.toBooleanStrictOrNull() ?: false
                            val reason = decoded.second.getOrNull(2).orEmpty()
                            onPromotionReady(clientId, handoffId, ready, reason)
                        }
                        "JOIN_RESULT" -> {
                            val clientId = peer.clientId ?: return@forEach
                            val attemptId = decoded.second.getOrNull(0) ?: return@forEach
                            val success = decoded.second.getOrNull(1)?.toBooleanStrictOrNull() ?: false
                            val reason = decoded.second.getOrNull(2).orEmpty()
                            onJoinResult(clientId, attemptId, success, reason)
                        }
                        "MANUAL_ACK" -> {
                            val clientId = peer.clientId ?: return@forEach
                            val attemptId = decoded.second.getOrNull(0) ?: return@forEach
                            val sequence = decoded.second.getOrNull(1)?.toIntOrNull() ?: return@forEach
                            onManualAck(clientId, attemptId, sequence)
                        }
                        "MANUAL_MENU_READY" -> {
                            val clientId = peer.clientId ?: return@forEach
                            val attemptId = decoded.second.getOrNull(0) ?: return@forEach
                            onManualMenuReady(clientId, attemptId)
                        }
                        "MANUAL_MENU_STATE" -> {
                            val clientId = peer.clientId ?: return@forEach
                            val attemptId = decoded.second.getOrNull(0) ?: return@forEach
                            val stage = decoded.second.getOrNull(1)
                                ?.let { runCatching { PartyMenuStage.valueOf(it) }.getOrNull() }
                                ?: PartyMenuStage.UNKNOWN
                            val intentVersion = decoded.second.getOrNull(2)?.toIntOrNull() ?: 0
                            onManualMenuState(clientId, attemptId, stage, intentVersion)
                        }
                        "MANUAL_RESYNC" -> {
                            val clientId = peer.clientId ?: return@forEach
                            val attemptId = decoded.second.getOrNull(0) ?: return@forEach
                            val sequence = decoded.second.getOrNull(1)?.toIntOrNull() ?: 0
                            onManualResync(clientId, attemptId, sequence)
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // The next heartbeat/reconnect will restore presence.
        } finally {
            peer.clientId?.let {
                val removed = peers.remove(it, peer)
                remoteClients.remove(it)
                if (removed) onStatus("peer-disconnected:$it")
            }
            publishSnapshot()
            broadcastSnapshot()
            peer.close()
        }
    }

    private fun connectLoop() {
        while (running && !isCoordinator) {
            if (promoteRequested) {
                promoteRequested = false
                Thread.sleep(PROMOTION_DELAY_MS)
                if (tryBecomeCoordinator()) return
            }
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), CONNECT_TIMEOUT_MS)
                val peer = Peer(socket, socket.getOutputStream().bufferedWriter())
                clientPeer = peer
                onStatus("connected")
                localSnapshot?.let { peer.send("HELLO", snapshotFields(it)) }
                socket.getInputStream().bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (running) handleClientFrame(PartyProtocol.decode(line))
                    }
                }
            } catch (_: IOException) {
                // Coordinator may not be running yet; retry on the next loop.
            } finally {
                clientPeer?.close()
                clientPeer = null
            }
            if (running && !isCoordinator && !tryBecomeCoordinator()) {
                Thread.sleep(RETRY_DELAY_MS)
            }
        }
    }

    private fun handleClientFrame(frame: Pair<String, List<String>>?) {
        if (frame == null) return
        when (frame.first) {
            "SYNC_BEGIN" -> remoteClients.clear()
            "CLIENT" -> snapshotFrom(frame.second)?.let { remoteClients[it.clientId] = it }
            "SYNC_END" -> publishSnapshot()
            "PREPARE" -> {
                val attemptId = frame.second.getOrNull(0) ?: return
                val serverKey = frame.second.getOrNull(1).orEmpty()
                onPrepare(attemptId, serverKey)
            }
            "PROMOTE" -> {
                frame.second.getOrNull(0)?.let(onPromoteRequest)
            }
            "PROMOTE_COMMIT" -> {
                promoteRequested = true
                clientPeer?.close()
            }
            "PROMOTE_WAIT" -> {
                forceClientUntil = System.currentTimeMillis() + HANDOFF_CLIENT_WAIT_MS
                clientPeer?.close()
            }
            "JOIN_GO" -> {
                val attemptId = frame.second.getOrNull(0) ?: return
                val command = frame.second.getOrNull(1) ?: "dchien"
                val gamemodeSlot = frame.second.getOrNull(2)?.toIntOrNull() ?: return
                val difficultySlot = frame.second.getOrNull(3)?.toIntOrNull() ?: return
                onJoinGo(attemptId, command, gamemodeSlot, difficultySlot)
            }
            "JOIN_CANCEL" -> {
                frame.second.getOrNull(0)?.let(onJoinCancel)
            }
            "MANUAL_COMMAND" -> {
                val attemptId = frame.second.getOrNull(0) ?: return
                val command = frame.second.getOrNull(1) ?: "dchien"
                onManualCommand(attemptId, command)
            }
            "PARTY_COMMAND" -> {
                frame.second.getOrNull(0)?.let(onPartyCommand)
            }
            "MANUAL_CLICK" -> {
                val attemptId = frame.second.getOrNull(0) ?: return
                val sequence = frame.second.getOrNull(1)?.toIntOrNull() ?: return
                val slot = frame.second.getOrNull(2)?.toIntOrNull() ?: return
                val button = frame.second.getOrNull(3)?.toIntOrNull() ?: return
                val action = frame.second.getOrNull(4) ?: return
                val intentVersion = frame.second.getOrNull(5)?.toIntOrNull() ?: 0
                val type = frame.second.getOrNull(6) ?: "SELECT_TYPE"
                val targetSignature = frame.second.getOrNull(7).orEmpty()
                onManualClick(attemptId, sequence, slot, button, action, intentVersion, type, targetSignature)
            }
            "MANUAL_ROOM_ACCEPTED" -> {
                val attemptId = frame.second.getOrNull(0) ?: return
                val sequence = frame.second.getOrNull(1)?.toIntOrNull() ?: return
                val slot = frame.second.getOrNull(2)?.toIntOrNull() ?: return
                val button = frame.second.getOrNull(3)?.toIntOrNull() ?: return
                val action = frame.second.getOrNull(4) ?: return
                val intentVersion = frame.second.getOrNull(5)?.toIntOrNull() ?: 0
                val type = frame.second.getOrNull(6) ?: "SELECT_TYPE"
                val targetSignature = frame.second.getOrNull(7).orEmpty()
                onManualRoomAccepted(
                    attemptId,
                    sequence,
                    slot,
                    button,
                    action,
                    intentVersion,
                    type,
                    targetSignature
                )
            }
            "MANUAL_REPLAY_BEGIN" -> {
                frame.second.getOrNull(0)?.let(onManualReplayBegin)
            }
        }
    }

    private fun tryBecomeCoordinator(): Boolean {
        if (!running || isCoordinator || System.currentTimeMillis() < forceClientUntil) return false
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), PORT))
            remoteClients.clear()
            serverSocket = socket
            isCoordinator = true
            executor.execute { acceptLoop(socket) }
            publishSnapshot()
            onStatus("primary")
            true
        } catch (_: BindException) {
            false
        } catch (_: IOException) {
            false
        }
    }

    private fun demoteToClient() {
        if (!running || !isCoordinator) return
        forceClientUntil = System.currentTimeMillis() + HANDOFF_CLIENT_WAIT_MS
        isCoordinator = false
        serverSocket?.close()
        serverSocket = null
        peers.values.forEach(Peer::close)
        peers.clear()
        remoteClients.clear()
        executor.execute { connectLoop() }
    }

    private fun broadcastSnapshot() {
        if (!isCoordinator) return
        val values = currentSnapshot()
        peers.values.forEach { peer ->
            peer.send("SYNC_BEGIN")
            values.forEach { peer.send("CLIENT", snapshotFields(it)) }
            peer.send("SYNC_END")
        }
    }

    private fun publishSnapshot() {
        onSnapshot(currentSnapshot())
    }

    private fun currentSnapshot(): List<PartyClientSnapshot> =
        (listOfNotNull(localSnapshot?.copy(isCoordinator = isCoordinator)) +
            remoteClients.values.map { it.copy(isCoordinator = it.isCoordinator) })
            .distinctBy { it.clientId }
            .sortedBy { it.name.lowercase() }

    private fun snapshotFields(snapshot: PartyClientSnapshot): List<String> = listOf(
        snapshot.clientId,
        snapshot.name,
        snapshot.state.name,
        snapshot.serverKey,
        snapshot.modVersion,
        snapshot.selected.toString(),
        snapshot.isCoordinator.toString(),
        snapshot.partyState.name,
        snapshot.partySelectedCount.toString(),
        snapshot.partyReadyCount.toString()
    )

    private fun snapshotFrom(fields: List<String>): PartyClientSnapshot? {
        if (fields.size < 5) return null
        val state = runCatching { PartyPresenceState.valueOf(fields[2]) }.getOrDefault(PartyPresenceState.CONNECTED)
        return PartyClientSnapshot(
            clientId = fields[0],
            name = fields[1],
            state = state,
            serverKey = fields[3],
            modVersion = fields[4],
            selected = fields.getOrNull(5)?.toBooleanStrictOrNull() ?: false,
            isCoordinator = fields.getOrNull(6)?.toBooleanStrictOrNull() ?: false,
            partyState = fields.getOrNull(7)
                ?.let { runCatching { net.eenda.rvladdons.feature.party.PartyAttemptState.valueOf(it) }.getOrNull() }
                ?: net.eenda.rvladdons.feature.party.PartyAttemptState.IDLE,
            partySelectedCount = fields.getOrNull(8)?.toIntOrNull() ?: 0,
            partyReadyCount = fields.getOrNull(9)?.toIntOrNull() ?: 0
        )
    }
}

