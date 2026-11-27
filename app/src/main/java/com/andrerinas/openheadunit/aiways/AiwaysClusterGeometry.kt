package com.andrerinas.openheadunit.aiways

/**
 * Where the phone's cluster map goes on the driver's display, and how it is cut to fit. Pure, so the
 * arithmetic is unit-tested.
 *
 * The driver's display shows the top-left [PANEL_W] x [PANEL_H] of the head unit's second display, one
 * pixel for one pixel, in portrait (launcher `ClusterNav.PANEL_W` / `PANEL_H`, calibration grid of
 * 18 November). The phone draws its map into the codec frame minus the announced margins, centred
 * ([AiwaysClusterLadder.Rung.contentWidth] x [contentHeight]). The map window fills the panel with that
 * content: scaled so its height fills the panel, centred, and cut at the sides when it is wider.
 */
internal object AiwaysClusterGeometry {
    const val PANEL_W = 270
    const val PANEL_H = 480

    /** A TextureView transform: scale about the view's origin, then translate, in view pixels. */
    data class Transform(val scaleX: Float, val scaleY: Float, val translateX: Float, val translateY: Float)

    /**
     * The transform for a TextureView of [viewW] x [viewH] that shows a decoded frame of the rung's codec
     * size. A TextureView first stretches the whole frame over the view; this undoes that and maps the
     * content as described above.
     */
    fun transform(rung: AiwaysClusterLadder.Rung, viewW: Int = PANEL_W, viewH: Int = PANEL_H): Transform {
        val bw = rung.codecWidth.toFloat()
        val bh = rung.codecHeight.toFloat()
        val cw = rung.contentWidth.toFloat()
        val ch = rung.contentHeight.toFloat()
        val cx = (bw - cw) / 2f
        val cy = (bh - ch) / 2f
        val s = viewH / ch
        // the frame's x that lands on the view's left edge: the content's centre minus half the view
        val x0 = cx + cw / 2f - viewW / (2f * s)
        return Transform(
            scaleX = s * bw / viewW,
            scaleY = s * bh / viewH,
            translateX = -s * x0,
            translateY = -s * cy,
        )
    }
}
