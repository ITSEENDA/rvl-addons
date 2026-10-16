package net.eenda.rvladdons.core

import net.eenda.rvladdons.feature.coma.ComaSetConfig
import net.eenda.rvladdons.feature.coma.ComaTimingConfig
import net.eenda.rvladdons.feature.revive.AutoReviveConfig

data class RvlAddonsConfig(
    var enabled: Boolean = true,
    var hudStatusVisible: Boolean = true,
    var hudStatus: HudLayout = HudLayout(),
    var hudTraceVisible: Boolean = true,
    var hudTrace: HudLayout = HudLayout(offsetY = 26),
    var hudCooldownVisible: Boolean = true,
    var hudCooldown: HudLayout = HudLayout(offsetY = 46),
    var hudComaVisible: Boolean = true,
    var hudComa: HudLayout = HudLayout(offsetY = 70),
    var autoRevive: AutoReviveConfig = AutoReviveConfig(),
    var comaSwapServerSync: Boolean = true,
    var comaTiming: ComaTimingConfig = ComaTimingConfig(),
    var comaSets: MutableList<ComaSetConfig> = mutableListOf(ComaSetConfig())
)
