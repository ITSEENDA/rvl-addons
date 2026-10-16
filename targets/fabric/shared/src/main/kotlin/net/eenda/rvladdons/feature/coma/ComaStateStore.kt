package net.eenda.rvladdons.feature.coma

import net.eenda.rvladdons.client.storage.JsonFileStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

object ComaStateStore {
    private var path: Path? = null
    private var state = PersistedComaState()

    fun load(statePath: Path, legacyAccountPath: Path? = null, legacyPath: Path? = null) {
        path = statePath
        JsonFileStore.read(statePath, PersistedComaState::class.java)?.let {
            state = it
            return
        }

        val legacySource = listOfNotNull(legacyAccountPath, legacyPath).firstOrNull(Files::exists)
        if (legacySource == null) {
            state = PersistedComaState()
            return
        }

        val properties = Properties()
        Files.newInputStream(legacySource).use(properties::load)
        val count = properties.getProperty("rule.count")?.toIntOrNull()?.coerceIn(0, 4) ?: 0
        state = PersistedComaState(
            serverKey = properties.getProperty("server")?.trim().orEmpty(),
            rules = List(count) { properties.getProperty("rule.$it").orEmpty() },
            iconItemIds = List(count) { properties.getProperty("icon.$it").orEmpty() }
        )
        save(state.serverKey, state.rules, state.iconItemIds)
        archive(legacySource)
    }

    fun current(): PersistedComaState = state

    fun save(serverKey: String, rules: List<String>, iconItemIds: List<String> = emptyList()) {
        state = PersistedComaState(serverKey.trim(), rules.take(4), iconItemIds.take(4))
        path?.let { JsonFileStore.write(it, state) }
    }

    private fun archive(path: Path) {
        if (Files.exists(path)) {
            Files.move(path, path.resolveSibling("${path.fileName}.legacy"), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
