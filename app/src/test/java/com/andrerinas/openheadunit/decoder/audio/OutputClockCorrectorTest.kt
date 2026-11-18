package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputClockCorrectorTest {

    /** Feeds one mixer write every 10 ms with a hardware that consumes [clock] x the track rate. */
    private fun run(corrector: OutputClockCorrector, clock: Double, seconds: Int, queued: Long = 4000): Int {
        var played = 0.0
        var now = 0L
        repeat(seconds * 100) {
            corrector.observe(now, played.toLong(), queued)
            now += 10
            played += corrector.rate * clock / 100
        }
        return corrector.rate
    }

    @Test
    fun `the slow Aiways output clock is compensated`() {
        // measured in the car: 46,154 of 48,000 frames per second
        val c = OutputClockCorrector()
        val rate = run(c, 46154.0 / 48000, 60)
        val consumption = rate * 46154.0 / 48000
        assertTrue("consumes $consumption frames/s", consumption in 47800.0..48200.0)
    }

    @Test
    fun `an accurate clock stays at the nominal rate`() {
        val c = OutputClockCorrector()
        assertEquals(48000, run(c, 1.0, 60))
    }

    @Test
    fun `a window in which the output ran dry changes nothing`() {
        val c = OutputClockCorrector()
        assertEquals(48000, run(c, 0.9, 30, queued = 100))
    }

    @Test
    fun `the correction is capped`() {
        val c = OutputClockCorrector()
        assertTrue(run(c, 0.5, 120) <= 48000 * 1.08 + 1)
    }

    @Test
    fun `a pause restarts the window but keeps the learned rate`() {
        val c = OutputClockCorrector()
        val learned = run(c, 46154.0 / 48000, 60)
        c.reset()
        assertEquals(learned, c.rate)
    }
}
