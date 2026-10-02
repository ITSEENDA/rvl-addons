package net.eenda.rvladdons.core

import net.eenda.rvladdons.feature.coma.ComaSetConfig
import net.eenda.rvladdons.feature.coma.ComaTimingConfig
import net.eenda.rvladdons.feature.revive.AutoReviveConfig
import net.eenda.rvladdons.feature.revive.AutoReviveMode
import net.eenda.rvladdons.feature.party.PartyJoinExecutionMode
import net.eenda.rvladdons.feature.party.PartyAutoRunMode
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

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

object RvlAddonsConfigStore {
    var config = RvlAddonsConfig()
        private set

    private var path: Path? = null
    private var comaProfilePath: Path? = null

    fun load(configPath: Path, accountComaProfilePath: Path? = null) {
        path = configPath
        comaProfilePath = accountComaProfilePath
        val properties = Properties()
        if (Files.exists(configPath)) {
            Files.newInputStream(configPath).use(properties::load)
        }

        val accountProperties = accountComaProfilePath?.takeIf(Files::exists)?.let(::loadProperties)
        val legacyLoopDelaySeconds = properties.getProperty("party.auto.loopDelaySeconds")?.toIntOrNull()
        val comaSets = readComaSets(accountProperties ?: properties)
        val hasLegacyComaSets = accountProperties == null && properties.keys.any {
            it.toString().startsWith("coma.set.")
        }

        config = RvlAddonsConfig(
            enabled = properties.getProperty("enabled")?.toBooleanStrictOrNull() ?: true,
            hudStatusVisible = properties.getProperty("hud.status.visible")?.toBooleanStrictOrNull() ?: true,
            hudStatus = HudLayout(
                anchor = properties.getProperty("hud.status.anchor")?.let {
                    runCatching { HudAnchor.valueOf(it) }.getOrNull()
                } ?: HudAnchor.TOP_LEFT,
                offsetX = properties.getProperty("hud.status.offsetX")?.toIntOrNull() ?: 8,
                offsetY = properties.getProperty("hud.status.offsetY")?.toIntOrNull() ?: 8,
                scale = properties.getProperty("hud.status.scale")?.toFloatOrNull()?.coerceIn(0.5f, 2f) ?: 1f
            ),
            hudTraceVisible = properties.getProperty("hud.trace.visible")?.toBooleanStrictOrNull() ?: true,
            hudTrace = HudLayout(
                anchor = properties.getProperty("hud.trace.anchor")?.let {
                    runCatching { HudAnchor.valueOf(it) }.getOrNull()
                } ?: HudAnchor.TOP_LEFT,
                offsetX = properties.getProperty("hud.trace.offsetX")?.toIntOrNull() ?: 8,
                offsetY = properties.getProperty("hud.trace.offsetY")?.toIntOrNull() ?: 26,
                scale = properties.getProperty("hud.trace.scale")?.toFloatOrNull()?.coerceIn(0.5f, 2f) ?: 1f
            ),
            hudCooldownVisible = properties.getProperty("hud.cooldown.visible")?.toBooleanStrictOrNull() ?: true,
            hudCooldown = HudLayout(
                anchor = properties.getProperty("hud.cooldown.anchor")?.let {
                    runCatching { HudAnchor.valueOf(it) }.getOrNull()
                } ?: HudAnchor.TOP_LEFT,
                offsetX = properties.getProperty("hud.cooldown.offsetX")?.toIntOrNull() ?: 8,
                offsetY = properties.getProperty("hud.cooldown.offsetY")?.toIntOrNull() ?: 46,
                scale = properties.getProperty("hud.cooldown.scale")?.toFloatOrNull()?.coerceIn(0.5f, 2f) ?: 1f
            ),
            hudComaVisible = properties.getProperty("hud.coma.visible")?.toBooleanStrictOrNull() ?: true,
            hudComa = HudLayout(
                anchor = properties.getProperty("hud.coma.anchor")?.let {
                    runCatching { HudAnchor.valueOf(it) }.getOrNull()
                } ?: HudAnchor.TOP_LEFT,
                offsetX = properties.getProperty("hud.coma.offsetX")?.toIntOrNull() ?: 8,
                offsetY = properties.getProperty("hud.coma.offsetY")?.toIntOrNull() ?: 70,
                scale = properties.getProperty("hud.coma.scale")?.toFloatOrNull()?.coerceIn(0.5f, 2f) ?: 1f
            ),
            hudPartyVisible = properties.getProperty("hud.party.visible")?.toBooleanStrictOrNull() ?: true,
            hudParty = HudLayout(
                anchor = properties.getProperty("hud.party.anchor")?.let {
                    runCatching { HudAnchor.valueOf(it) }.getOrNull()
                } ?: HudAnchor.TOP_LEFT,
                offsetX = properties.getProperty("hud.party.offsetX")?.toIntOrNull() ?: 8,
                offsetY = properties.getProperty("hud.party.offsetY")?.toIntOrNull() ?: 94,
                scale = properties.getProperty("hud.party.scale")?.toFloatOrNull()?.coerceIn(0.5f, 2f) ?: 1f
            ),
            autoRevive = AutoReviveConfig(
                enabled = properties.getProperty("revive.enabled")?.toBooleanStrictOrNull()
                    ?: properties.getProperty("revive.mode")?.let { it != "NONE" }
                    ?: false,
                mode = when (properties.getProperty("revive.mode")) {
                    "INSTANT", "INSTANT_REVIVE" -> AutoReviveMode.INSTANT
                    "FAST", "RBD" -> AutoReviveMode.FAST
                    else -> AutoReviveMode.FAST
                },
                respawnDelayMs = properties.getProperty("revive.respawn.delayMs")
                    ?.toIntOrNull()?.coerceIn(0, 5000) ?: 0,
                backDelayMs = properties.getProperty("revive.back.delayMs")
                    ?.toIntOrNull()?.coerceIn(0, 5000) ?: 0,
                soundEnabled = properties.getProperty("revive.sound.enabled")?.toBooleanStrictOrNull() ?: true,
                soundVolume = properties.getProperty("revive.sound.volume")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
            ),
            partyJoinExecutionMode = runCatching {
                PartyJoinExecutionMode.valueOf(properties.getProperty("party.join.mode").orEmpty())
            }.getOrDefault(PartyJoinExecutionMode.MANUAL),
            partyAutoRunMode = runCatching {
                PartyAutoRunMode.valueOf(properties.getProperty("party.auto.runMode").orEmpty())
            }.getOrDefault(PartyAutoRunMode.ONCE),
            partyDchienLoopDelaySeconds = properties.getProperty("party.auto.dchienLoopDelaySeconds")
                ?.toIntOrNull()?.coerceAtLeast(3) ?: legacyLoopDelaySeconds?.coerceAtLeast(3) ?: 12,
            partyTlLoopDelaySeconds = properties.getProperty("party.auto.tlLoopDelaySeconds")
                ?.toIntOrNull()?.coerceAtLeast(10) ?: legacyLoopDelaySeconds?.coerceAtLeast(10) ?: 12,
            partyAutomaticTypeSlot = properties.getProperty("party.auto.typeSlot")
                ?.toIntOrNull()?.coerceIn(0, 53) ?: 11,
            partyAutomaticRoomSlot = properties.getProperty("party.auto.roomSlot")
                ?.toIntOrNull()?.coerceIn(0, 53) ?: 11,
            partyAutomaticRoomName = properties.getProperty("party.auto.roomName").orEmpty(),
            partyTlAutomaticTypeSlot = properties.getProperty("party.auto.tl.typeSlot")
                ?.toIntOrNull()?.coerceIn(0, 53)
                ?: properties.getProperty("party.auto.typeSlot")?.toIntOrNull()?.coerceIn(0, 53) ?: 11,
            partyTlAutomaticRoomSlot = properties.getProperty("party.auto.tl.roomSlot")
                ?.toIntOrNull()?.coerceIn(0, 53)
                ?: properties.getProperty("party.auto.roomSlot")?.toIntOrNull()?.coerceIn(0, 53) ?: 11,
            partyTlAutomaticRoomName = properties.getProperty("party.auto.tl.roomName").orEmpty(),
            partyMenuSettleDelayMs = properties.getProperty("party.menu.settleDelayMs")
                ?.toIntOrNull()?.coerceIn(0, 5000) ?: 1_000,
            partyDebugToasts = properties.getProperty("party.debug.toasts")?.toBooleanStrictOrNull() ?: true,
            comaSwapServerSync = properties.getProperty("coma.swap.serverSync")?.toBooleanStrictOrNull() ?: true,
            comaTiming = ComaTimingConfig(
                openMs = properties.getProperty("coma.timing.openMs")?.toIntOrNull()?.coerceIn(0, 5000) ?: 50,
                clearIntervalMs = properties.getProperty("coma.timing.clearIntervalMs")?.toIntOrNull()?.coerceIn(0, 5000) ?: 0,
                clearToMoveMs = properties.getProperty("coma.timing.clearToMoveMs")?.toIntOrNull()?.coerceIn(0, 5000) ?: 10,
                moveIntervalMs = properties.getProperty("coma.timing.moveIntervalMs")?.toIntOrNull()?.coerceIn(0, 5000) ?: 0,
                closeMs = properties.getProperty("coma.timing.closeMs")?.toIntOrNull()?.coerceIn(0, 5000) ?: 50
            ),
            comaSets = comaSets
        )

        if (hasLegacyComaSets) save()
    }

    fun save() {
        val configPath = path ?: return
        Files.createDirectories(configPath.parent)

        val properties = Properties()
        properties["enabled"] = config.enabled.toString()
        properties["hud.status.visible"] = config.hudStatusVisible.toString()
        properties["hud.status.anchor"] = config.hudStatus.anchor.name
        properties["hud.status.offsetX"] = config.hudStatus.offsetX.toString()
        properties["hud.status.offsetY"] = config.hudStatus.offsetY.toString()
        properties["hud.status.scale"] = config.hudStatus.scale.toString()
        properties["hud.trace.visible"] = config.hudTraceVisible.toString()
        properties["hud.trace.anchor"] = config.hudTrace.anchor.name
        properties["hud.trace.offsetX"] = config.hudTrace.offsetX.toString()
        properties["hud.trace.offsetY"] = config.hudTrace.offsetY.toString()
        properties["hud.trace.scale"] = config.hudTrace.scale.toString()
        properties["hud.cooldown.visible"] = config.hudCooldownVisible.toString()
        properties["hud.cooldown.anchor"] = config.hudCooldown.anchor.name
        properties["hud.cooldown.offsetX"] = config.hudCooldown.offsetX.toString()
        properties["hud.cooldown.offsetY"] = config.hudCooldown.offsetY.toString()
        properties["hud.cooldown.scale"] = config.hudCooldown.scale.toString()
        properties["hud.coma.visible"] = config.hudComaVisible.toString()
        properties["hud.coma.anchor"] = config.hudComa.anchor.name
        properties["hud.coma.offsetX"] = config.hudComa.offsetX.toString()
        properties["hud.coma.offsetY"] = config.hudComa.offsetY.toString()
        properties["hud.coma.scale"] = config.hudComa.scale.toString()
        properties["hud.party.visible"] = config.hudPartyVisible.toString()
        properties["hud.party.anchor"] = config.hudParty.anchor.name
        properties["hud.party.offsetX"] = config.hudParty.offsetX.toString()
        properties["hud.party.offsetY"] = config.hudParty.offsetY.toString()
        properties["hud.party.scale"] = config.hudParty.scale.toString()
        properties["revive.enabled"] = config.autoRevive.enabled.toString()
        properties["revive.mode"] = config.autoRevive.mode.name
        properties["revive.respawn.delayMs"] = config.autoRevive.respawnDelayMs.toString()
        properties["revive.back.delayMs"] = config.autoRevive.backDelayMs.toString()
        properties["revive.sound.enabled"] = config.autoRevive.soundEnabled.toString()
        properties["revive.sound.volume"] = config.autoRevive.soundVolume.toString()
        properties["party.join.mode"] = config.partyJoinExecutionMode.name
        properties["party.auto.runMode"] = config.partyAutoRunMode.name
        properties["party.auto.dchienLoopDelaySeconds"] = config.partyDchienLoopDelaySeconds.coerceAtLeast(3).toString()
        properties["party.auto.tlLoopDelaySeconds"] = config.partyTlLoopDelaySeconds.coerceAtLeast(10).toString()
        properties["party.auto.typeSlot"] = config.partyAutomaticTypeSlot.toString()
        properties["party.auto.roomSlot"] = config.partyAutomaticRoomSlot.toString()
        properties["party.auto.roomName"] = config.partyAutomaticRoomName
        properties["party.auto.tl.typeSlot"] = config.partyTlAutomaticTypeSlot.toString()
        properties["party.auto.tl.roomSlot"] = config.partyTlAutomaticRoomSlot.toString()
        properties["party.auto.tl.roomName"] = config.partyTlAutomaticRoomName
        properties["party.menu.settleDelayMs"] = config.partyMenuSettleDelayMs.toString()
        properties["party.debug.toasts"] = config.partyDebugToasts.toString()
        properties["coma.swap.serverSync"] = config.comaSwapServerSync.toString()
        properties["coma.timing.openMs"] = config.comaTiming.openMs.toString()
        properties["coma.timing.clearIntervalMs"] = config.comaTiming.clearIntervalMs.toString()
        properties["coma.timing.clearToMoveMs"] = config.comaTiming.clearToMoveMs.toString()
        properties["coma.timing.moveIntervalMs"] = config.comaTiming.moveIntervalMs.toString()
        properties["coma.timing.closeMs"] = config.comaTiming.closeMs.toString()

        val profilePath = comaProfilePath
        if (profilePath == null) {
            writeComaSets(properties)
        } else {
            writeComaSets(profilePath)
        }
        Files.newOutputStream(configPath).use { properties.store(it, "RVL Addons configuration") }
    }

    private fun readComaSets(properties: Properties): MutableList<ComaSetConfig> {
        val count = properties.getProperty("coma.set.count")?.toIntOrNull()?.coerceIn(0, 50) ?: 1
        return MutableList(count) { index ->
            ComaSetConfig(
                name = properties.getProperty("coma.set.$index.name") ?: "Set ${index + 1}",
                enabled = properties.getProperty("coma.set.$index.enabled")?.toBooleanStrictOrNull() ?: true,
                helmet = properties.getProperty("coma.set.$index.helmet") ?: "",
                chestplate = properties.getProperty("coma.set.$index.chestplate") ?: "",
                leggings = properties.getProperty("coma.set.$index.leggings") ?: "",
                boots = properties.getProperty("coma.set.$index.boots") ?: ""
            )
        }
    }

    private fun writeComaSets(properties: Properties) {
        properties["coma.set.count"] = config.comaSets.size.toString()
        config.comaSets.forEachIndexed { index, set ->
            properties["coma.set.$index.name"] = set.name
            properties["coma.set.$index.enabled"] = set.enabled.toString()
            properties["coma.set.$index.helmet"] = set.helmet
            properties["coma.set.$index.chestplate"] = set.chestplate
            properties["coma.set.$index.leggings"] = set.leggings
            properties["coma.set.$index.boots"] = set.boots
        }
    }

    private fun writeComaSets(profilePath: Path) {
        Files.createDirectories(profilePath.parent)
        val properties = Properties()
        writeComaSets(properties)
        Files.newOutputStream(profilePath).use { properties.store(it, "RVL Addons COMA profiles") }
    }

    private fun loadProperties(path: Path): Properties = Properties().also { properties ->
        Files.newInputStream(path).use(properties::load)
    }
}
