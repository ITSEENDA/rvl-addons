package net.eenda.rvladdons.client.party

import net.eenda.rvladdons.core.RvlAddonsConfigStore
import net.eenda.rvladdons.core.RvlTextMatcher
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
    private val manualReplay = PartyManualReplay(this)
    private var run: JoinRun? = null
    private var failureReason: String? = null
    private var successRequested = false
    private var result: ((Boolean, String, PartyAllowanceCost?) -> Unit)? = null
    private var dispatchingCommand = false
    private var manualMode = false
    private var automaticTypeTargetSignature = ""
    private var automaticTypeRetryRequested = false
    private var allowanceReader: (String) -> Int? = { null }

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
            manualReplay.menuStage == PartyMenuStage.ROOM_MENU && failureReason == null
        }
        stateMachine.addTransitionFrom(waitingRoomState, completedState) {
            successRequested && failureReason == null
        }
        stateMachine.addTransitionFrom(waitingRoomState, manualTypeMenuState) {
            manualReplay.menuStage == PartyMenuStage.TYPE_MENU && failureReason == null
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
        manualReplay.start(
            onActionApplied,
            onMenuReady,
            onMenuStage,
            onResyncRequested,
            onPhase
        )
        automaticTypeTargetSignature = ""
        automaticTypeRetryRequested = false
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
        manualReplay.onCancelled(reason)
        complete(false, reason)
        stateMachine.changeState(idleState)
    }

    fun isRunning(): Boolean = run != null

    fun isDispatchingCommand(): Boolean = dispatchingCommand

    fun isManualModeActive(): Boolean = manualMode && run != null

    fun currentManualMenuStage(): PartyMenuStage = manualReplay.currentStage()

    internal fun refreshManualMenuStage(): PartyMenuStage? = manualReplay.refreshMenuStage()

    internal fun isRoomSelectionExpected(): Boolean = manualReplay.isRoomSelectionExpected()

    fun currentManualIntentVersion(): Int = manualReplay.intentVersion

    fun manualSlotSignature(slot: Int): String {
        return menuDetector.itemSignature(slot)
    }

    internal fun captureAutomaticTypeMenu() {
        manualReplay.captureTypeMenu(currentMenuFingerprint())
        currentRun()?.let { automaticTypeTargetSignature = manualSlotSignature(it.typeSlot) }
    }

    internal fun isTypeMenuVisible(): Boolean = manualReplay.isTypeMenuVisible()

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

    internal fun detectManualRoomAccepted(): Boolean = manualReplay.detectRoomAccepted()

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

    fun onServerMenuOpened() = manualReplay.onServerMenuOpened()

    fun queueManualClick(
        sequence: Int,
        slot: Int,
        button: Int,
        action: String,
        intentVersion: Int,
        type: String,
        targetSignature: String
    ): Boolean = manualReplay.queueClick(sequence, slot, button, action, intentVersion, type, targetSignature)

    fun queueManualRecoveryType(
        sequence: Int,
        slot: Int,
        button: Int,
        action: String,
        intentVersion: Int,
        targetSignature: String,
        allowanceCost: Int?
    ): Boolean = manualReplay.queueRecoveryType(
        sequence, slot, button, action, intentVersion, targetSignature, allowanceCost
    )

    internal fun observeLocalRoomSelection() = manualReplay.observeLocalRoomSelection()

    fun resetManualReplay() = manualReplay.resetReplay()

    fun recordManualCoordinatorAction(
        sequence: Int,
        intentVersion: Int,
        type: PartyManualActionType,
        targetSignature: String = ""
    ) = manualReplay.recordCoordinatorAction(sequence, intentVersion, type)

    internal fun currentRun(): JoinRun? = run

    internal fun freshAllowanceRemaining(commandRoot: String): Int? = allowanceReader(commandRoot)

    internal fun setAllowanceReader(reader: (String) -> Int?) {
        allowanceReader = reader
    }

    internal fun allowanceExhaustedReason(commandRoot: String): String =
        "no ${commandRoot.trim().removePrefix("/").substringBefore(' ').lowercase()} runs remaining"

    internal fun currentHandler(): ScreenHandler? {
        return menuDetector.handler()
    }

    internal fun hasMenuItem(slot: Int): Boolean =
        menuDetector.hasMenuItem(slot)

    internal fun detectManualMenuStage(typeMenuFingerprint: String?): PartyMenuStage? =
        menuDetector.stage(typeMenuFingerprint)

    internal fun findManualSlot(signature: String, fallback: Int): Int =
        menuDetector.findMenuItem(signature, fallback)

    internal fun currentMenuFingerprint(): String? {
        return menuDetector.fingerprint()
    }

    internal fun markManualInitialMenuReady() = manualReplay.markInitialMenuReady()

    internal fun clickSlot(slot: Int): Boolean {
        return clickSlotAction(slot, 0, SlotActionType.PICKUP)
    }

    internal fun processManualActions() = manualReplay.processActions()

    internal fun releaseInitialManualAction() = manualReplay.releaseInitialAction()

    internal fun clickSlotAction(slot: Int, button: Int, action: SlotActionType): Boolean {
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
        manualReplay.onFailure(reason)
    }

    internal fun debugPhase(message: String) {
        manualReplay.debugPhase(message)
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
        manualReplay.onRunCompleted()
        automaticTypeRetryRequested = false
        callback?.invoke(success, reason, committedCost)
    }

    internal fun notifyManualMenuReady() = manualReplay.notifyMenuReady()
}
