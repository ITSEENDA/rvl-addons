package net.eenda.rvladdons.client.party

import net.eenda.rvladdons.core.RvlAddonsConfigStore
import net.eenda.rvladdons.core.RvlTextMatcher
import net.eenda.rvladdons.feature.party.PartyAttemptState
import net.eenda.rvladdons.feature.party.PartyJoinExecutionMode
import net.eenda.rvladdons.feature.party.PartyManualActionType
import net.eenda.rvladdons.feature.party.PartyMenuStage
import net.minecraft.client.MinecraftClient
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.text.Text
import java.util.*

/** Owns the join attempt and all manual/automatic join actions. */
internal class PartyJoinCoordinator(
    private val clientId: () -> String,
    private val isCoordinator: () -> Boolean,
    private val participantIds: () -> Set<String>,
    private val transport: () -> LocalPartyTransport?,
    private val readinessClear: () -> Unit,
    private val readinessPause: () -> Unit,
    private val activePartyClientIds: () -> Set<String>,
    private val setAttemptState: (PartyAttemptState) -> Unit,
    private val debugToast: (String) -> Unit
) {
    companion object {
        private const val JOIN_TIMEOUT_MS = 15_000L
    }

    val session = PartyJoinSession()
    val history = PartyActionHistory()
    private var activeRoomCommandRoot: String? = null

    fun handlePartyCommand(command: String) {
        MinecraftClient.getInstance().execute {
            val normalized = command.trim().removePrefix("/").trim()
            val root = normalized.split(Regex("\\s+"), limit = 2).firstOrNull()?.lowercase()
            if (root != "dchien" && root != "tl") return@execute
            PartyJoinExecutor.sendCommand(normalized)
            if (normalized.split(Regex("\\s+"), limit = 2).getOrNull(1)
                    ?.equals("quit", ignoreCase = true) == true
            ) {
                cancel(normalized, false)
            }
        }
    }

    fun tick(now: Long) {
        if (session.isActive && session.isExpired(now)) {
            setAttemptState(PartyAttemptState.READY)
            overlay("Party join timed out")
            clear()
        }
    }

    fun begin(command: String, mode: PartyJoinExecutionMode) {
        if (mode == PartyJoinExecutionMode.AUTOMATIC) {
            beginAutomatic(command)
        } else {
            beginManual(command)
        }
    }

    private fun beginAutomatic(command: String) {
        if (session.isActive || participantIds().isEmpty()) return
        val client = MinecraftClient.getInstance()
        val attemptId = UUID.randomUUID().toString()
        val participants = participantIds()
        session.begin(
            attemptId,
            command.lowercase(),
            participants + clientId(),
            System.currentTimeMillis() + JOIN_TIMEOUT_MS
        )
        history.reset()
        readinessPause()
        setAttemptState(PartyAttemptState.JOINING)
        val sent = transport()?.sendJoinGo(
            participants,
            attemptId,
            command,
            RvlAddonsConfigStore.config.partyAutomaticTypeSlot,
            RvlAddonsConfigStore.config.partyAutomaticRoomSlot
        ) ?: 0
        if (sent != participants.size) {
            setAttemptState(PartyAttemptState.READY)
            clear()
            overlay("Party: JOIN_GO reached $sent/${participants.size} clients")
            return
        }
        overlay("Party: JOIN_GO sent to $sent clients")
        startLocalJoin(
            client,
            attemptId,
            command,
            RvlAddonsConfigStore.config.partyAutomaticTypeSlot,
            RvlAddonsConfigStore.config.partyAutomaticRoomSlot
        )
    }

    fun beginManual(command: String) {
        if (session.isActive || participantIds().isEmpty()) return
        val client = MinecraftClient.getInstance()
        val attemptId = UUID.randomUUID().toString()
        val participants = participantIds()
        session.begin(
            attemptId,
            command.lowercase(),
            participants + clientId(),
            System.currentTimeMillis() + JOIN_TIMEOUT_MS
        )
        history.reset()
        readinessPause()
        setAttemptState(PartyAttemptState.JOINING)
        val sent = transport()?.sendManualCommand(participants, attemptId, command) ?: 0
        if (sent != participants.size) {
            setAttemptState(PartyAttemptState.READY)
            clear()
            overlay("Party: manual command reached $sent/${participants.size} clients")
            return
        }
        debugToast("Party: manual command sent to $sent clients")
        debugToast("Party: waiting for all client menus")
    }

    fun handleJoinGo(
        attemptId: String,
        command: String,
        typeSlot: Int,
        roomSlot: Int
    ) {
        MinecraftClient.getInstance().execute {
            if (session.attemptId == attemptId) return@execute
            if (session.isActive || PartyJoinExecutor.isRunning()) {
                PartyJoinExecutor.cancel("superseded by new join attempt")
                clear()
            }
            if (session.isActive || PartyJoinExecutor.isRunning()) {
                transport()?.sendJoinResult(attemptId, false, "join executor busy")
                return@execute
            }
            session.begin(
                attemptId,
                command.trim().removePrefix("/").trim()
                    .split(Regex("\\s+"), limit = 2).first().lowercase(),
                setOf(clientId()),
                System.currentTimeMillis() + JOIN_TIMEOUT_MS
            )
            setAttemptState(PartyAttemptState.JOINING)
            debugToast("Party: JOIN_GO received; opening /dchien")
            overlay("Party: JOIN_GO received; opening /dchien")
            startLocalJoin(MinecraftClient.getInstance(), attemptId, command, typeSlot, roomSlot)
        }
    }

    fun handleManualCommand(attemptId: String, command: String) {
        MinecraftClient.getInstance().execute {
            if (session.attemptId == attemptId) return@execute
            if (session.isActive || PartyJoinExecutor.isRunning()) {
                PartyJoinExecutor.cancel("superseded by new manual attempt")
                clear()
            }
            if (session.isActive || PartyJoinExecutor.isRunning()) {
                transport()?.sendJoinResult(attemptId, false, "join executor busy")
                return@execute
            }
            session.begin(
                attemptId,
                command.trim().removePrefix("/").trim()
                    .split(Regex("\\s+"), limit = 2).first().lowercase(),
                setOf(clientId()),
                0L
            )
            setAttemptState(PartyAttemptState.JOINING)
            debugToast("Party: manual command received")
            startLocalManualJoin(MinecraftClient.getInstance(), attemptId, command)
        }
    }

    fun handleManualMenuReady(clientId: String, attemptId: String) {
        MinecraftClient.getInstance().execute {
            if (!isCoordinator() || attemptId != session.attemptId || clientId !in participantIds()) return@execute
            session.readyMenuClientIds.add(clientId)
            debugToast("Party: $clientId menu ready (${session.readyMenuClientIds.size}/${participantIds().size})")
            if (!session.readyMenuClientIds.containsAll(participantIds()) || PartyJoinExecutor.isRunning()) return@execute
            session.stopDeadline()
            debugToast("Party: all menus ready; starting coordinator menu")
            startLocalManualJoin(MinecraftClient.getInstance(), attemptId, session.commandRoot)
        }
    }

    fun handleManualMenuState(clientId: String, attemptId: String, stage: PartyMenuStage, intentVersion: Int) {
        MinecraftClient.getInstance().execute {
            if (!isCoordinator() || attemptId != session.attemptId || clientId !in participantIds()) return@execute
            session.menuStages[clientId] = stage
            debugToast("Party: $clientId stage=${stage.name} intent=$intentVersion")
        }
    }

    fun handleManualClick(
        attemptId: String,
        sequence: Int,
        slot: Int,
        button: Int,
        action: String,
        intentVersion: Int,
        type: String,
        targetSignature: String
    ) {
        MinecraftClient.getInstance().execute {
            if (attemptId != session.attemptId) return@execute
            if (PartyJoinExecutor.queueManualClick(
                    sequence,
                    slot,
                    button,
                    action,
                    intentVersion,
                    type,
                    targetSignature
                )
            ) {
                debugToast("Party: received $type #$sequence intent=$intentVersion slot=$slot")
            }
        }
    }

    fun handleManualRoomAccepted(
        attemptId: String,
        sequence: Int,
        slot: Int,
        button: Int,
        action: String,
        intentVersion: Int,
        type: String,
        targetSignature: String
    ) {
        MinecraftClient.getInstance().execute {
            if (attemptId != session.attemptId) return@execute
            if (PartyJoinExecutor.queueManualRecoveryType(
                    sequence,
                    slot,
                    button,
                    action,
                    intentVersion,
                    targetSignature
                )
            ) {
                debugToast("Party: room accepted; recovery type queued from #$sequence")
            }
        }
    }

    fun handleManualReplayBegin(attemptId: String) {
        MinecraftClient.getInstance().execute {
            if (attemptId == session.attemptId) {
                debugToast("Party: replay history received")
                PartyJoinExecutor.resetManualReplay()
            }
        }
    }

    fun handleManualAck(clientId: String, attemptId: String, sequence: Int) {
        if (attemptId == session.attemptId) debugToast("Party: $clientId ACK action #$sequence")
    }

    fun handleManualResync(clientId: String, attemptId: String, lastSequence: Int) {
        if (attemptId != session.attemptId || !isCoordinator()) return
        val actions = history.after(lastSequence)
        transport()?.sendManualHistory(clientId, attemptId, actions)
        debugToast("Party: replaying ${actions.size} actions to $clientId")
    }

    fun onLocalSlotAction(slot: Int, button: Int, action: SlotActionType) {
        if (!session.isActive || !isCoordinator() || !PartyJoinExecutor.isManualModeActive()) return
        if (PartyJoinExecutor.isRoomSelectionExpected()) {
            PartyJoinExecutor.observeLocalRoomSelection()
            return
        }
        val menuStage = PartyJoinExecutor.refreshManualMenuStage() ?: return
        if (menuStage == PartyMenuStage.ROOM_MENU) {
            PartyJoinExecutor.observeLocalRoomSelection()
            return
        }
        if (menuStage != PartyMenuStage.TYPE_MENU) return
        val type = PartyManualActionType.SELECT_TYPE
        val manualAction = history.record(
            slot,
            button,
            action,
            type,
            if (type == PartyManualActionType.SELECT_TYPE) PartyJoinExecutor.manualSlotSignature(slot) else ""
        ) ?: run {
            debugToast("Party: ignored duplicate SELECT_ROOM click")
            return
        }
        PartyJoinExecutor.recordManualCoordinatorAction(
            manualAction.sequence,
            manualAction.intentVersion,
            manualAction.type,
            manualAction.targetSignature
        )
        transport()?.sendManualClick(session.attemptId, manualAction)
        debugToast("Party: sent ${type.name} #${manualAction.sequence} slot=$slot")
    }

    fun handleJoinCancel(attemptId: String) {
        MinecraftClient.getInstance().execute {
            if (attemptId != session.attemptId) return@execute
            cancel("dchien quit", false)
        }
    }

    fun handleParticipantQuit(clientId: String, attemptId: String) {
        MinecraftClient.getInstance().execute {
            if (!isCoordinator()) return@execute
            if (attemptId.isNotBlank() && attemptId != session.attemptId) return@execute

            session.expectedClientIds.remove(clientId)
            session.results.remove(clientId)
            session.failures.remove(clientId)
            session.readyMenuClientIds.remove(clientId)
            session.menuStages.remove(clientId)

            if (session.isActive && session.results.containsAll(session.expectedClientIds)) {
                setAttemptState(PartyAttemptState.READY)
                clear()
            }
        }
    }

    fun cancel(command: String, broadcast: Boolean) {
        val attemptId = session.attemptId
        val keepReady = isQuitCommand(command)
        if (keepReady && broadcast && !isCoordinator()) {
            transport()?.sendJoinCancelRequest(attemptId)
        }
        if (broadcast && isCoordinator() && hasActiveSession()) {
            val peers = activePartyClientIds()
            transport()?.sendPartyCommand(peers, command)
            if (attemptId.isNotBlank()) transport()?.sendJoinCancel(peers, attemptId)
        }
        PartyJoinExecutor.cancel(command)
        stopRoomMonitor()
        clear()
        if (keepReady) {
            setAttemptState(PartyAttemptState.READY)
        } else {
            readinessClear()
            setAttemptState(PartyAttemptState.IDLE)
        }
        debugToast("Party: join cancelled")
    }

    fun clear() {
        session.reset()
        history.reset()
    }

    fun handleJoinChat(message: String) {
        if (handleRoomFinishedChat(message)) return
        PartyJoinExecutor.handleChatMessage(message)
    }

    fun handleLocalMenuEscape() {
        if (!isCoordinator() || !session.isActive || !PartyJoinExecutor.isRunning()) return
        cancel("party menu closed", broadcast = true)
    }

    fun stopRoomMonitor() {
        activeRoomCommandRoot = null
    }

    private fun startLocalJoin(
        client: MinecraftClient,
        attemptId: String,
        command: String,
        typeSlot: Int,
        roomSlot: Int
    ) {
        PartyJoinExecutor.start(client, attemptId, command, typeSlot, roomSlot) { success, reason ->
            if (success) startRoomMonitor(session.commandRoot)
            if (isCoordinator()) {
                handleJoinResult(clientId(), attemptId, success, reason)
            } else {
                transport()?.sendJoinResult(attemptId, success, reason)
                setAttemptState(PartyAttemptState.READY)
                clear()
            }
        }
    }

    private fun startLocalManualJoin(client: MinecraftClient, attemptId: String, command: String) {
        PartyJoinExecutor.startManual(
            client,
            attemptId,
            command,
            onActionApplied = { sequence -> transport()?.sendManualAck(attemptId, sequence) },
            onMenuReady = { transport()?.sendManualMenuReady(attemptId) },
            onMenuStage = { stage, intentVersion ->
                if (isCoordinator()) history.onMenuStage(stage)
                transport()?.sendManualMenuState(attemptId, stage, intentVersion)
            },
            onResyncRequested = { lastSequence ->
                if (isCoordinator()) {
                    PartyJoinExecutor.resetManualReplay()
                    history.all().forEach { action ->
                        PartyJoinExecutor.queueManualClick(
                            action.sequence,
                            action.slot,
                            action.button,
                            action.action,
                            action.intentVersion,
                            action.type.name,
                            action.targetSignature
                        )
                    }
                    participantIds().forEach { peerId ->
                        transport()?.sendManualHistory(
                            peerId,
                            attemptId,
                            history.all()
                        )
                    }
                } else {
                    transport()?.requestManualResync(attemptId, lastSequence)
                }
            },
            onPhase = { phase -> debugToast("Party: $phase") }
        ) { success, reason ->
            if (success) startRoomMonitor(session.commandRoot)
            if (isCoordinator()) {
                handleJoinResult(clientId(), attemptId, success, reason)
            } else {
                transport()?.sendJoinResult(attemptId, success, reason)
                setAttemptState(PartyAttemptState.READY)
                clear()
            }
        }
    }

    fun handleJoinResult(clientId: String, attemptId: String, success: Boolean, reason: String) {
        MinecraftClient.getInstance().execute {
            if (attemptId != session.attemptId || clientId !in session.expectedClientIds) return@execute
            if (success) {
                if (isCoordinator() &&
                    clientId == this.clientId() &&
                    RvlAddonsConfigStore.config.partyJoinExecutionMode == PartyJoinExecutionMode.MANUAL
                ) {
                    history.latestTypeAction()
                        ?.let { transport()?.sendManualRoomAccepted(participantIds(), attemptId, it) }
                }
                session.results.add(clientId)
                debugToast("Party: $clientId joined")
            } else {
                session.failures[clientId] = reason.ifBlank { "join failed" }
                debugToast("Party: $clientId failed to join")
            }
            if (session.failures.isNotEmpty()) {
                setAttemptState(PartyAttemptState.READY)
                overlay("Party join failed: ${session.failures.values.first()}")
                clear()
            } else if (session.results.containsAll(session.expectedClientIds)) {
                setAttemptState(PartyAttemptState.READY)
                overlay("Party joined successfully")
                clear()
            }
        }
    }

    private fun hasActiveSession(): Boolean = session.isActive || PartyJoinExecutor.isRunning()

    private fun startRoomMonitor(commandRoot: String) {
        activeRoomCommandRoot = commandRoot.trim().removePrefix("/").lowercase()
    }

    private fun handleRoomFinishedChat(message: String): Boolean {
        val root = activeRoomCommandRoot ?: return false
        val finished = when (root) {
            "dchien" ->
                RvlTextMatcher.contains(message, "VƯỢT THÀNH CÔNG ĐOÀN CHIẾN") ||
                        RvlTextMatcher.contains(message, "THẢO PHẠT ĐOÀN CHIẾN THÀNH CÔNG")

            "tl" -> RvlTextMatcher.contains(message, "VƯỢT THANH LÂN THÀNH CÔNG")
            else -> false
        }
        if (!finished) return false
        activeRoomCommandRoot = null
        debugToast("Party: $root room finished")
        return true
    }

    private fun isQuitCommand(command: String): Boolean {
        val parts = command.trim().removePrefix("/").split(Regex("\\s+"), limit = 2)
        return parts.getOrNull(1)?.equals("quit", ignoreCase = true) == true
    }

    private fun overlay(message: String) {
        MinecraftClient.getInstance().inGameHud.setOverlayMessage(Text.literal(message), false)
    }
}
