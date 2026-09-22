package net.eenda.rvladdons.client.party

import net.eenda.rvladdons.feature.party.PartyClientSnapshot
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

class LocalPartyTransport(private val onSnapshot: (List<PartyClientSnapshot>) -> Unit) {
    companion object {
        const val PORT = 28765
        private const val CONNECT_TIMEOUT_MS = 500
        private const val RETRY_DELAY_MS = 1_000L
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

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var isCoordinator: Boolean = false
        private set

    fun start() {
        if (running) return
        running = true
        try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), PORT))
            serverSocket = socket
            isCoordinator = true
            executor.execute { acceptLoop(socket) }
        } catch (_: BindException) {
            isCoordinator = false
            executor.execute { connectLoop() }
        } catch (_: IOException) {
            isCoordinator = false
            executor.execute { connectLoop() }
        }
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

    fun stop() {
        running = false
        serverSocket?.close()
        clientPeer?.close()
        peers.values.forEach(Peer::close)
        peers.clear()
        remoteClients.clear()
        executor.shutdownNow()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            try {
                val peerSocket = socket.accept()
                val peer = Peer(peerSocket, peerSocket.getOutputStream().bufferedWriter())
                executor.execute { readCoordinatorPeer(peer) }
            } catch (_: IOException) {
                if (running) Thread.sleep(RETRY_DELAY_MS)
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
                            peers[snapshot.clientId] = peer
                            remoteClients[snapshot.clientId] = snapshot
                            publishSnapshot()
                            broadcastSnapshot()
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // The next heartbeat/reconnect will restore presence.
        } finally {
            peer.clientId?.let { remoteClients.remove(it); peers.remove(it, peer) }
            publishSnapshot()
            broadcastSnapshot()
            peer.close()
        }
    }

    private fun connectLoop() {
        while (running) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), CONNECT_TIMEOUT_MS)
                val peer = Peer(socket, socket.getOutputStream().bufferedWriter())
                clientPeer = peer
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
            if (running) Thread.sleep(RETRY_DELAY_MS)
        }
    }

    private fun handleClientFrame(frame: Pair<String, List<String>>?) {
        if (frame == null) return
        when (frame.first) {
            "SYNC_BEGIN" -> remoteClients.clear()
            "CLIENT" -> snapshotFrom(frame.second)?.let { remoteClients[it.clientId] = it }
            "SYNC_END" -> publishSnapshot()
        }
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
        (listOfNotNull(localSnapshot) + remoteClients.values)
            .distinctBy { it.clientId }
            .sortedBy { it.name.lowercase() }

    private fun snapshotFields(snapshot: PartyClientSnapshot): List<String> = listOf(
        snapshot.clientId,
        snapshot.name,
        snapshot.state.name,
        snapshot.serverKey,
        snapshot.modVersion,
        snapshot.selected.toString()
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
            selected = fields.getOrNull(5)?.toBooleanStrictOrNull() ?: false
        )
    }
}
