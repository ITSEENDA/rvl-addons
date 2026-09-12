package net.eenda.rvladdons.client.coma

internal class ComaIdleState(owner: ComaSwapController) : ComaState(owner) {
    override fun onEnter() {
        super.onEnter()
        owner.onIdleEntered()
    }
}
