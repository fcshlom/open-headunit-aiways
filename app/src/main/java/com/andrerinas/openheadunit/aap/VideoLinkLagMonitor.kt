package com.andrerinas.openheadunit.aap

/**
 * How far the picture runs behind the phone, measured from the phone's own frame timestamps.
 *
 * Every video DATA message starts with the phone's capture time in microseconds. The phone's clock
 * and ours share no origin, so one frame says nothing; but the difference `arrival - timestamp`
 * is constant on a link that keeps up and grows by exactly the time frames spend queued between
 * the phone's encoder and this unit (the phone's send buffer, the Wi-Fi link, our receive buffer
 * and the transport's video queue). The smallest difference seen in the session is the baseline -
 * the moment the path was empty - and everything above it is lag.
 *
 * This is the one stretch of a tap's round trip nothing else here measures: the decoder reports its
 * own latency and the uplink its own stalls, and both read healthy on a session whose picture is
 * seconds late because the phone is sending faster than the link carries.
 *
 * What it cannot see: lag that was already there at the best moment of the session, and the two
 * clocks drifting apart (tens of milliseconds per hour). A jump of more than [REBASE_JUMP_MS] in
 * either direction is the phone restarting its stream clock, not lag, and starts a new baseline.
 *
 * **Diagnostic only - nothing reads the report.** Pure and clock-free: the caller passes both
 * times in, so a measured session is replayable in a unit test.
 */
class VideoLinkLagMonitor {

    private var hasBaseline = false
    private var baselineMs = 0L
    private var windowStartMs = 0L
    private var frames = 0
    private var sumLagMs = 0L
    private var maxLagMs = 0L
    private var lastLagMs = 0L
    private var rebases = 0

    /**
     * Feed one timestamped frame: [timestampUs] is the phone's capture time off the wire and
     * [arrivalMs] a monotonic clock reading of when the frame reached the video path.
     *
     * Returns a report when a window of [WINDOW_MS] closes, `null` otherwise.
     */
    fun onFrame(timestampUs: Long, arrivalMs: Long): Report? {
        val offsetMs = arrivalMs - timestampUs / 1000L
        if (!hasBaseline) {
            hasBaseline = true
            baselineMs = offsetMs
            windowStartMs = arrivalMs
        } else if (offsetMs < baselineMs - REBASE_JUMP_MS || offsetMs > baselineMs + REBASE_JUMP_MS) {
            baselineMs = offsetMs
            rebases++
        } else if (offsetMs < baselineMs) {
            baselineMs = offsetMs
        }

        val lagMs = offsetMs - baselineMs
        frames++
        sumLagMs += lagMs
        if (lagMs > maxLagMs) maxLagMs = lagMs
        lastLagMs = lagMs

        val elapsedMs = arrivalMs - windowStartMs
        if (elapsedMs < WINDOW_MS) return null

        val report = Report(
            windowMs = elapsedMs,
            frames = frames,
            averageMs = sumLagMs / frames,
            maxMs = maxLagMs,
            lastMs = lastLagMs,
            rebases = rebases
        )
        windowStartMs = arrivalMs
        frames = 0
        sumLagMs = 0L
        maxLagMs = 0L
        rebases = 0
        return report
    }

    /** Forget the previous session: the next one has its own stream clock and its own baseline. */
    fun reset() {
        hasBaseline = false
        baselineMs = 0L
        windowStartMs = 0L
        frames = 0
        sumLagMs = 0L
        maxLagMs = 0L
        lastLagMs = 0L
        rebases = 0
    }

    /** One window's worth of picture lag. */
    data class Report(
        val windowMs: Long,
        val frames: Int,
        val averageMs: Long,
        val maxMs: Long,
        val lastMs: Long,
        val rebases: Int
    ) {
        override fun toString(): String =
            "video link lag over ${windowMs}ms: average=${averageMs}ms, max=${maxMs}ms, " +
                "now=${lastMs}ms behind the session's best ($frames frames" +
                (if (rebases > 0) ", stream clock restarted $rebases time${if (rebases == 1) "" else "s"}" else "") +
                ")"
    }

    companion object {
        /** How much link time one report covers; the transport's other lines use the same. */
        const val WINDOW_MS = 30_000L

        /**
         * A change this large between two frames is a new stream clock rather than lag: no queue on
         * this path holds ten seconds of video, and the session is torn down long before that.
         */
        const val REBASE_JUMP_MS = 10_000L
    }
}
