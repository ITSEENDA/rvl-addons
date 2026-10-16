package net.eenda.rvladdons.core

import net.eenda.rvladdons.feature.coma.ComaSetConfig
import net.eenda.rvladdons.feature.coma.ComaTimingConfig
import net.eenda.rvladdons.feature.party.PartyAutoRunMode
import net.eenda.rvladdons.feature.party.PartyJoinExecutionMode
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
    var hudPartyVisible: Boolean = true,
    var hudParty: HudLayout = HudLayout(offsetY = 94),
    var autoRevive: AutoReviveConfig = AutoReviveConfig(),
    var partyJoinExecutionMode: PartyJoinExecutionMode = PartyJoinExecutionMode.MANUAL,
    var partyAutoRunMode: PartyAutoRunMode = PartyAutoRunMode.ONCE,
    var partyDchienLoopDelaySeconds: Int = 12,
    var partyTlLoopDelaySeconds: Int = 12,
    var partyAutomaticTypeSlot: Int = 11,
    var partyAutomaticRoomSlot: Int = 11,
    var partyAutomaticRoomName: String = "",
    var partyTlAutomaticTypeSlot: Int = 11,
    var partyTlAutomaticRoomSlot: Int = 11,
    var partyTlAutomaticRoomName: String = "",
    var partyMenuSettleDelayMs: Int = 1_000,
    var partyDebugToasts: Boolean = true,
    var comaSwapServerSync: Boolean = true,
    var comaTiming: ComaTimingConfig = ComaTimingConfig(),
    var comaSets: MutableList<ComaSetConfig> = mutableListOf(ComaSetConfig())
)
