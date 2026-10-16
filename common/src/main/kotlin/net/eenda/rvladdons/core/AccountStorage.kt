package net.eenda.rvladdons.core

import java.nio.file.Path
import java.util.UUID

object AccountStorage {
    fun directory(dataDir: Path, uuid: UUID?, username: String): Path =
        dataDir.resolve("accounts").resolve(accountId(uuid, username))

    fun accountId(uuid: UUID?, username: String): String =
        (uuid ?: UUID.nameUUIDFromBytes("OfflinePlayer:$username".toByteArray(Charsets.UTF_8))).toString()
}
