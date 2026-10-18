package net.eenda.rvladdons.feature.cooldown

import org.junit.Assert.assertEquals
import org.junit.Test

class SkillTextParserTest {
    @Test
    fun parsesTriggerAndCooldown() {
        val descriptors = SkillTextParser.parse(
            itemId = "minecraft:diamond_sword",
            itemDisplayName = "Huyen Tinh",
            itemFingerprint = "abc",
            setId = "Co Ma",
            loreLines = listOf("Skill: right click", "CD: 29.5s")
        )

        assertEquals(1, descriptors.size)
        assertEquals(SkillTrigger.RIGHT_CLICK, descriptors.single().trigger)
        assertEquals(29_500L, descriptors.single().cooldownHintMs)
    }

    @Test
    fun parsesComboCooldownWithComma() {
        val descriptor = SkillTextParser.parse(
            "item",
            "Combo item",
            "fingerprint",
            null,
            listOf("Combo: left click", "CD every Combo: 0,6s")
        ).single()

        assertEquals(SkillTrigger.LEFT_CLICK, descriptor.trigger)
        assertEquals(600L, descriptor.cooldownHintMs)
    }

    @Test
    fun ignoresCooldownWithoutActivation() {
        val descriptors = SkillTextParser.parse(
            "item",
            "Unknown item",
            "fingerprint",
            null,
            listOf("CD: 30s")
        )

        assertEquals(0, descriptors.size)
    }

    @Test
    fun parsesMojibakeRightClickText() {
        val descriptor = SkillTextParser.parse(
            "item",
            "Crimson Katana",
            "fingerprint",
            null,
            listOf("KÃ­ch hoáº¡t: Chuá»™t pháº£i", "CD: 30s")
        ).single()

        assertEquals(SkillTrigger.RIGHT_CLICK, descriptor.trigger)
        assertEquals(30_000L, descriptor.cooldownHintMs)
    }

    @Test
    fun separatesSetBonusTiersAndTypes() {
        val descriptors = SkillTextParser.parse(
            "item",
            "Non Ba Vuong Cuong Bao",
            "fingerprint",
            "Co Ma",
            listOf(
                "Kich hoat bo Co Ma",
                "[Bo 2] Tang 3 Sat Thuong Co Ban",
                "[Bo 2] Khi bi tan cong se co ti le nhan hieu ung",
                "CD: 40s",
                "[Bo 3] Tang 7 Sat Thuong Co Ban",
                "[Ky Nang Moc Bo 3] Khi tan cong bang Chuot Trai",
                "+ Combo 1-2: Chem hai duong kiem",
                "+ Combo 4-5: Nhay chem lam doi thu bi hat tung",
                "CD moi lan Combo: 0.6s",
                "[Ky Nang Kich Bo 4] An Chuot Phai de tu luc",
                "CD: 70s"
            )
        )

        assertEquals(3, descriptors.size)
        assertEquals(SkillTrigger.PASSIVE_DAMAGED, descriptors[0].trigger)
        assertEquals(SkillType.PASSIVE, descriptors[0].skillType)
        assertEquals(2, descriptors[0].setBonusLevel)
        assertEquals(40_000L, descriptors[0].cooldownHintMs)
        assertEquals(SkillTrigger.LEFT_CLICK, descriptors[1].trigger)
        assertEquals(SkillType.COMBO, descriptors[1].skillType)
        assertEquals(3, descriptors[1].setBonusLevel)
        assertEquals(600L, descriptors[1].cooldownHintMs)
        assertEquals(SkillTrigger.RIGHT_CLICK, descriptors[2].trigger)
        assertEquals(SkillType.ACTIVE, descriptors[2].skillType)
        assertEquals(4, descriptors[2].setBonusLevel)
        assertEquals(70_000L, descriptors[2].cooldownHintMs)
    }

    @Test
    fun doesNotTreatGenericSneakTextAsActivation() {
        val descriptors = SkillTextParser.parse(
            "item",
            "Set item",
            "fingerprint",
            "Co Ma",
            listOf("[Bo 3] Tang sat thuong khi di chuyen va sneak", "CD: 30s")
        )

        assertEquals(0, descriptors.size)
    }

    @Test
    fun parsesExplicitSneakHoldActivation() {
        val descriptor = SkillTextParser.parse(
            "item",
            "Set item",
            "fingerprint",
            "Co Ma",
            listOf("[Bo 3] Giu Shift de kich hoat", "CD: 30s")
        ).single()

        assertEquals(SkillTrigger.SNEAK_HOLD, descriptor.trigger)
    }

    @Test
    fun removesForgePrefixFromTieredItemName() {
        assertEquals(
            "I Ti Tran Nguyet Chuong [EX]",
            SkillTextParser.cleanItemDisplayName("Nhiem Hon - I Ti Tran Nguyet Chuong [EX]")
        )
        assertEquals(
            "I Ti Tran Nguyet Chuong [EX] - [Bo 3] Tang 20",
            SkillTextParser.cleanLegacyProfileLabel(
                "Nhiem Hon - I Ti Tran Nguyet Chuong [EX] - [Bo 3] Tang 20"
            )
        )
    }
}
