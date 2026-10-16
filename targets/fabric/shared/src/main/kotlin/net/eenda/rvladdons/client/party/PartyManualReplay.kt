package net.eenda.rvladdons.client.party

import net.eenda.rvladdons.feature.party.PartyManualAction
import net.eenda.rvladdons.feature.party.PartyManualActionQueue
import net.eenda.rvladdons.feature.party.PartyManualActionType
import net.eenda.rvladdons.feature.party.PartyMenuStage
import net.minecraft.screen.ScreenHandler
import net.minecraft.screen.slot.SlotActionType

/** Tracks menu barriers and replays coordinator actions for one manual join run. */
internal class PartyManualReplay(private val executor: PartyJoinExecutor) {
    private val actionQueue = PartyManualActionQueue()
    private var lastClickSyncId = -1
    private var lastHandler: ScreenHandler? = null
    private var awaitingTransition = false
    private var waitingForRefresh = false
    private var skipNextTransition = false
    private var menuReadyNotified = false
    private var typeMenuFingerprint: String? = null
    private var lastActionType: PartyManualActionType? = null
    private var recoveryPending = false
    private var roomSelectionExpected = false

    var menuStage: PartyMenuStage = PartyMenuStage.UNKNOWN
        private set

    private var actionAppliedCallback: (Int) -> Unit = {}
    private var menuReadyCallback: () -> Unit = {}
    private var menuStageCallback: (PartyMenuStage, Int) -> Unit = { _, _ -> }
    private var resyncCallback: (Int) -> Unit = {}
    private var phaseCallback: (String) -> Unit = {}

    val intentVersion: Int
        get() = actionQueue.intentVersion

    fun start(
        onActionApplied: (Int) -> Unit,
        onMenuReady: () -> Unit,
        onMenuStage: (PartyMenuStage, Int) -> Unit,
        onResyncRequested: (Int) -> Unit,
        onPhase: (String) -> Unit
    ) {
        actionQueue.reset()
        lastClickSyncId = -1
        lastHandler = null
        awaitingTransition = false
        waitingForRefresh = false
        skipNextTransition = false
        menuReadyNotified = false
        menuStage = PartyMenuStage.UNKNOWN
        typeMenuFingerprint = null
        lastActionType = null
        recoveryPending = false
        roomSelectionExpected = false
        actionAppliedCallback = onActionApplied
        menuReadyCallback = onMenuReady
        menuStageCallback = onMenuStage
        resyncCallback = onResyncRequested
        phaseCallback = onPhase
    }

    fun onRunCompleted() {
        actionQueue.clearPending()
        awaitingTransition = false
        waitingForRefresh = false
        skipNextTransition = false
        menuReadyNotified = false
        roomSelectionExpected = false
        actionAppliedCallback = {}
        menuReadyCallback = {}
        resyncCallback = {}
        phaseCallback = {}
    }

    fun onCancelled(reason: String) = phaseCallback("cancelled: $reason")

    fun onFailure(reason: String) = phaseCallback("failed: $reason")

    fun debugPhase(message: String) {
        if (executor.isManualModeActive()) phaseCallback(message)
    }

    fun currentStage(): PartyMenuStage = menuStage

    fun isRoomSelectionExpected(): Boolean = executor.isManualModeActive() && roomSelectionExpected

    fun refreshMenuStage(): PartyMenuStage? {
        if (!executor.isManualModeActive()) return null
        val detected = executor.detectManualMenuStage(typeMenuFingerprint) ?: return menuStage
        if (detected != menuStage) updateMenuStage(detected)
        return detected
    }

    fun captureTypeMenu(fingerprint: String?) {
        typeMenuFingerprint = fingerprint
    }

    fun isTypeMenuVisible(): Boolean =
        typeMenuFingerprint != null && executor.currentMenuFingerprint() == typeMenuFingerprint

    fun detectRoomAccepted(): Boolean {
        if (!executor.isManualModeActive() || menuStage != PartyMenuStage.ROOM_MENU) return false
        if (lastActionType != PartyManualActionType.SELECT_ROOM || !awaitingTransition) return false
        if (executor.currentHandler() != null) return false
        awaitingTransition = false
        updateMenuStage(PartyMenuStage.JOINED)
        executor.markRoomSubmitted()
        debugPhase("room selection submitted; waiting for join confirmation")
        return true
    }

    fun onServerMenuOpened() {
        if (!executor.isManualModeActive()) return
        val handler = executor.currentHandler() ?: return
        val run = executor.currentRun() ?: return
        val nextStage = executor.detectManualMenuStage(typeMenuFingerprint) ?: return

        if (!menuReadyNotified) {
            executor.waitingMenuState.menuReady = true
            run.menuSyncId = handler.syncId
            run.deadline = 0L
            updateMenuStage(PartyMenuStage.TYPE_MENU)
            notifyMenuReady()
            debugPhase("dchien type menu ready")
            releaseInitialAction()
            return
        }

        val previousStage = menuStage
        if (previousStage == PartyMenuStage.ROOM_MENU && nextStage == PartyMenuStage.TYPE_MENU) {
            val newTypeActionPending = actionQueue.any {
                it.type == PartyManualActionType.SELECT_TYPE && it.intentVersion >= actionQueue.intentVersion
            }
            if (!newTypeActionPending) actionQueue.clearPending()
            waitingForRefresh = false
            awaitingTransition = false
            roomSelectionExpected = false
            executor.delayTypeClick()
            updateMenuStage(PartyMenuStage.TYPE_MENU)
            debugPhase("room selection invalidated; waiting for coordinator")
            return
        }

        updateMenuStage(nextStage)
        if (awaitingTransition) {
            awaitingTransition = false
            lastHandler = handler
            lastClickSyncId = handler.syncId
            if (skipNextTransition) {
                skipNextTransition = false
                debugPhase("menu navigation received; waiting for room refresh")
                return
            }
            debugPhase("server menu barrier received")
        }
        lastHandler = handler
        lastClickSyncId = handler.syncId
        if (previousStage == PartyMenuStage.ROOM_MENU && nextStage == PartyMenuStage.ROOM_MENU) {
            waitingForRefresh = false
        }
        processActions()
    }

    fun queueClick(
        sequence: Int,
        slot: Int,
        button: Int,
        action: String,
        intentVersion: Int,
        type: String,
        targetSignature: String
    ): Boolean {
        if (!executor.isManualModeActive() || sequence <= actionQueue.lastAppliedSequence) return false
        val slotAction = runCatching { SlotActionType.valueOf(action) }.getOrNull() ?: run {
            executor.fail("unknown slot action $action")
            return false
        }
        val actionType = runCatching { PartyManualActionType.valueOf(type) }.getOrNull() ?: run {
            executor.fail("unknown party action type $type")
            return false
        }

        val update = actionQueue.enqueue(
            PartyManualAction(sequence, slot, button, slotAction.name, intentVersion, actionType, targetSignature),
            lastActionType,
            awaitingTransition
        )
        if (!update.accepted) return false
        if (update.intentChanged) {
            awaitingTransition = false
            waitingForRefresh = false
            skipNextTransition = false
        }
        if (update.clearsRoomWait) waitingForRefresh = false
        if (update.shouldDelayType) executor.delayTypeClick()
        if (update.queued) waitingForRefresh = actionType == PartyManualActionType.SELECT_ROOM
        return true
    }

    fun queueRecoveryType(
        sequence: Int,
        slot: Int,
        button: Int,
        action: String,
        intentVersion: Int,
        targetSignature: String,
        allowanceCost: Int?
    ): Boolean {
        if (!executor.isManualModeActive()) return false
        executor.receiveRoomCost(allowanceCost)
        val slotAction = runCatching { SlotActionType.valueOf(action) }.getOrNull() ?: return false
        if (!actionQueue.enqueueRecoveryType(
                PartyManualAction(sequence, slot, button, slotAction.name, intentVersion,
                    PartyManualActionType.SELECT_TYPE, targetSignature)
            )
        ) return false

        waitingForRefresh = false
        awaitingTransition = false
        skipNextTransition = false
        recoveryPending = true
        executor.delayTypeClick()
        if (menuStage == PartyMenuStage.TYPE_MENU) processActions()
        return true
    }

    fun observeLocalRoomSelection() {
        if (!executor.isManualModeActive()) return
        executor.markRoomSubmitted()
        val handler = executor.currentHandler()
        roomSelectionExpected = false
        updateMenuStage(PartyMenuStage.ROOM_MENU)
        lastActionType = PartyManualActionType.SELECT_ROOM
        lastClickSyncId = handler?.syncId ?: -1
        lastHandler = handler
        waitingForRefresh = false
        awaitingTransition = true
        debugPhase("local room selection observed")
    }

    fun resetReplay() {
        if (!executor.isManualModeActive()) return
        actionQueue.resetReplay()
        lastClickSyncId = -1
        lastHandler = executor.currentHandler()
        awaitingTransition = false
        waitingForRefresh = false
        skipNextTransition = false
        menuReadyNotified = false
        recoveryPending = false
        roomSelectionExpected = false
    }

    fun recordCoordinatorAction(
        sequence: Int,
        intentVersion: Int,
        type: PartyManualActionType
    ) {
        if (!executor.isManualModeActive()) return
        val handler = executor.currentHandler()
        actionQueue.markCoordinatorAction(sequence, intentVersion)
        lastActionType = type
        if (type == PartyManualActionType.SELECT_TYPE) executor.markTypeSubmitted()
        roomSelectionExpected = type == PartyManualActionType.SELECT_TYPE
        lastClickSyncId = handler?.syncId ?: -1
        lastHandler = handler
        awaitingTransition = true
    }

    fun markInitialMenuReady() {
        if (!executor.isManualModeActive() || menuReadyNotified) return
        val handler = executor.currentHandler() ?: return
        executor.currentRun()?.menuSyncId = handler.syncId
        typeMenuFingerprint = executor.currentMenuFingerprint()
        updateMenuStage(PartyMenuStage.TYPE_MENU)
        notifyMenuReady()
        releaseInitialAction()
    }

    fun processActions() {
        if (!executor.isManualModeActive()) return
        val handler = executor.currentHandler() ?: return
        if (awaitingTransition) {
            if (handler !== lastHandler || handler.syncId != lastClickSyncId) {
                val previousStage = menuStage
                val nextStage = executor.detectManualMenuStage(typeMenuFingerprint) ?: PartyMenuStage.UNKNOWN
                awaitingTransition = false
                lastHandler = handler
                lastClickSyncId = handler.syncId
                updateMenuStage(nextStage)
                if (previousStage == PartyMenuStage.TYPE_MENU &&
                    nextStage == PartyMenuStage.ROOM_MENU &&
                    lastActionType == PartyManualActionType.SELECT_TYPE
                ) {
                    debugPhase("type menu navigation received")
                    return
                }
                if (skipNextTransition) {
                    skipNextTransition = false
                    debugPhase("menu navigation received; waiting for room refresh")
                    return
                }
                waitingForRefresh = false
                debugPhase("server menu barrier received")
            } else {
                return
            }
        }
        if (lastHandler != null && (handler !== lastHandler || handler.syncId != lastClickSyncId)) {
            lastHandler = handler
            lastClickSyncId = handler.syncId
            waitingForRefresh = false
            debugPhase("server menu barrier received")
        }
        if (waitingForRefresh) return

        val pending = actionQueue.first() ?: return
        if (pending.type == PartyManualActionType.SELECT_TYPE && menuStage != PartyMenuStage.TYPE_MENU) return
        if (pending.type == PartyManualActionType.SELECT_TYPE && !executor.canClickType()) return
        if (pending.type == PartyManualActionType.SELECT_ROOM && menuStage != PartyMenuStage.ROOM_MENU) return

        val targetSlot = executor.findManualSlot(pending.targetSignature, pending.slot)
        if (pending.type == PartyManualActionType.SELECT_ROOM) executor.captureRoomCost(targetSlot)
        if (pending.type == PartyManualActionType.SELECT_ROOM) executor.markRoomSubmitted()
        val action = SlotActionType.valueOf(pending.action)
        if (!executor.clickSlotAction(targetSlot, pending.button, action)) return

        actionQueue.removeFirst()
        lastActionType = pending.type
        if (pending.type == PartyManualActionType.SELECT_TYPE) executor.markTypeSubmitted()
        actionQueue.markApplied(pending.sequence)
        lastClickSyncId = handler.syncId
        lastHandler = handler
        awaitingTransition = true
        if (pending.type == PartyManualActionType.SELECT_TYPE && recoveryPending) {
            executor.markRoomSubmitted()
            recoveryPending = false
        }
        actionAppliedCallback(pending.sequence)
        debugPhase("applied action #${pending.sequence}")
    }

    fun releaseInitialAction() {
        if (actionQueue.lastAppliedSequence != 0 || actionQueue.isEmpty()) return
        waitingForRefresh = false
        processActions()
    }

    fun notifyMenuReady() {
        if (!executor.isManualModeActive() || menuReadyNotified) return
        menuReadyNotified = true
        menuReadyCallback()
    }

    private fun updateMenuStage(stage: PartyMenuStage) {
        if (menuStage == stage) return
        menuStage = stage
        menuStageCallback(stage, actionQueue.intentVersion)
    }
}
