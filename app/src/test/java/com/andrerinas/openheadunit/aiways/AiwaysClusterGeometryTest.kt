package com.andrerinas.openheadunit.aiways

import org.junit.Assert.assertEquals
import org.junit.Test

class AiwaysClusterGeometryTest {

    private val eps = 0.01f

    /** Where a pixel of the decoded frame lands in the view, the way a TextureView maps it. */
    private fun place(t: AiwaysClusterGeometry.Transform, rung: AiwaysClusterLadder.Rung, xb: Float, yb: Float): Pair<Float, Float> {
        val w = AiwaysClusterGeometry.PANEL_W.toFloat()
        val h = AiwaysClusterGeometry.PANEL_H.toFloat()
        // the default stretch of the whole frame over the view, then the transform
        val xd = xb * w / rung.codecWidth
        val yd = yb * h / rung.codecHeight
        return Pair(t.scaleX * xd + t.translateX, t.scaleY * yd + t.translateY)
    }

    @Test
    fun `the fitted rung's content fills the panel pixel for pixel`() {
        val fit = AiwaysClusterLadder.RUNGS[1]
        val t = AiwaysClusterGeometry.transform(fit)
        // the content starts 265 px in (half of the 530 px margin) and is 270 wide
        val (l, top) = place(t, fit, 265f, 0f)
        val (r, bottom) = place(t, fit, 535f, 480f)
        assertEquals(0f, l, eps)
        assertEquals(0f, top, eps)
        assertEquals(270f, r, eps)
        assertEquals(480f, bottom, eps)
    }

    @Test
    fun `the safe rung is scaled to the panel's height and cut at the sides, centred`() {
        val safe = AiwaysClusterLadder.RUNGS[0]
        val t = AiwaysClusterGeometry.transform(safe)
        val (_, top) = place(t, safe, 640f, 0f)
        val (centre, bottom) = place(t, safe, 640f, 720f)
        assertEquals(0f, top, eps)
        assertEquals(480f, bottom, eps)
        assertEquals(135f, centre, eps)   // the frame's centre on the panel's centre
        // 405 frame pixels (2/3 scale) fill the 270 px panel: 437.5 .. 842.5
        assertEquals(0f, place(t, safe, 437.5f, 0f).first, eps)
        assertEquals(270f, place(t, safe, 842.5f, 0f).first, eps)
    }
}
