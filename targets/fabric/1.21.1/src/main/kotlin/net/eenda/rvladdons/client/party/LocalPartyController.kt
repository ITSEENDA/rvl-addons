package net.eenda.rvladdons.client.party

import net.eenda.rvladdons.feature.party.PartyAttemptState
import net.eenda.rvladdons.feature.party.PartyClientSnapshot
import net.eenda.rvladdons.feature.party.PartyPresenceState
import net.eenda.rvladdons.feature.party.PartyRegistry
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.UUID

object LocalPartyController {
    private const val ID_FILE = "rvl-addons-party.properties"
    private val registry = PartyRegistry()
    private var transport: LocalPartyTransport? = null
    private var clientId = ""
    private var attemptState = PartyAttemptState.IDLE
    private var lastLocalUpdate = 0L

    fun initialize() {
        if (transport != null) return
        clientId = loadClientId(FabricLoader.getInstance().configDir.resolve(ID_FILE))
        transport = LocalPartyTransport(registry::replace).also { it.start() }
    }

    fun tick(client: MinecraftClient) {
        val now = System.currentTimeMillis()
        if (now - lastLocalUpdate < 500L) return
        lastLocalUpdate = now
        val state = if (client.world != null && client.player != null) {
            PartyPresenceState.IN_SERVER
        } else {
            PartyPresenceState.MAIN_MENU
        }
        publishPresence(client, state)
    }

    fun onJoin(client: MinecraftClient) {
        publishPresence(client, PartyPresenceState.IN_SERVER)
    }

    fun onDisconnect(client: MinecraftClient) {
        publishPresence(client, PartyPresenceState.DISCONNECTED)
    }

    fun isEligible(snapshot: PartyClientSnapshot, client: MinecraftClient): Boolean =
        snapshot.state == PartyPresenceState.IN_SERVER && snapshot.serverKey == currentServerKey(client)

    private fun publishPresence(client: MinecraftClient, state: PartyPresenceState) {
        val currentTransport = transport ?: return
        val selected = registry.get(clientId)?.selected ?: false
        currentTransport.updateLocal(
            PartyClientSnapshot(
                clientId = clientId,
                name = client.player?.name?.string ?: "Client-${clientId.take(6)}",
                state = state,
                serverKey = currentServerKey(client),
                modVersion = "rvl-addons",
                selected = selected
            )
        )
    }

    fun clients(): List<PartyClientSnapshot> = registry.snapshot()

    fun selectedCount(): Int = registry.selected().size

    fun currentState(): PartyAttemptState = attemptState

    fun isCoordinator(): Boolean = transport?.isCoordinator == true

    fun toggleSelection(clientId: String) {
        val current = registry.get(clientId) ?: return
        registry.updateSelection(clientId, !current.selected)
    }

    fun selectAllEligible(client: MinecraftClient) {
        registry.snapshot().forEach { snapshot ->
            registry.updateSelection(snapshot.clientId, isEligible(snapshot, client))
        }
    }

    fun startDraft(client: MinecraftClient) {
        if (registry.selected().isEmpty()) {
            client.inGameHud.setOverlayMessage(Text.literal("Party: select at least one client"), false)
            return
        }
        attemptState = PartyAttemptState.PREPARING
        client.inGameHud.setOverlayMessage(Text.literal("Party draft: preparing local clients"), false)
    }

    fun cancelDraft(client: MinecraftClient) {
        attemptState = PartyAttemptState.IDLE
        client.inGameHud.setOverlayMessage(Text.literal("Party draft cancelled"), false)
    }

    fun statusText(): String {
        val clients = registry.snapshot()
        if (clients.isEmpty()) return ""
        val ready = clients.count { it.state == PartyPresenceState.IN_SERVER }
        val selected = selectedCount()
        return "Party ${selected}/${clients.size} | Ready $ready/${clients.size} | ${attemptState.name}"
    }

    private fun loadClientId(path: Path): String {
        val properties = Properties()
        if (Files.exists(path)) {
            runCatching { Files.newInputStream(path).use(properties::load) }
        }
        val existing = properties.getProperty("clientId")?.takeIf { it.isNotBlank() }
        if (existing != null) return existing
        val generated = UUID.randomUUID().toString()
        runCatching {
            Files.createDirectories(path.parent)
            properties["clientId"] = generated
            Files.newOutputStream(path).use { properties.store(it, "RVL local party identity") }
        }
        return generated
    }

    private fun currentServerKey(client: MinecraftClient): String =
        client.currentServerEntry?.address?.trim()?.lowercase().orEmpty()
}
