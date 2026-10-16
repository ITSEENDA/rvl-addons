package net.eenda.rvladdons.feature.coma

import net.eenda.rvladdons.client.storage.JsonFileStore
import java.nio.file.Path

object ComaStateStore {
    private var path: Path? = null
    private var state = PersistedComaState()

    fun load(statePath: Path) {
        path = statePath
        state = JsonFileStore.read(statePath, PersistedComaState::class.java) ?: PersistedComaState()
    }

    fun current(): PersistedComaState = state

    fun save(serverKey: String, rules: List<String>, iconItemIds: List<String> = emptyList()) {
        state = PersistedComaState(serverKey.trim(), rules.take(4), iconItemIds.take(4))
        path?.let { JsonFileStore.write(it, state) }
    }
}
