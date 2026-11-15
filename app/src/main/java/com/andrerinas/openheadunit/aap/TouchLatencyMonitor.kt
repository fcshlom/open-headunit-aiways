package com.andrerinas.openheadunit.aap

/**
 * How long a touch takes to leave this unit: from the touch screen's own event time to the app
 * seeing it, then through the send queue and the socket write.
 *
 * A tap that answers seconds late has to lose that time somewhere, and this is the part of the
 * round trip that belongs to the head unit alone. Read next to [VideoLinkLagMonitor], which covers
 * the way back: if both stay small, the delay is on the phone.
 *
 * Fed from two threads (the UI thread sees the touch, the send thread writes it), hence the
 * locking. Says nothing in a window without a touch.
 *
 * **Diagnostic only - nothing reads the report.** Clock-free: the caller passes every time in.
 */
class TouchLatencyMonitor {

    private var windowStartMs = 0L
    private var started = false
    private var events = 0
    private var presses = 0
    private var sumInputMs = 0L
    private var maxInputMs = 0L
    private var maxQueueMs = 0L
    private var maxWriteMs = 0L

    /**
     * A touch event reached the app. [inputDelayMs] is the time since the touch screen produced
     * it; [isPress] marks the first finger going down, so the report can count taps.
     */
    @Synchronized
    fun onTouch(inputDelayMs: Long, isPress: Boolean, nowMs: Long) {
        arm(nowMs)
        val delay = inputDelayMs.coerceAtLeast(0L)
        events++
        if (isPress) presses++
        sumInputMs += delay
        if (delay > maxInputMs) maxInputMs = delay
    }

    /**
     * An input message left the socket after [queueMs] in the send queue and [writeMs] inside the
     * write. Returns a report when a window of [WINDOW_MS] closes with touches in it.
     */
    @Synchronized
    fun onSent(queueMs: Long, writeMs: Long, nowMs: Long): Report? {
        arm(nowMs)
        if (queueMs > maxQueueMs) maxQueueMs = queueMs
        if (writeMs > maxWriteMs) maxWriteMs = writeMs

        val elapsedMs = nowMs - windowStartMs
        if (elapsedMs < WINDOW_MS) return null

        val report = if (events == 0) null else Report(
            windowMs = elapsedMs,
            presses = presses,
            events = events,
            averageInputMs = sumInputMs / events,
            maxInputMs = maxInputMs,
            maxQueueMs = maxQueueMs,
            maxWriteMs = maxWriteMs
        )
        clear()
        windowStartMs = nowMs
        return report
    }

    @Synchronized
    fun reset() {
        started = false
        windowStartMs = 0L
        clear()
    }

    private fun arm(nowMs: Long) {
        if (started) return
        started = true
        windowStartMs = nowMs
    }

    private fun clear() {
        events = 0
        presses = 0
        sumInputMs = 0L
        maxInputMs = 0L
        maxQueueMs = 0L
        maxWriteMs = 0L
    }

    /** One window's worth of outbound touch timing. */
    data class Report(
        val windowMs: Long,
        val presses: Int,
        val events: Int,
        val averageInputMs: Long,
        val maxInputMs: Long,
        val maxQueueMs: Long,
        val maxWriteMs: Long
    ) {
        override fun toString(): String =
            "touch latency over ${windowMs}ms: $presses press${if (presses == 1) "" else "es"} " +
                "($events events), screen to app average=${averageInputMs}ms max=${maxInputMs}ms, " +
                "send queue max=${maxQueueMs}ms, socket write max=${maxWriteMs}ms"
    }

    companion object {
        const val WINDOW_MS = 30_000L

        /** The projection activity and the transport feed the same session, so they share one. */
        val shared = TouchLatencyMonitor()
    }
}
