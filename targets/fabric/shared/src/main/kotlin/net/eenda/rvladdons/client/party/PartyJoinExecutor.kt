package net.eenda.rvladdons.client.party

import net.eenda.rvladdons.core.RvlTextMatcher
import net.eenda.rvladdons.core.state.BaseState
import net.eenda.rvladdons.core.state.StateMachine
import net.eenda.rvladdons.feature.party.PartyManualActionType
import net.eenda.rvladdons.feature.party.PartyMenuStage
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.screen.ScreenHandler
import net.minecraft.screen.slot.SlotActionType

object PartyJoinExecutor {
    internal const val MENU_TIMEOUT_MS = 5_000L
    internal const val DIFFICULTY_TIMEOUT_MS = 2_500L
    internal const val RESULT_TIMEOUT_MS = 10_000L

    internal data class JoinRun(
        val attemptId: String,
        val command: String,
        val gamemodeSlot: Int,
        val difficultySlot: Int,
        val baselineSyncId: Int,
        var menuSyncId: Int = -1,
        var sawMenuClose: Boolean = false,
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
    internal val manualTypeMenuState = PartyJoinManualTypeMenuState(this)
    internal val manualRoomMenuState = PartyJoinManualRoomMenuState(this)
    internal val clickGamemodeState = PartyJoinClickGamemodeState(this)
    internal val waitingDifficultyState = PartyJoinWaitingDifficultyState(this)
    internal val clickDifficultyState = PartyJoinClickDifficultyState(this)
    internal val waitingResultState = PartyJoinWaitingResultState(this)
    internal val completedState = PartyJoinCompletedState(this)
    internal val failedState = PartyJoinFailedState(this)

    private var client: MinecraftClient? = null
    private var run: JoinRun? = null
    private var failureReason: String? = null
    private var successRequested = false
    private var result: ((Boolean, String) -> Unit)? = null
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
    private var automaticTypeTargetSignature = ""
    private var automaticTypeRetryRequested = false
    private var manualActionAppliedCallback: (Int) -> Unit = {}
    private var manualMenuReadyCallback: () -> Unit = {}
    private var manualMenuStageCallback: (PartyMenuStage, Int) -> Unit = { _, _ -> }
    private var manualResyncCallback: (Int) -> Unit = {}
    private var manualPhaseCallback: (String) -> Unit = {}

    init {
        stateMachine.setState(idleState)
        stateMachine.addTransition(failedState) {
            failureReason != null && stateMachine.currentState !== failedState
        }
        stateMachine.addTransitionFrom(waitingMenuState, clickGamemodeState) {
            waitingMenuState.menuReady && !manualMode && failureReason == null
        }
        stateMachine.addTransitionFrom(waitingMenuState, manualTypeMenuState) {
            waitingMenuState.menuReady && manualMode && failureReason == null
        }
        stateMachine.addTransitionFrom(manualTypeMenuState, manualRoomMenuState) {
            manualMenuStage == PartyMenuStage.ROOM_MENU && failureReason == null
        }
        stateMachine.addTransitionFrom(manualRoomMenuState, manualTypeMenuState) {
            manualMenuStage == PartyMenuStage.TYPE_MENU && failureReason == null
        }
        stateMachine.addTransitionFrom(clickGamemodeState, waitingDifficultyState) {
            clickGamemodeState.clicked && failureReason == null
        }
        stateMachine.addTransitionFrom(waitingDifficultyState, clickDifficultyState) {
            waitingDifficultyState.menuReady && failureReason == null
        }
        stateMachine.addTransitionFrom(waitingDifficultyState, clickGamemodeState) {
            automaticTypeRetryRequested && failureReason == null
        }
        stateMachine.addTransitionFrom(clickDifficultyState, waitingResultState) {
            clickDifficultyState.clicked && failureReason == null
        }
        stateMachine.addTransitionFrom(waitingDifficultyState, waitingResultState) {
            successRequested && failureReason == null
        }
        stateMachine.addTransitionFrom(waitingResultState, completedState) {
            successRequested && failureReason == null
        }
        stateMachine.addTransitionFrom(manualTypeMenuState, completedState) {
            successRequested && failureReason == null
        }
        stateMachine.addTransitionFrom(manualRoomMenuState, completedState) {
            successRequested && failureReason == null
        }
        stateMachine.addTransitionFrom(completedState, idleState) { run == null }
        stateMachine.addTransitionFrom(failedState, idleState) { run == null }
    }

    fun start(
        client: MinecraftClient,
        attemptId: String,
        command: String,
        gamemodeSlot: Int,
        difficultySlot: Int,
        onResult: (Boolean, String) -> Unit
    ) {
        startInternal(
            client,
            attemptId,
            command,
            gamemodeSlot,
            difficultySlot,
            false,
            onResult = onResult
        )
    }

    fun startManual(
        client: MinecraftClient,
        attemptId: String,
        command: String,
        onActionApplied: (Int) -> Unit,
        onMenuReady: () -> Unit,
        onMenuStage: (PartyMenuStage, Int) -> Unit,
        onResyncRequested: (Int) -> Unit,
        onPhase: (String) -> Unit,
        onResult: (Boolean, String) -> Unit
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
        gamemodeSlot: Int,
        difficultySlot: Int,
        manual: Boolean,
        onActionApplied: (Int) -> Unit = {},
        onMenuReady: () -> Unit = {},
        onMenuStage: (PartyMenuStage, Int) -> Unit = { _, _ -> },
        onResyncRequested: (Int) -> Unit = {},
        onPhase: (String) -> Unit = {},
        onResult: (Boolean, String) -> Unit
    ) {
        if (run != null) {
            onResult(false, "join executor busy")
            return
        }
        val player = client.player ?: run {
            onResult(false, "player unavailable")
            return
        }
        this.client = client
        val handler = currentHandler()
        this.run = JoinRun(
            attemptId = attemptId,
            command = command.removePrefix("/"),
            gamemodeSlot = gamemodeSlot,
            difficultySlot = difficultySlot,
            baselineSyncId = handler?.syncId ?: -1
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
        val genericSuccess = RvlTextMatcher.contains(message, "THAO PHAT") &&
                RvlTextMatcher.contains(message, "CHIEN THANH CONG")
        val specificSuccess = RvlTextMatcher.contains(message, "VUOT THANH CONG") &&
                (RvlTextMatcher.contains(message, "TUONG QUAN HOP LUC") ||
                        RvlTextMatcher.contains(message, "KIEM VUA SA NGA"))
        when {
            genericSuccess || specificSuccess -> {
                successRequested = true
                debugPhase("server success received")
            }
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

    fun currentManualActionType(): PartyManualActionType =
        if (manualMenuStage == PartyMenuStage.ROOM_MENU) {
            PartyManualActionType.SELECT_ROOM
        } else {
            PartyManualActionType.SELECT_TYPE
        }

    fun currentManualIntentVersion(): Int = manualIntentVersion

    fun manualSlotSignature(slot: Int): String {
        val handler = currentHandler() ?: return ""
        val stack = handler.slots.getOrNull(slot)?.stack ?: return ""
        return if (stack.isEmpty) "" else stack.name.string.trim().lowercase()
    }

    internal fun captureAutomaticTypeMenu() {
        manualTypeFingerprint = currentMenuFingerprint()
        currentRun()?.let { automaticTypeTargetSignature = manualSlotSignature(it.gamemodeSlot) }
    }

    internal fun isTypeMenuVisible(): Boolean =
        manualTypeFingerprint != null && currentMenuFingerprint() == manualTypeFingerprint

    internal fun requestAutomaticTypeRetry() {
        val currentHandler = currentHandler() ?: return
        currentRun()?.menuSyncId = currentHandler.syncId
        automaticTypeRetryRequested = true
        debugPhase("type menu refreshed; retrying type selection")
    }

    internal fun clearAutomaticTypeRetry() {
        automaticTypeRetryRequested = false
    }

    internal fun detectManualRoomAccepted(): Boolean {
        if (!manualMode || manualMenuStage != PartyMenuStage.ROOM_MENU) return false
        if (manualLastActionType != PartyManualActionType.SELECT_ROOM || !manualAwaitingTransition) return false
        if (currentHandler() != null) return false
        manualAwaitingTransition = false
        updateManualMenuStage(PartyMenuStage.JOINED)
        successRequested = true
        debugPhase("room menu closed after room selection")
        return true
    }

    internal fun automaticTypeSlot(fallback: Int): Int {
        if (automaticTypeTargetSignature.isBlank()) return fallback
        val handler = currentHandler() ?: return fallback
        val menuSlots = if (handler.slots.size > 36) handler.slots.dropLast(36) else handler.slots
        return menuSlots.indexOfFirst {
            val stack = it.stack
            !stack.isEmpty && stack.name.string.trim().lowercase() == automaticTypeTargetSignature
        }.takeIf { it >= 0 } ?: fallback
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
        if (previousStage == PartyMenuStage.TYPE_MENU &&
            nextStage == PartyMenuStage.TYPE_MENU &&
            manualAwaitingTransition &&
            manualLastActionType == PartyManualActionType.SELECT_TYPE
        ) {
            manualAwaitingTransition = false
            manualPendingActions.clear()
            manualWaitingForRefresh = false
            updateManualMenuStage(PartyMenuStage.AUTO_JOINED)
            successRequested = true
            debugPhase("server auto-joined the preparing room")
            return
        }
        if (previousStage == PartyMenuStage.ROOM_MENU && nextStage == PartyMenuStage.TYPE_MENU) {
            val newTypeActionPending = manualPendingActions.any {
                it.type == PartyManualActionType.SELECT_TYPE && it.intentVersion >= manualIntentVersion
            }
            if (!newTypeActionPending) manualPendingActions.clear()
            manualWaitingForRefresh = false
            manualAwaitingTransition = false
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
    }

    fun recordManualCoordinatorAction(
        sequence: Int,
        intentVersion: Int,
        type: PartyManualActionType
    ) {
        if (!isManualModeActive()) return
        val handler = currentHandler()
        manualIntentVersion = intentVersion
        manualLastActionType = type
        manualLastAppliedSequence = sequence
        manualLastClickSyncId = handler?.syncId ?: -1
        manualLastHandler = handler
        manualAwaitingTransition = true
    }

    internal fun currentRun(): JoinRun? = run

    internal fun currentHandler(): ScreenHandler? {
        val currentClient = client ?: return null
        if (currentClient.currentScreen !is HandledScreen<*>) return null
        return currentClient.player?.currentScreenHandler
    }

    private fun detectManualMenuStage(): PartyMenuStage? {
        val fingerprint = currentMenuFingerprint() ?: return null
        if (manualTypeFingerprint == null) {
            manualTypeFingerprint = fingerprint
            return PartyMenuStage.TYPE_MENU
        }
        return if (fingerprint == manualTypeFingerprint) {
            PartyMenuStage.TYPE_MENU
        } else {
            PartyMenuStage.ROOM_MENU
        }
    }

    internal fun currentMenuFingerprint(): String? {
        val currentClient = client ?: return null
        val screen = currentClient.currentScreen as? HandledScreen<*> ?: return null
        val handler = currentHandler() ?: return null
        val menuSlots = if (handler.slots.size > 36) handler.slots.dropLast(36) else handler.slots
        val items = menuSlots.joinToString("|") { slot ->
            val stack = slot.stack
            if (stack.isEmpty) "empty" else "${stack.name.string}:${stack.count}"
        }
        return "${screen.title.string}|${menuSlots.size}|$items"
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
            if (pending.type == PartyManualActionType.SELECT_ROOM &&
                manualMenuStage != PartyMenuStage.ROOM_MENU
            ) return
            val targetSlot = resolveManualSlot(pending)
            if (clickSlotAction(targetSlot, pending.button, pending.action)) {
                manualPendingActions.removeFirst()
                manualLastActionType = pending.type
                manualLastAppliedSequence = pending.sequence
                manualLastClickSyncId = handler.syncId
                manualLastHandler = handler
                manualAwaitingTransition = true
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
        if (action.targetSignature.isBlank()) return action.slot
        val handler = currentHandler() ?: return action.slot
        val menuSlots = if (handler.slots.size > 36) handler.slots.dropLast(36) else handler.slots
        return menuSlots.indexOfFirst {
            val stack = it.stack
            !stack.isEmpty && stack.name.string.trim().lowercase() == action.targetSignature
        }.takeIf { it >= 0 } ?: action.slot
    }

    fun applyManualClick(slot: Int, button: Int, action: String) {
        if (!isManualModeActive()) return
        val slotAction = runCatching { SlotActionType.valueOf(action) }.getOrNull() ?: run {
            fail("unknown slot action $action")
            return
        }
        clickSlotAction(slot, button, slotAction)
    }

    private fun clickSlotAction(slot: Int, button: Int, action: SlotActionType): Boolean {
        val currentClient = client ?: return false
        val handler = currentHandler() ?: return false
        val player = currentClient.player ?: return false
        if (slot !in handler.slots.indices) {
            fail("invalid slot $slot")
            return false
        }
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
        automaticTypeRetryRequested = false
        manualActionAppliedCallback = {}
        manualMenuReadyCallback = {}
        manualResyncCallback = {}
        manualPhaseCallback = {}
        callback?.invoke(success, reason)
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

internal class PartyJoinManualRoomMenuState(owner: PartyJoinExecutor) : PartyJoinState(owner) {
    override fun onEnter() {
        owner.debugPhase("room menu stage")
    }

    override fun onUpdate() {
        if (owner.detectManualRoomAccepted()) return
        owner.processManualActions()
    }
}

internal class PartyJoinClickGamemodeState(owner: PartyJoinExecutor) : PartyJoinState(owner) {
    internal var clicked = false

    override fun onEnter() {
        clicked = false
        owner.clearAutomaticTypeRetry()
    }

    override fun onUpdate() {
        val run = owner.currentRun() ?: return
        if (owner.currentHandler()?.syncId != run.menuSyncId) return
        clicked = owner.clickSlot(owner.automaticTypeSlot(run.gamemodeSlot))
        if (!clicked) owner.fail("gamemode menu unavailable")
    }
}

internal class PartyJoinWaitingDifficultyState(owner: PartyJoinExecutor) : PartyJoinState(owner) {
    internal var menuReady = false

    override fun onEnter() {
        menuReady = false
        owner.currentRun()?.apply {
            sawMenuClose = false
            deadline = System.currentTimeMillis() + PartyJoinExecutor.DIFFICULTY_TIMEOUT_MS
        }
    }

    override fun onUpdate() {
        val run = owner.currentRun() ?: return
        if (System.currentTimeMillis() >= run.deadline) {
            owner.fail("difficulty menu timeout")
            return
        }
        if (owner.isTypeMenuVisible()) {
            menuReady = false
            owner.requestAutomaticTypeRetry()
            return
        }
        val handler = owner.currentHandler()
        if (handler == null) {
            run.sawMenuClose = true
            return
        }
        if (handler.syncId != run.menuSyncId || run.sawMenuClose) {
            menuReady = true
        }
    }
}

internal class PartyJoinClickDifficultyState(owner: PartyJoinExecutor) : PartyJoinState(owner) {
    internal var clicked = false

    override fun onEnter() {
        clicked = false
    }

    override fun onUpdate() {
        val run = owner.currentRun() ?: return
        clicked = owner.clickSlot(run.difficultySlot)
        if (!clicked) owner.fail("difficulty menu unavailable")
    }
}

internal class PartyJoinWaitingResultState(owner: PartyJoinExecutor) : PartyJoinState(owner) {
    override fun onEnter() {
        owner.currentRun()?.deadline = System.currentTimeMillis() + PartyJoinExecutor.RESULT_TIMEOUT_MS
    }

    override fun onUpdate() {
        val run = owner.currentRun() ?: return
        if (System.currentTimeMillis() >= run.deadline) owner.fail("join result timeout")
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
