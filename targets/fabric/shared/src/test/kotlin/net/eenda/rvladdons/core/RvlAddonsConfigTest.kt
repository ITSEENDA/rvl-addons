package net.eenda.rvladdons.core

import java.nio.file.Files
import java.util.Properties
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RvlAddonsConfigTest {
    @Test
    fun migratesGlobalComaProfilesToAccountJsonOnce() {
        val directory = Files.createTempDirectory("rvl-coma-profiles")
        val configPath = directory.resolve("rvl-addons.properties")
        val dataDir = directory.resolve("rvl-addons-data")
        val accountA = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val accountB = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val accountAPath = AccountStorage.directory(dataDir, accountA, "Alice").resolve("coma-profiles.json")
        val accountBPath = AccountStorage.directory(dataDir, accountB, "Bob").resolve("coma-profiles.json")
        try {
            val properties = Properties()
            properties["enabled"] = "false"
            properties["coma.set.count"] = "1"
            properties["coma.set.0.name"] = "Alice's set"
            properties["coma.set.0.enabled"] = "true"
            properties["coma.set.0.helmet"] = "helmet-rule"
            Files.newOutputStream(configPath).use { properties.store(it, null) }

            RvlAddonsConfigStore.load(configPath, accountAPath)

            assertEquals("Alice's set", RvlAddonsConfigStore.config.comaSets.single().name)
            assertTrue(Files.exists(accountAPath))
            assertFalse(loadProperties(configPath).keys.any { it.toString().startsWith("coma.set.") })

            RvlAddonsConfigStore.load(configPath, accountBPath)
            assertEquals("Set 1", RvlAddonsConfigStore.config.comaSets.single().name)
            assertFalse(RvlAddonsConfigStore.config.enabled)

            RvlAddonsConfigStore.load(configPath, accountAPath)
            assertEquals("Alice's set", RvlAddonsConfigStore.config.comaSets.single().name)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun migratesAccountComaPropertiesAndKeepsBackup() {
        val directory = Files.createTempDirectory("rvl-coma-account-migration")
        val configPath = directory.resolve("rvl-addons.properties")
        val dataDir = directory.resolve("rvl-addons-data")
        val accountId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val newPath = AccountStorage.directory(dataDir, accountId, "Alice").resolve("coma-profiles.json")
        val oldPath = AccountStorage.path(directory, "rvl-addons-coma-profiles", accountId, "Alice")
        try {
            Files.newOutputStream(configPath).use { Properties().store(it, null) }
            val oldProperties = Properties().apply {
                this["coma.set.count"] = "1"
                this["coma.set.0.name"] = "Account set"
                this["coma.set.0.enabled"] = "true"
                this["coma.set.0.boots"] = "boot-rule"
            }
            Files.createDirectories(oldPath.parent)
            Files.newOutputStream(oldPath).use { oldProperties.store(it, null) }

            RvlAddonsConfigStore.load(configPath, newPath, oldPath)

            assertEquals("Account set", RvlAddonsConfigStore.config.comaSets.single().name)
            assertEquals("boot-rule", RvlAddonsConfigStore.config.comaSets.single().boots)
            assertTrue(Files.exists(newPath))
            assertFalse(Files.exists(oldPath))
            assertTrue(Files.exists(oldPath.resolveSibling("${oldPath.fileName}.legacy")))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun savesSettingsWithoutAProfilePath() {
        val directory = Files.createTempDirectory("rvl-party-config")
        val configPath = directory.resolve("rvl-addons.properties")
        val profilePath = directory.resolve("coma-profiles.json")
        try {
            RvlAddonsConfigStore.load(configPath, profilePath)
            RvlAddonsConfigStore.config.partyAutomaticRoomName = "Dchien Trial"
            RvlAddonsConfigStore.config.partyTlAutomaticRoomName = "Thanh Lan Trial"
            RvlAddonsConfigStore.save()
            RvlAddonsConfigStore.load(configPath, profilePath)

            assertEquals("Dchien Trial", RvlAddonsConfigStore.config.partyAutomaticRoomName)
            assertEquals("Thanh Lan Trial", RvlAddonsConfigStore.config.partyTlAutomaticRoomName)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun partyLoopDelaysHaveCommandSpecificMinimumsAndPersist() {
        val directory = Files.createTempDirectory("rvl-party-loop-delay")
        val configPath = directory.resolve("rvl-addons.properties")
        val profilePath = directory.resolve("coma-profiles.json")
        try {
            val properties = Properties().apply {
                setProperty("party.auto.loopDelaySeconds", "5")
                setProperty("party.auto.dchienLoopDelaySeconds", "2")
            }
            Files.newOutputStream(configPath).use { properties.store(it, null) }
            RvlAddonsConfigStore.load(configPath, profilePath)
            assertEquals(3, RvlAddonsConfigStore.config.partyDchienLoopDelaySeconds)
            assertEquals(10, RvlAddonsConfigStore.config.partyTlLoopDelaySeconds)

            RvlAddonsConfigStore.config.partyDchienLoopDelaySeconds = 7
            RvlAddonsConfigStore.config.partyTlLoopDelaySeconds = 22
            RvlAddonsConfigStore.save()
            RvlAddonsConfigStore.load(configPath, profilePath)
            assertEquals(7, RvlAddonsConfigStore.config.partyDchienLoopDelaySeconds)
            assertEquals(22, RvlAddonsConfigStore.config.partyTlLoopDelaySeconds)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun offlineAccountIdIsDeterministic() {
        assertEquals(AccountStorage.accountId(null, "Alice"), AccountStorage.accountId(null, "Alice"))
        assertFalse(AccountStorage.accountId(null, "Alice") == AccountStorage.accountId(null, "Bob"))
        assertEquals(
            "00000000-0000-0000-0000-000000000001",
            AccountStorage.accountId(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Alice")
        )
    }

    private fun loadProperties(path: java.nio.file.Path): Properties = Properties().also { properties ->
        Files.newInputStream(path).use(properties::load)
    }
}
