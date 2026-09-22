package net.eenda.rvladdons.feature.revive

enum class AutoReviveMode(private val label: String) {
    INSTANT("Instant"),
    FAST("Fast");

    override fun toString(): String = label
}
