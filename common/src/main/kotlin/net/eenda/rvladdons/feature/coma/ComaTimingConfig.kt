package net.eenda.rvladdons.feature.coma

data class ComaTimingConfig(
    var openMs: Int = 5,
    var clearIntervalMs: Int = 0,
    var clearToMoveMs: Int = 0,
    var moveIntervalMs: Int = 0,
    var closeMs: Int = 5
)
