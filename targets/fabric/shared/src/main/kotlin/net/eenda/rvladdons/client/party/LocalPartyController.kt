package net.eenda.rvladdons.client.party

import dev.tako.libs.client.ui.feedback.TakoToastManager
import net.eenda.rvladdons.core.AccountStorage
import net.eenda.rvladdons.core.ModEnabledState
import net.eenda.rvladdons.core.RvlAddonsConfigStore
import net.eenda.rvladdons.core.GameplayServerState
import net.eenda.rvladdons.feature.party.PartyAttemptState
import net.eenda.rvladdons.feature.party.PartyClientSnapshot
import net.eenda.rvladdons.feature.party.PartyJoinExecutionMode
import net.eenda.rvladdons.feature.party.PartyPresenceState
import net.eenda.rvladdons.feature.party.PartyPrimaryTransferCoordinator
import net.eenda.rvladdons.feature.party.PartyRegistry
import net.eenda.rvladdons.feature.party.PartyStateStore
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.text.Text
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object LocalPartyController {
    private val synchronizedCommandRoots = setOf("dchien", "tl")
    private const val ID_FILE = "rvl-addons-party.properties"
    private const val PREPARE_TIMEOUT_MS = 2_000L
    private val partyStatePath = FabricLoader.getInstance().gameDir
        .resolve("rvl-addons-data")
        .resolve("party-state.json")
    private val legacyPartyStatePath = FabricLoader.getInstance().configDir.resolve(ID_FILE)
    private val registry = PartyRegistry()
    private var transport: LocalPartyTransport? = null
    private var clientId = ""
    private var attemptState = PartyAttemptState.IDLE
    private var lastLocalUpdate = 0L
    private var pendingAutomaticHandoffRoot: String? = null
    private var pendingAutomaticHandoffDeadline = 0L
    private val selectedClientIds = ConcurrentHashMap.newKeySet<String>()
    private val primaryTransfer = PartyPrimaryTransferCoordinator()
    private val allowances = PartyAllowanceTracker()
    private val roomCreatorRotation = RoomCreatorRotation()
    private val readiness = PartyReadinessCoordinator(
        selectedClients = ::selectedClients,
        isCoordinator = ::isCoordinator,
        transport = { transport },
        currentServerKey = ::currentServerKey,
        setAttemptState = { attemptState = it },
        showStatus = { client, state, ready, selected, reason ->
            showReadinessStatus(client, state, ready, selected, reason)
        }
    )
    private val joinCoordinator = PartyJoinCoordinator(
        clientId = ::clientId,
        isCoordinator = ::isCoordinator,
        participantIds = { readiness.participantIds },
        creatorCandidates = {
            val server = currentServerKey(MinecraftClient.getInstance())
            val selectedIds = readiness.participantIds + clientId
            val remoteCandidates = selectedClients()
                .filter { it.clientId in selectedIds }
                .map { snapshot ->
                    RoomCreatorCandidate(
                        snapshot.clientId,
                        "$server|${snapshot.name.lowercase(Locale.ROOT)}",
                        snapshot.name,
                        snapshot.runsRemaining("dchien"),
                        snapshot.runsRemaining("tl")
                    )
                }
            val playerName = MinecraftClient.getInstance().player?.name?.string ?: "This client"
            remoteCandidates + RoomCreatorCandidate(
                clientId,
                "$server|${playerName.lowercase(Locale.ROOT)}",
                playerName,
                allowances.freshRemaining("dchien"),
                allowances.freshRemaining("tl")
            )
        },
        creatorRotation = roomCreatorRotation,
        transport = { transport },
        readinessClear = readiness::clear,
        readinessResume = readiness::resumeReady,
        readinessPause = readiness::pauseForJoin,
        activePartyClientIds = ::activePartyClientIds,
        setAttemptState = { attemptState = it },
        commitAllowance = ::commitAllowance,
        requestAllowanceRefresh = allowances::requestRefresh,
        unselectExhaustedClient = ::unselectExhaustedClient,
        localRunsRemaining = allowances::freshRemaining,
        onCoordinatorExhausted = { root ->
            requestAutomaticHandoff(root)
            processAutomaticHandoff(MinecraftClient.getInstance())
        },
        debugToast = ::debugToast
    )

    private val commandRouter = PartyCommandRouter(
        roots = synchronizedCommandRoots,
        isDispatching = PartyJoinExecutor::isDispatchingCommand,
        isCoordinator = ::isCoordinator,
        selectedCount = ::selectedCount,
        state = ::currentState,
        mode = { RvlAddonsConfigStore.config.partyJoinExecutionMode },
        showWaiting = { debugToast("Party: waiting for all clients to become READY") },
        startJoin = { root, mode ->
            beginPartyJoin(root, mode)
        },
        cancelJoin = ::cancelSynchronizedJoin,
        broadcastCommand = { command ->
            val peers = activePartyClientIds()
            if (peers.isNotEmpty()) {
                val sent = transport?.sendPartyCommand(peers, command) ?: 0
                debugToast("Party: command '$command' sent to $sent/${peers.size}")
            }
        }
    )

    private val slotCapture = PartySlotCaptureController(
        isCoordinator = ::isCoordinator,
        isJoinActive = {
            attemptState == PartyAttemptState.PREPARING ||
                attemptState == PartyAttemptState.JOINING || PartyJoinExecutor.isRunning()
        },
        sendCommand = PartyJoinExecutor::sendCommand,
        publishCapturedRoom = {
            publishPresence(MinecraftClient.getInstance(), PartyPresenceState.IN_SERVER)
        },
        notify = { message ->
            MinecraftClient.getInstance().execute {
                TakoToastManager.show("Party: $message")
            }
        }
    )


    fun initialize() {
        if (!ModEnabledState.isEnabled()) return
        transport?.let { current ->
            if (current.running) return
            current.stop()
            transport = null
        }
        clientId = "${PartyStateStore.loadOrCreateClientId(partyStatePath, legacyPartyStatePath)}-${UUID.randomUUID().toString().take(8)}"
        PartyJoinExecutor.setAllowanceReader(allowances::freshRemaining)
        transport = LocalPartyTransport(
            PartyTransportCallbacks(
                onSnapshot = ::replaceSnapshots,
                onPrepare = ::handlePrepareRequest,
                onReady = ::handleReadyResponse,
                onPromoteRequest = ::handlePromoteRequest,
                onPromotionCommitted = ::cachePromotionSelection,
                onPromotionReady = ::handlePromotionReady,
                onJoinGo = joinCoordinator::handleJoinGo,
                onJoinResult = { clientId, attemptId, success, reason ->
                    joinCoordinator.handleJoinResult(clientId, attemptId, success, reason)
                },
                onPartyCommand = joinCoordinator::handlePartyCommand,
                onManualCommand = joinCoordinator::handleManualCommand,
                onManualMenuReady = joinCoordinator::handleManualMenuReady,
                onManualMenuState = joinCoordinator::handleManualMenuState,
                onManualClick = joinCoordinator::handleManualClick,
                onManualRoomAccepted = { attemptId, sequence, slot, button, action, intentVersion, type, signature, cost ->
                    joinCoordinator.handleManualRoomAccepted(
                        attemptId,
                        sequence,
                        slot,
                        button,
                        action,
                        intentVersion,
                        type,
                        signature,
                        cost
                    )
                },
                onManualReplayBegin = joinCoordinator::handleManualReplayBegin,
                onManualAck = joinCoordinator::handleManualAck,
                onManualResync = joinCoordinator::handleManualResync,
                onJoinCancel = ::handleJoinCancel,
                onJoinCancelRequest = { clientId, attemptId ->
                    MinecraftClient.getInstance().execute {
                        selectedClientIds.remove(clientId)
                        registry.updateSelection(clientId, false)
                        readiness.removeParticipant(clientId)
                        joinCoordinator.handleParticipantQuit(clientId, attemptId)
                        debugToast("Party: $clientId quit the party join")
                    }
                },
                onStatus = ::handleTransportStatus
            )
        ).also { it.start() }
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled) {
            lastLocalUpdate = 0L
            initialize()
            MinecraftClient.getInstance().let { client ->
                if (client.player != null) {
                    onGameplayServerDetected()
                    publishPresence(client, PartyPresenceState.IN_SERVER)
                }
            }
            return
        }
        PartyJoinExecutor.currentRun()
            ?.takeIf { it.roomSubmitted }
            ?.command
            ?.let(allowances::requestRefresh)
        allowances.markStale()
        slotCapture.reset()
        pendingAutomaticHandoffRoot = null
        pendingAutomaticHandoffDeadline = 0L
        PartyJoinExecutor.cancel("party disabled")
        transport?.stop()
        transport = null
        attemptState = PartyAttemptState.IDLE
        selectedClientIds.clear()
        readiness.clear()
        primaryTransfer.reset()
        clearJoin()
        registry.replace(emptyList())
    }

    fun tick(client: MinecraftClient) {
        val canScanAllowances = ModEnabledState.isEnabled() && GameplayServerState.isActive() &&
            client.player != null && client.world != null && client.currentScreen == null &&
            !PartyJoinExecutor.isRunning() && !slotCapture.isActive &&
            attemptState != PartyAttemptState.PREPARING && attemptState != PartyAttemptState.JOINING
        if (allowances.tick(client, PartyJoinExecutor::sendCommand, canScanAllowances)) {
            publishPresence(client, PartyPresenceState.IN_SERVER)
        }
        PartyJoinExecutor.tick()
        val now = System.currentTimeMillis()
        joinCoordinator.tick(now)
        slotCapture.tick(now)
        readiness.reconcileParticipants(selectedClients().mapTo(HashSet()) { it.clientId })
        processAutomaticHandoff(client)
        if (now - lastLocalUpdate < 500L) return
        lastLocalUpdate = now
        val state = if (client.world != null && client.player != null) {
            PartyPresenceState.IN_SERVER
        } else {
            PartyPresenceState.MAIN_MENU
        }
        publishPresence(client, state)
        readiness.tick(client)
        if (primaryTransfer.isExpired(now)) {
            primaryTransfer.clearTransfer()
            client.inGameHud.setOverlayMessage(Text.literal("Party: primary transfer timed out"), false)
        }
    }

    fun onJoin(client: MinecraftClient) {
        publishPresence(client, PartyPresenceState.IN_SERVER)
        readiness.tick(client)
        onGameplayServerDetected()
    }

    fun onGameplayServerDetected() {
        val client = MinecraftClient.getInstance()
        allowances.onConnected(
            partyStatePath,
            AccountStorage.accountId(client.session.uuidOrNull, client.session.username),
            currentServerKey(client)
        )
    }

    fun onGameplayServerLeft() {
        allowances.markStale()
    }

    private fun beginPartyJoin(commandRoot: String, mode: PartyJoinExecutionMode) {
        unselectExhaustedParticipants(commandRoot)
        readiness.reconcileParticipants(selectedClients().mapTo(HashSet()) { it.clientId })

        if (allowances.freshRemaining(commandRoot) == 0) {
            if (mode == PartyJoinExecutionMode.AUTOMATIC && isCoordinator() && selectedClients().isNotEmpty()) {
                requestAutomaticHandoff(commandRoot)
                processAutomaticHandoff(MinecraftClient.getInstance())
            } else {
                MinecraftClient.getInstance().inGameHud.setOverlayMessage(
                    Text.literal("Party: no $commandRoot runs remaining on coordinator"),
                    false
                )
            }
            return
        }
        joinCoordinator.begin(commandRoot, mode)
    }

    private fun cancelSynchronizedJoin(command: String) {
        val prepareAttemptId = readiness.attemptId
        val prepareParticipants = readiness.participantIds
        val keepReady = command.trim().removePrefix("/").split(Regex("\\s+"), limit = 2)
            .getOrNull(1)?.equals("quit", ignoreCase = true) == true
        if (isCoordinator() && prepareAttemptId.isNotBlank() && !joinCoordinator.isJoinActive()) {
            transport?.sendPartyCommand(prepareParticipants, command)
        }
        joinCoordinator.cancel(command, broadcast = true)
        if (isCoordinator() && prepareAttemptId.isNotBlank()) {
            transport?.sendJoinCancel(prepareParticipants, prepareAttemptId, keepReady)
        }
    }

    private fun handleJoinCancel(attemptId: String, keepReady: Boolean) {
        MinecraftClient.getInstance().execute {
            if (!readiness.cancelAttempt(attemptId)) {
                joinCoordinator.handleJoinCancel(attemptId, keepReady)
                return@execute
            }
            joinCoordinator.cancel(
                if (keepReady) "dchien quit" else "party preparation cancelled",
                broadcast = false
            )
        }
    }

    private fun commitAllowance(attemptId: String, cost: PartyAllowanceCost?) {
        allowances.commit(cost, attemptId)
        val client = MinecraftClient.getInstance()
        publishPresence(
            client,
            if (client.player != null && client.world != null) PartyPresenceState.IN_SERVER
            else PartyPresenceState.MAIN_MENU
        )
        if (cost != null && isCoordinator() &&
            RvlAddonsConfigStore.config.partyJoinExecutionMode == PartyJoinExecutionMode.AUTOMATIC &&
            allowances.freshRemaining(cost.commandRoot) == 0
        ) {
            requestAutomaticHandoff(cost.commandRoot)
        }
    }

    private fun requestAutomaticHandoff(commandRoot: String) {
        if (pendingAutomaticHandoffRoot == commandRoot) return
        pendingAutomaticHandoffRoot = commandRoot
        pendingAutomaticHandoffDeadline = System.currentTimeMillis() + 10_000L
    }

    private fun unselectExhaustedParticipants(commandRoot: String) {
        selectedClients().filter { it.runsRemaining(commandRoot) == 0 }.forEach {
            unselectExhaustedClient(it.clientId, commandRoot)
        }
    }

    private fun unselectExhaustedClient(clientId: String, commandRoot: String) {
        if (!selectedClientIds.remove(clientId)) return
        registry.updateSelection(clientId, false)
        readiness.reconcileParticipants(selectedClients().mapTo(HashSet()) { it.clientId })
        val name = registry.get(clientId)?.name ?: clientId
        debugToast("Party: $name has no /$commandRoot runs; unselected")
    }

    private fun processAutomaticHandoff(client: MinecraftClient) {
        val commandRoot = pendingAutomaticHandoffRoot ?: return
        if (!isCoordinator() || RvlAddonsConfigStore.config.partyJoinExecutionMode != PartyJoinExecutionMode.AUTOMATIC) {
            pendingAutomaticHandoffRoot = null
            pendingAutomaticHandoffDeadline = 0L
            return
        }
        if (joinCoordinator.session.isActive || PartyJoinExecutor.isRunning() ||
            attemptState == PartyAttemptState.PREPARING || attemptState == PartyAttemptState.JOINING ||
            primaryTransfer.isPending
        ) return

        val candidates = selectedClients().filter { isEligible(it, client) }
        if (candidates.any { it.runsRemaining(commandRoot) == null } &&
            System.currentTimeMillis() < pendingAutomaticHandoffDeadline
        ) return
        val replacement = candidates
            .filter { (it.runsRemaining(commandRoot) ?: 0) > 0 }
            .maxByOrNull { it.runsRemaining(commandRoot) ?: 0 }
        pendingAutomaticHandoffRoot = null
        pendingAutomaticHandoffDeadline = 0L
        if (replacement == null) {
            client.inGameHud.setOverlayMessage(
                Text.literal("Party: coordinator exhausted; no selected client has /$commandRoot runs"),
                false
            )
            return
        }

        client.inGameHud.setOverlayMessage(
            Text.literal("Party: primary exhausted; transferring to ${replacement.name}. Retry /$commandRoot after handoff"),
            false
        )
        debugToast("Party: coordinator exhausted; transferring primary to ${replacement.name}")
        promoteClient(replacement.clientId, automaticExhaustion = true)
    }

    fun onDisconnect(client: MinecraftClient) {
        PartyJoinExecutor.currentRun()
            ?.takeIf { it.roomSubmitted }
            ?.command
            ?.let(allowances::requestRefresh)
        allowances.markStale()
        pendingAutomaticHandoffRoot = null
        pendingAutomaticHandoffDeadline = 0L
        slotCapture.reset()
        attemptState = PartyAttemptState.IDLE
        selectedClientIds.clear()
        readiness.clear()
        primaryTransfer.reset()
        joinCoordinator.stopRoomMonitor()
        PartyJoinExecutor.cancel("disconnected")
        clearJoin()
        publishPresence(client, PartyPresenceState.DISCONNECTED)
    }

    fun isEligible(snapshot: PartyClientSnapshot, client: MinecraftClient): Boolean =
        snapshot.clientId != clientId &&
                snapshot.state == PartyPresenceState.IN_SERVER &&
                snapshot.serverKey == currentServerKey(client) &&
                !(snapshot.dchienAllowanceFresh && snapshot.dchienRunsRemaining == 0 &&
                    snapshot.tlAllowanceFresh && snapshot.tlTicketsRemaining == 0)

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
                partyReadyCount = readiness.readyCount(selectedClients(), client),
                dchienRunsRemaining = allowances.dchienRemaining,
                tlTicketsRemaining = allowances.tlTicketsRemaining,
                dchienAllowanceFresh = allowances.dchienFresh,
                tlAllowanceFresh = allowances.tlFresh,
                dchienRoomName = RvlAddonsConfigStore.config.partyAutomaticRoomName,
                tlRoomName = RvlAddonsConfigStore.config.partyTlAutomaticRoomName
            )
        )
    }

    fun clients(): List<PartyClientSnapshot> = registry.snapshot().filterNot { it.clientId == clientId }

    fun coordinatorName(): String = registry.snapshot()
        .firstOrNull { it.isCoordinator }
        ?.name
        ?: if (isCoordinator()) "This client" else "Unknown"

    fun capturedRoomNames(): Pair<String, String> {
        if (isCoordinator()) {
            val config = RvlAddonsConfigStore.config
            return config.partyAutomaticRoomName to config.partyTlAutomaticRoomName
        }
        val coordinator = registry.snapshot().firstOrNull { it.isCoordinator } ?: return "" to ""
        return coordinator.dchienRoomName to coordinator.tlRoomName
    }

    fun selectedCount(): Int = clients().count { it.selected }

    fun currentState(): PartyAttemptState = attemptState

    fun isCoordinator(): Boolean = transport?.isCoordinator == true

    fun handleOutgoingCommand(command: String): Boolean {
        if (!ModEnabledState.isEnabled()) return true
        return commandRouter.handle(command)
    }

    fun onServerMenuOpened() {
        MinecraftClient.getInstance().execute {
            slotCapture.onMenuOpened()
            PartyJoinExecutor.onServerMenuOpened()
        }
    }

    fun onPartyMenuEscape() {
        if (MinecraftClient.getInstance().currentScreen !is HandledScreen<*>) return
        if (slotCapture.cancel()) {
            debugToast("Party: slot capture cancelled")
            return
        }
        joinCoordinator.handleLocalMenuEscape()
    }

    fun onServerPositionCorrection() {
        MinecraftClient.getInstance().execute {
            PartyJoinExecutor.markServerJoinConfirmed()
        }
    }

    fun promoteClient(clientId: String) = promoteClient(clientId, automaticExhaustion = false)

    private fun promoteClient(clientId: String, automaticExhaustion: Boolean) {
        val client = MinecraftClient.getInstance()
        if (!isCoordinator()) {
            client.inGameHud.setOverlayMessage(
                Text.literal("Party: only the coordinator can transfer primary"),
                false
            )
            return
        }
        if (attemptState == PartyAttemptState.PREPARING ||
            (!automaticExhaustion && attemptState == PartyAttemptState.READY) ||
            attemptState == PartyAttemptState.JOINING ||
            PartyJoinExecutor.isRunning() || joinCoordinator.session.isActive
        ) {
            client.inGameHud.setOverlayMessage(
                Text.literal("Party: finish or cancel the current join before transferring primary"),
                false
            )
            debugToast("Party: primary transfer blocked while join is active")
            return
        }
        if (clientId == this.clientId || primaryTransfer.isPending) return
        if (registry.get(clientId) == null) return

        val handoffId = UUID.randomUUID().toString()
        if (transport?.requestPromotion(clientId, handoffId, automaticExhaustion) != true) {
            client.inGameHud.setOverlayMessage(
                Text.literal("Party: selected client is not connected"),
                false
            )
            return
        }
        readiness.clear()
        if (!automaticExhaustion) attemptState = PartyAttemptState.IDLE
        primaryTransfer.begin(
            handoffId,
            clientId,
            System.currentTimeMillis() + PREPARE_TIMEOUT_MS
        )
        client.inGameHud.setOverlayMessage(Text.literal("Party: transferring primary"), false)
    }


    fun onLocalSlotAction(syncId: Int, slot: Int, button: Int, action: SlotActionType): Boolean {
        if (slotCapture.onSlotAction(syncId, slot, button, action)) return true
        joinCoordinator.onLocalSlotAction(slot, button, action)
        return false
    }

    fun startAutomaticSlotCapture(commandRoot: String) = slotCapture.start(commandRoot)

    fun handleJoinChat(message: String) {
        joinCoordinator.handleJoinChat(message)
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

    fun startPreparation(client: MinecraftClient) {
        if (!isCoordinator()) {
            client.inGameHud.setOverlayMessage(Text.literal("Party: only the coordinator can start"), false)
            return
        }
        if (selectedClients().isEmpty()) {
            client.inGameHud.setOverlayMessage(Text.literal("Party: select at least one client"), false)
            return
        }
        if (attemptState == PartyAttemptState.PREPARING || attemptState == PartyAttemptState.JOINING ||
            joinCoordinator.isJoinActive() || primaryTransfer.isPending
        ) {
            client.inGameHud.setOverlayMessage(
                Text.literal("Party: finish or cancel the current session before preparing again"),
                false
            )
            return
        }
        if (!readiness.start(client)) {
            client.inGameHud.setOverlayMessage(Text.literal("Party: preparation could not start"), false)
        }
    }

    fun cancelPreparation(client: MinecraftClient) {
        val prepareAttemptId = readiness.attemptId
        val prepareParticipants = readiness.participantIds
        joinCoordinator.cancel("party preparation cancelled", broadcast = true)
        if (isCoordinator() && prepareAttemptId.isNotBlank()) {
            transport?.sendJoinCancel(prepareParticipants, prepareAttemptId, keepReady = false)
        }
        clearPromotion()
        attemptState = PartyAttemptState.IDLE
        client.inGameHud.setOverlayMessage(Text.literal("Party preparation cancelled"), false)
    }

    fun partyHudLines(): List<Pair<String, String>> {
        if (!GameplayServerState.isActive() || !ModEnabledState.isEnabled()) return emptyList()
        val coordinator = registry.snapshot().firstOrNull { it.isCoordinator }
        val role = when {
            isCoordinator() -> "PRIMARY"
            coordinator == null -> "SOLO"
            else -> "CLIENT"
        }
        val state = if (!isCoordinator() && coordinator != null) {
            partyStateLabel(coordinator.partyState)
        } else {
            partyStateLabel(attemptState)
        }
        return listOf(
            "Role" to role,
            "State" to state,
            "Doan chien ticket(s)" to allowanceLabel("dchien"),
            "Thanh lan ticket(s)" to allowanceLabel("tl")
        )
    }

    private fun allowanceLabel(root: String): String = allowances.remaining(root)?.let {
        (if (allowances.isFresh(root)) "" else "~") + it
    } ?: "?"

    private fun partyStateLabel(state: PartyAttemptState): String = when (state) {
        PartyAttemptState.IDLE -> "Not ready"
        PartyAttemptState.PREPARING -> "Preparing"
        PartyAttemptState.READY -> "Ready"
        PartyAttemptState.JOINING -> "Joining"
        PartyAttemptState.JOINED -> "Joined"
        PartyAttemptState.FAILED -> "Failed"
    }

    fun statusText(): String = partyHudLines().joinToString(" | ") { "${it.first}: ${it.second}" }

    private fun selectedClients(): List<PartyClientSnapshot> = clients().filter { it.selected }

    private fun activePartyClientIds(): Set<String> {
        val selectedIds = selectedClients().mapTo(LinkedHashSet()) { it.clientId }
        return if (readiness.attemptId.isNotBlank()) {
            readiness.participantIds.intersect(selectedIds)
        } else {
            selectedIds
        }
    }


    private fun handlePrepareRequest(attemptId: String, expectedServerKey: String) {
        MinecraftClient.getInstance().execute {
            readiness.handlePrepareRequest(attemptId, expectedServerKey)
        }
    }

    private fun handleReadyResponse(
        clientId: String,
        attemptId: String,
        ready: Boolean,
        reason: String
    ) {
        MinecraftClient.getInstance().execute {
            readiness.handleReadyResponse(
                clientId,
                attemptId,
                ready,
                reason,
                MinecraftClient.getInstance()
            )
        }
    }

    private fun handlePromoteRequest(handoffId: String, allowReady: Boolean) {
        MinecraftClient.getInstance().execute {
            val blocked = attemptState == PartyAttemptState.PREPARING ||
                    attemptState == PartyAttemptState.JOINING ||
                    (!allowReady && attemptState == PartyAttemptState.READY) ||
                    PartyJoinExecutor.isRunning() || joinCoordinator.session.isActive
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
            if (!primaryTransfer.matches(handoffId, clientId)) return@execute
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
            if (transport?.commitPromotion(clientId, handoffId, selectedClientIds.toSet()) == true) {
                clearPromotion()
            }
        }
    }

    private fun showReadinessStatus(
        client: MinecraftClient,
        state: PartyAttemptState,
        ready: Int,
        selected: Int,
        reason: String?
    ) {
        val message = when (state) {
            PartyAttemptState.READY -> "Party preparation: all selected clients ready"
            PartyAttemptState.FAILED -> "Party preparation failed: ${reason ?: "timeout"}"
            else -> "Party preparation: preparing clients ($ready/$selected)"
        }
        client.inGameHud.setOverlayMessage(Text.literal(message), false)
    }

    private fun clearPromotion() {
        primaryTransfer.clearTransfer()
    }

    private fun clearJoin() {
        joinCoordinator.clear()
    }

    private fun handleTransportStatus(status: String) {
        if (status == "primary") {
            MinecraftClient.getInstance().execute { applyPromotionSelection() }
        }
        if (status.startsWith("peer-disconnected:")) {
            val disconnectedId = status.removePrefix("peer-disconnected:")
            MinecraftClient.getInstance().execute {
                val reconnectingAfterPromotion = primaryTransfer.isReconnecting(
                    disconnectedId,
                    System.currentTimeMillis()
                )
                if (!reconnectingAfterPromotion) selectedClientIds.remove(disconnectedId)
                registry.updateSelection(disconnectedId, false)
                readiness.removeParticipant(disconnectedId)
                joinCoordinator.handleParticipantQuit(disconnectedId, "")
            }
        }
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
        val retainedSelection = primaryTransfer.reconcileSelection(
            selectedClientIds.toSet(),
            clientIds,
            System.currentTimeMillis()
        )
        selectedClientIds.retainAll(retainedSelection)
        registry.replace(values.map { it.copy(selected = selectedClientIds.contains(it.clientId)) })
    }

    private fun cachePromotionSelection(clientIds: Set<String>) {
        primaryTransfer.cacheCommittedSelection(clientIds, System.currentTimeMillis())
    }

    private fun applyPromotionSelection() {
        val selected = primaryTransfer.takePendingSelection() ?: return
        selectedClientIds.clear()
        selectedClientIds.addAll(selected.filterNot { it == clientId })
        registry.snapshot().forEach { snapshot ->
            registry.updateSelection(snapshot.clientId, selectedClientIds.contains(snapshot.clientId))
        }
    }

    private fun currentServerKey(client: MinecraftClient): String =
        client.currentServerEntry?.address?.trim()?.lowercase().orEmpty()
}
