package net.eenda.rvladdons.client.party

import dev.tako.libs.client.ui.feedback.TakoToastManager
import net.eenda.rvladdons.core.ModEnabledState
import net.eenda.rvladdons.core.RvlAddonsConfigStore
import net.eenda.rvladdons.core.GameplayServerState
import net.eenda.rvladdons.feature.party.PartyAttemptState
import net.eenda.rvladdons.feature.party.PartyClientSnapshot
import net.eenda.rvladdons.feature.party.PartyJoinExecutionMode
import net.eenda.rvladdons.feature.party.PartyPresenceState
import net.eenda.rvladdons.feature.party.PartyRegistry
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.item.Items
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.text.Text
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object LocalPartyController {
    private enum class SlotCaptureStage { IDLE, WAITING_TYPE_MENU, TYPE, WAITING_ROOM, ROOM }

    private data class MenuCaptureSnapshot(
        val syncId: Int,
        val fingerprint: String,
        val itemName: String = ""
    )

    private val synchronizedCommandRoots = setOf("dchien", "tl")
    private const val ID_FILE = "rvl-addons-party.properties"
    private const val PREPARE_TIMEOUT_MS = 2_000L
    private const val SLOT_CAPTURE_TIMEOUT_MS = 15_000L
    private val registry = PartyRegistry()
    private var transport: LocalPartyTransport? = null
    private var clientId = ""
    private var attemptState = PartyAttemptState.IDLE
    private var lastLocalUpdate = 0L
    private var slotCaptureStage = SlotCaptureStage.IDLE
    private var initialTypeMenuSyncId = -1
    private var slotCaptureCommandRoot = "dchien"
    private var typeMenuBeforeCapture: MenuCaptureSnapshot? = null
    private var slotCaptureDeadline = 0L
    private var allowanceScanStarted = false
    @Volatile private var pendingPromotionSelection: Set<String>? = null
    @Volatile private var promotionReconnectIds: Set<String> = emptySet()
    @Volatile private var promotionReconnectDeadline = 0L
    private var pendingAutomaticHandoffRoot: String? = null
    private var pendingAutomaticHandoffDeadline = 0L
    private val selectedClientIds = ConcurrentHashMap.newKeySet<String>()
    private val primaryTransfer = PartyPrimaryTransferCoordinator()
    private val allowances = PartyAllowanceTracker(::debugToast)
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
                        snapshot.dchienRunsRemaining,
                        snapshot.tlTicketsRemaining
                    )
                }
            val playerName = MinecraftClient.getInstance().player?.name?.string ?: "This client"
            remoteCandidates + RoomCreatorCandidate(
                clientId,
                "$server|${playerName.lowercase(Locale.ROOT)}",
                playerName,
                allowances.dchienRemaining,
                allowances.tlTicketsRemaining
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
        unselectExhaustedClient = ::unselectExhaustedClient,
        localRunsRemaining = allowances::remaining,
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
            stopAllowanceScan()
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


    fun initialize() {
        if (!ModEnabledState.isEnabled()) return
        transport?.let { current ->
            if (current.running) return
            current.stop()
            transport = null
        }
        clientId = createRuntimeClientId(
            FabricLoader.getInstance().configDir.resolve(ID_FILE)
        )
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
                if (client.player != null) publishPresence(client, PartyPresenceState.IN_SERVER)
            }
            return
        }
        stopAllowanceScan()
        allowances.stopScan()
        resetSlotCapture()
        pendingAutomaticHandoffRoot = null
        pendingAutomaticHandoffDeadline = 0L
        pendingPromotionSelection = null
        promotionReconnectIds = emptySet()
        promotionReconnectDeadline = 0L
        PartyJoinExecutor.cancel("party disabled")
        transport?.stop()
        transport = null
        attemptState = PartyAttemptState.IDLE
        selectedClientIds.clear()
        readiness.clear()
        clearPromotion()
        clearJoin()
        registry.replace(emptyList())
    }

    fun tick(client: MinecraftClient) {
        PartyJoinExecutor.tick()
        val now = System.currentTimeMillis()
        joinCoordinator.tick(now)
        tickSlotCapture(now)
        if (!PartyJoinExecutor.isRunning() && slotCaptureStage == SlotCaptureStage.IDLE) {
            allowances.tick(client, PartyJoinExecutor::sendCommand)
        }
        tryStartAllowanceScan(client)
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
            primaryTransfer.clear()
            client.inGameHud.setOverlayMessage(Text.literal("Party: primary transfer timed out"), false)
        }
    }

    fun onJoin(client: MinecraftClient) {
        publishPresence(client, PartyPresenceState.IN_SERVER)
        readiness.tick(client)
        onGameplayServerDetected()
    }

    fun onGameplayServerDetected() {
        tryStartAllowanceScan(MinecraftClient.getInstance())
    }

    fun onGameplayServerLeft() {
        allowanceScanStarted = false
        allowances.reset()
    }

    private fun tryStartAllowanceScan(client: MinecraftClient) {
        if (allowanceScanStarted || !ModEnabledState.isEnabled() || !GameplayServerState.isActive()) return
        if (PartyJoinExecutor.isRunning() || slotCaptureStage != SlotCaptureStage.IDLE) return
        if (allowances.isScanning()) {
            allowanceScanStarted = true
            return
        }
        allowanceScanStarted = allowances.start(client, PartyJoinExecutor::sendCommand)
    }

    private fun stopAllowanceScan() {
        if (allowances.isScanning()) allowanceScanStarted = false
        allowances.stopScan()
    }

    private fun beginPartyJoin(commandRoot: String, mode: PartyJoinExecutionMode) {
        unselectExhaustedParticipants(commandRoot)
        readiness.reconcileParticipants(selectedClients().mapTo(HashSet()) { it.clientId })

        if (allowances.remaining(commandRoot) == 0) {
            if (mode == PartyJoinExecutionMode.AUTOMATIC && isCoordinator()) {
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

        if (selectedClients().isEmpty()) {
            PartyJoinExecutor.sendCommand(commandRoot)
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

    private fun commitAllowance(cost: PartyAllowanceCost?) {
        allowances.commit(cost)
        val client = MinecraftClient.getInstance()
        publishPresence(
            client,
            if (client.player != null && client.world != null) PartyPresenceState.IN_SERVER
            else PartyPresenceState.MAIN_MENU
        )
        if (cost != null && isCoordinator() &&
            RvlAddonsConfigStore.config.partyJoinExecutionMode == PartyJoinExecutionMode.AUTOMATIC &&
            allowances.remaining(cost.commandRoot) == 0
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
        allowanceScanStarted = false
        allowances.reset()
        pendingAutomaticHandoffRoot = null
        pendingAutomaticHandoffDeadline = 0L
        pendingPromotionSelection = null
        promotionReconnectIds = emptySet()
        promotionReconnectDeadline = 0L
        resetSlotCapture()
        attemptState = PartyAttemptState.IDLE
        selectedClientIds.clear()
        readiness.clear()
        clearPromotion()
        joinCoordinator.stopRoomMonitor()
        PartyJoinExecutor.cancel("disconnected")
        clearJoin()
        publishPresence(client, PartyPresenceState.DISCONNECTED)
    }

    fun isEligible(snapshot: PartyClientSnapshot, client: MinecraftClient): Boolean =
        snapshot.clientId != clientId &&
                snapshot.state == PartyPresenceState.IN_SERVER &&
                snapshot.serverKey == currentServerKey(client) &&
                !(snapshot.dchienRunsRemaining == 0 && snapshot.tlTicketsRemaining == 0)

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
            updateSlotCaptureMenu(menuOpenedPacket = true)
            PartyJoinExecutor.onServerMenuOpened()
        }
    }

    fun onPartyMenuEscape() {
        if (MinecraftClient.getInstance().currentScreen !is HandledScreen<*>) return
        if (slotCaptureStage != SlotCaptureStage.IDLE) {
            resetSlotCapture()
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
        updateSlotCaptureMenu()
        if (handleSlotCaptureClick(syncId, slot, button, action)) return true
        joinCoordinator.onLocalSlotAction(slot, button, action)
        return false
    }

    fun startAutomaticSlotCapture(commandRoot: String) {
        if (!isCoordinator()) {
            slotCaptureNotice("Only the coordinator can configure automatic slots")
            return
        }
        val root = commandRoot.trim().removePrefix("/").lowercase(Locale.ROOT)
        if (root != "dchien" && root != "tl") return
        if (attemptState == PartyAttemptState.PREPARING ||
            attemptState == PartyAttemptState.JOINING ||
            PartyJoinExecutor.isRunning()
        ) {
            slotCaptureNotice("Finish the current join before capturing slots")
            return
        }

        allowances.stopScan()
        stopAllowanceScan()
        slotCaptureStage = SlotCaptureStage.WAITING_TYPE_MENU
        slotCaptureCommandRoot = root
        initialTypeMenuSyncId = currentMenuCaptureSnapshot()?.syncId ?: -1
        typeMenuBeforeCapture = null
        slotCaptureDeadline = System.currentTimeMillis() + SLOT_CAPTURE_TIMEOUT_MS
        slotCaptureNotice("Opening /$root; click the type item when the menu appears")
        if (!PartyJoinExecutor.sendCommand(root)) {
            resetSlotCapture()
            slotCaptureNotice("Could not open /$root")
        }
    }

    private fun handleSlotCaptureClick(
        syncId: Int,
        slot: Int,
        button: Int,
        action: SlotActionType
    ): Boolean {
        if (slotCaptureStage == SlotCaptureStage.IDLE) return false
        if (slotCaptureStage == SlotCaptureStage.WAITING_TYPE_MENU ||
            slotCaptureStage == SlotCaptureStage.WAITING_ROOM
        ) {
            return true
        }
        if (button != 0 || action != SlotActionType.PICKUP) {
            slotCaptureNotice("Use a left click on a menu item")
            return true
        }

        val snapshot = menuCaptureSnapshot(syncId, slot) ?: run {
            slotCaptureNotice("Click a filled menu slot, not your inventory")
            return true
        }
        val config = RvlAddonsConfigStore.config
        if (slotCaptureStage == SlotCaptureStage.TYPE) {
            if (slotCaptureCommandRoot == "tl") config.partyTlAutomaticTypeSlot = slot
            else config.partyAutomaticTypeSlot = slot
            RvlAddonsConfigStore.save()
            typeMenuBeforeCapture = snapshot
            slotCaptureStage = SlotCaptureStage.WAITING_ROOM
            slotCaptureDeadline = System.currentTimeMillis() + SLOT_CAPTURE_TIMEOUT_MS
            slotCaptureNotice("/$slotCaptureCommandRoot type #$slot captured (${snapshot.itemName.take(24)}); waiting for room menu")
            return false
        }

        if (slotCaptureCommandRoot == "tl") {
            config.partyTlAutomaticRoomSlot = slot
            config.partyTlAutomaticRoomName = snapshot.itemName
        } else {
            config.partyAutomaticRoomSlot = slot
            config.partyAutomaticRoomName = snapshot.itemName
        }
        RvlAddonsConfigStore.save()
        val capturedCommand = slotCaptureCommandRoot
        resetSlotCapture()
        MinecraftClient.getInstance().player?.closeHandledScreen()
        slotCaptureNotice("/$capturedCommand room #$slot captured (${snapshot.itemName.take(24)}); click blocked")
        return true
    }

    private fun menuCaptureSnapshot(syncId: Int, slot: Int): MenuCaptureSnapshot? {
        val client = MinecraftClient.getInstance()
        val screen = client.currentScreen as? HandledScreen<*> ?: return null
        val handler = client.player?.currentScreenHandler ?: return null
        if (handler.syncId != syncId) return null

        val menuSlots = if (handler.slots.size > 36) handler.slots.dropLast(36) else handler.slots
        val clickedStack = menuSlots.getOrNull(slot)?.stack ?: return null
        if (clickedStack.isEmpty || clickedStack.item == Items.BLACK_STAINED_GLASS_PANE ||
            clickedStack.item == Items.BOOK
        ) return null

        return MenuCaptureSnapshot(
            syncId,
            menuFingerprint(screen.title.string, menuSlots),
            clickedStack.name.string
        )
    }

    private fun updateSlotCaptureMenu(menuOpenedPacket: Boolean = false) {
        if (slotCaptureStage == SlotCaptureStage.WAITING_TYPE_MENU) {
            val current = currentMenuCaptureSnapshot() ?: return
            if (!menuOpenedPacket && initialTypeMenuSyncId >= 0 && current.syncId == initialTypeMenuSyncId) return
            initialTypeMenuSyncId = -1
            slotCaptureStage = SlotCaptureStage.TYPE
            slotCaptureDeadline = System.currentTimeMillis() + SLOT_CAPTURE_TIMEOUT_MS
            slotCaptureNotice("Type menu ready; click its item to capture the type slot")
            return
        }
        if (slotCaptureStage != SlotCaptureStage.WAITING_ROOM) return
        val current = currentMenuCaptureSnapshot() ?: return
        val previous = typeMenuBeforeCapture ?: return resetSlotCapture()
        if (!menuOpenedPacket && current.syncId == previous.syncId && current.fingerprint == previous.fingerprint) return

        slotCaptureStage = SlotCaptureStage.ROOM
        slotCaptureDeadline = System.currentTimeMillis() + SLOT_CAPTURE_TIMEOUT_MS
        slotCaptureNotice("Room menu ready; click its item to capture the room slot")
    }

    private fun currentMenuCaptureSnapshot(): MenuCaptureSnapshot? {
        val client = MinecraftClient.getInstance()
        val screen = client.currentScreen as? HandledScreen<*> ?: return null
        val handler = client.player?.currentScreenHandler ?: return null
        val menuSlots = if (handler.slots.size > 36) handler.slots.dropLast(36) else handler.slots
        return MenuCaptureSnapshot(handler.syncId, menuFingerprint(screen.title.string, menuSlots))
    }

    private fun menuFingerprint(title: String, slots: List<net.minecraft.screen.slot.Slot>): String =
        "$title|${slots.size}|" + slots.joinToString("|") { slot ->
            val stack = slot.stack
            if (stack.isEmpty) "empty" else "${stack.name.string}:${stack.count}"
        }

    private fun tickSlotCapture(now: Long) {
        updateSlotCaptureMenu()
        if (slotCaptureStage != SlotCaptureStage.IDLE && now >= slotCaptureDeadline) {
            resetSlotCapture()
            slotCaptureNotice("Slot capture timed out")
        }
    }

    private fun resetSlotCapture() {
        slotCaptureStage = SlotCaptureStage.IDLE
        slotCaptureCommandRoot = "dchien"
        initialTypeMenuSyncId = -1
        typeMenuBeforeCapture = null
        slotCaptureDeadline = 0L
    }

    private fun slotCaptureNotice(message: String) {
        MinecraftClient.getInstance().execute {
            TakoToastManager.show("Party: $message")
        }
    }

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
            coordinator.partyState.name
        } else {
            attemptState.name
        }
        return listOf(
            "Role" to role,
            "State" to state,
            "Doan chien ticket(s)" to (allowances.dchienRemaining?.toString() ?: "?"),
            "Thanh lan ticket(s)" to (allowances.tlTicketsRemaining?.toString() ?: "?")
        )
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
        primaryTransfer.clear()
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
                val reconnectingAfterPromotion = disconnectedId in promotionReconnectIds &&
                        System.currentTimeMillis() <= promotionReconnectDeadline
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
        if (System.currentTimeMillis() <= promotionReconnectDeadline) {
            selectedClientIds.retainAll(clientIds + promotionReconnectIds)
            promotionReconnectIds = promotionReconnectIds - clientIds
        } else {
            selectedClientIds.retainAll(clientIds)
            promotionReconnectIds = emptySet()
            promotionReconnectDeadline = 0L
        }
        registry.replace(values.map { it.copy(selected = selectedClientIds.contains(it.clientId)) })
    }

    private fun cachePromotionSelection(clientIds: Set<String>) {
        pendingPromotionSelection = clientIds
        promotionReconnectIds = clientIds
        promotionReconnectDeadline = System.currentTimeMillis() + 5_000L
    }

    private fun applyPromotionSelection() {
        val selected = pendingPromotionSelection ?: return
        selectedClientIds.clear()
        selectedClientIds.addAll(selected.filterNot { it == clientId })
        pendingPromotionSelection = null
        registry.snapshot().forEach { snapshot ->
            registry.updateSelection(snapshot.clientId, selectedClientIds.contains(snapshot.clientId))
        }
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

    private fun createRuntimeClientId(path: Path): String =
        "${loadClientId(path)}-${UUID.randomUUID().toString().take(8)}"

    private fun currentServerKey(client: MinecraftClient): String =
        client.currentServerEntry?.address?.trim()?.lowercase().orEmpty()
}
