package com.andrerinas.openheadunit.aiways

import com.andrerinas.openheadunit.aiways.AiwaysClusterLadder.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AiwaysClusterLadderTest {

    private val v = 20

    @Test
    fun `a fresh version starts on the safe rung`() {
        assertEquals(0, AiwaysClusterLadder.rungToAnnounce(State(v, 0, 0)))
    }

    @Test
    fun `once the safe rung streamed, the fitted one is tried`() {
        val s = AiwaysClusterLadder.withStreamed(State(v, 0, 0), 0)
        assertEquals(1, AiwaysClusterLadder.rungToAnnounce(s))
    }

    @Test
    fun `a strike on the fitted rung goes back to the safe one`() {
        val s = AiwaysClusterLadder.withFailed(AiwaysClusterLadder.withStreamed(State(v, 0, 0), 0), 1)
        assertEquals(0, AiwaysClusterLadder.rungToAnnounce(s))
    }

    @Test
    fun `a strike on the safe rung stops the test`() {
        assertEquals(-1, AiwaysClusterLadder.rungToAnnounce(AiwaysClusterLadder.withFailed(State(v, 0, 0), 0)))
        val streamedThenFailed = AiwaysClusterLadder.withFailed(AiwaysClusterLadder.withStreamed(State(v, 0, 0), 0), 0)
        assertEquals(-1, AiwaysClusterLadder.rungToAnnounce(streamedThenFailed))
    }

    @Test
    fun `strikes are kept for one app version only`() {
        val struck = State(v, failed = 1, streamed = 1)
        assertEquals(struck, AiwaysClusterLadder.forVersion(struck, v))
        assertEquals(State(v + 1, 0, 0), AiwaysClusterLadder.forVersion(struck, v + 1))
    }

    @Test
    fun `the fitted rung draws into the panel's 270 x 480`() {
        val fit = AiwaysClusterLadder.RUNGS[1]
        assertEquals(270, fit.contentWidth)
        assertEquals(480, fit.contentHeight)
        val safe = AiwaysClusterLadder.RUNGS[0]
        assertEquals(1280, safe.contentWidth)
        assertEquals(720, safe.contentHeight)
    }

    @Test
    fun `an early end counts as a strike`() {
        assertNotNull(AiwaysClusterLadder.strikeReason("link_lost", false, 5_000L, -1L))
        assertNotNull(AiwaysClusterLadder.strikeReason("phone_left", false, 119_000L, -1L))
        assertNotNull(AiwaysClusterLadder.strikeReason("peer_silent", false, 1_000L, -1L))
    }

    @Test
    fun `an end right after the cluster stream started counts, even late in the session`() {
        assertNotNull(AiwaysClusterLadder.strikeReason("link_lost", false, 900_000L, 2_000L))
        assertNull(AiwaysClusterLadder.strikeReason("link_lost", false, 900_000L, 31_000L))
        assertNull(AiwaysClusterLadder.strikeReason("link_lost", false, 900_000L, -1L))
    }

    @Test
    fun `the driver leaving Android Auto or the car going off is no strike`() {
        assertNull(AiwaysClusterLadder.strikeReason("user_exit", false, 5_000L, 1_000L))
        assertNull(AiwaysClusterLadder.strikeReason("settings_restart", false, 5_000L, 1_000L))
        assertNull(AiwaysClusterLadder.strikeReason("phone_left", true, 5_000L, 1_000L))
    }
}
