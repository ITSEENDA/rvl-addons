package net.eenda.rvladdons.feature.coma

import java.nio.file.Files
import java.util.Properties
import java.util.UUID
import net.eenda.rvladdons.core.AccountStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComaStateStoreTest {
    @Test
    fun migratesLastActiveStatePerAccount() {
        val directory = Files.createTempDirectory("rvl-coma-state")
        val legacyPath = ComaStateStore.legacyStatePath(directory)
        val accountAPath = AccountStorage.path(
            directory,
            "rvl-addons-coma-state",
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "Alice"
        )
        val accountBPath = AccountStorage.path(
            directory,
            "rvl-addons-coma-state",
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            "Bob"
        )
        try {
            val properties = Properties()
            properties["server"] = "play.example.test"
            properties["rule.count"] = "1"
            properties["rule.0"] = "alice-rule"
            properties["icon.0"] = "minecraft:diamond_helmet"
            Files.newOutputStream(legacyPath).use { properties.store(it, null) }

            ComaStateStore.load(accountAPath, legacyPath)

            assertEquals("play.example.test", ComaStateStore.current().serverKey)
            assertEquals(listOf("alice-rule"), ComaStateStore.current().rules)
            assertTrue(Files.exists(accountAPath))
            assertFalse(Files.exists(legacyPath))
            assertTrue(Files.exists(legacyPath.resolveSibling("${legacyPath.fileName}.legacy")))

            ComaStateStore.load(accountBPath, legacyPath)
            assertTrue(ComaStateStore.current().rules.isEmpty())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
