package net.eenda.rvladdons.client.party

import dev.tako.libs.client.ui.feedback.TakoToastManager
import net.eenda.rvladdons.core.ModEnabledState
import net.eenda.rvladdons.core.RvlAddonsConfigStore
import net.eenda.rvladdons.feature.party.PartyAttemptState
import net.eenda.rvladdons.feature.party.PartyClientSnapshot
import net.eenda.rvladdons.feature.party.PartyJoinExecutionMode
import net.eenda.rvladdons.feature.party.PartyManualAction
import net.eenda.rvladdons.feature.party.PartyManualActionType
import net.eenda.rvladdons.feature.party.PartyMenuStage
import net.eenda.rvladdons.feature.party.PartyPresenceState
import net.eenda.rvladdons.feature.party.PartyRegistry
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import net.minecraft.screen.slot.SlotActionType
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object LocalPartyController {
    private val synchronizedCommandRoots = setOf("dchien", "tl")
    private const val ID_FILE = "rvl-addons-party.properties"
    private const val PREPARE_TIMEOUT_MS = 2_000L
    private const val JOIN_TIMEOUT_MS = 15_000L
    private const val DEFAULT_GAMEMODE_SLOT = 11
    private const val DEFAULT_DIFFICULTY_SLOT = 11
    private val registry = PartyRegistry()
    private var transport: LocalPartyTransport? = null
    private var clientId = ""
    private var attemptState = PartyAttemptState.IDLE
    private var lastLocalUpdate = 0L
    private val selectedClientIds = ConcurrentHashMap.newKeySet<String>()
    private var activeAttemptId = ""
    private var preparingClientIds = emptySet<String>()
    private val readyClientIds = ConcurrentHashMap.newKeySet<String>()
    private val notReadyReasons = ConcurrentHashMap<String, String>()
    private var prepareDeadline = 0L
    private var pendingHandoffId = ""
    private var pendingPromotionClientId = ""
    private var promotionDeadline = 0L
    private var joinAttemptId = ""
    private var joinExpectedClientIds = emptySet<String>()
    private val joinResults = ConcurrentHashMap.newKeySet<String>()
    private val joinFailures = ConcurrentHashMap<String, String>()
    private var joinDeadline = 0L
    private val manualHistory = LinkedHashMap<Int, PartyManualAction>()
    private val manualReadyClientIds = ConcurrentHashMap.newKeySet<String>()
    private val manualClientStages = ConcurrentHashMap<String, PartyMenuStage>()
    private var activeCommandRoot = ""
    private var manualIntentVersion = 0
    private var manualRoomActionPending = false
    private var manualSequence = 0

    fun initialize() {
        if (!ModEnabledState.isEnabled()) return
        transport?.let { current ->
            if (current.running) return
            current.stop()
            transport = null
        }
        clientId = loadClientId(FabricLoader.getInstance().configDir.resolve(ID_FILE))
        transport = LocalPartyTransport(
            onSnapshot = ::replaceSnapshots,
            onPrepare = ::handlePrepareRequest,
            onReady = ::handleReadyResponse,
            onPromoteRequest = ::handlePromoteRequest,
            onPromotionReady = ::handlePromotionReady,
            onJoinGo = ::handleJoinGo,
            onJoinResult = ::handleJoinResult,
            onPartyCommand = ::handlePartyCommand,
            onManualCommand = ::handleManualCommand,
            onManualMenuReady = ::handleManualMenuReady,
            onManualMenuState = ::handleManualMenuState,
            onManualClick = ::handleManualClick,
            onManualRoomAccepted = ::handleManualRoomAccepted,
            onManualReplayBegin = ::handleManualReplayBegin,
            onManualAck = ::handleManualAck,
            onManualResync = ::handleManualResync,
            onJoinCancel = ::handleJoinCancel,
            onStatus = ::handleTransportStatus
        ).also { it.start() }
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled) {
            lastLocalUpdate = 0L
            initialize()
            MinecraftClient.getInstance().let { client ->
                if (client.player != null) publishPresence(client, PartyPresenceState.IN_SERVER)
            }
            return
        }
        PartyJoinExecutor.cancel("party disabled")
        transport?.stop()
        transport = null
        attemptState = PartyAttemptState.IDLE
        selectedClientIds.clear()
        clearAttempt()
        clearPromotion()
        clearJoin()
        registry.replace(emptyList())
    }

    fun tick(client: MinecraftClient) {
        PartyJoinExecutor.tick()
        val now = System.currentTimeMillis()
        if (now - lastLocalUpdate < 500L) return
        lastLocalUpdate = now
        val state = if (client.world != null && client.player != null) {
            PartyPresenceState.IN_SERVER
        } else {
            PartyPresenceState.MAIN_MENU
        }
        publishPresence(client, state)
        updateAttemptState(client)
        if (pendingHandoffId.isNotBlank() && now >= promotionDeadline) {
            clearPromotion()
            client.inGameHud.setOverlayMessage(Text.literal("Party: primary transfer timed out"), false)
        }
        if (joinAttemptId.isNotBlank() && joinDeadline > 0L && now >= joinDeadline) {
            attemptState = PartyAttemptState.FAILED
            client.inGameHud.setOverlayMessage(Text.literal("Party join timed out"), false)
            clearJoin()
        }
    }

    fun onJoin(client: MinecraftClient) {
        publishPresence(client, PartyPresenceState.IN_SERVER)
        updateAttemptState(client)
    }

    fun onDisconnect(client: MinecraftClient) {
        attemptState = PartyAttemptState.IDLE
        selectedClientIds.clear()
        clearAttempt()
        clearPromotion()
        PartyJoinExecutor.cancel("disconnected")
        clearJoin()
        publishPresence(client, PartyPresenceState.DISCONNECTED)
    }

    fun isEligible(snapshot: PartyClientSnapshot, client: MinecraftClient): Boolean =
        snapshot.clientId != clientId &&
                snapshot.state == PartyPresenceState.IN_SERVER &&
                snapshot.serverKey == currentServerKey(client)

    private fun publishPresence(client: MinecraftClient, state: PartyPresenceState) {
        val currentTransport = transport ?: return
        val selected = selectedClientIds.contains(clientId)
        currentTransport.updateLocal(
            PartyClientSnapshot(
                clientId = clientId,
                name = client.player?.name?.string ?: "Client-${clientId.take(6)}",
                state = state,
                serverKey = currentServerKey(client),
                modVersion = "rvl-addons",
                selected = selected,
                partyState = attemptState,
                partySelectedCount = selectedClients().size,
                partyReadyCount = readyCount(selectedClients(), client)
            )
        )
    }

    fun clients(): List<PartyClientSnapshot> = registry.snapshot().filterNot { it.clientId == clientId }

    fun coordinatorName(): String = registry.snapshot()
        .firstOrNull { it.isCoordinator }
        ?.name
        ?: if (isCoordinator()) "This client" else "Unknown"

    fun selectedCount(): Int = clients().count { it.selected }

    fun currentState(): PartyAttemptState = attemptState

    fun isCoordinator(): Boolean = transport?.isCoordinator == true

    fun handleOutgoingCommand(command: String): Boolean {
        if (!ModEnabledState.isEnabled()) return true
        if (PartyJoinExecutor.isDispatchingCommand()) return true
        val normalizedCommand = command.trim().removePrefix("/").trim()
        val commandParts = normalizedCommand.split(Regex("\\s+"), limit = 2)
        val root = commandParts.firstOrNull()?.lowercase().orEmpty()
        if (root !in synchronizedCommandRoots) return true
        if (commandParts.getOrNull(1)?.equals("quit", ignoreCase = true) == true) {
            cancelJoinFromCommand(normalizedCommand, broadcast = true)
            return true
        }
        if (commandParts.size == 1) {
            if (!isCoordinator()) return true
            if (selectedClients().isNotEmpty() && attemptState != PartyAttemptState.READY) {
                MinecraftClient.getInstance().inGameHud.setOverlayMessage(
                    Text.literal("Party: waiting for all clients to become READY"),
                    false
                )
                return false
            }
            if (selectedClients().isEmpty()) return true
            debugToast("Party: /$root intercepted")
            if (RvlAddonsConfigStore.config.partyJoinExecutionMode == PartyJoinExecutionMode.MANUAL) {
                beginManualJoin(root)
            } else {
                beginJoin(root, DEFAULT_GAMEMODE_SLOT, DEFAULT_DIFFICULTY_SLOT)
            }
            return false
        }
        if (isCoordinator() && hasActivePartySession()) {
            val peers = activePartyClientIds()
            if (peers.isNotEmpty()) {
                val sent = transport?.sendPartyCommand(peers, normalizedCommand) ?: 0
                debugToast("Party: command '$normalizedCommand' sent to $sent/${peers.size}")
            }
        }
        return true
    }

    fun onServerMenuOpened() {
        MinecraftClient.getInstance().execute {
            PartyJoinExecutor.onServerMenuOpened()
        }
    }

    fun promoteClient(clientId: String) {
        val client = MinecraftClient.getInstance()
        if (!isCoordinator()) {
            client.inGameHud.setOverlayMessage(
                Text.literal("Party: only the coordinator can transfer primary"),
                false
            )
            return
        }
        if (attemptState == PartyAttemptState.PREPARING ||
            attemptState == PartyAttemptState.READY ||
            attemptState == PartyAttemptState.JOINING ||
            PartyJoinExecutor.isRunning()
        ) {
            client.inGameHud.setOverlayMessage(
                Text.literal("Party: finish or cancel the current join before transferring primary"),
                false
            )
            debugToast("Party: primary transfer blocked while join is active")
            return
        }
        if (clientId == this.clientId || pendingHandoffId.isNotBlank()) return
        if (registry.get(clientId) == null) return

        val handoffId = UUID.randomUUID().toString()
        if (transport?.requestPromotion(clientId, handoffId) != true) {
            client.inGameHud.setOverlayMessage(
                Text.literal("Party: selected client is not connected"),
                false
            )
            return
        }
        clearAttempt()
        attemptState = PartyAttemptState.IDLE
        pendingHandoffId = handoffId
        pendingPromotionClientId = clientId
        promotionDeadline = System.currentTimeMillis() + PREPARE_TIMEOUT_MS
        client.inGameHud.setOverlayMessage(Text.literal("Party: transferring primary"), false)
    }

    private fun beginJoin(command: String, gamemodeSlot: Int, difficultySlot: Int) {
        if (joinAttemptId.isNotBlank() || preparingClientIds.isEmpty()) return
        val client = MinecraftClient.getInstance()
        val attemptId = activeAttemptId.ifBlank { UUID.randomUUID().toString() }
        joinAttemptId = attemptId
        joinExpectedClientIds = preparingClientIds + clientId
        activeCommandRoot = command.lowercase()
        joinResults.clear()
        joinFailures.clear()
        joinDeadline = if (RvlAddonsConfigStore.config.partyJoinExecutionMode == PartyJoinExecutionMode.MANUAL) {
            0L
        } else {
            System.currentTimeMillis() + JOIN_TIMEOUT_MS
        }
        manualHistory.clear()
        manualSequence = 0
        attemptState = PartyAttemptState.JOINING
        val sent = transport?.sendJoinGo(
            preparingClientIds,
            attemptId,
            command,
            gamemodeSlot,
            difficultySlot
        ) ?: 0
        if (sent != preparingClientIds.size) {
            clearAttempt()
            attemptState = PartyAttemptState.IDLE
            clearJoin()
            client.inGameHud.setOverlayMessage(
                Text.literal("Party: JOIN_GO reached $sent/${preparingClientIds.size} clients"),
                false
            )
            return
        }
        client.inGameHud.setOverlayMessage(Text.literal("Party: JOIN_GO sent to $sent clients"), false)
        startLocalJoin(client, attemptId, command, gamemodeSlot, difficultySlot)
    }

    private fun beginManualJoin(command: String) {
        if (joinAttemptId.isNotBlank() || preparingClientIds.isEmpty()) return
        val client = MinecraftClient.getInstance()
        val attemptId = activeAttemptId.ifBlank { UUID.randomUUID().toString() }
        joinAttemptId = attemptId
        joinExpectedClientIds = preparingClientIds + clientId
        joinResults.clear()
        joinFailures.clear()
        joinDeadline = System.currentTimeMillis() + JOIN_TIMEOUT_MS
        manualHistory.clear()
        manualReadyClientIds.clear()
        manualSequence = 0
        manualIntentVersion = 0
        manualRoomActionPending = false
        manualClientStages.clear()
        activeCommandRoot = command.lowercase()
        attemptState = PartyAttemptState.JOINING
        val sent = transport?.sendManualCommand(preparingClientIds, attemptId, command) ?: 0
        if (sent != preparingClientIds.size) {
            clearAttempt()
            attemptState = PartyAttemptState.IDLE
            clearJoin()
            client.inGameHud.setOverlayMessage(
                Text.literal("Party: manual command reached $sent/${preparingClientIds.size} clients"),
                false
            )
            return
        }
        debugToast("Party: manual command sent to $sent clients")
        debugToast("Party: waiting for all client menus")
    }

    private fun startLocalManualJoin(client: MinecraftClient, attemptId: String, command: String) {
        PartyJoinExecutor.startManual(
            client,
            attemptId,
            command,
            onActionApplied = { sequence -> transport?.sendManualAck(attemptId, sequence) },
            onMenuReady = { transport?.sendManualMenuReady(attemptId) },
            onMenuStage = { stage, intentVersion ->
                if (isCoordinator() &&
                    intentVersion == manualIntentVersion &&
                    stage == PartyMenuStage.TYPE_MENU
                ) {
                    manualRoomActionPending = false
                }
                transport?.sendManualMenuState(attemptId, stage, intentVersion)
            },
            onResyncRequested = { lastSequence ->
                if (isCoordinator()) {
                    PartyJoinExecutor.resetManualReplay()
                    manualHistory.values.forEach { action ->
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
                    preparingClientIds.forEach { peerId ->
                        transport?.sendManualHistory(peerId, attemptId, manualHistory.values.toList())
                    }
                } else {
                    transport?.requestManualResync(attemptId, lastSequence)
                }
            },
            onPhase = { phase -> debugToast("Party: $phase") }
        ) { success, reason ->
            if (isCoordinator()) {
                handleJoinResult(clientId, attemptId, success, reason)
            } else {
                transport?.sendJoinResult(attemptId, success, reason)
                if (success) {
                    attemptState = PartyAttemptState.JOINED
                } else {
                    clearAttempt()
                    attemptState = PartyAttemptState.IDLE
                }
                clearJoin()
            }
        }
    }

    private fun handleManualMenuReady(clientId: String, attemptId: String) {
        MinecraftClient.getInstance().execute {
            if (!isCoordinator() || attemptId != joinAttemptId || clientId !in preparingClientIds) return@execute
            manualReadyClientIds.add(clientId)
            debugToast("Party: $clientId menu ready (${manualReadyClientIds.size}/${preparingClientIds.size})")
            if (!manualReadyClientIds.containsAll(preparingClientIds) || PartyJoinExecutor.isRunning()) return@execute
            joinDeadline = 0L
            debugToast("Party: all menus ready; starting coordinator menu")
            startLocalManualJoin(MinecraftClient.getInstance(), attemptId, activeCommandRoot)
        }
    }

    private fun handleManualMenuState(
        clientId: String,
        attemptId: String,
        stage: PartyMenuStage,
        intentVersion: Int
    ) {
        MinecraftClient.getInstance().execute {
            if (!isCoordinator() || attemptId != joinAttemptId || clientId !in preparingClientIds) return@execute
            manualClientStages[clientId] = stage
            debugToast("Party: $clientId stage=${stage.name} intent=$intentVersion")
        }
    }

    private fun handlePartyCommand(command: String) {
        MinecraftClient.getInstance().execute {
            val normalizedCommand = command.trim().removePrefix("/").trim()
            if (!isSynchronizedCommand(normalizedCommand)) return@execute
            PartyJoinExecutor.sendCommand(normalizedCommand)
            val parts = normalizedCommand.split(Regex("\\s+"), limit = 2)
            if (parts.getOrNull(1)?.equals("quit", ignoreCase = true) == true) {
                cancelJoinFromCommand(normalizedCommand, broadcast = false)
            }
        }
    }

    private fun handleManualCommand(attemptId: String, command: String) {
        MinecraftClient.getInstance().execute {
            if (joinAttemptId == attemptId) return@execute
            if (joinAttemptId.isNotBlank() || PartyJoinExecutor.isRunning()) {
                PartyJoinExecutor.cancel("superseded by new manual attempt")
                clearJoin()
            }
            if (joinAttemptId.isNotBlank() || PartyJoinExecutor.isRunning()) {
                transport?.sendJoinResult(attemptId, false, "join executor busy")
                return@execute
            }
            joinAttemptId = attemptId
            joinExpectedClientIds = setOf(clientId)
            joinResults.clear()
            joinFailures.clear()
            joinDeadline = 0L
            activeCommandRoot = command.trim().removePrefix("/").trim()
                .split(Regex("\\s+"), limit = 2).first().lowercase()
            attemptState = PartyAttemptState.JOINING
            debugToast("Party: manual command received")
            startLocalManualJoin(MinecraftClient.getInstance(), attemptId, command)
        }
    }

    private fun handleManualClick(
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
            if (attemptId != joinAttemptId) return@execute
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

    private fun handleManualRoomAccepted(
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
            if (attemptId != joinAttemptId) return@execute
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
                debugToast("Party: room accepted; retrying $type #$sequence")
            }
        }
    }

    private fun handleManualReplayBegin(attemptId: String) {
        MinecraftClient.getInstance().execute {
            if (attemptId == joinAttemptId) {
                debugToast("Party: replay history received")
                PartyJoinExecutor.resetManualReplay()
            }
        }
    }

    private fun handleManualAck(clientId: String, attemptId: String, sequence: Int) {
        if (attemptId == joinAttemptId) debugToast("Party: $clientId ACK action #$sequence")
    }

    private fun handleManualResync(clientId: String, attemptId: String, lastSequence: Int) {
        if (attemptId != joinAttemptId || !isCoordinator()) return
        val actions = manualHistory.values.filter { it.sequence > lastSequence }
        transport?.sendManualHistory(clientId, attemptId, actions)
        debugToast("Party: replaying ${actions.size} actions to $clientId")
    }

    private fun handleJoinCancel(attemptId: String) {
        MinecraftClient.getInstance().execute {
            if (attemptId != joinAttemptId) return@execute
            PartyJoinExecutor.cancel("dchien quit")
            clearAttempt()
            clearJoin()
            attemptState = PartyAttemptState.IDLE
            debugToast("Party: join cancelled")
        }
    }

    private fun cancelJoinFromCommand(command: String, broadcast: Boolean) {
        val attemptId = joinAttemptId
        if (broadcast && isCoordinator() && hasActivePartySession()) {
            val peers = activePartyClientIds()
            transport?.sendPartyCommand(peers, command)
            if (attemptId.isNotBlank()) transport?.sendJoinCancel(peers, attemptId)
        }
        PartyJoinExecutor.cancel(command)
        clearAttempt()
        clearJoin()
        attemptState = PartyAttemptState.IDLE
        debugToast("Party: join cancelled")
    }

    private fun activePartyClientIds(): Set<String> =
        if (preparingClientIds.isNotEmpty()) preparingClientIds
        else selectedClients().mapTo(LinkedHashSet()) { it.clientId }

    private fun hasActivePartySession(): Boolean =
        activeAttemptId.isNotBlank() ||
                joinAttemptId.isNotBlank() ||
                attemptState == PartyAttemptState.JOINING ||
                attemptState == PartyAttemptState.JOINED

    private fun isSynchronizedCommand(command: String): Boolean =
        command.trim().removePrefix("/").trim()
            .split(Regex("\\s+"), limit = 2)
            .firstOrNull()
            ?.lowercase()
            ?.let(synchronizedCommandRoots::contains)
            ?: false

    fun onLocalSlotAction(syncId: Int, slot: Int, button: Int, action: SlotActionType) {
        if (RvlAddonsConfigStore.config.partyJoinExecutionMode != PartyJoinExecutionMode.MANUAL ||
            !isCoordinator() || joinAttemptId.isBlank() || !PartyJoinExecutor.isManualModeActive()
        ) return
        if (activeCommandRoot !in synchronizedCommandRoots) return
        val type = PartyJoinExecutor.currentManualActionType()
        if (type == PartyManualActionType.SELECT_ROOM && manualRoomActionPending) {
            debugToast("Party: ignored duplicate SELECT_ROOM click")
            return
        }
        if (type == PartyManualActionType.SELECT_TYPE) manualIntentVersion++
        val manualAction = PartyManualAction(
            sequence = ++manualSequence,
            slot = slot,
            button = button,
            action = action.name,
            intentVersion = manualIntentVersion,
            type = type,
            targetSignature = if (type == PartyManualActionType.SELECT_TYPE) {
                PartyJoinExecutor.manualSlotSignature(slot)
            } else {
                ""
            }
        )
        manualHistory[manualAction.sequence] = manualAction
        if (type == PartyManualActionType.SELECT_ROOM) manualRoomActionPending = true
        PartyJoinExecutor.recordManualCoordinatorAction(
            manualAction.sequence,
            manualAction.intentVersion,
            manualAction.type
        )
        transport?.sendManualClick(joinAttemptId, manualAction)
        debugToast("Party: sent ${type.name} #${manualAction.sequence} slot=$slot")
    }

    fun toggleSelection(clientId: String) {
        if (clientId == this.clientId) return
        val current = registry.get(clientId) ?: return
        if (current.selected) {
            selectedClientIds.remove(clientId)
        } else {
            selectedClientIds.add(clientId)
        }
        registry.updateSelection(clientId, selectedClientIds.contains(clientId))
    }

    fun selectAllEligible(client: MinecraftClient) {
        selectedClientIds.clear()
        registry.snapshot().forEach { snapshot ->
            if (isEligible(snapshot, client)) selectedClientIds.add(snapshot.clientId)
            registry.updateSelection(snapshot.clientId, selectedClientIds.contains(snapshot.clientId))
        }
    }

    fun startDraft(client: MinecraftClient) {
        if (!isCoordinator()) {
            client.inGameHud.setOverlayMessage(Text.literal("Party: only the coordinator can start"), false)
            return
        }
        if (selectedClients().isEmpty()) {
            client.inGameHud.setOverlayMessage(Text.literal("Party: select at least one client"), false)
            return
        }
        val selected = selectedClients()
        activeAttemptId = UUID.randomUUID().toString()
        preparingClientIds = selected.mapTo(LinkedHashSet()) { it.clientId }
        readyClientIds.clear()
        notReadyReasons.clear()
        prepareDeadline = System.currentTimeMillis() + PREPARE_TIMEOUT_MS
        attemptState = PartyAttemptState.PREPARING
        transport?.prepare(preparingClientIds, activeAttemptId, currentServerKey(client))
        updateAttemptState(client)
        showAttemptStatus(client)
    }

    fun cancelDraft(client: MinecraftClient) {
        PartyJoinExecutor.cancel("party draft cancelled")
        clearAttempt()
        clearPromotion()
        clearJoin()
        attemptState = PartyAttemptState.IDLE
        client.inGameHud.setOverlayMessage(Text.literal("Party draft cancelled"), false)
    }

    fun statusText(): String {
        val clients = clients()
        if (clients.isEmpty()) return ""
        val client = MinecraftClient.getInstance()
        if (!isCoordinator()) {
            val coordinator = registry.snapshot().firstOrNull { it.isCoordinator }
            if (coordinator != null) {
                return "Party ${coordinator.partySelectedCount}/${clients.size} | " +
                    "Ready ${coordinator.partyReadyCount}/${coordinator.partySelectedCount} | " +
                    coordinator.partyState.name
            }
        }
        val selected = selectedClients()
        val ready = readyCount(selected, client)
        return "Party ${selected.size}/${clients.size} | Ready $ready/${selected.size} | ${attemptState.name}"
    }

    private fun updateAttemptState(client: MinecraftClient) {
        if (attemptState != PartyAttemptState.PREPARING && attemptState != PartyAttemptState.READY) return
        val selected = selectedClients()
        if (selected.isEmpty()) {
            clearAttempt()
            attemptState = PartyAttemptState.IDLE
            return
        }
        val selectedIds = selected.mapTo(HashSet()) { it.clientId }
        preparingClientIds = preparingClientIds.intersect(selectedIds)
        if (notReadyReasons.isNotEmpty()) {
            attemptState = PartyAttemptState.FAILED
            return
        }
        attemptState = if (preparingClientIds.isNotEmpty() && readyClientIds.containsAll(preparingClientIds)) {
            PartyAttemptState.READY
        } else if (System.currentTimeMillis() >= prepareDeadline) {
            PartyAttemptState.FAILED
        } else {
            PartyAttemptState.PREPARING
        }
        if (attemptState == PartyAttemptState.FAILED) {
            showAttemptStatus(client)
        }
    }

    private fun selectedClients(): List<PartyClientSnapshot> = clients().filter { it.selected }

    private fun readyCount(selected: List<PartyClientSnapshot>, client: MinecraftClient): Int {
        if (activeAttemptId.isBlank()) return selected.count { isEligible(it, client) }
        return selected.count { it.clientId in readyClientIds }
    }

    private fun handlePrepareRequest(attemptId: String, expectedServerKey: String) {
        MinecraftClient.getInstance().execute {
            val client = MinecraftClient.getInstance()
            val ready = client.world != null &&
                    client.player != null &&
                    currentServerKey(client) == expectedServerKey
            transport?.sendReady(attemptId, ready, if (ready) "" else "not on target server")
        }
    }

    private fun handleReadyResponse(
        clientId: String,
        attemptId: String,
        ready: Boolean,
        reason: String
    ) {
        MinecraftClient.getInstance().execute {
            if (attemptId != activeAttemptId || clientId !in preparingClientIds) return@execute
            if (ready) {
                readyClientIds.add(clientId)
            } else {
                notReadyReasons[clientId] = reason.ifBlank { "not ready" }
            }
            updateAttemptState(MinecraftClient.getInstance())
            showAttemptStatus(MinecraftClient.getInstance())
        }
    }

    private fun handlePromoteRequest(handoffId: String) {
        MinecraftClient.getInstance().execute {
            val blocked = attemptState == PartyAttemptState.PREPARING ||
                    attemptState == PartyAttemptState.READY ||
                    attemptState == PartyAttemptState.JOINING ||
                    PartyJoinExecutor.isRunning()
            if (blocked) {
                transport?.sendPromotionReady(handoffId, false, "join active")
                debugToast("Party: primary promotion rejected while join is active")
            } else {
                transport?.sendPromotionReady(handoffId, true)
            }
        }
    }

    private fun handlePromotionReady(
        clientId: String,
        handoffId: String,
        ready: Boolean,
        reason: String
    ) {
        MinecraftClient.getInstance().execute {
            if (handoffId != pendingHandoffId || clientId != pendingPromotionClientId) return@execute
            if (!ready) {
                clearPromotion()
                MinecraftClient.getInstance().inGameHud.setOverlayMessage(
                    Text.literal(
                        "Party: primary transfer failed" +
                                reason.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()
                    ),
                    false
                )
                return@execute
            }
            if (transport?.commitPromotion(clientId, handoffId) == true) {
                clearPromotion()
            }
        }
    }

    private fun handleJoinGo(
        attemptId: String,
        command: String,
        gamemodeSlot: Int,
        difficultySlot: Int
    ) {
        MinecraftClient.getInstance().execute {
            if (joinAttemptId == attemptId) return@execute
            if (joinAttemptId.isNotBlank() || PartyJoinExecutor.isRunning()) {
                PartyJoinExecutor.cancel("superseded by new join attempt")
                clearJoin()
            }
            if (joinAttemptId.isNotBlank() || PartyJoinExecutor.isRunning()) {
                transport?.sendJoinResult(attemptId, false, "join executor busy")
                return@execute
            }
            joinAttemptId = attemptId
            joinExpectedClientIds = setOf(clientId)
            joinResults.clear()
            joinFailures.clear()
            joinDeadline = System.currentTimeMillis() + JOIN_TIMEOUT_MS
            activeCommandRoot = command.trim().removePrefix("/").trim()
                .split(Regex("\\s+"), limit = 2).first().lowercase()
            attemptState = PartyAttemptState.JOINING
            debugToast("Party: JOIN_GO received; opening /dchien")
            MinecraftClient.getInstance().inGameHud.setOverlayMessage(
                Text.literal("Party: JOIN_GO received; opening /dchien"),
                false
            )
            startLocalJoin(
                MinecraftClient.getInstance(),
                attemptId,
                command,
                gamemodeSlot,
                difficultySlot
            )
        }
    }

    private fun startLocalJoin(
        client: MinecraftClient,
        attemptId: String,
        command: String,
        gamemodeSlot: Int,
        difficultySlot: Int
    ) {
        PartyJoinExecutor.start(client, attemptId, command, gamemodeSlot, difficultySlot) { success, reason ->
            if (isCoordinator()) {
                handleJoinResult(clientId, attemptId, success, reason)
            } else {
                transport?.sendJoinResult(attemptId, success, reason)
                if (success) {
                    attemptState = PartyAttemptState.JOINED
                } else {
                    clearAttempt()
                    attemptState = PartyAttemptState.IDLE
                }
                clearJoin()
            }
        }
    }

    fun handleJoinChat(message: String) {
        PartyJoinExecutor.handleChatMessage(message)
    }

    private fun handleJoinResult(
        clientId: String,
        attemptId: String,
        success: Boolean,
        reason: String
    ) {
        MinecraftClient.getInstance().execute {
            if (attemptId != joinAttemptId || clientId !in joinExpectedClientIds) return@execute
            if (success) {
                if (isCoordinator() &&
                    clientId == this.clientId &&
                    RvlAddonsConfigStore.config.partyJoinExecutionMode == PartyJoinExecutionMode.MANUAL
                ) {
                    broadcastManualRoomAccepted(attemptId)
                }
                joinResults.add(clientId)
                debugToast("Party: $clientId joined")
            } else {
                joinFailures[clientId] = reason.ifBlank { "join failed" }
                debugToast("Party: $clientId failed to join")
            }
            if (joinFailures.isNotEmpty()) {
                attemptState = PartyAttemptState.FAILED
                MinecraftClient.getInstance().inGameHud.setOverlayMessage(
                    Text.literal("Party join failed: ${joinFailures.values.first()}"),
                    false
                )
                clearAttempt()
                attemptState = PartyAttemptState.IDLE
                clearJoin()
                return@execute
            }
            if (joinResults.containsAll(joinExpectedClientIds)) {
                attemptState = PartyAttemptState.JOINED
                MinecraftClient.getInstance().inGameHud.setOverlayMessage(
                    Text.literal("Party joined successfully"),
                    false
                )
                clearJoin()
            }
        }
    }

    private fun broadcastManualRoomAccepted(attemptId: String) {
        val typeAction = manualHistory.values.lastOrNull {
            it.type == PartyManualActionType.SELECT_TYPE &&
                    it.intentVersion == manualIntentVersion
        } ?: return
        val recoveryAction = typeAction.copy(sequence = ++manualSequence)
        manualRoomActionPending = false
        manualHistory[recoveryAction.sequence] = recoveryAction
        transport?.sendManualRoomAccepted(preparingClientIds, attemptId, recoveryAction)
        debugToast("Party: coordinator accepted room; replaying type selection")
    }

    private fun showAttemptStatus(client: MinecraftClient) {
        val selected = selectedClients()
        val ready = readyCount(selected, client)
        val message = when (attemptState) {
            PartyAttemptState.READY -> "Party draft: all selected clients ready"
            PartyAttemptState.FAILED -> "Party draft failed: ${notReadyReasons.values.firstOrNull() ?: "timeout"}"
            else -> "Party draft: preparing clients ($ready/${selected.size})"
        }
        client.inGameHud.setOverlayMessage(Text.literal(message), false)
    }

    private fun clearAttempt() {
        activeAttemptId = ""
        preparingClientIds = emptySet()
        readyClientIds.clear()
        notReadyReasons.clear()
        prepareDeadline = 0L
    }

    private fun clearPromotion() {
        pendingHandoffId = ""
        pendingPromotionClientId = ""
        promotionDeadline = 0L
    }

    private fun clearJoin() {
        joinAttemptId = ""
        joinExpectedClientIds = emptySet()
        joinResults.clear()
        joinFailures.clear()
        joinDeadline = 0L
        manualHistory.clear()
        manualReadyClientIds.clear()
        manualClientStages.clear()
        activeCommandRoot = ""
        manualIntentVersion = 0
        manualRoomActionPending = false
        manualSequence = 0
    }

    private fun handleTransportStatus(status: String) {
        val message = when {
            status == "primary" -> "Party: primary started"
            status == "connected" -> "Party: connected to primary"
            status.startsWith("join-go:") -> "Party: ${status.removePrefix("join-go:")} JOIN_GO delivered"
            status.startsWith("peer-connected:") -> "Party: ${status.removePrefix("peer-connected:")} connected"
            status.startsWith("peer-disconnected:") -> "Party: client disconnected"
            else -> "Party: $status"
        }
        debugToast(message)
    }

    private fun debugToast(message: String) {
        if (!RvlAddonsConfigStore.config.partyDebugToasts) return
        MinecraftClient.getInstance().execute {
            TakoToastManager.show(message)
        }
    }

    private fun replaceSnapshots(values: List<PartyClientSnapshot>) {
        val clientIds = values.mapTo(HashSet()) { it.clientId }
        selectedClientIds.retainAll(clientIds)
        registry.replace(values.map { it.copy(selected = selectedClientIds.contains(it.clientId)) })
    }

    private fun loadClientId(path: Path): String {
        val properties = Properties()
        if (Files.exists(path)) {
            runCatching { Files.newInputStream(path).use(properties::load) }
        }
        val existing = properties.getProperty("clientId")?.takeIf { it.isNotBlank() }
        if (existing != null) return existing
        val generated = UUID.randomUUID().toString()
        runCatching {
            Files.createDirectories(path.parent)
            properties["clientId"] = generated
            Files.newOutputStream(path).use { properties.store(it, "RVL local party identity") }
        }
        return generated
    }

    private fun currentServerKey(client: MinecraftClient): String =
        client.currentServerEntry?.address?.trim()?.lowercase().orEmpty()
}
