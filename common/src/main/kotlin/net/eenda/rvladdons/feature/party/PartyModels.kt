package net.eenda.rvladdons.feature.party

enum class PartyPresenceState {
    CONNECTED,
    IN_SERVER,
    MAIN_MENU,
    DISCONNECTED
}

enum class PartyAttemptState {
    IDLE,
    PREPARING,
    READY,
    JOINING,
    JOINED,
    FAILED
}

data class PartyClientSnapshot(
    val clientId: String,
    val name: String,
    val state: PartyPresenceState,
    val serverKey: String,
    val modVersion: String,
    val selected: Boolean = false,
    val lastSeenAt: Long = System.currentTimeMillis()
)

class PartyRegistry {
    private val clients = linkedMapOf<String, PartyClientSnapshot>()

    @Synchronized
    fun replace(values: Collection<PartyClientSnapshot>) {
        clients.clear()
        values.forEach { clients[it.clientId] = it }
    }

    @Synchronized
    fun upsert(value: PartyClientSnapshot) {
        clients[value.clientId] = value
    }

    @Synchronized
    fun updateSelection(clientId: String, selected: Boolean) {
        clients[clientId]?.let { clients[clientId] = it.copy(selected = selected) }
    }

    @Synchronized
    fun snapshot(): List<PartyClientSnapshot> = clients.values.toList()

    @Synchronized
    fun selected(): List<PartyClientSnapshot> = clients.values.filter { it.selected }

    @Synchronized
    fun get(clientId: String): PartyClientSnapshot? = clients[clientId]
}
