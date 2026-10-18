package net.eenda.rvladdons.feature.party

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PartyStateStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun migratesLegacyPartyPropertiesIntoAccountAndServerJsonState() {
        val root = temp.root.toPath()
        val path = root.resolve("rvl-addons-data/party-state.json")
        val legacy = root.resolve("config/rvl-addons-party.properties")
        val scope = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "account-a|play.example.net".toByteArray(StandardCharsets.UTF_8)
        )
        val properties = Properties().apply {
            this["clientId"] = "stable-party-id"
            this["allowance.$scope.dchien.remaining"] = "7"
            this["allowance.$scope.dchien.observedAt"] = "123"
            this["allowance.$scope.tl.remaining"] = "4"
            this["allowance.$scope.tl.observedAt"] = "456"
        }
        Files.createDirectories(legacy.parent)
        Files.newOutputStream(legacy).use { properties.store(it, null) }

        val clientId = PartyStateStore.loadOrCreateClientId(path, legacy)

        assertEquals("stable-party-id", clientId)
        assertTrue(Files.exists(path))
        assertFalse(Files.exists(legacy))
        assertTrue(Files.exists(legacy.resolveSibling("${legacy.fileName}.legacy")))
        assertEquals(clientId, PartyStateStore.loadOrCreateClientId(path))
        assertEquals(
            StoredAllowance(7, 123L),
            PartyStateStore.loadAllowance(path, "account-a", "PLAY.EXAMPLE.NET", "dchien")
        )
        assertEquals(
            StoredAllowance(4, 456L),
            PartyStateStore.loadAllowance(path, "account-a", "play.example.net", "tl")
        )
        assertEquals(StoredAllowance(null, null), PartyStateStore.loadAllowance(path, "account-b", "play.example.net", "dchien"))
        assertTrue(PartyStateStore.saveAllowance(path, "account-a", "play.example.net", "dchien", 6, 789L))
        assertEquals(
            StoredAllowance(6, 789L),
            PartyStateStore.loadAllowance(path, "account-a", "play.example.net", "dchien")
        )
    }

    @Test
    fun refreshesMissingOrOlderThanOneDayAllowances() {
        val now = 10L * PartyStateStore.ALLOWANCE_REFRESH_INTERVAL_MS

        assertTrue(PartyStateStore.allowanceNeedsRefresh(null, now))
        assertFalse(PartyStateStore.allowanceNeedsRefresh(now - 1, now))
        assertTrue(PartyStateStore.allowanceNeedsRefresh(
            now - PartyStateStore.ALLOWANCE_REFRESH_INTERVAL_MS,
            now
        ))
    }
}
