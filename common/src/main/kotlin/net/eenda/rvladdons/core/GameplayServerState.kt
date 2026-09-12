package net.eenda.rvladdons.core

object GameplayServerState {
    @Volatile
    private var active = false

    fun isActive(): Boolean = active

    fun update(isGameplayServer: Boolean) {
        active = isGameplayServer
    }

    fun reset() {
        active = false
    }
}
