package net.eenda.rvladdons.client.coma

internal class ComaLocalWaitingForMoveState(owner: ComaSwapController) : ComaSwapState(owner) {
    internal var readyForMove = false
    private var moveAt = 0L

    internal fun begin(at: Long) {
        moveAt = at
        readyForMove = false
    }

    override fun onExit() {
        moveAt = 0L
        readyForMove = false
        super.onExit()
    }

    override fun onUpdate() {
        if (owner.swapOperation == null || System.currentTimeMillis() < moveAt) return
        readyForMove = true
    }
}
