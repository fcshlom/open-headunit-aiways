package com.andrerinas.openheadunit.aiways

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The exit read from real crops of Waze's turn icon, and nothing read from what else can be in that corner. */
class AiwaysRoundaboutReaderTest {

    private val crops: Map<String, IntArray> by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("aiways/roundabout_crops.txt")
        stream.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line ->
                val (name, hex) = line.split(" ")
                name to IntArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16) }
            }
    }

    private fun read(name: String): AiwaysRoundaboutReader.Result {
        val lum = crops.getValue(name)
        assertEquals(34 * 28, lum.size)
        return AiwaysRoundaboutReader.read(lum, 34, 28)
    }

    @Test
    fun `a roundabout exiting to the left reads 270`() {
        assertEquals(270, read("LEFT").exitDeg)
        assertEquals(270, read("LEFT_MORNING").exitDeg)
    }

    @Test
    fun `a roundabout exiting to the right reads 90`() {
        assertEquals(90, read("RIGHT").exitDeg)
    }

    @Test
    fun `a roundabout straight on reads 180`() {
        assertEquals(180, read("STRAIGHT").exitDeg)
    }

    @Test
    fun `another app's page in the corner reads nothing`() {
        assertEquals(-1, read("SPOTIFY").exitDeg)
        assertEquals(-1, read("SPOTIFY_PODCAST").exitDeg)
    }

    @Test
    fun `a notification over the corner reads nothing`() {
        assertEquals(-1, read("NOTIFICATION").exitDeg)
    }

    @Test
    fun `a plain turn arrow reads nothing`() {
        assertEquals(-1, read("TURN_RIGHT").exitDeg)
    }

    @Test
    fun `a dark corner reads nothing`() {
        assertEquals(-1, AiwaysRoundaboutReader.read(IntArray(34 * 28), 34, 28).exitDeg)
    }

    @Test
    fun `a crop at another size is scaled`() {
        // the left-exit crop doubled in both directions, as a screen read at full size would give it
        val lum = crops.getValue("LEFT")
        val big = IntArray(68 * 56) { i -> lum[(i / 68 / 2) * 34 + (i % 68) / 2] }
        assertEquals(270, AiwaysRoundaboutReader.read(big, 68, 56).exitDeg)
    }

    @Test
    fun `brightness weighs green most`() {
        assertEquals(255, AiwaysRoundaboutReader.lum(0xffffffff.toInt()))
        assertEquals(0, AiwaysRoundaboutReader.lum(0xff000000.toInt()))
        assertTrue(AiwaysRoundaboutReader.lum(0xff00ff00.toInt()) > AiwaysRoundaboutReader.lum(0xffff0000.toInt()))
    }
}
