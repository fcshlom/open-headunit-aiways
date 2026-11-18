package com.andrerinas.openheadunit.decoder.audio

/** Sizing for the 48kHz stereo PCM16 AudioTrack, in its input frames (four bytes each).
 * getMinBufferSize is a platform estimate, not a HAL period or a guaranteed glitch-free size.
 * Use the whole estimate as a floor; the output controller can add scheduling reserve. */
internal object AudioTrackBufferSizing {
    /** Aiways: 200 ms at 48 kHz for tracks that cannot be resized (API < 24). */
    const val LEGACY_FIXED_FRAMES = 9600

    fun minimumFrames(minimumBytes: Int): Int {
        require(minimumBytes > 0)
        return maxOf(960, ((minimumBytes.toLong() + 3) / 4).toInt())
    }

    fun allocationBytes(minimumFrames: Int, canResize: Boolean): Int {
        require(minimumFrames in 1..Int.MAX_VALUE / 4)
        // Keep growth space even when the platform floor exceeds the usual 400ms capacity.
        // Older Android cannot resize a live track, so its fixed allocation is the whole budget.
        // Aiways: on the car's Android 4.4 unit the platform floor is 3343 frames (70 ms) while
        // the mixer thread is regularly 90-140 ms late (video decoding on the same CPU); every
        // such gap ran the track dry and stuttered music. Give fixed-size tracks 200 ms.
        val frames = if (canResize) maxOf(19200,
            OutputBufferPolicy(48000, 480, minimumFrames).maximumFrames) else maxOf(minimumFrames, LEGACY_FIXED_FRAMES)
        val bytes = frames.toLong() * 4
        require(bytes <= Int.MAX_VALUE) { "AudioTrack buffer is too large: $frames frames" }
        return bytes.toInt()
    }
}
