package net.eenda.rvladdons.feature.coma

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

data class PersistedComaState(
    val serverKey: String,
    val rules: List<String>,
    val iconItemIds: List<String>
)

object ComaStateStore {
    private var path: Path? = null
    private var state = PersistedComaState("", emptyList(), emptyList())

    fun load(statePath: Path) {
        path = statePath
        if (!Files.exists(statePath)) {
            state = PersistedComaState("", emptyList(), emptyList())
            return
        }
        val properties = Properties()
        Files.newInputStream(statePath).use(properties::load)
        val count = properties.getProperty("rule.count")?.toIntOrNull()?.coerceIn(0, 4) ?: 0
        state = PersistedComaState(
            serverKey = properties.getProperty("server")?.trim().orEmpty(),
            rules = List(count) { index -> properties.getProperty("rule.$index").orEmpty() },
            iconItemIds = List(count) { index -> properties.getProperty("icon.$index").orEmpty() }
        )
    }

    fun current(): PersistedComaState = state

    fun save(serverKey: String, rules: List<String>, iconItemIds: List<String> = emptyList()) {
        state = PersistedComaState(serverKey.trim(), rules.take(4), iconItemIds.take(4))
        val statePath = path ?: return
        Files.createDirectories(statePath.parent)
        val properties = Properties()
        properties["server"] = state.serverKey
        properties["rule.count"] = state.rules.size.toString()
        state.rules.forEachIndexed { index, rule -> properties["rule.$index"] = rule }
        state.iconItemIds.forEachIndexed { index, itemId -> properties["icon.$index"] = itemId }
        Files.newOutputStream(statePath).use { properties.store(it, "RVL Addons last confirmed coma set") }
    }
}
