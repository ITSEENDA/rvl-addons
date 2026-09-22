package net.eenda.rvladdons.feature.cooldown

import java.nio.file.Files
import java.util.Properties
import org.junit.Assert.assertEquals
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
