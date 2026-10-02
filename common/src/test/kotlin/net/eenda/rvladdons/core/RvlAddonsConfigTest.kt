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
    fun migratesComaProfilesPerAccount() {
        val directory = Files.createTempDirectory("rvl-coma-profiles")
        val configPath = directory.resolve("rvl-addons.properties")
        val accountAPath = AccountStorage.path(
            directory,
            "rvl-addons-coma-profiles",
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "Alice"
        )
        val accountBPath = AccountStorage.path(
            directory,
            "rvl-addons-coma-profiles",
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            "Bob"
        )
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
            assertTrue(RvlAddonsConfigStore.config.enabled.not())
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

    @Test
    fun capturedPartyRoomNamesPersist() {
        val directory = Files.createTempDirectory("rvl-party-rooms")
        val configPath = directory.resolve("rvl-addons.properties")
        try {
            RvlAddonsConfigStore.load(configPath)
            RvlAddonsConfigStore.config.partyAutomaticRoomName = "Dchien Trial"
            RvlAddonsConfigStore.config.partyTlAutomaticRoomName = "Thanh Lan Trial"
            RvlAddonsConfigStore.save()
            RvlAddonsConfigStore.load(configPath)

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
        try {
            val properties = Properties().apply {
                setProperty("party.auto.loopDelaySeconds", "5")
                setProperty("party.auto.dchienLoopDelaySeconds", "2")
            }
            Files.newOutputStream(configPath).use { properties.store(it, null) }
            RvlAddonsConfigStore.load(configPath)
            assertEquals(3, RvlAddonsConfigStore.config.partyDchienLoopDelaySeconds)
            assertEquals(10, RvlAddonsConfigStore.config.partyTlLoopDelaySeconds)

            RvlAddonsConfigStore.config.partyDchienLoopDelaySeconds = 7
            RvlAddonsConfigStore.config.partyTlLoopDelaySeconds = 22
            RvlAddonsConfigStore.save()
            RvlAddonsConfigStore.load(configPath)
            assertEquals(7, RvlAddonsConfigStore.config.partyDchienLoopDelaySeconds)
            assertEquals(22, RvlAddonsConfigStore.config.partyTlLoopDelaySeconds)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun loadProperties(path: java.nio.file.Path): Properties = Properties().also { properties ->
        Files.newInputStream(path).use(properties::load)
    }
}
