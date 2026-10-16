package net.eenda.rvladdons.feature.coma

import java.nio.file.Files
import java.util.UUID
import net.eenda.rvladdons.core.AccountStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComaStateStoreTest {
    @Test
    fun storesLastActiveStatePerAccountAsJson() {
        val directory = Files.createTempDirectory("rvl-coma-state")
        val accountAPath = AccountStorage.directory(
            directory,
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "Alice"
        ).resolve("coma-state.json")
        val accountBPath = AccountStorage.directory(
            directory,
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            "Bob"
        ).resolve("coma-state.json")
        try {
            ComaStateStore.load(accountAPath)
            ComaStateStore.save("play.example.test", listOf("alice-rule"), listOf("minecraft:diamond_helmet"))
            assertTrue(Files.readString(accountAPath).contains("\"serverKey\""))

            ComaStateStore.load(accountAPath)
            assertEquals("play.example.test", ComaStateStore.current().serverKey)
            assertEquals(listOf("alice-rule"), ComaStateStore.current().rules)
            assertEquals(listOf("minecraft:diamond_helmet"), ComaStateStore.current().iconItemIds)

            ComaStateStore.load(accountBPath)
            assertEquals(PersistedComaState(), ComaStateStore.current())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
