package net.eenda.rvladdons.client.party

import net.eenda.rvladdons.feature.party.PartyStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PartyAllowanceTrackerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun commitSubtractsOnlyFromFreshBalanceAndRefreshesStaleBalance() {
        val path = temporaryFolder.root.toPath().resolve("party-state.json")
        val now = System.currentTimeMillis()
        PartyStateStore.saveAllowance(path, "account", "server", "dchien", 8, now)

        val freshTracker = PartyAllowanceTracker()
        freshTracker.onConnected(path, "account", "server", now + 1)
        freshTracker.commit(PartyAllowanceCost("dchien", 3), "fresh-attempt")

        assertEquals(5, freshTracker.remaining("dchien"))
        assertTrue(freshTracker.isFresh("dchien"))

        val stalePath = temporaryFolder.root.toPath().resolve("stale-party-state.json")
        PartyStateStore.saveAllowance(
            stalePath,
            "account",
            "server",
            "tl",
            8,
            now - PartyStateStore.ALLOWANCE_REFRESH_INTERVAL_MS - 1
        )
        val staleTracker = PartyAllowanceTracker()
        staleTracker.onConnected(stalePath, "account", "server", now)
        staleTracker.commit(PartyAllowanceCost("tl", 3), "stale-attempt")

        assertEquals(8, staleTracker.remaining("tl"))
        assertFalse(staleTracker.isFresh("tl"))
        assertEquals(0L, PartyStateStore.loadAllowance(stalePath, "account", "server", "tl").observedAt)
    }
}
