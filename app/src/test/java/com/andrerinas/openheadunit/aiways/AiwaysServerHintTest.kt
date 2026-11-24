package com.andrerinas.openheadunit.aiways

import org.junit.Assert.assertEquals
import org.junit.Test

class AiwaysServerHintTest {

    private val server = "192.168.1.39:5277"
    private val repeat = AiwaysServerHint.REPEAT_MS

    @Test
    fun `no hint before the third silent attempt in a row`() {
        val s = AiwaysServerHint.Streak()
        assertEquals(0, s.onFailedHandshake(true, server, 1_000L))
        assertEquals(0, s.onFailedHandshake(true, server, 2_000L))
        assertEquals(3, s.onFailedHandshake(true, server, 3_000L))
    }

    @Test
    fun `while it lasts the hint comes back every five minutes, not on every attempt`() {
        val s = AiwaysServerHint.Streak()
        repeat(3) { s.onFailedHandshake(true, server, 1_000L) }
        assertEquals(0, s.onFailedHandshake(true, server, 1_000L + repeat - 1))
        assertEquals(5, s.onFailedHandshake(true, server, 1_000L + repeat))
    }

    @Test
    fun `the phone moving between the T-Box WiFi and its hotspot keeps the streak`() {
        val s = AiwaysServerHint.Streak()
        s.onFailedHandshake(true, "192.168.1.39:5277", 1_000L)
        s.onFailedHandshake(true, "10.24.39.209:5277", 2_000L)
        assertEquals(3, s.onFailedHandshake(true, "192.168.1.39:5277", 3_000L))
    }

    @Test
    fun `an answer, or a peer that said something, ends the streak`() {
        val s = AiwaysServerHint.Streak()
        repeat(3) { s.onFailedHandshake(true, server, 1_000L) }
        s.onAnswered()
        assertEquals(0, s.onFailedHandshake(true, server, 2_000L))
        assertEquals(0, s.onFailedHandshake(true, server, 2_000L))
        s.onFailedHandshake(false, server, 2_000L)
        assertEquals(0, s.onFailedHandshake(true, server, 2_000L))
        assertEquals(0, s.onFailedHandshake(true, server, 2_000L))
        assertEquals(3, s.onFailedHandshake(true, server, 2_000L))
    }

    @Test
    fun `only the phone's head unit server counts, not our own wireless server or USB`() {
        val s = AiwaysServerHint.Streak()
        repeat(5) { assertEquals(0, s.onFailedHandshake(true, "192.168.1.39:5288", 1_000L)) }
        repeat(5) { assertEquals(0, s.onFailedHandshake(true, null, 1_000L)) }
    }
}
