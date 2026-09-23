package net.eenda.rvladdons.core

import java.nio.file.Path
import java.util.UUID

object AccountStorage {
    fun path(configDir: Path, directory: String, uuid: UUID?, username: String): Path =
        configDir.resolve(directory).resolve("${accountId(uuid, username)}.properties")

    fun accountId(uuid: UUID?, username: String): String =
        (uuid ?: UUID.nameUUIDFromBytes("OfflinePlayer:$username".toByteArray(Charsets.UTF_8))).toString()
}
