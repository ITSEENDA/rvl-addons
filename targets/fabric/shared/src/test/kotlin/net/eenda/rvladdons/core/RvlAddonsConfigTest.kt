package net.eenda.rvladdons.core

import java.nio.file.Files
import java.util.Properties
import java.util.UUID
import net.eenda.rvladdons.feature.revive.AutoReviveMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RvlAddonsConfigTest {
    @Test
    fun keepsSettingsInPropertiesAndComaProfilesInAccountJson() {
        val directory = Files.createTempDirectory("rvl-coma-profiles")
        val configPath = directory.resolve("config/rvl-addons.properties")
        val accountAPath = AccountStorage.directory(
            directory,
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "Alice"
        ).resolve("coma-profiles.json")
        val accountBPath = AccountStorage.directory(
            directory,
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            "Bob"
        ).resolve("coma-profiles.json")
        try {
            val properties = Properties()
            properties["enabled"] = "false"
            properties["coma.set.count"] = "1"
            properties["coma.set.0.name"] = "Ignored old set"
            properties["revive.mode"] = "RBD"
            Files.createDirectories(configPath.parent)
            Files.newOutputStream(configPath).use { properties.store(it, null) }

            RvlAddonsConfigStore.load(configPath, accountAPath)

            assertEquals("Set 1", RvlAddonsConfigStore.config.comaSets.single().name)
            assertFalse(Files.exists(accountAPath))
            assertFalse(RvlAddonsConfigStore.config.autoRevive.enabled)
            assertEquals(AutoReviveMode.FAST, RvlAddonsConfigStore.config.autoRevive.mode)

            RvlAddonsConfigStore.config.comaSets[0].name = "Alice's set"
            RvlAddonsConfigStore.save()
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
    fun roundTripsHudLayouts() {
        val directory = Files.createTempDirectory("rvl-hud-config")
        val configPath = directory.resolve("rvl-addons.properties")
        val profilePath = AccountStorage.directory(
            directory,
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "Alice"
        ).resolve("coma-profiles.json")
        try {
            RvlAddonsConfigStore.load(configPath, profilePath)
            RvlAddonsConfigStore.config.hudStatus = HudLayout(HudAnchor.BOTTOM_RIGHT, 12, 34, 1.5f)
            RvlAddonsConfigStore.config.hudTrace = HudLayout(HudAnchor.BOTTOM_LEFT, -4, 56, 0.75f)
            RvlAddonsConfigStore.save()

            RvlAddonsConfigStore.load(configPath, profilePath)

            assertEquals(HudLayout(HudAnchor.BOTTOM_RIGHT, 12, 34, 1.5f), RvlAddonsConfigStore.config.hudStatus)
            assertEquals(HudLayout(HudAnchor.BOTTOM_LEFT, -4, 56, 0.75f), RvlAddonsConfigStore.config.hudTrace)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun accountIdsAreDeterministic() {
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
