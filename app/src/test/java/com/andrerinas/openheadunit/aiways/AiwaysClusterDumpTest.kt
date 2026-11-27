package com.andrerinas.openheadunit.aiways

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiwaysClusterDumpTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private val sps = bytes(0, 0, 0, 1, 0x67, 0x42, 0xc0, 0x1f)
    private val pps = bytes(0, 0, 0, 1, 0x68, 0xce, 0x3c, 0x80)
    private val idr = bytes(0, 0, 0, 1, 0x65, 0x88, 0x84, 0x00, 0x21)
    private val p = bytes(0, 0, 1, 0x41, 0x9a, 0x02)
    private val timestamp = ByteArray(8) { (it + 1).toByte() }

    @Test
    fun `finds the H264 data behind the timestamp of a DATA payload`() {
        assertEquals(8, AiwaysClusterDump.mediaStart(timestamp + idr, 0, 8 + idr.size))
        assertEquals(0, AiwaysClusterDump.mediaStart(sps + pps, 0, sps.size + pps.size))
        assertEquals(-1, AiwaysClusterDump.mediaStart(timestamp + timestamp, 0, 16))
    }

    @Test
    fun `reads every NAL type, with three and four byte start codes`() {
        val all = sps + pps + idr + p
        assertEquals(listOf(7, 8, 5, 1), AiwaysClusterDump.nalTypes(all, 0, all.size))
    }

    @Test
    fun `respects offset and length`() {
        val framed = bytes(0xff, 0xff) + idr + p
        assertEquals(listOf(5), AiwaysClusterDump.nalTypes(framed, 2, idr.size))
    }

    @Test
    fun `starts at the first keyframe with the codec config in front of it`() {
        val d = AiwaysClusterDump(maxFrames = 10)
        d.onCodecConfig(sps + pps, 0, sps.size + pps.size)
        assertFalse(d.onFrame(p, 0, p.size, 0L))
        assertFalse(d.onFrame(idr, 0, idr.size, 10L))
        assertFalse(d.onFrame(p, 0, p.size, 20L))
        assertEquals(1, d.skippedBeforeKeyframe)
        assertEquals(2, d.frames)
        assertArrayEquals(sps + pps + idr + p, d.bytes())
    }

    @Test
    fun `ends after the frame, byte or time limit`() {
        val byFrames = AiwaysClusterDump(maxFrames = 2)
        assertFalse(byFrames.onFrame(idr, 0, idr.size, 0L))
        assertTrue(byFrames.onFrame(p, 0, p.size, 1L))
        assertTrue(byFrames.done)
        // Nothing more is taken once done.
        byFrames.onFrame(p, 0, p.size, 2L)
        assertEquals(2, byFrames.frames)

        val byTime = AiwaysClusterDump(maxFrames = 100, maxMs = 1_000L)
        assertFalse(byTime.onFrame(idr, 0, idr.size, 5_000L))
        assertTrue(byTime.onFrame(p, 0, p.size, 6_000L))

        val byBytes = AiwaysClusterDump(maxFrames = 100, maxBytes = idr.size)
        assertTrue(byBytes.onFrame(idr, 0, idr.size, 0L))
    }

    @Test
    fun `never holds more than maxBytes, even for one large frame`() {
        val d = AiwaysClusterDump(maxFrames = 100, maxBytes = idr.size + p.size - 1)
        assertFalse(d.onFrame(idr, 0, idr.size, 0L))
        // The next frame would cross the limit: the dump ends without it.
        assertTrue(d.onFrame(p, 0, p.size, 1L))
        assertEquals(1, d.frames)
        assertArrayEquals(idr, d.bytes())

        val tooBig = AiwaysClusterDump(maxBytes = idr.size - 1)
        assertTrue(tooBig.onFrame(idr, 0, idr.size, 0L))
        assertEquals(0, tooBig.frames)
        assertEquals(0, tooBig.bytes().size)
    }
}
