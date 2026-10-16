package net.eenda.rvladdons.feature.party

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PartyRoomRunMonitorTest {
    @Test
    fun recognizesDchienCompletionOnceAndPreservesAutomaticMode() {
        val monitor = PartyRoomRunMonitor()
        monitor.start("/dchien", automatic = true)

        assertNull(monitor.completeIfMatched("room still running"))
        val finished = monitor.completeIfMatched("VƯỢT THÀNH CÔNG ĐOÀN CHIẾN")

        assertEquals(FinishedPartyRoom("dchien", true), finished)
        assertNull(monitor.completeIfMatched("THẢO PHẠT ĐOÀN CHIẾN THÀNH CÔNG"))
    }

    @Test
    fun recognizesTlCompletionAndStopClearsTheMonitor() {
        val monitor = PartyRoomRunMonitor()
        monitor.start("tl", automatic = false)
        assertEquals(
            FinishedPartyRoom("tl", false),
            monitor.completeIfMatched("VƯỢT THANH LÂN THÀNH CÔNG")
        )

        monitor.start("dchien", automatic = true)
        monitor.stop()
        assertNull(monitor.completeIfMatched("VƯỢT THÀNH CÔNG ĐOÀN CHIẾN"))
    }
}
