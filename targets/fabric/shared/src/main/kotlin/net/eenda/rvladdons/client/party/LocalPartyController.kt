package net.eenda.rvladdons.client.party

import dev.tako.libs.client.ui.feedback.TakoToastManager
import net.eenda.rvladdons.core.ModEnabledState
import net.eenda.rvladdons.core.RvlAddonsConfigStore
import net.eenda.rvladdons.feature.party.PartyAttemptState
import net.eenda.rvladdons.feature.party.PartyClientSnapshot
import net.eenda.rvladdons.feature.party.PartyPresenceState
import net.eenda.rvladdons.feature.party.PartyRegistry
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.text.Text
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object LocalPartyController {
    private enum class SlotCaptureStage { IDLE, WAITING_TYPE_MENU, TYPE, WAITING_ROOM, ROOM }

    private data class MenuCaptureSnapshot(val syncId: Int, val itemName: String = "")

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
    private var typeMenuBeforeCapture: MenuCaptureSnapshot? = null
    private var slotCaptureDeadline = 0L
    private val selectedClientIds = ConcurrentHashMap.newKeySet<String>()
    private val primaryTransfer = PartyPrimaryTransferCoordinator()
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
        transport = { transport },
        readinessClear = readiness::clear,
        readinessPause = readiness::pauseForJoin,
        activePartyClientIds = ::activePartyClientIds,
        setAttemptState = { attemptState = it },
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
        startJoin = { root, mode -> joinCoordinator.begin(root, mode) },
        cancelJoin = { command -> joinCoordinator.cancel(command, broadcast = true) },
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
                onManualRoomAccepted = joinCoordinator::handleManualRoomAccepted,
                onManualReplayBegin = joinCoordinator::handleManualReplayBegin,
                onManualAck = joinCoordinator::handleManualAck,
                onManualResync = joinCoordinator::handleManualResync,
                onJoinCancel = joinCoordinator::handleJoinCancel,
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
        resetSlotCapture()
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
        readiness.reconcileParticipants(selectedClients().mapTo(HashSet()) { it.clientId })
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
    }

    fun onDisconnect(client: MinecraftClient) {
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
                partyReadyCount = readiness.readyCount(selectedClients(), client)
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
        return commandRouter.handle(command)
    }

    fun onServerMenuOpened() {
        MinecraftClient.getInstance().execute {
            updateSlotCaptureMenu(menuOpenedPacket = true)
            PartyJoinExecutor.onServerMenuOpened()
        }
    }

    fun onPartyMenuEscape() {
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
        if (clientId == this.clientId || primaryTransfer.isPending) return
        if (registry.get(clientId) == null) return

        val handoffId = UUID.randomUUID().toString()
        if (transport?.requestPromotion(clientId, handoffId) != true) {
            client.inGameHud.setOverlayMessage(
                Text.literal("Party: selected client is not connected"),
                false
            )
            return
        }
        readiness.clear()
        attemptState = PartyAttemptState.IDLE
        primaryTransfer.begin(
            handoffId,
            clientId,
            System.currentTimeMillis() + PREPARE_TIMEOUT_MS
        )
        client.inGameHud.setOverlayMessage(Text.literal("Party: transferring primary"), false)
    }


    fun onLocalSlotAction(syncId: Int, slot: Int, button: Int, action: SlotActionType): Boolean {
        if (handleSlotCaptureClick(syncId, slot, button, action)) return true
        joinCoordinator.onLocalSlotAction(slot, button, action)
        return false
    }

    fun startAutomaticSlotCapture() {
        if (!isCoordinator()) {
            slotCaptureNotice("Only the coordinator can configure automatic slots")
            return
        }
        if (attemptState == PartyAttemptState.PREPARING ||
            attemptState == PartyAttemptState.JOINING ||
            PartyJoinExecutor.isRunning()
        ) {
            slotCaptureNotice("Finish the current join before capturing slots")
            return
        }

        slotCaptureStage = SlotCaptureStage.WAITING_TYPE_MENU
        initialTypeMenuSyncId = currentMenuCaptureSnapshot()?.syncId ?: -1
        typeMenuBeforeCapture = null
        slotCaptureDeadline = System.currentTimeMillis() + SLOT_CAPTURE_TIMEOUT_MS
        slotCaptureNotice("Opening /dchien; click the type item when the menu appears")
        if (!PartyJoinExecutor.sendCommand("dchien")) {
            resetSlotCapture()
            slotCaptureNotice("Could not open /dchien")
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
            config.partyAutomaticTypeSlot = slot
            RvlAddonsConfigStore.save()
            typeMenuBeforeCapture = snapshot
            slotCaptureStage = SlotCaptureStage.WAITING_ROOM
            slotCaptureDeadline = System.currentTimeMillis() + SLOT_CAPTURE_TIMEOUT_MS
            slotCaptureNotice("Type slot #$slot captured (${snapshot.itemName.take(24)}); waiting for room menu")
            return false
        }

        config.partyAutomaticRoomSlot = slot
        RvlAddonsConfigStore.save()
        resetSlotCapture()
        slotCaptureNotice("Room slot #$slot captured (${snapshot.itemName.take(24)}); click was blocked")
        return true
    }

    private fun menuCaptureSnapshot(syncId: Int, slot: Int): MenuCaptureSnapshot? {
        val client = MinecraftClient.getInstance()
        if (client.currentScreen !is HandledScreen<*>) return null
        val handler = client.player?.currentScreenHandler ?: return null
        if (handler.syncId != syncId) return null

        val menuSlots = if (handler.slots.size > 36) handler.slots.dropLast(36) else handler.slots
        val clickedStack = menuSlots.getOrNull(slot)?.stack ?: return null
        if (clickedStack.isEmpty) return null

        return MenuCaptureSnapshot(syncId, clickedStack.name.string)
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
        if (!menuOpenedPacket && current.syncId == previous.syncId) return

        slotCaptureStage = SlotCaptureStage.ROOM
        slotCaptureDeadline = System.currentTimeMillis() + SLOT_CAPTURE_TIMEOUT_MS
        slotCaptureNotice("Room menu ready; click its item to capture the room slot")
    }

    private fun currentMenuCaptureSnapshot(): MenuCaptureSnapshot? {
        val client = MinecraftClient.getInstance()
        if (client.currentScreen !is HandledScreen<*>) return null
        val handler = client.player?.currentScreenHandler ?: return null
        return MenuCaptureSnapshot(handler.syncId)
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
        if (!readiness.start(client)) {
            client.inGameHud.setOverlayMessage(Text.literal("Party: preparation could not start"), false)
        }
    }

    fun cancelPreparation(client: MinecraftClient) {
        PartyJoinExecutor.cancel("party preparation cancelled")
        readiness.clear()
        clearPromotion()
        clearJoin()
        attemptState = PartyAttemptState.IDLE
        client.inGameHud.setOverlayMessage(Text.literal("Party preparation cancelled"), false)
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
        val ready = readiness.readyCount(selected, client)
        return "Party ${selected.size}/${clients.size} | Ready $ready/${selected.size} | ${attemptState.name}"
    }

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
            if (transport?.commitPromotion(clientId, handoffId) == true) {
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
        if (status.startsWith("peer-disconnected:")) {
            val disconnectedId = status.removePrefix("peer-disconnected:")
            MinecraftClient.getInstance().execute {
                selectedClientIds.remove(disconnectedId)
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

    private fun createRuntimeClientId(path: Path): String =
        "${loadClientId(path)}-${UUID.randomUUID().toString().take(8)}"

    private fun currentServerKey(client: MinecraftClient): String =
        client.currentServerEntry?.address?.trim()?.lowercase().orEmpty()
}
