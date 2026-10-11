package net.eenda.rvladdons.feature.party

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PartyStateStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun storesBalancesByAccountAndServerWithoutReplacingPartyId() {
        val path = temp.root.toPath().resolve("rvl-addons-party.properties")
        val clientId = PartyStateStore.loadOrCreateClientId(path)

        PartyStateStore.saveAllowance(path, "account-a", "play.example.net", "dchien", 7, 123L)
        PartyStateStore.saveAllowance(path, "account-a", "play.example.net", "tl", 4, 456L)

        assertEquals(clientId, PartyStateStore.loadOrCreateClientId(path))
        assertEquals(StoredAllowance(7, 123L), PartyStateStore.loadAllowance(path, "account-a", "PLAY.EXAMPLE.NET", "dchien"))
        assertEquals(StoredAllowance(4, 456L), PartyStateStore.loadAllowance(path, "account-a", "play.example.net", "tl"))
        assertEquals(StoredAllowance(null, null), PartyStateStore.loadAllowance(path, "account-b", "play.example.net", "dchien"))
        assertNull(PartyStateStore.loadAllowance(path, "account-a", "other.example.net", "dchien").remaining)
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
