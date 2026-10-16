package net.eenda.rvladdons.core

import net.eenda.rvladdons.client.storage.JsonFileStore
import net.eenda.rvladdons.feature.coma.ComaSetConfig
import net.eenda.rvladdons.feature.coma.ComaTimingConfig
import net.eenda.rvladdons.feature.revive.AutoReviveConfig
import net.eenda.rvladdons.feature.revive.AutoReviveMode
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

object RvlAddonsConfigStore {
    var config = RvlAddonsConfig()
        private set

    private var path: Path? = null
    private var comaProfilePath: Path? = null

    fun load(configPath: Path, accountComaProfilePath: Path) {
        path = configPath
        comaProfilePath = accountComaProfilePath
        val properties = Properties()
        if (Files.exists(configPath)) Files.newInputStream(configPath).use(properties::load)
        val comaSets = JsonFileStore.read(accountComaProfilePath, Array<ComaSetConfig>::class.java)
            ?.filterNotNull()
            ?.take(50)
            ?.toMutableList()
            ?: mutableListOf(ComaSetConfig())

        config = RvlAddonsConfig(
            enabled = properties.getProperty("enabled")?.toBooleanStrictOrNull() ?: true,
            hudStatusVisible = properties.getProperty("hud.status.visible")?.toBooleanStrictOrNull() ?: true,
            hudStatus = readHudLayout(properties, "hud.status", 8),
            hudTraceVisible = properties.getProperty("hud.trace.visible")?.toBooleanStrictOrNull() ?: true,
            hudTrace = readHudLayout(properties, "hud.trace", 26),
            hudCooldownVisible = properties.getProperty("hud.cooldown.visible")?.toBooleanStrictOrNull() ?: true,
            hudCooldown = readHudLayout(properties, "hud.cooldown", 46),
            hudComaVisible = properties.getProperty("hud.coma.visible")?.toBooleanStrictOrNull() ?: true,
            hudComa = readHudLayout(properties, "hud.coma", 70),
            autoRevive = AutoReviveConfig(
                enabled = properties.getProperty("revive.enabled")?.toBooleanStrictOrNull() ?: false,
                mode = properties.getProperty("revive.mode")
                    ?.let { runCatching { AutoReviveMode.valueOf(it) }.getOrNull() }
                    ?: AutoReviveMode.FAST,
                respawnDelayMs = properties.getProperty("revive.respawn.delayMs")
                    ?.toIntOrNull()?.coerceIn(0, 5000) ?: 0,
                backDelayMs = properties.getProperty("revive.back.delayMs")
                    ?.toIntOrNull()?.coerceIn(0, 5000) ?: 0,
                soundEnabled = properties.getProperty("revive.sound.enabled")?.toBooleanStrictOrNull() ?: true,
                soundVolume = properties.getProperty("revive.sound.volume")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
            ),
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
    }

    fun save() {
        val configPath = path ?: return
        val profilePath = comaProfilePath ?: return
        Files.createDirectories(configPath.toAbsolutePath().parent)
        JsonFileStore.write(profilePath, config.comaSets)

        val properties = Properties().apply {
            this["enabled"] = config.enabled.toString()
            this["hud.status.visible"] = config.hudStatusVisible.toString()
            this["hud.trace.visible"] = config.hudTraceVisible.toString()
            this["hud.cooldown.visible"] = config.hudCooldownVisible.toString()
            this["hud.coma.visible"] = config.hudComaVisible.toString()
            writeHudLayout(this, "hud.status", config.hudStatus)
            writeHudLayout(this, "hud.trace", config.hudTrace)
            writeHudLayout(this, "hud.cooldown", config.hudCooldown)
            writeHudLayout(this, "hud.coma", config.hudComa)
            this["revive.enabled"] = config.autoRevive.enabled.toString()
            this["revive.mode"] = config.autoRevive.mode.name
            this["revive.respawn.delayMs"] = config.autoRevive.respawnDelayMs.toString()
            this["revive.back.delayMs"] = config.autoRevive.backDelayMs.toString()
            this["revive.sound.enabled"] = config.autoRevive.soundEnabled.toString()
            this["revive.sound.volume"] = config.autoRevive.soundVolume.toString()
            this["coma.swap.serverSync"] = config.comaSwapServerSync.toString()
            this["coma.timing.openMs"] = config.comaTiming.openMs.toString()
            this["coma.timing.clearIntervalMs"] = config.comaTiming.clearIntervalMs.toString()
            this["coma.timing.clearToMoveMs"] = config.comaTiming.clearToMoveMs.toString()
            this["coma.timing.moveIntervalMs"] = config.comaTiming.moveIntervalMs.toString()
            this["coma.timing.closeMs"] = config.comaTiming.closeMs.toString()
        }
        Files.newOutputStream(configPath).use { properties.store(it, "RVL Addons configuration") }
    }

    private fun readHudLayout(properties: Properties, key: String, defaultY: Int) = HudLayout(
        anchor = properties.getProperty("$key.anchor")
            ?.let { runCatching { HudAnchor.valueOf(it) }.getOrNull() }
            ?: HudAnchor.TOP_LEFT,
        offsetX = properties.getProperty("$key.offsetX")?.toIntOrNull() ?: 8,
        offsetY = properties.getProperty("$key.offsetY")?.toIntOrNull() ?: defaultY,
        scale = properties.getProperty("$key.scale")?.toFloatOrNull()?.coerceIn(0.5f, 2f) ?: 1f
    )

    private fun writeHudLayout(properties: Properties, key: String, layout: HudLayout) {
        properties["$key.anchor"] = layout.anchor.name
        properties["$key.offsetX"] = layout.offsetX.toString()
        properties["$key.offsetY"] = layout.offsetY.toString()
        properties["$key.scale"] = layout.scale.toString()
    }
}
