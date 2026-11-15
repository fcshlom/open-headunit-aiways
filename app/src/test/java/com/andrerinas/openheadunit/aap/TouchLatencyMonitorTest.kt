package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TouchLatencyMonitorTest {

    @Test
    fun `a window without a touch says nothing`() {
        val monitor = TouchLatencyMonitor()
        assertNull(monitor.onSent(1L, 1L, 0L))
        assertNull(monitor.onSent(1L, 1L, 31_000L))
    }

    @Test
    fun `a window reports its presses and the worst of each stage`() {
        val monitor = TouchLatencyMonitor()
        monitor.onTouch(inputDelayMs = 20L, isPress = true, nowMs = 1_000L)
        assertNull(monitor.onSent(queueMs = 3L, writeMs = 1L, nowMs = 1_001L))
        monitor.onTouch(inputDelayMs = 2_900L, isPress = false, nowMs = 5_000L)
        assertNull(monitor.onSent(queueMs = 40L, writeMs = 250L, nowMs = 5_300L))
        monitor.onTouch(inputDelayMs = -5L, isPress = true, nowMs = 31_500L)
        val report = monitor.onSent(queueMs = 2L, writeMs = 2L, nowMs = 31_502L)!!
        assertEquals(2, report.presses)
        assertEquals(3, report.events)
        assertEquals(2_900L, report.maxInputMs)
        assertEquals(973L, report.averageInputMs)
        assertEquals(40L, report.maxQueueMs)
        assertEquals(250L, report.maxWriteMs)
    }

    @Test
    fun `each window starts clean`() {
        val monitor = TouchLatencyMonitor()
        monitor.onTouch(500L, true, 0L)
        monitor.onSent(1L, 1L, 30_000L)
        monitor.onTouch(10L, true, 40_000L)
        val report = monitor.onSent(1L, 1L, 60_001L)!!
        assertEquals(1, report.presses)
        assertEquals(10L, report.maxInputMs)
    }
}
