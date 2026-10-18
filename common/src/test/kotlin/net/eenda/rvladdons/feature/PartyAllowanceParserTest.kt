package net.eenda.rvladdons.feature.party

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PartyAllowanceParserTest {
    @Test
    fun parsesDchienBalanceAndSkipsRoomCost() {
        assertEquals(
            9,
            PartyAllowanceParser.remaining(
                "dchien",
                listOf("Lượt tham gia: 9", "Tiêu hao 1 lượt tham gia")
            )
        )
    }

    @Test
    fun parsesTlBalanceAndCost() {
        val lore = listOf("Vé Thanh Lân: 247", "Tiêu hao 2 vé thanh lân")
        assertEquals(247, PartyAllowanceParser.remaining("/tl", lore))
        assertEquals(2, PartyAllowanceParser.cost("tl", lore))
    }

    @Test
    fun parsesDchienCostAcrossLoreLines() {
        assertEquals(2, PartyAllowanceParser.cost("dchien", listOf("Tiêu hao 2 lượt tham gia")))
        assertEquals(2, PartyAllowanceParser.cost("/dchien", listOf("Tiêu hao", "2", "Lượt tham gia")))
    }

    @Test
    fun acceptsBalanceNumberOnFollowingLoreLineOnlyWhenItIsNumeric() {
        assertEquals(9, PartyAllowanceParser.remaining("dchien", listOf("Lượt tham gia:", "9")))
        assertNull(PartyAllowanceParser.remaining("dchien", listOf("Lượt tham gia", "Tổng số trận: 34")))
    }
}
