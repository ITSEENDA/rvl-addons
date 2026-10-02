package net.eenda.rvladdons.client.party

import net.eenda.rvladdons.core.RvlAddonsConfigStore
import net.eenda.rvladdons.core.RvlTextMatcher
import net.eenda.rvladdons.core.state.BaseState
import net.eenda.rvladdons.core.state.StateMachine
import net.eenda.rvladdons.feature.party.PartyManualActionType
import net.eenda.rvladdons.feature.party.PartyMenuStage
import net.minecraft.client.MinecraftClient
import net.minecraft.screen.ScreenHandler
import net.minecraft.screen.slot.SlotActionType

object PartyJoinExecutor {
    internal const val MENU_TIMEOUT_MS = 5_000L
    internal const val ROOM_TIMEOUT_MS = 2_500L
    internal const val RESULT_TIMEOUT_MS = 10_000L

    internal data class JoinRun(
        val attemptId: String,
        val command: String,
        val typeSlot: Int,
        val roomSlot: Int,
        val baselineSyncId: Int,
        val baselineWorld: String?,
        val baselineX: Double,
        val baselineY: Double,
        val baselineZ: Double,
        var menuSyncId: Int = -1,
        var sawMenuClose: Boolean = false,
        var typeSubmitted: Boolean = false,
        var typeX: Double = 0.0,
        var typeY: Double = 0.0,
        var typeZ: Double = 0.0,
        var roomSubmitted: Boolean = false,
        var submittedX: Double = 0.0,
        var submittedY: Double = 0.0,
        var submittedZ: Double = 0.0,
        var allowanceCost: PartyAllowanceCost? = null,
        var deadline: Long = 0L
    )

    private data class PendingManualAction(
        val sequence: Int,
        val slot: Int,
        val button: Int,
        val action: SlotActionType,
        val intentVersion: Int,
        val type: PartyManualActionType,
        val targetSignature: String
    )

    private val stateMachine = StateMachine()
    internal val idleState = PartyJoinIdleState(this)
    internal val waitingMenuState = PartyJoinWaitingMenuState(this)
    internal val clickTypeState = PartyJoinClickTypeState(this)
    internal val manualTypeMenuState = PartyJoinManualTypeMenuState(this)
    internal val waitingRoomState = PartyJoinWaitingRoomState(this)
    internal val clickRoomState = PartyJoinClickRoomState(this)
    internal val waitingResultState = PartyJoinWaitingResultState(this)
    internal val completedState = PartyJoinCompletedState(this)
    internal val failedState = PartyJoinFailedState(this)

    private var client: MinecraftClient? = null
    private val menuDetector = PartyMenuDetector { client }
    private var run: JoinRun? = null
    private var failureReason: String? = null
    private var successRequested = false
    private var result: ((Boolean, String, PartyAllowanceCost?) -> Unit)? = null
    private var dispatchingCommand = false
    private var manualMode = false
    private val manualPendingActions = ArrayDeque<PendingManualAction>()
    private var manualLastAppliedSequence = 0
    private var manualLastClickSyncId = -1
    private var manualLastHandler: ScreenHandler? = null
    private var manualAwaitingTransition = false
    private var manualWaitingForRefresh = false
    private var manualSkipNextTransition = false
    private var manualMenuReadyNotified = false
    private var manualIntentVersion = 0
    private var manualMenuStage = PartyMenuStage.UNKNOWN
    private var manualTypeFingerprint: String? = null
    private var manualLastActionType: PartyManualActionType? = null
    private var manualRecoveryPending = false
    private var roomSelectionExpected = false
    private var automaticTypeTargetSignature = ""
    private var automaticTypeRetryRequested = false
    private var manualActionAppliedCallback: (Int) -> Unit = {}
    private var manualMenuReadyCallback: () -> Unit = {}
    private var manualMenuStageCallback: (PartyMenuStage, Int) -> Unit = { _, _ -> }
    private var manualResyncCallback: (Int) -> Unit = {}
    private var manualPhaseCallback: (String) -> Unit = {}

    private var typeClickNotBefore = 0L

    init {
        stateMachine.setState(idleState)
        stateMachine.addTransition(failedState) {
            failureReason != null && stateMachine.currentState !== failedState
        }
        stateMachine.addTransitionFrom(waitingMenuState, manualTypeMenuState) {
            waitingMenuState.menuReady && manualMode && failureReason == null
        }
        stateMachine.addTransitionFrom(waitingMenuState, clickTypeState) {
            waitingMenuState.menuReady && !manualMode && failureReason == null
        }
        stateMachine.addTransitionFrom(clickTypeState, waitingRoomState) {
            clickTypeState.clicked && failureReason == null
        }
        stateMachine.addTransitionFrom(manualTypeMenuState, waitingRoomState) {
            manualMenuStage == PartyMenuStage.ROOM_MENU && failureReason == null
        }
        stateMachine.addTransitionFrom(waitingRoomState, completedState) {
            successRequested && failureReason == null
        }
        stateMachine.addTransitionFrom(waitingRoomState, manualTypeMenuState) {
            manualMenuStage == PartyMenuStage.TYPE_MENU && failureReason == null
        }
        stateMachine.addTransitionFrom(waitingRoomState, clickRoomState) {
            waitingRoomState.menuReady && failureReason == null
        }
        stateMachine.addTransitionFrom(waitingRoomState, clickTypeState) {
            automaticTypeRetryRequested && !manualMode && failureReason == null
        }
        stateMachine.addTransitionFrom(clickRoomState, waitingResultState) {
            clickRoomState.clicked && failureReason == null
        }
        stateMachine.addTransitionFrom(waitingRoomState, waitingResultState) {
            currentRun()?.roomSubmitted == true && failureReason == null
        }
        stateMachine.addTransitionFrom(waitingResultState, completedState) {
            successRequested && failureReason == null
        }
        stateMachine.addTransitionFrom(manualTypeMenuState, completedState) {
            successRequested && failureReason == null
        }
        stateMachine.addTransitionFrom(completedState, idleState) { run == null }
        stateMachine.addTransitionFrom(failedState, idleState) { run == null }
    }

    internal fun start(
        client: MinecraftClient,
        attemptId: String,
        command: String,
        typeSlot: Int,
        roomSlot: Int,
        onResult: (Boolean, String, PartyAllowanceCost?) -> Unit
    ) {
        startInternal(
            client,
            attemptId,
            command,
            typeSlot,
            roomSlot,
            false,
            onResult = onResult
        )
    }

    internal fun startManual(
        client: MinecraftClient,
        attemptId: String,
        command: String,
        onActionApplied: (Int) -> Unit,
        onMenuReady: () -> Unit,
        onMenuStage: (PartyMenuStage, Int) -> Unit,
        onResyncRequested: (Int) -> Unit,
        onPhase: (String) -> Unit,
        onResult: (Boolean, String, PartyAllowanceCost?) -> Unit
    ) {
        startInternal(
            client,
            attemptId,
            command,
            0,
            0,
            true,
            onActionApplied,
            onMenuReady,
            onMenuStage,
            onResyncRequested,
            onPhase,
            onResult = onResult
        )
    }

    private fun startInternal(
        client: MinecraftClient,
        attemptId: String,
        command: String,
        typeSlot: Int,
        roomSlot: Int,
        manual: Boolean,
        onActionApplied: (Int) -> Unit = {},
        onMenuReady: () -> Unit = {},
        onMenuStage: (PartyMenuStage, Int) -> Unit = { _, _ -> },
        onResyncRequested: (Int) -> Unit = {},
        onPhase: (String) -> Unit = {},
        onResult: (Boolean, String, PartyAllowanceCost?) -> Unit
    ) {
        if (run != null) {
            onResult(false, "join executor busy", null)
            return
        }
        val player = client.player ?: run {
            onResult(false, "player unavailable", null)
            return
        }
        this.client = client
        val handler = currentHandler()
        val world = client.world
        this.run = JoinRun(
            attemptId = attemptId,
            command = command.removePrefix("/"),
            typeSlot = typeSlot,
            roomSlot = roomSlot,
            baselineSyncId = handler?.syncId ?: -1,
            baselineWorld = world?.registryKey?.toString(),
            baselineX = player.x,
            baselineY = player.y,
            baselineZ = player.z
        )
        failureReason = null
        successRequested = false
        result = onResult
        manualMode = manual
        manualPendingActions.clear()
        manualLastAppliedSequence = 0
        manualLastClickSyncId = -1
        manualLastHandler = null
        manualAwaitingTransition = false
        manualWaitingForRefresh = false
        manualSkipNextTransition = false
        manualMenuReadyNotified = false
        manualIntentVersion = 0
        manualMenuStage = PartyMenuStage.UNKNOWN
        manualTypeFingerprint = null
        manualLastActionType = null
        manualRecoveryPending = false
        roomSelectionExpected = false
        automaticTypeTargetSignature = ""
        automaticTypeRetryRequested = false
        manualActionAppliedCallback = onActionApplied
        manualMenuReadyCallback = onMenuReady
        manualMenuStageCallback = onMenuStage
        manualResyncCallback = onResyncRequested
        manualPhaseCallback = onPhase
        stateMachine.changeState(waitingMenuState)
        dispatchingCommand = true
        try {
            player.networkHandler.sendChatCommand(command.removePrefix("/"))
        } finally {
            dispatchingCommand = false
        }
    }

    fun tick() {
        stateMachine.update()
    }

    fun handleChatMessage(message: String) {
        if (!manualMode && stateMachine.currentState !== waitingResultState) return
        when {
            RvlTextMatcher.contains(message, "BAN KHONG THE THAM GIA PHONG NAY NGAY BAY GIO") ->
                fail("server rejected join")
        }
    }

    fun cancel(reason: String) {
        if (run == null) {
            stateMachine.changeState(idleState)
            return
        }
        manualPhaseCallback("cancelled: $reason")
        complete(false, reason)
        stateMachine.changeState(idleState)
    }

    fun isRunning(): Boolean = run != null

    fun isDispatchingCommand(): Boolean = dispatchingCommand

    fun isManualModeActive(): Boolean = manualMode && run != null

    fun currentManualMenuStage(): PartyMenuStage = manualMenuStage

    internal fun refreshManualMenuStage(): PartyMenuStage? {
        if (!isManualModeActive()) return null
        val detected = detectManualMenuStage() ?: return manualMenuStage
        if (detected != manualMenuStage) updateManualMenuStage(detected)
        return detected
    }

    internal fun isRoomSelectionExpected(): Boolean =
        isManualModeActive() && roomSelectionExpected

    fun currentManualIntentVersion(): Int = manualIntentVersion

    fun manualSlotSignature(slot: Int): String {
        return menuDetector.itemSignature(slot)
    }

    internal fun captureAutomaticTypeMenu() {
        manualTypeFingerprint = currentMenuFingerprint()
        currentRun()?.let { automaticTypeTargetSignature = manualSlotSignature(it.typeSlot) }
    }

    internal fun isTypeMenuVisible(): Boolean =
        manualTypeFingerprint != null && menuDetector.fingerprint() == manualTypeFingerprint

    internal fun requestAutomaticTypeRetry() {
        val currentHandler = currentHandler() ?: return
        currentRun()?.menuSyncId = currentHandler.syncId
        delayTypeClick()
        automaticTypeRetryRequested = true
        debugPhase("type menu refreshed; retrying type selection")
    }

    internal fun delayTypeClick() {
        typeClickNotBefore = System.currentTimeMillis() + menuSettleDelayMs()
    }

    internal fun menuSettleDelayMs(): Long =
        RvlAddonsConfigStore.config.partyMenuSettleDelayMs.toLong()

    internal fun canClickType(): Boolean =
        System.currentTimeMillis() >= typeClickNotBefore

    internal fun clearAutomaticTypeRetry() {
        automaticTypeRetryRequested = false
    }

    internal fun detectManualRoomAccepted(): Boolean {
        if (!manualMode || manualMenuStage != PartyMenuStage.ROOM_MENU) return false
        if (manualLastActionType != PartyManualActionType.SELECT_ROOM || !manualAwaitingTransition) return false
        if (currentHandler() != null) return false
        manualAwaitingTransition = false
        updateManualMenuStage(PartyMenuStage.JOINED)
        markRoomSubmitted()
        debugPhase("room selection submitted; waiting for join confirmation")
        return true
    }

    internal fun markRoomSubmitted() {
        val currentRun = run ?: return
        if (currentRun.roomSubmitted) return
        currentRun.roomSubmitted = true
        val currentClient = client ?: return
        val currentPlayer = currentClient.player ?: return
        currentRun.submittedX = currentPlayer.x
        currentRun.submittedY = currentPlayer.y
        currentRun.submittedZ = currentPlayer.z
    }

    internal fun captureRoomCost(slot: Int) {
        val currentRun = run ?: return
        if (currentRun.allowanceCost != null) return
        val stack = menuDetector.menuStack(slot) ?: return
        currentRun.allowanceCost = PartyAllowanceTracker.roomCost(currentRun.command, stack)
    }

    internal fun receiveRoomCost(amount: Int?) {
        val currentRun = run ?: return
        val value = amount?.takeIf { it > 0 } ?: return
        val root = currentRun.command.trim().substringBefore(' ').lowercase()
        if (root == "dchien" || root == "tl") {
            currentRun.allowanceCost = PartyAllowanceCost(root, value)
        }
    }

    internal fun markJoinSuccess() {
        if (successRequested) return
        successRequested = true
        debugPhase("join confirmation detected")
    }

    internal fun markTypeSubmitted() {
        val currentRun = run ?: return
        if (currentRun.typeSubmitted) return
        currentRun.typeSubmitted = true
        val currentPlayer = client?.player ?: return
        currentRun.typeX = currentPlayer.x
        currentRun.typeY = currentPlayer.y
        currentRun.typeZ = currentPlayer.z
    }

    internal fun markServerJoinConfirmed() {
        val currentRun = run ?: return
        if (!currentRun.typeSubmitted && !currentRun.roomSubmitted) return
        markJoinSuccess()
    }

    internal fun detectJoinSuccess(): Boolean {
        val currentRun = run ?: return false
        val currentClient = client ?: return false
        val canCheckPosition = currentRun.roomSubmitted ||
                (currentRun.typeSubmitted && currentClient.currentScreen == null)
        if (!canCheckPosition) return false
        val currentWorld = currentClient.world?.registryKey?.toString()
        if (currentWorld != null && currentWorld != currentRun.baselineWorld) return true

        val currentPlayer = currentClient.player ?: return false
        val dx = currentPlayer.x - currentRun.baselineX
        val dy = currentPlayer.y - currentRun.baselineY
        val dz = currentPlayer.z - currentRun.baselineZ
        if (dx * dx + dy * dy + dz * dz >= 16.0) return true

        val referenceX = if (currentRun.roomSubmitted) currentRun.submittedX else currentRun.typeX
        val referenceY = if (currentRun.roomSubmitted) currentRun.submittedY else currentRun.typeY
        val referenceZ = if (currentRun.roomSubmitted) currentRun.submittedZ else currentRun.typeZ
        val dxFromSelection = currentPlayer.x - referenceX
        val dyFromSelection = currentPlayer.y - referenceY
        val dzFromSelection = currentPlayer.z - referenceZ
        return dxFromSelection * dxFromSelection + dyFromSelection * dyFromSelection +
                dzFromSelection * dzFromSelection >= 1.0
    }

    internal fun automaticTypeSlot(fallback: Int): Int {
        return menuDetector.findMenuItem(automaticTypeTargetSignature, fallback)
    }

    fun sendCommand(command: String): Boolean {
        val currentClient = MinecraftClient.getInstance()
        val player = currentClient.player ?: return false
        dispatchingCommand = true
        try {
            player.networkHandler.sendChatCommand(command.trim().removePrefix("/"))
            return true
        } finally {
            dispatchingCommand = false
        }
    }

    fun onServerMenuOpened() {
        if (!isManualModeActive()) return
        val currentHandler = currentHandler() ?: return
        val currentRun = run ?: return
        val nextStage = detectManualMenuStage() ?: return

        if (!manualMenuReadyNotified) {
            waitingMenuState.menuReady = true
            currentRun.menuSyncId = currentHandler.syncId
            currentRun.deadline = 0L
            updateManualMenuStage(PartyMenuStage.TYPE_MENU)
            notifyManualMenuReady()
            debugPhase("dchien type menu ready")
            releaseInitialManualAction()
            return
        }

        val previousStage = manualMenuStage
        if (previousStage == PartyMenuStage.ROOM_MENU && nextStage == PartyMenuStage.TYPE_MENU) {
            val newTypeActionPending = manualPendingActions.any {
                it.type == PartyManualActionType.SELECT_TYPE && it.intentVersion >= manualIntentVersion
            }
            if (!newTypeActionPending) manualPendingActions.clear()
            manualWaitingForRefresh = false
            manualAwaitingTransition = false
            roomSelectionExpected = false
            delayTypeClick()
            updateManualMenuStage(PartyMenuStage.TYPE_MENU)
            debugPhase("room selection invalidated; waiting for coordinator")
            return
        }

        updateManualMenuStage(nextStage)
        if (manualAwaitingTransition) {
            manualAwaitingTransition = false
            manualLastHandler = currentHandler
            manualLastClickSyncId = currentHandler.syncId
            if (manualSkipNextTransition) {
                manualSkipNextTransition = false
                debugPhase("menu navigation received; waiting for room refresh")
                return
            }
            debugPhase("server menu barrier received")
        }
        manualLastHandler = currentHandler
        manualLastClickSyncId = currentHandler.syncId
        if (previousStage == PartyMenuStage.ROOM_MENU && nextStage == PartyMenuStage.ROOM_MENU) {
            manualWaitingForRefresh = false
        }
        processManualActions()
    }

    fun queueManualClick(
        sequence: Int,
        slot: Int,
        button: Int,
        action: String,
        intentVersion: Int,
        type: String,
        targetSignature: String
    ): Boolean {
        if (!isManualModeActive() || sequence <= manualLastAppliedSequence) return false
        val slotAction = runCatching { SlotActionType.valueOf(action) }.getOrNull() ?: run {
            fail("unknown slot action $action")
            return false
        }
        val actionType = runCatching { PartyManualActionType.valueOf(type) }.getOrNull() ?: run {
            fail("unknown party action type $type")
            return false
        }
        if (intentVersion < manualIntentVersion) return false
        if (intentVersion > manualIntentVersion) {
            manualPendingActions.clear()
            manualAwaitingTransition = false
            manualWaitingForRefresh = false
            manualSkipNextTransition = false
            manualIntentVersion = intentVersion
        }
        if (actionType == PartyManualActionType.SELECT_TYPE) {
            manualPendingActions.removeIf {
                it.type == PartyManualActionType.SELECT_ROOM && it.intentVersion <= intentVersion
            }
            manualWaitingForRefresh = false
        }
        if (actionType == PartyManualActionType.SELECT_ROOM &&
            (manualPendingActions.any {
                it.type == PartyManualActionType.SELECT_ROOM && it.intentVersion == intentVersion
            } || (manualLastActionType == PartyManualActionType.SELECT_ROOM &&
                    manualAwaitingTransition && manualIntentVersion == intentVersion))
        ) {
            return false
        }
        if (manualPendingActions.none { it.sequence == sequence }) {
            if (actionType == PartyManualActionType.SELECT_TYPE &&
                manualLastActionType == PartyManualActionType.SELECT_ROOM
            ) {
                delayTypeClick()
            }
            manualPendingActions.addLast(
                PendingManualAction(
                    sequence,
                    slot,
                    button,
                    slotAction,
                    intentVersion,
                    actionType,
                    targetSignature
                )
            )
            manualWaitingForRefresh = actionType == PartyManualActionType.SELECT_ROOM
        }
        return true
    }

    fun queueManualRecoveryType(
        sequence: Int,
        slot: Int,
        button: Int,
        action: String,
        intentVersion: Int,
        targetSignature: String,
        allowanceCost: Int?
    ): Boolean {
        if (!isManualModeActive()) return false
        receiveRoomCost(allowanceCost)
        val slotAction = runCatching { SlotActionType.valueOf(action) }.getOrNull() ?: return false
        if (intentVersion < manualIntentVersion) return false
        manualIntentVersion = maxOf(manualIntentVersion, intentVersion)
        manualPendingActions.removeIf { it.type == PartyManualActionType.SELECT_ROOM }
        manualWaitingForRefresh = false
        manualAwaitingTransition = false
        manualSkipNextTransition = false
        manualPendingActions.removeIf { it.sequence == sequence && it.type == PartyManualActionType.SELECT_TYPE }
        manualPendingActions.addFirst(
            PendingManualAction(
                sequence,
                slot,
                button,
                slotAction,
                intentVersion,
                PartyManualActionType.SELECT_TYPE,
                targetSignature
            )
        )
        manualRecoveryPending = true
        delayTypeClick()
        if (manualMenuStage == PartyMenuStage.TYPE_MENU) processManualActions()
        return true
    }

    internal fun observeLocalRoomSelection() {
        if (!isManualModeActive()) return
        markRoomSubmitted()
        val handler = currentHandler()
        roomSelectionExpected = false
        updateManualMenuStage(PartyMenuStage.ROOM_MENU)
        manualLastActionType = PartyManualActionType.SELECT_ROOM
        manualLastClickSyncId = handler?.syncId ?: -1
        manualLastHandler = handler
        manualWaitingForRefresh = false
        manualAwaitingTransition = true
        debugPhase("local room selection observed")
    }

    fun resetManualReplay() {
        if (!isManualModeActive()) return
        manualPendingActions.clear()
        manualLastAppliedSequence = 0
        manualLastClickSyncId = -1
        manualLastHandler = currentHandler()
        manualAwaitingTransition = false
        manualWaitingForRefresh = false
        manualSkipNextTransition = false
        manualMenuReadyNotified = false
        manualRecoveryPending = false
        roomSelectionExpected = false
    }

    fun recordManualCoordinatorAction(
        sequence: Int,
        intentVersion: Int,
        type: PartyManualActionType,
        targetSignature: String = ""
    ) {
        if (!isManualModeActive()) return
        val handler = currentHandler()
        manualIntentVersion = intentVersion
        manualLastActionType = type
        if (type == PartyManualActionType.SELECT_TYPE) markTypeSubmitted()
        roomSelectionExpected = type == PartyManualActionType.SELECT_TYPE
        manualLastAppliedSequence = sequence
        manualLastClickSyncId = handler?.syncId ?: -1
        manualLastHandler = handler
        manualAwaitingTransition = true
    }

    internal fun currentRun(): JoinRun? = run

    internal fun currentHandler(): ScreenHandler? {
        return menuDetector.handler()
    }

    internal fun hasMenuItem(slot: Int): Boolean =
        menuDetector.hasMenuItem(slot)

    private fun detectManualMenuStage(): PartyMenuStage? {
        return menuDetector.stage(manualTypeFingerprint)
    }

    internal fun currentMenuFingerprint(): String? {
        return menuDetector.fingerprint()
    }

    private fun updateManualMenuStage(stage: PartyMenuStage) {
        if (manualMenuStage == stage) return
        manualMenuStage = stage
        manualMenuStageCallback(stage, manualIntentVersion)
    }

    internal fun markManualInitialMenuReady() {
        if (!isManualModeActive() || manualMenuReadyNotified) return
        val handler = currentHandler() ?: return
        run?.menuSyncId = handler.syncId
        manualTypeFingerprint = currentMenuFingerprint()
        updateManualMenuStage(PartyMenuStage.TYPE_MENU)
        notifyManualMenuReady()
        releaseInitialManualAction()
    }

    internal fun clickSlot(slot: Int): Boolean {
        return clickSlotAction(slot, 0, SlotActionType.PICKUP)
    }

    internal fun processManualActions() {
        if (!isManualModeActive()) return
        val handler = currentHandler() ?: return
        if (manualAwaitingTransition) {
            if (handler !== manualLastHandler || handler.syncId != manualLastClickSyncId) {
                val previousStage = manualMenuStage
                val nextStage = detectManualMenuStage() ?: PartyMenuStage.UNKNOWN
                manualAwaitingTransition = false
                manualLastHandler = handler
                manualLastClickSyncId = handler.syncId
                updateManualMenuStage(nextStage)
                if (previousStage == PartyMenuStage.TYPE_MENU &&
                    nextStage == PartyMenuStage.ROOM_MENU &&
                    manualLastActionType == PartyManualActionType.SELECT_TYPE
                ) {
                    debugPhase("type menu navigation received")
                    return
                }
                if (manualSkipNextTransition) {
                    manualSkipNextTransition = false
                    debugPhase("menu navigation received; waiting for room refresh")
                    return
                }
                manualWaitingForRefresh = false
                debugPhase("server menu barrier received")
            } else {
                return
            }
        }
        if (manualLastHandler != null &&
            (handler !== manualLastHandler || handler.syncId != manualLastClickSyncId)
        ) {
            manualLastHandler = handler
            manualLastClickSyncId = handler.syncId
            manualWaitingForRefresh = false
            debugPhase("server menu barrier received")
        }
        if (manualWaitingForRefresh) return
        val pending = manualPendingActions.firstOrNull()
        if (pending != null) {
            if (pending.type == PartyManualActionType.SELECT_TYPE &&
                manualMenuStage != PartyMenuStage.TYPE_MENU
            ) return
            if (pending.type == PartyManualActionType.SELECT_TYPE && !canClickType()) return
            if (pending.type == PartyManualActionType.SELECT_ROOM &&
                manualMenuStage != PartyMenuStage.ROOM_MENU
            ) return
            val targetSlot = resolveManualSlot(pending)
            if (pending.type == PartyManualActionType.SELECT_ROOM) captureRoomCost(targetSlot)
            if (pending.type == PartyManualActionType.SELECT_ROOM) markRoomSubmitted()
            if (clickSlotAction(targetSlot, pending.button, pending.action)) {
                manualPendingActions.removeFirst()
                manualLastActionType = pending.type
                if (pending.type == PartyManualActionType.SELECT_TYPE) markTypeSubmitted()
                manualLastAppliedSequence = pending.sequence
                manualLastClickSyncId = handler.syncId
                manualLastHandler = handler
                manualAwaitingTransition = true
                if (pending.type == PartyManualActionType.SELECT_TYPE && manualRecoveryPending) {
                    markRoomSubmitted()
                    manualRecoveryPending = false
                }
                manualActionAppliedCallback(pending.sequence)
                debugPhase("applied action #${pending.sequence}")
            }
            return
        }
    }

    internal fun releaseInitialManualAction() {
        if (manualLastAppliedSequence != 0 || manualPendingActions.isEmpty()) return
        manualWaitingForRefresh = false
        processManualActions()
    }

    private fun resolveManualSlot(action: PendingManualAction): Int {
        return menuDetector.findMenuItem(action.targetSignature, action.slot)
    }

    private fun clickSlotAction(slot: Int, button: Int, action: SlotActionType): Boolean {
        val currentClient = client ?: return false
        val handler = currentHandler() ?: return false
        val player = currentClient.player ?: return false
        if (slot !in handler.slots.indices) {
            fail("invalid slot $slot")
            return false
        }
        debugPhase("clicking slot: $slot")
        currentClient.interactionManager?.clickSlot(handler.syncId, slot, button, action, player)
            ?: return false
        return true
    }

    internal fun fail(reason: String) {
        if (failureReason == null) failureReason = reason
        manualPhaseCallback("failed: $reason")
    }

    internal fun debugPhase(message: String) {
        if (manualMode) manualPhaseCallback(message)
    }

    internal fun complete(success: Boolean, reason: String = "") {
        val committedCost = run?.allowanceCost.takeIf { success }
        client?.player?.closeHandledScreen()
        val callback = result
        result = null
        run = null
        failureReason = null
        successRequested = false
        manualMode = false
        manualPendingActions.clear()
        manualAwaitingTransition = false
        manualWaitingForRefresh = false
        manualSkipNextTransition = false
        manualMenuReadyNotified = false
        roomSelectionExpected = false
        automaticTypeRetryRequested = false
        manualActionAppliedCallback = {}
        manualMenuReadyCallback = {}
        manualResyncCallback = {}
        manualPhaseCallback = {}
        callback?.invoke(success, reason, committedCost)
    }

    internal fun notifyManualMenuReady() {
        if (!manualMode || manualMenuReadyNotified) return
        manualMenuReadyNotified = true
        manualMenuReadyCallback()
    }
}

internal abstract class PartyJoinState(protected val owner: PartyJoinExecutor) : BaseState()

internal class PartyJoinIdleState(owner: PartyJoinExecutor) : PartyJoinState(owner)

internal class PartyJoinWaitingMenuState(owner: PartyJoinExecutor) : PartyJoinState(owner) {
    internal var menuReady = false

    override fun onEnter() {
        menuReady = false
        owner.debugPhase("waiting for dchien menu")
        owner.currentRun()?.deadline = System.currentTimeMillis() + PartyJoinExecutor.MENU_TIMEOUT_MS
    }

    override fun onUpdate() {
        val run = owner.currentRun() ?: return
        if (System.currentTimeMillis() >= run.deadline) {
            owner.fail("dchien menu timeout")
            return
        }
        val handler = owner.currentHandler() ?: return
        if (run.baselineSyncId < 0 || handler.syncId != run.baselineSyncId) {
            run.menuSyncId = handler.syncId
            menuReady = true
            owner.captureAutomaticTypeMenu()
            owner.debugPhase("dchien menu ready")
        }
    }
}

internal class PartyJoinManualTypeMenuState(owner: PartyJoinExecutor) : PartyJoinState(owner) {
    override fun onEnter() {
        owner.markManualInitialMenuReady()
        owner.debugPhase("type menu stage")
    }

    override fun onUpdate() {
        owner.processManualActions()
    }
}

internal class PartyJoinClickTypeState(owner: PartyJoinExecutor) : PartyJoinState(owner) {
    internal var clicked = false

    override fun onEnter() {
        clicked = false
        owner.clearAutomaticTypeRetry()
    }

    override fun onUpdate() {
        val run = owner.currentRun() ?: return
        if (!owner.canClickType()) return
        if (owner.currentHandler()?.syncId != run.menuSyncId) return
        clicked = owner.clickSlot(owner.automaticTypeSlot(run.typeSlot))
        if (clicked) owner.markTypeSubmitted()
        if (!clicked) owner.fail("type menu unavailable")
    }
}

internal class PartyJoinWaitingRoomState(owner: PartyJoinExecutor) : PartyJoinState(owner) {
    internal var menuReady = false
    private var candidateFingerprint: String? = null
    private var roomReadyAt = 0L
    override fun onEnter() {
        menuReady = false
        candidateFingerprint = null
        roomReadyAt = 0L
        if (owner.isManualModeActive()) {
            owner.debugPhase("room menu stage")
            return
        }
        owner.currentRun()?.apply {
            sawMenuClose = false
            deadline = System.currentTimeMillis() + PartyJoinExecutor.ROOM_TIMEOUT_MS
        }
    }

    override fun onUpdate() {
        if (owner.isManualModeActive()) {
            if (owner.detectJoinSuccess()) {
                owner.markJoinSuccess()
                return
            }
            if (owner.detectManualRoomAccepted()) return
            owner.processManualActions()
            return
        }
        val run = owner.currentRun() ?: return
        if (owner.detectJoinSuccess()) {
            owner.markJoinSuccess()
            return
        }
        val now = System.currentTimeMillis()

        if (now >= run.deadline) {
            owner.fail("room menu timeout")
            return
        }
        val handler = owner.currentHandler()
        if (handler == null) {
            run.sawMenuClose = true
            return
        }

        val fingerprint = owner.currentMenuFingerprint() ?: return
        val handlerChanged = handler.syncId != run.menuSyncId || run.sawMenuClose

        if (owner.isTypeMenuVisible()) {
            if (!handlerChanged) return

            menuReady = false
            owner.requestAutomaticTypeRetry()
            return
        }
        if (candidateFingerprint != fingerprint) {
            candidateFingerprint = fingerprint
            roomReadyAt = now + PartyJoinExecutor.menuSettleDelayMs()
            return
        }

        if (now < roomReadyAt) return
        if (!owner.hasMenuItem(run.roomSlot)) return

        menuReady = true
    }
}

internal class PartyJoinClickRoomState(owner: PartyJoinExecutor) : PartyJoinState(owner) {
    internal var clicked = false

    override fun onEnter() {
        clicked = false
    }

    override fun onUpdate() {
        val run = owner.currentRun() ?: return
        if (!owner.hasMenuItem(run.roomSlot)) return
        owner.captureRoomCost(run.roomSlot)
        owner.markRoomSubmitted()
        clicked = owner.clickSlot(run.roomSlot)
        if (!clicked) owner.fail("room menu unavailable")
    }
}

internal class PartyJoinWaitingResultState(owner: PartyJoinExecutor) : PartyJoinState(owner) {
    override fun onEnter() {
        owner.currentRun()?.deadline = System.currentTimeMillis() + PartyJoinExecutor.RESULT_TIMEOUT_MS
    }

    override fun onUpdate() {
        val run = owner.currentRun() ?: return
        if (System.currentTimeMillis() >= run.deadline) {
            owner.fail("join result timeout")
            return
        }
        if (owner.detectJoinSuccess()) owner.markJoinSuccess()
        owner.processManualActions()
    }
}

internal class PartyJoinCompletedState(owner: PartyJoinExecutor) : PartyJoinState(owner) {
    override fun onEnter() {
        owner.complete(true)
    }
}

internal class PartyJoinFailedState(owner: PartyJoinExecutor) : PartyJoinState(owner) {
    override fun onEnter() {
        owner.complete(false, "join failed")
    }
}
