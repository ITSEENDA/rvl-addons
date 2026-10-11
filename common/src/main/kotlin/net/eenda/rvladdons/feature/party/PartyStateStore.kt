package net.eenda.rvladdons.feature.party

import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.util.Properties
import java.util.UUID

data class StoredAllowance(val remaining: Int?, val observedAt: Long?)

/** Stores the party identity and per-account/server allowance cache in one properties file. */
object PartyStateStore {
    const val ALLOWANCE_REFRESH_INTERVAL_MS = 24L * 60L * 60L * 1_000L

    fun allowanceNeedsRefresh(observedAt: Long?, now: Long): Boolean =
        observedAt == null || observedAt > now || now - observedAt >= ALLOWANCE_REFRESH_INTERVAL_MS

    fun loadOrCreateClientId(path: Path): String {
        var clientId = ""
        runCatching {
            update(path) { properties ->
                clientId = properties.getProperty("clientId")?.takeIf(String::isNotBlank)
                    ?: UUID.randomUUID().toString().also { properties["clientId"] = it }
            }
        }
        if (clientId.isBlank()) clientId = UUID.randomUUID().toString()
        return clientId
    }

    fun loadAllowance(path: Path, accountId: String, serverKey: String, root: String): StoredAllowance {
        val key = allowanceKey(accountId, serverKey, root) ?: return StoredAllowance(null, null)
        val properties = Properties()
        if (Files.exists(path)) runCatching { Files.newInputStream(path).use(properties::load) }
        return StoredAllowance(
            properties.getProperty("$key.remaining")?.toIntOrNull()?.coerceAtLeast(0),
            properties.getProperty("$key.observedAt")?.toLongOrNull()?.takeIf { it >= 0L }
        )
    }

    fun saveAllowance(
        path: Path,
        accountId: String,
        serverKey: String,
        root: String,
        remaining: Int,
        observedAt: Long?
    ): Boolean {
        val key = allowanceKey(accountId, serverKey, root) ?: return false
        return runCatching {
            update(path) { properties ->
                properties["$key.remaining"] = remaining.coerceAtLeast(0).toString()
                if (observedAt != null) properties["$key.observedAt"] = observedAt.toString()
            }
        }.isSuccess
    }

    private fun allowanceKey(accountId: String, serverKey: String, root: String): String? {
        val normalizedRoot = root.trim().removePrefix("/").lowercase()
        if (normalizedRoot != "dchien" && normalizedRoot != "tl") return null
        val scope = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "$accountId|${serverKey.trim().lowercase()}".toByteArray(StandardCharsets.UTF_8)
        )
        return "allowance.$scope.$normalizedRoot"
    }

    @Synchronized
    private fun update(path: Path, change: (Properties) -> Unit) {
        path.parent?.let { Files.createDirectories(it) }
        val lockPath = path.resolveSibling("${path.fileName}.lock")
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val properties = Properties()
                if (Files.exists(path)) Files.newInputStream(path).use(properties::load)
                change(properties)

                val parent = path.parent ?: path.toAbsolutePath().parent
                val temp = Files.createTempFile(parent, "party-state-", ".tmp")
                try {
                    Files.newOutputStream(temp).use { properties.store(it, "RVL Addons party state") }
                    try {
                        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    Files.deleteIfExists(temp)
                }
            }
        }
    }
}
