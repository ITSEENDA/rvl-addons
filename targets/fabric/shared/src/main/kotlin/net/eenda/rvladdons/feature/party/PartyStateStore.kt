package net.eenda.rvladdons.feature.party

import net.eenda.rvladdons.client.storage.JsonFileStore
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.util.Properties
import java.util.UUID

/** Stores party identity and per-account/server allowances in one JSON file. */
object PartyStateStore {
    const val ALLOWANCE_REFRESH_INTERVAL_MS = 24L * 60L * 60L * 1_000L

    fun allowanceNeedsRefresh(observedAt: Long?, now: Long): Boolean =
        observedAt == null || observedAt > now || now - observedAt >= ALLOWANCE_REFRESH_INTERVAL_MS

    fun loadOrCreateClientId(path: Path, legacyPath: Path? = null): String {
        if (legacyPath != null) migrateLegacy(path, legacyPath)
        var clientId = ""
        runCatching {
            update(path) { state ->
                clientId = state.clientId.takeIf(String::isNotBlank)
                    ?: UUID.randomUUID().toString().also { state.clientId = it }
            }
        }
        return clientId.ifBlank { UUID.randomUUID().toString() }
    }

    fun loadAllowance(path: Path, accountId: String, serverKey: String, root: String): StoredAllowance {
        val normalizedRoot = normalizeRoot(root) ?: return StoredAllowance(null, null)
        val account = accountId.trim()
        val server = serverKey.trim().lowercase()
        val entry = JsonFileStore.read(path, PartyPersistentState::class.java)
            ?.allowances
            ?.firstOrNull {
                it.accountId == account && it.serverKey == server && it.commandRoot == normalizedRoot
            }
        return StoredAllowance(entry?.remaining?.coerceAtLeast(0), entry?.observedAt)
    }

    fun saveAllowance(
        path: Path,
        accountId: String,
        serverKey: String,
        root: String,
        remaining: Int,
        observedAt: Long?
    ): Boolean {
        val normalizedRoot = normalizeRoot(root) ?: return false
        val account = accountId.trim()
        val server = serverKey.trim().lowercase()
        if (account.isBlank() || server.isBlank()) return false
        return runCatching {
            update(path) { state ->
                val index = state.allowances.indexOfFirst {
                    it.accountId == account && it.serverKey == server && it.commandRoot == normalizedRoot
                }
                val oldObservedAt = state.allowances.getOrNull(index)?.observedAt
                val entry = PartyAllowanceRecord(
                    accountId = account,
                    serverKey = server,
                    commandRoot = normalizedRoot,
                    remaining = remaining.coerceAtLeast(0),
                    observedAt = observedAt ?: oldObservedAt
                )
                if (index < 0) state.allowances.add(entry) else state.allowances[index] = entry
            }
        }.isSuccess
    }

    private fun migrateLegacy(path: Path, legacyPath: Path) {
        if (JsonFileStore.read(path, PartyPersistentState::class.java) != null || !Files.exists(legacyPath)) return
        val legacy = readLegacy(legacyPath)
        update(path) { state ->
            if (state.clientId.isBlank()) state.clientId = legacy.clientId
            legacy.allowances.forEach { migrated ->
                val exists = state.allowances.any {
                    it.accountId == migrated.accountId && it.serverKey == migrated.serverKey &&
                        it.commandRoot == migrated.commandRoot
                }
                if (!exists) state.allowances.add(migrated)
            }
        }
        if (Files.exists(legacyPath)) {
            Files.move(
                legacyPath,
                legacyPath.resolveSibling("${legacyPath.fileName}.legacy"),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun readLegacy(path: Path): PartyPersistentState {
        val properties = Properties()
        Files.newInputStream(path).use(properties::load)
        val state = PartyPersistentState(clientId = properties.getProperty("clientId").orEmpty())
        properties.stringPropertyNames()
            .filter { it.startsWith("allowance.") && it.endsWith(".remaining") }
            .forEach { key ->
                val encodedScopeAndRoot = key.removePrefix("allowance.").removeSuffix(".remaining")
                val separator = encodedScopeAndRoot.lastIndexOf('.')
                if (separator <= 0) return@forEach
                val root = normalizeRoot(encodedScopeAndRoot.substring(separator + 1)) ?: return@forEach
                val scope = runCatching {
                    String(
                        Base64.getUrlDecoder().decode(encodedScopeAndRoot.substring(0, separator)),
                        StandardCharsets.UTF_8
                    )
                }.getOrNull() ?: return@forEach
                val scopeSeparator = scope.indexOf('|')
                if (scopeSeparator <= 0 || scopeSeparator == scope.lastIndex) return@forEach
                val remaining = properties.getProperty(key)?.toIntOrNull()?.coerceAtLeast(0) ?: return@forEach
                val observedAt = properties.getProperty(key.removeSuffix("remaining") + "observedAt")
                    ?.toLongOrNull()?.takeIf { it >= 0L }
                state.allowances.add(
                    PartyAllowanceRecord(
                        accountId = scope.substring(0, scopeSeparator),
                        serverKey = scope.substring(scopeSeparator + 1).lowercase(),
                        commandRoot = root,
                        remaining = remaining,
                        observedAt = observedAt
                    )
                )
            }
        return state
    }

    @Synchronized
    private fun update(path: Path, change: (PartyPersistentState) -> Unit) {
        val target = path.toAbsolutePath()
        val parent = target.parent
        Files.createDirectories(parent)
        val lockPath = target.resolveSibling("${target.fileName}.lock")
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val state = JsonFileStore.read(target, PartyPersistentState::class.java) ?: PartyPersistentState()
                change(state)
                JsonFileStore.write(target, state)
            }
        }
    }

    private fun normalizeRoot(root: String): String? =
        root.trim().removePrefix("/").substringBefore(' ').lowercase()
            .takeIf { it == "dchien" || it == "tl" }
}
