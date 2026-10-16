package net.eenda.rvladdons.feature.cooldown

import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executors
import net.eenda.rvladdons.core.AccountStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillProfileStoreTest {
    @Test
    fun roundTripsProfilesAsJson() {
        val path = Files.createTempDirectory("rvl-skill-profiles").resolve("profiles.json")
        try {
            SkillProfileStore.load(path)
            SkillProfileStore.register(
                skillKey = "coma",
                label = "Ba Vuong",
                setId = "set-1",
                trigger = SkillTrigger.RIGHT_CLICK,
                sourceFingerprint = "set:set-1",
                sourceType = SkillSourceType.COMA_SET,
                skillType = SkillType.ACTIVE,
                cooldownHintMs = 10_000L
            )

            SkillProfileStore.load(path)

            assertEquals(1, SkillProfileStore.all().size)
            assertEquals("coma", SkillProfileStore.all().single().skillKey)
            assertEquals(10_000L, SkillProfileStore.all().single().cooldownHintMs)
            assertTrue(Files.readString(path).startsWith("["))
        } finally {
            path.parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun serializesConcurrentProfileRegistration() {
        val path = Files.createTempDirectory("rvl-skill-profiles-concurrent").resolve("profiles.json")
        val executor = Executors.newFixedThreadPool(8)
        try {
            SkillProfileStore.load(path)
            val futures = (0 until 8).map { worker ->
                executor.submit {
                    repeat(50) { index ->
                        SkillProfileStore.register(
                            skillKey = "skill-$worker-$index",
                            label = "Skill $worker $index",
                            setId = null,
                            trigger = SkillTrigger.RIGHT_CLICK,
                            sourceFingerprint = "fingerprint-$worker-$index",
                            cooldownHintMs = 1_000L
                        )
                    }
                }
            }
            futures.forEach { it.get() }
            assertEquals(400, SkillProfileStore.all().size)
            SkillProfileStore.load(path)
            assertEquals(400, SkillProfileStore.all().size)
        } finally {
            executor.shutdownNow()
            path.parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun isolatesProfilesByAccount() {
        val directory = Files.createTempDirectory("rvl-skill-profiles-accounts")
        val accountAPath = SkillProfileStore.accountProfilePath(
            directory,
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "Alice"
        )
        val accountBPath = SkillProfileStore.accountProfilePath(
            directory,
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            "Bob"
        )
        try {
            SkillProfileStore.load(accountAPath)
            SkillProfileStore.register(
                skillKey = "alice-skill",
                label = "Alice skill",
                setId = null,
                trigger = SkillTrigger.RIGHT_CLICK,
                sourceFingerprint = "alice"
            )
            SkillProfileStore.load(accountBPath)
            assertTrue(SkillProfileStore.all().isEmpty())
            assertFalse(accountAPath == accountBPath)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
