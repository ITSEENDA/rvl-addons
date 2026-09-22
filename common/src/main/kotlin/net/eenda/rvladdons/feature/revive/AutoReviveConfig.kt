package net.eenda.rvladdons.feature.revive

data class AutoReviveConfig(
    var enabled: Boolean = false,
    var mode: AutoReviveMode = AutoReviveMode.FAST,
    var respawnDelayMs: Int = 0,
    var backDelayMs: Int = 0,
    var soundEnabled: Boolean = true,
    var soundVolume: Float = 1f
)
