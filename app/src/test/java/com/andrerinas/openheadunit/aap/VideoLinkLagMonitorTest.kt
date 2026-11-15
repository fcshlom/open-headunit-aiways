package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VideoLinkLagMonitorTest {

    /** 30 fps for [seconds]; [lagAt] says how late (ms) the frame captured at a given ms arrives. */
    private fun run(
        monitor: VideoLinkLagMonitor,
        seconds: Int,
        phoneStartUs: Long = 5_000_000_000L,
        unitStartMs: Long = 1_000L,
        lagAt: (Long) -> Long
    ): List<VideoLinkLagMonitor.Report> {
        val reports = mutableListOf<VideoLinkLagMonitor.Report>()
        var t = 0L
        while (t <= seconds * 1000L) {
            monitor.onFrame(phoneStartUs + t * 1000L, unitStartMs + t + lagAt(t))?.let { reports.add(it) }
            t += 33L
        }
        return reports
    }

    @Test
    fun `a link that keeps up reports no lag whatever the two clocks read`() {
        val reports = run(VideoLinkLagMonitor(), 65) { 0L }
        assertEquals(2, reports.size)
        reports.forEach {
            assertEquals(0L, it.maxMs)
            assertEquals(0L, it.averageMs)
        }
    }

    @Test
    fun `frames queued on the way show up as lag above the session's best moment`() {
        // Empty path for 10 s, then the phone outruns the link and the picture falls 3 s behind.
        val reports = run(VideoLinkLagMonitor(), 65) { t -> if (t < 10_000L) 0L else 3_000L }
        assertEquals(3_000L, reports[0].maxMs)
        assertEquals(3_000L, reports[0].lastMs)
        assertEquals(3_000L, reports[1].averageMs)
    }

    @Test
    fun `a session that starts behind is measured once the path empties`() {
        val reports = run(VideoLinkLagMonitor(), 65) { t -> if (t < 40_000L) 800L else 0L }
        // The first window knew no better moment; the second one does.
        assertEquals(0L, reports[0].maxMs)
        assertEquals(0L, reports[1].lastMs)
    }

    @Test
    fun `a restarted stream clock starts a new baseline instead of reading as lag`() {
        val monitor = VideoLinkLagMonitor()
        run(monitor, 10) { 0L }
        // The phone's clock starts over from a different origin, a minute off.
        val reports = run(monitor, 35, phoneStartUs = 65_000_000_000L, unitStartMs = 11_100L) { 0L }
        val report = reports.first()
        assertEquals(0L, report.maxMs)
        assertEquals(1, report.rebases)
    }

    @Test
    fun `reset forgets the session`() {
        val monitor = VideoLinkLagMonitor()
        run(monitor, 10) { 0L }
        monitor.reset()
        assertNull(monitor.onFrame(1_000L, 500_000L))
        assertNotNull(monitor.onFrame(31_000_000L, 530_500L))
    }
}
