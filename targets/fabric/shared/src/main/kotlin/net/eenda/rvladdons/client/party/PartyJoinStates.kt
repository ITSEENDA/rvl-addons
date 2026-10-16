package net.eenda.rvladdons.client.party

import net.eenda.rvladdons.core.state.BaseState

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
        if (owner.freshAllowanceRemaining(run.command) == 0) {
            owner.fail(owner.allowanceExhaustedReason(run.command))
            return
        }
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
        if (now < roomReadyAt || !owner.hasMenuItem(run.roomSlot)) return
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
        owner.complete(false, owner.joinFailureReason())
    }
}
