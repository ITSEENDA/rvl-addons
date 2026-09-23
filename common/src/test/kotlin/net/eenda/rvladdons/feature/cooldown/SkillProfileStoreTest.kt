package net.eenda.rvladdons.feature.cooldown

import java.nio.file.Files
import java.util.Properties
import java.util.UUID
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillProfileStoreTest {
    @Test
    fun mergesConfiguredComaSetWithLegacyEquippedSet() {
        val path = Files.createTempFile("rvl-skill-profiles", ".properties")
        try {
            val properties = Properties()
            properties["profile.count"] = "2"
            writeProfile(properties, 0, "coma", "Ba Vuong Cuong Bao Chinh [EX]", "COMA_SET:1", "COMA_SET")
            writeProfile(properties, 1, "equipped", "CO MA Ba Vuong Cuong Bao - Chinh:", "CO MA Ba Vuong Cuong Bao - Chinh:", "EQUIPPED_SET")
            Files.newOutputStream(path).use { properties.store(it, null) }

            SkillProfileStore.load(path)

            assertEquals(1, SkillProfileStore.all().size)
            assertEquals(SkillSourceType.COMA_SET, SkillProfileStore.all().single().sourceType)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun serializesConcurrentProfileRegistration() {
        val path = Files.createTempFile("rvl-skill-profiles-concurrent", ".properties")
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
        } finally {
            executor.shutdownNow()
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun isolatesProfilesByAccountAndMigratesLegacyFileOnce() {
        val directory = Files.createTempDirectory("rvl-skill-profiles-accounts")
        val legacyPath = SkillProfileStore.legacyProfilePath(directory)
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
            val properties = Properties()
            properties["profile.count"] = "1"
            writeProfile(properties, 0, "legacy", "Legacy skill", "legacy", "ITEM")
            Files.newOutputStream(legacyPath).use { properties.store(it, null) }

            SkillProfileStore.load(accountAPath, legacyPath)
            assertEquals(listOf("legacy"), SkillProfileStore.all().map { it.skillKey })
            assertTrue(Files.exists(accountAPath))
            assertFalse(Files.exists(legacyPath))
            assertTrue(Files.exists(legacyPath.resolveSibling("${legacyPath.fileName}.legacy")))

            SkillProfileStore.load(accountBPath, legacyPath)
            assertTrue(SkillProfileStore.all().isEmpty())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun createsDeterministicOfflineAccountId() {
        assertEquals(
            SkillProfileStore.accountId(null, "Alice"),
            SkillProfileStore.accountId(null, "Alice")
        )
        assertFalse(
            SkillProfileStore.accountId(null, "Alice") ==
                SkillProfileStore.accountId(null, "Bob")
        )
    }

    private fun writeProfile(
        properties: Properties,
        index: Int,
        key: String,
        label: String,
        setId: String,
        sourceType: String
    ) {
        properties["profile.$index.key"] = key
        properties["profile.$index.label"] = label
        properties["profile.$index.setId"] = setId
        properties["profile.$index.fingerprint"] = "set:$setId"
        properties["profile.$index.sourceType"] = sourceType
        properties["profile.$index.trigger"] = SkillTrigger.RIGHT_CLICK.name
        properties["profile.$index.skillType"] = SkillType.ACTIVE.name
        properties["profile.$index.enabled"] = "true"
    }
}
