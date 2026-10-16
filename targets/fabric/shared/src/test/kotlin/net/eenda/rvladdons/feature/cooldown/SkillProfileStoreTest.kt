package net.eenda.rvladdons.feature.cooldown

import java.nio.file.Files
import java.util.Properties
import java.util.UUID
import java.util.concurrent.Executors
import net.eenda.rvladdons.core.AccountStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillProfileStoreTest {
    @Test
    fun migratesAccountProfilesAndKeepsLegacyBackup() {
        val directory = Files.createTempDirectory("rvl-skill-profiles")
        val accountId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val newPath = SkillProfileStore.accountProfilePath(directory.resolve("data"), accountId, "Alice")
        val oldPath = SkillProfileStore.legacyAccountProfilePath(directory, accountId, "Alice")
        try {
            val properties = Properties().apply {
                this["profile.count"] = "2"
                writeProfile(this, 0, "coma", "Ba Vuong Cuong Bao Chinh [EX]", "COMA_SET:1", "COMA_SET")
                writeProfile(this, 1, "equipped", "CO MA Ba Vuong Cuong Bao - Chinh:", "CO MA Ba Vuong Cuong Bao - Chinh:", "EQUIPPED_SET")
            }
            Files.createDirectories(oldPath.parent)
            Files.newOutputStream(oldPath).use { properties.store(it, null) }

            SkillProfileStore.load(newPath, oldPath)

            assertEquals(1, SkillProfileStore.all().size)
            assertEquals(SkillSourceType.COMA_SET, SkillProfileStore.all().single().sourceType)
            assertTrue(Files.exists(newPath))
            assertFalse(Files.exists(oldPath))
            assertTrue(Files.exists(oldPath.resolveSibling("${oldPath.fileName}.legacy")))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun migratesGlobalProfilesWhenNoAccountFileExists() {
        val directory = Files.createTempDirectory("rvl-skill-profiles-global")
        val newPath = SkillProfileStore.accountProfilePath(
            directory.resolve("data"),
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "Alice"
        )
        val legacyPath = SkillProfileStore.legacyProfilePath(directory)
        try {
            val properties = Properties().apply {
                this["profile.count"] = "1"
                writeProfile(this, 0, "legacy", "Legacy skill", "legacy", "ITEM")
            }
            Files.newOutputStream(legacyPath).use { properties.store(it, null) }

            SkillProfileStore.load(newPath, legacyPath = legacyPath)

            assertEquals(listOf("legacy"), SkillProfileStore.all().map { it.skillKey })
            assertTrue(Files.exists(newPath))
            assertFalse(Files.exists(legacyPath))
            assertTrue(Files.exists(legacyPath.resolveSibling("${legacyPath.fileName}.legacy")))
        } finally {
            directory.toFile().deleteRecursively()
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
    fun keepsProfilesPerAccountAndOfflineAccountIdsDeterministic() {
        val directory = Files.createTempDirectory("rvl-skill-profiles-accounts")
        val accountA = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val accountB = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val accountAPath = SkillProfileStore.accountProfilePath(directory, accountA, "Alice")
        val accountBPath = SkillProfileStore.accountProfilePath(directory, accountB, "Bob")
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
            assertEquals(SkillProfileStore.accountId(null, "Alice"), SkillProfileStore.accountId(null, "Alice"))
            assertFalse(SkillProfileStore.accountId(null, "Alice") == SkillProfileStore.accountId(null, "Bob"))
        } finally {
            directory.toFile().deleteRecursively()
        }
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
