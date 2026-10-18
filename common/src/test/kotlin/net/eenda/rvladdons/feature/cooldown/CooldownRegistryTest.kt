package net.eenda.rvladdons.feature.cooldown

import org.junit.Assert.assertEquals
import org.junit.Test

class CooldownRegistryTest {
    @Test
    fun expiresEntriesAndKeepsParallelSkills() {
        val registry = CooldownRegistry()
        registry.upsert(entry("one", 1_000L, 3_000L))
        registry.upsert(entry("two", 1_500L, 5_000L))

        assertEquals(listOf("one", "two"), registry.active(2_000L).map { it.skillKey })
        assertEquals(listOf("two"), registry.active(3_001L).map { it.skillKey })
    }

    private fun entry(key: String, startedAt: Long, endAt: Long) = CooldownEntry(
        skillKey = key,
        displayName = key,
        trigger = SkillTrigger.RIGHT_CLICK,
        source = null,
        startedAt = startedAt,
        endAt = endAt,
        lastServerRemainingMs = endAt - startedAt,
        lastServerUpdateAt = startedAt
    )
}
