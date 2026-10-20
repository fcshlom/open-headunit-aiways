package com.andrerinas.openheadunit.decoder.audio

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Aiways: matches the AudioTrack's real consumption to the stream's real time.
 *
 * Measured on the Aiways head unit (i.MX6, Android 4.4): over 100 s of music the phone delivered
 * 47,983 frames/s, but the 48 kHz AudioTrack consumed only 46,154 frames/s. The output clock runs
 * about 3.8 % slow, so the mixer's bank filled to capacity and dropped PCM every second, heard as
 * stutter. Playing the track a little faster than nominal ([rate] above 48000) makes it consume
 * exactly real time; since the hardware itself runs slow by the same factor, the pitch heard is the
 * one the phone sent.
 *
 * Pure: fed the played-frame count and the queued frames on every mixer write, it returns a new
 * playback rate when one is due. A window only counts when the output never ran dry, so an
 * underrun (head stalled for lack of PCM) is never mistaken for a slow clock.
 */
internal class OutputClockCorrector(private val nominalRate: Int = 48000) {
    var rate = nominalRate
        private set

    private var startMs = -1L
    private var startFrames = 0L
    private var lastMs = -1L
    private var minimumQueued = Int.MAX_VALUE

    /** @return the playback rate to apply now, or 0 to keep the current one. */
    fun observe(nowMs: Long, playedFrames: Long, queuedFrames: Long): Int {
        if (playedFrames < 0 || startMs < 0 || nowMs - lastMs !in 0..GAP_MS || playedFrames < startFrames) {
            restart(nowMs, playedFrames)
            return 0
        }
        lastMs = nowMs
        minimumQueued = minOf(minimumQueued, queuedFrames.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
        val elapsed = nowMs - startMs
        if (elapsed < WINDOW_MS) return 0
        val consumed = playedFrames - startFrames
        val starved = minimumQueued < MIN_QUEUED_FRAMES
        restart(nowMs, playedFrames)
        if (consumed <= 0 || starved) return 0
        val measured = consumed * 1000.0 / elapsed
        val wanted = rate * nominalRate / measured
        val next = (rate + (wanted - rate) * GAIN).roundToInt()
            .coerceIn((nominalRate * (1 - MAX_CORRECTION)).roundToInt(), (nominalRate * (1 + MAX_CORRECTION)).roundToInt())
        if (abs(next - rate) < rate * DEADBAND) return 0
        rate = next
        return next
    }

    /** Pause, flush or a new track: the next window starts from scratch; the learned rate stays. */
    fun reset() {
        startMs = -1L
    }

    private fun restart(nowMs: Long, playedFrames: Long) {
        startMs = if (playedFrames < 0) -1L else nowMs
        startFrames = playedFrames
        lastMs = nowMs
        minimumQueued = Int.MAX_VALUE
    }

    internal companion object {
        /** Mixer writes come every ~10 ms; a longer gap means paused or stalled. */
        const val GAP_MS = 300L
        const val WINDOW_MS = 8000L
        /** 10 ms at 48 kHz: below that the output may have run dry inside the window. */
        const val MIN_QUEUED_FRAMES = 480
        const val GAIN = 0.8
        const val DEADBAND = 0.002
        const val MAX_CORRECTION = 0.08
    }
}
