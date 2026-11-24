package com.andrerinas.openheadunit.aiways

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DayLogThrottleTest {

    private fun frame(n: Int) = "[76] AapSslContext.decrypt | SSL Decrypt: produced $n, consumed ${n + 29} bytes"

    @Test
    fun `the thread and the numbers do not make a new kind of line`() {
        assertEquals(
            "AapSslContext.decrypt | SSL Decrypt: produced #, consumed # bytes",
            DayLogThrottle.kindOf(frame(842))
        )
        assertEquals(DayLogThrottle.kindOf(frame(4)), DayLogThrottle.kindOf("[74] " + frame(123456).substring(5)))
    }

    @Test
    fun `a per-frame line is written a few times per window, not once per frame`() {
        val t = DayLogThrottle()
        var written = 0
        for (i in 0 until 30 * 60) { // one minute of video at 30 fps
            if (t.admit(frame(i), 1_000L + i * 33L) != null) written++
        }
        assertEquals(DayLogThrottle.PER_WINDOW, written)
    }

    @Test
    fun `the next window says how many were left out`() {
        val t = DayLogThrottle()
        repeat(DayLogThrottle.PER_WINDOW + 7) { t.admit(frame(it), 1_000L) }
        assertNull(t.admit(frame(1), 2_000L))
        assertEquals(
            frame(5) + "  (+8 like this left out)",
            t.admit(frame(5), 1_000L + DayLogThrottle.WINDOW_MS)
        )
        assertEquals(frame(6), t.admit(frame(6), 1_000L + DayLogThrottle.WINDOW_MS + 1))
    }

    @Test
    fun `other kinds of lines are not held back by a noisy one`() {
        val t = DayLogThrottle()
        repeat(1000) { t.admit(frame(it), 1_000L) }
        val bye = "[71] AapControl.process | ByeBye request received, reason 1"
        assertEquals(bye, t.admit(bye, 1_001L))
    }

    @Test
    fun `a clock set back by the T-Box time starts a new window`() {
        val t = DayLogThrottle()
        repeat(DayLogThrottle.PER_WINDOW + 1) { t.admit(frame(it), 1_800_000_000_000L) }
        assertEquals(frame(9) + "  (+1 like this left out)", t.admit(frame(9), 1_000L))
    }

    @Test
    fun `many different kinds do not grow without bound`() {
        val t = DayLogThrottle()
        for (i in 0 until DayLogThrottle.MAX_KINDS * 3) {
            val road = ('a' + i % 26).toString() + ('a' + i / 26 % 26) + ('a' + i / 676)
            assertEquals("Nav: road=$road", t.admit("Nav: road=$road", 1_000L + i))
        }
    }
}
