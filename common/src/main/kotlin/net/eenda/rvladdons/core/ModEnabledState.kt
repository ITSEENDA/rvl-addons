package net.eenda.rvladdons.core

object ModEnabledState {
    @Volatile
    private var enabled = true

    fun isEnabled(): Boolean = enabled

    fun set(value: Boolean) {
        enabled = value
    }

    fun toggle(): Boolean {
        enabled = !enabled
        return enabled
    }
}
