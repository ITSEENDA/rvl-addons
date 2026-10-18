package net.eenda.rvladdons.client.party

import net.eenda.rvladdons.core.RvlAddonsConfigStore
import net.eenda.rvladdons.feature.party.PartyAttemptState
import net.eenda.rvladdons.feature.party.PartyAutoRunMode
import net.eenda.rvladdons.feature.party.PartyJoinExecutionMode
import net.eenda.rvladdons.feature.party.PartyManualActionType
import net.eenda.rvladdons.feature.party.PartyMenuStage
import net.eenda.rvladdons.feature.party.PartyRoomRunMonitor
import net.minecraft.client.MinecraftClient
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.text.Text
import java.util.*

/** Owns the join attempt and all manual/automatic join actions. */
internal class PartyJoinCoordinator(
    private val clientId: () -> String,
    private val isCoordinator: () -> Boolean,
    private val participantIds: () -> Set<String>,
    private val creatorCandidates: () -> List<RoomCreatorCandidate>,
    private val creatorRotation: RoomCreatorRotation,
    private val transport: () -> LocalPartyTransport?,
    private val readinessClear: () -> Unit,
    private val readinessResume: () -> Unit,
    private val readinessPause: () -> Unit,
    private val activePartyClientIds: () -> Set<String>,
    private val setAttemptState: (PartyAttemptState) -> Unit,
    private val commitAllowance: (String, PartyAllowanceCost?) -> Unit,
    private val requestAllowanceRefresh: (String) -> Unit,
    private val unselectExhaustedClient: (String, String) -> Unit,
    private val localRunsRemaining: (String) -> Int?,
    private val onCoordinatorExhausted: (String) -> Unit,
    private val debugToast: (String) -> Unit
) {
    companion object {
        private const val JOIN_TIMEOUT_MS = 15_000L
    }

    val session = PartyJoinSession()
    val history = PartyActionHistory()
    private var activeRoomCreator: RoomCreatorCandidate? = null
    private val failedCreatorClientIds = HashSet<String>()
    private var automaticFollowersLaunched = false
    private var pendingAutomaticCommand: String? = null
    private var pendingAutomaticStartAt = 0L
    private var pendingAutomaticExcludedCreators: Set<String> = emptySet()
    private val roomRunMonitor = PartyRoomRunMonitor()

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
        val pendingCommand = pendingAutomaticCommand
        if (pendingCommand != null && now >= pendingAutomaticStartAt) {
            val excludedCreators = pendingAutomaticExcludedCreators
            pendingAutomaticCommand = null
            pendingAutomaticStartAt = 0L
            pendingAutomaticExcludedCreators = emptySet()
            beginAutomatic(pendingCommand, excludedCreators)
        }
        if (session.isActive && session.isExpired(now)) {
            if (isCoordinator() && session.attemptId.isNotBlank()) {
                transport()?.sendJoinCancel(activePartyClientIds(), session.attemptId, keepReady = true)
            }
            setAttemptState(PartyAttemptState.READY)
            readinessResume()
            overlay("Party join timed out")
            clear()
        }
    }

    fun begin(command: String, mode: PartyJoinExecutionMode) {
        if (mode == PartyJoinExecutionMode.AUTOMATIC) {
            failedCreatorClientIds.clear()
            beginAutomatic(command)
        } else {
            beginManual(command)
        }
    }

    private fun beginAutomatic(command: String, excludedCreators: Set<String> = emptySet()) {
        if (session.isActive) return
        val commandRoot = command.trim().removePrefix("/").substringBefore(' ').lowercase()
        failedCreatorClientIds.clear()
        failedCreatorClientIds.addAll(excludedCreators)
        val currentCandidates = creatorCandidates()
        participantIds().toList().forEach { participantId ->
            if (currentCandidates.firstOrNull { it.clientId == participantId }
                    ?.remainingFor(commandRoot) == 0
            ) {
                unselectExhaustedClient(participantId, commandRoot)
            }
        }
        if (currentCandidates.firstOrNull { it.clientId == clientId() }
                ?.remainingFor(commandRoot) == 0
        ) {
            onCoordinatorExhausted(commandRoot)
            return
        }
        val participants = participantIds()
        val now = System.currentTimeMillis()
        val partyClientIds = participants + clientId()
        val selection = creatorRotation.choose(
            creatorCandidates().filter { it.clientId in partyClientIds },
            now,
            excludedCreators,
            command
        ) ?: run {
            overlay("Party: no eligible room creator")
            return
        }
        if (selection.availableAt > now) {
            pendingAutomaticCommand = command
            pendingAutomaticStartAt = selection.availableAt
            pendingAutomaticExcludedCreators = excludedCreators
            val seconds = ((selection.availableAt - now + 999) / 1000).coerceAtLeast(1)
            overlay("Party: waiting ${seconds}s for ${selection.candidate.name}'s room cooldown")
            return
        }

        startAutomaticWithCreator(command, selection.candidate)
    }

    private fun startAutomaticWithCreator(command: String, creator: RoomCreatorCandidate) {
        if (session.isActive) return
        val client = MinecraftClient.getInstance()
        val attemptId = UUID.randomUUID().toString()
        val participants = participantIds()
        activeRoomCreator = creator
        automaticFollowersLaunched = false
        session.begin(
            attemptId,
            command.lowercase(),
            participants + clientId(),
            if (participants.isEmpty()) 0L else System.currentTimeMillis() + JOIN_TIMEOUT_MS
        )
        history.reset()
        if (participants.isNotEmpty()) readinessPause()
        setAttemptState(PartyAttemptState.JOINING)
        if (creator.clientId == clientId()) {
            overlay("Party: ${creator.name} is creating the room first")
            startAutomaticCreator(client, attemptId, command, creator)
            return
        }

        if (!sendAutomaticCreatorGo(attemptId, command, creator)) {
            setAttemptState(PartyAttemptState.READY)
            readinessResume()
            clear()
            overlay("Party: could not start room creator ${creator.name}")
            return
        }
        overlay("Party: ${creator.name} is creating the room first")
    }

    private fun startAutomaticCreator(
        client: MinecraftClient,
        attemptId: String,
        command: String,
        creator: RoomCreatorCandidate
    ) {
        val slots = automaticSlots(command)
        startLocalJoin(
            client,
            attemptId,
            command,
            slots.first,
            slots.second
        )
    }

    private fun sendAutomaticCreatorGo(
        attemptId: String,
        command: String,
        creator: RoomCreatorCandidate
    ): Boolean {
        val slots = automaticSlots(command)
        val sent = transport()?.sendJoinGo(
            setOf(creator.clientId),
            attemptId,
            command,
            slots.first,
            slots.second
        ) ?: 0
        return sent == 1
    }

    private fun startAutomaticFollowers(attemptId: String) {
        MinecraftClient.getInstance().execute {
            val creator = activeRoomCreator ?: return@execute
            if (!isCoordinator() || attemptId != session.attemptId || automaticFollowersLaunched) return@execute

            automaticFollowersLaunched = true
            creatorRotation.markCreated(creator.accountKey, System.currentTimeMillis())
            failedCreatorClientIds.clear()
            val followers = session.expectedClientIds - creator.clientId
            val localIsFollower = clientId() in followers
            val peers = followers - clientId()
            val slots = automaticSlots(session.commandRoot)
            val sent = if (peers.isEmpty()) 0 else {
                transport()?.sendJoinGo(
                    peers,
                    attemptId,
                    session.commandRoot,
                    slots.first,
                    slots.second
                ) ?: 0
            }
            if (sent != peers.size) {
                overlay("Party: JOIN_GO reached $sent/${peers.size} followers")
            }

            if (localIsFollower) {
                startLocalJoin(
                    MinecraftClient.getInstance(),
                    attemptId,
                    session.commandRoot,
                    slots.first,
                    slots.second
                )
            }
            debugToast("Party: creator joined; followers started")
        }
    }

    private fun retryAutomaticCreator(attemptId: String): Boolean {
        val creator = activeRoomCreator ?: return false
        failedCreatorClientIds.add(creator.clientId)
        val now = System.currentTimeMillis()
        val candidates = creatorCandidates().filter { it.clientId in session.expectedClientIds }
        val selection = creatorRotation.choose(
            candidates,
            now,
            failedCreatorClientIds,
            session.commandRoot
        ) ?: return false

        if (selection.availableAt > now) {
            val command = session.commandRoot
            val excluded = failedCreatorClientIds.toSet()
            setAttemptState(PartyAttemptState.READY)
            readinessResume()
            clear()
            pendingAutomaticCommand = command
            pendingAutomaticStartAt = selection.availableAt
            pendingAutomaticExcludedCreators = excluded
            val seconds = ((selection.availableAt - now + 999) / 1000).coerceAtLeast(1)
            overlay("Party: next creator ${selection.candidate.name} available in ${seconds}s")
            return true
        }

        activeRoomCreator = selection.candidate
        automaticFollowersLaunched = false
        val attemptId = session.attemptId
        val command = session.commandRoot
        val started = if (selection.candidate.clientId == clientId()) {
            startAutomaticCreator(MinecraftClient.getInstance(), attemptId, command, selection.candidate)
            true
        } else {
            sendAutomaticCreatorGo(attemptId, command, selection.candidate)
        }
        if (started) overlay("Party: retrying room creation with ${selection.candidate.name}")
        return started
    }

    fun beginManual(command: String) {
        if (session.isActive) return
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
        if (participants.isNotEmpty()) readinessPause()
        setAttemptState(PartyAttemptState.JOINING)
        if (participants.isEmpty()) {
            session.stopDeadline()
            debugToast("Party: starting solo manual join")
            startLocalManualJoin(client, attemptId, command)
            return
        }
        val sent = transport()?.sendManualCommand(participants, attemptId, command) ?: 0
        if (sent != participants.size) {
            transport()?.sendJoinCancel(participants, attemptId, keepReady = true)
            setAttemptState(PartyAttemptState.READY)
            readinessResume()
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
        targetSignature: String,
        allowanceCost: Int?
    ) {
        MinecraftClient.getInstance().execute {
            if (attemptId != session.attemptId) return@execute
            if (PartyJoinExecutor.queueManualRecoveryType(
                    sequence,
                    slot,
                    button,
                    action,
                    intentVersion,
                    targetSignature,
                    allowanceCost
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
            PartyJoinExecutor.captureRoomCost(slot)
            PartyJoinExecutor.observeLocalRoomSelection()
            return
        }
        val menuStage = PartyJoinExecutor.refreshManualMenuStage() ?: return
        if (menuStage == PartyMenuStage.ROOM_MENU) {
            PartyJoinExecutor.captureRoomCost(slot)
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
        if (participantIds().isNotEmpty()) {
            transport()?.sendManualClick(session.attemptId, manualAction)
        }
        debugToast("Party: sent ${type.name} #${manualAction.sequence} slot=$slot")
    }

    fun handleJoinCancel(attemptId: String, keepReady: Boolean) {
        MinecraftClient.getInstance().execute {
            if (attemptId != session.attemptId) return@execute
            cancel(if (keepReady) "dchien quit" else "party preparation cancelled", false)
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

            if (activeRoomCreator?.clientId == clientId && !automaticFollowersLaunched && session.isActive) {
                val candidates = creatorCandidates().filter { it.clientId in participantIds() + this.clientId() }
                val replacement = creatorRotation.choose(
                    candidates,
                    System.currentTimeMillis(),
                    commandRoot = session.commandRoot
                )
                if (replacement == null || replacement.availableAt > System.currentTimeMillis()) {
                    overlay("Party: room creator left; retry after cooldown")
                    transport()?.sendJoinCancel(
                        activePartyClientIds(),
                        session.attemptId,
                        keepReady = true
                    )
                    setAttemptState(PartyAttemptState.READY)
                    readinessResume()
                    clear()
                    return@execute
                }
                activeRoomCreator = replacement.candidate
                if (replacement.candidate.clientId == this.clientId()) {
                    startAutomaticCreator(
                        MinecraftClient.getInstance(),
                        session.attemptId,
                        session.commandRoot,
                        replacement.candidate
                    )
                } else if (!sendAutomaticCreatorGo(session.attemptId, session.commandRoot, replacement.candidate)) {
                    overlay("Party: could not start replacement room creator")
                    setAttemptState(PartyAttemptState.READY)
                    readinessResume()
                    clear()
                }
                return@execute
            }

            if (session.isActive && session.results.containsAll(session.expectedClientIds)) {
                setAttemptState(PartyAttemptState.READY)
                readinessResume()
                clear()
            }
        }
    }

    fun cancel(command: String, broadcast: Boolean) {
        val attemptId = session.attemptId
        val keepReady = isQuitCommand(command)
        PartyJoinExecutor.currentRun()
            ?.takeIf { it.roomSubmitted }
            ?.command
            ?.let(requestAllowanceRefresh)
        pendingAutomaticCommand = null
        pendingAutomaticStartAt = 0L
        pendingAutomaticExcludedCreators = emptySet()
        if (keepReady && broadcast && !isCoordinator()) {
            transport()?.sendJoinCancelRequest(attemptId)
        }
        if (broadcast && isCoordinator() && hasActiveSession()) {
            val peers = activePartyClientIds()
            transport()?.sendPartyCommand(peers, command)
            if (attemptId.isNotBlank()) transport()?.sendJoinCancel(peers, attemptId, keepReady)
        }
        PartyJoinExecutor.cancel(command)
        stopRoomMonitor()
        clear()
        if (keepReady) {
            readinessResume()
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
        pendingAutomaticCommand = null
        pendingAutomaticStartAt = 0L
        pendingAutomaticExcludedCreators = emptySet()
        activeRoomCreator = null
        automaticFollowersLaunched = false
        failedCreatorClientIds.clear()
    }

    fun handleJoinChat(message: String) {
        if (handleRoomFinishedChat(message)) return
        PartyJoinExecutor.handleChatMessage(message)
    }

    fun handleLocalMenuEscape() {
        if (isCoordinator()) {
            if (session.isActive) cancel("party menu closed", broadcast = true)
        } else if (PartyJoinExecutor.isRunning()) {
            cancel("dchien quit", broadcast = true)
        }
    }

    fun isJoinActive(): Boolean = session.isActive || PartyJoinExecutor.isRunning()

    fun stopRoomMonitor() {
        roomRunMonitor.stop()
    }

    private fun startLocalJoin(
        client: MinecraftClient,
        attemptId: String,
        command: String,
        typeSlot: Int,
        roomSlot: Int
    ) {
        PartyJoinExecutor.start(client, attemptId, command, typeSlot, roomSlot) { success, reason, cost ->
            commitAllowanceAndNotify(attemptId, success, reason, cost)
            if (isCoordinator()) {
                handleJoinResult(clientId(), attemptId, success, reason, cost)
            } else {
                transport()?.sendJoinResult(attemptId, success, reason)
                readinessResume()
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
        ) { success, reason, cost ->
            commitAllowanceAndNotify(attemptId, success, reason, cost)
            if (isCoordinator()) {
                handleJoinResult(clientId(), attemptId, success, reason, cost)
            } else {
                transport()?.sendJoinResult(attemptId, success, reason)
                readinessResume()
                setAttemptState(PartyAttemptState.READY)
                clear()
            }
        }
    }

    private fun commitAllowanceAndNotify(
        attemptId: String,
        success: Boolean,
        reason: String,
        cost: PartyAllowanceCost?
    ) {
        if (!success) {
            if (reason == "join result timeout") requestAllowanceRefresh(session.commandRoot)
            return
        }
        commitAllowance(attemptId, cost)
        if (cost == null) {
            requestAllowanceRefresh(session.commandRoot)
            debugToast("Party: /${session.commandRoot} cost not parsed; allowance unchanged")
            return
        }
        val remaining = localRunsRemaining(cost.commandRoot)
        val status = remaining?.let { "/${cost.commandRoot} -${cost.amount}; $it left" }
            ?: "/${cost.commandRoot} cost ${cost.amount}; remaining unknown"
        debugToast("Party: $status")
    }

    fun handleJoinResult(
        clientId: String,
        attemptId: String,
        success: Boolean,
        reason: String,
        roomCost: PartyAllowanceCost? = null
    ) {
        MinecraftClient.getInstance().execute {
            if (attemptId != session.attemptId || clientId !in session.expectedClientIds) return@execute
            if (success) {
                if (clientId != this.clientId() &&
                    creatorCandidates().firstOrNull { it.clientId == clientId }
                        ?.remainingFor(session.commandRoot) == 0
                ) {
                    unselectExhaustedClient(clientId, session.commandRoot)
                }
                val automaticCreator = activeRoomCreator?.takeIf {
                    it.clientId == clientId &&
                        RvlAddonsConfigStore.config.partyJoinExecutionMode == PartyJoinExecutionMode.AUTOMATIC
                }
                if (automaticCreator != null) {
                    if (session.expectedClientIds.size == 1) {
                        creatorRotation.markCreated(automaticCreator.accountKey, System.currentTimeMillis())
                        automaticFollowersLaunched = true
                    } else {
                        startAutomaticFollowers(attemptId)
                    }
                }
                if (isCoordinator() &&
                    clientId == this.clientId() &&
                    RvlAddonsConfigStore.config.partyJoinExecutionMode == PartyJoinExecutionMode.MANUAL
                ) {
                    history.latestTypeAction()?.let {
                        transport()?.sendManualRoomAccepted(participantIds(), attemptId, it, roomCost?.amount)
                    }
                }
                session.results.add(clientId)
                debugToast("Party: $clientId joined")
            } else {
                val autoMode = RvlAddonsConfigStore.config.partyJoinExecutionMode ==
                    PartyJoinExecutionMode.AUTOMATIC
                val allowanceExhausted = reason == PartyJoinExecutor.allowanceExhaustedReason(session.commandRoot)
                val activeCreator = activeRoomCreator?.clientId == clientId
                if (autoMode && allowanceExhausted && clientId != this.clientId()) {
                    unselectExhaustedClient(clientId, session.commandRoot)
                }
                if (isCoordinator() &&
                    autoMode &&
                    activeCreator && !automaticFollowersLaunched &&
                    retryAutomaticCreator(attemptId)
                ) return@execute
                if (autoMode && allowanceExhausted && !activeCreator &&
                    (clientId != this.clientId() || isCoordinator())
                ) {
                    if (clientId == this.clientId()) onCoordinatorExhausted(session.commandRoot)
                    session.results.add(clientId)
                    debugToast("Party: $clientId exhausted; skipped")
                } else {
                    if (autoMode && allowanceExhausted && clientId == this.clientId() && isCoordinator()) {
                        onCoordinatorExhausted(session.commandRoot)
                    }
                    session.failures[clientId] = reason.ifBlank { "join failed" }
                    debugToast("Party: $clientId failed to join")
                }
            }
            if (session.failures.isNotEmpty()) {
                transport()?.sendJoinCancel(activePartyClientIds(), session.attemptId, keepReady = true)
                setAttemptState(PartyAttemptState.READY)
                readinessResume()
                overlay("Party join failed: ${session.failures.values.first()}")
                clear()
            } else if (session.results.containsAll(session.expectedClientIds)) {
                startRoomMonitor(
                    session.commandRoot,
                    automatic = RvlAddonsConfigStore.config.partyJoinExecutionMode == PartyJoinExecutionMode.AUTOMATIC
                )
                setAttemptState(PartyAttemptState.READY)
                readinessResume()
                overlay("Party joined successfully")
                clear()
            }
        }
    }

    private fun hasActiveSession(): Boolean = session.isActive || PartyJoinExecutor.isRunning()

    private fun automaticSlots(command: String): Pair<Int, Int> {
        val config = RvlAddonsConfigStore.config
        return if (command.trim().removePrefix("/").substringBefore(' ').equals("tl", ignoreCase = true)) {
            config.partyTlAutomaticTypeSlot to config.partyTlAutomaticRoomSlot
        } else {
            config.partyAutomaticTypeSlot to config.partyAutomaticRoomSlot
        }
    }

    private fun startRoomMonitor(commandRoot: String, automatic: Boolean) {
        roomRunMonitor.start(commandRoot, automatic)
    }

    private fun handleRoomFinishedChat(message: String): Boolean {
        val finished = roomRunMonitor.completeIfMatched(message) ?: return false
        val root = finished.commandRoot
        val shouldLoop = finished.wasAutomatic &&
                RvlAddonsConfigStore.config.partyJoinExecutionMode == PartyJoinExecutionMode.AUTOMATIC &&
                RvlAddonsConfigStore.config.partyAutoRunMode == PartyAutoRunMode.LOOP &&
                isCoordinator()
        debugToast("Party: $root room finished")
        if (shouldLoop) {
            val config = RvlAddonsConfigStore.config
            val delaySeconds = if (root == "tl") {
                config.partyTlLoopDelaySeconds.coerceAtLeast(10)
            } else {
                config.partyDchienLoopDelaySeconds.coerceAtLeast(3)
            }
            pendingAutomaticCommand = root
            pendingAutomaticStartAt = System.currentTimeMillis() + delaySeconds * 1_000L
            pendingAutomaticExcludedCreators = emptySet()
            debugToast("Party: next loop in ${delaySeconds}s")
        }
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
