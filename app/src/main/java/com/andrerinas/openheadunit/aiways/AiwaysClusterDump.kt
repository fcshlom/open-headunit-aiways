package com.andrerinas.openheadunit.aiways

import java.io.ByteArrayOutputStream

/**
 * A short piece of the cluster's H.264 stream, kept as raw Annex B so it can be decoded off the car
 * (`ffmpeg -i dump.h264 frame%02d.png`). Pure, so the scanning is unit-tested.
 *
 * It starts at the first keyframe (IDR), with the codec config (SPS and PPS) in front of it, and
 * ends after [maxFrames] frames, [maxBytes] bytes or [maxMs] milliseconds, whichever comes first.
 * Frames before the first keyframe cannot be decoded and are only counted.
 */
internal class AiwaysClusterDump(
    private val maxFrames: Int = 60,
    private val maxBytes: Int = 1_000_000,
    private val maxMs: Long = 4_000L,
) {
    private var codecConfig: ByteArray? = null
    private val out = ByteArrayOutputStream()
    private var startedAtMs = -1L

    var frames = 0
        private set
    var skippedBeforeKeyframe = 0
        private set
    var done = false
        private set

    /** A CODEC_CONFIG message's payload (SPS and PPS). The latest one wins until the dump starts. */
    fun onCodecConfig(data: ByteArray, offset: Int, length: Int) {
        if (startedAtMs >= 0 || length <= 0) return
        codecConfig = data.copyOfRange(offset, offset + length)
    }

    /**
     * A DATA message's payload, from its first start code on. Returns true once the dump is complete;
     * then [bytes] holds it and nothing more is taken.
     */
    fun onFrame(data: ByteArray, offset: Int, length: Int, nowMs: Long): Boolean {
        if (done || length <= 0) return done
        if (startedAtMs < 0) {
            if (NAL_IDR !in nalTypes(data, offset, length)) {
                skippedBeforeKeyframe++
                return false
            }
            startedAtMs = nowMs
            codecConfig?.let { out.write(it, 0, it.size) }
        }
        out.write(data, offset, length)
        frames++
        if (frames >= maxFrames || out.size() >= maxBytes || nowMs - startedAtMs >= maxMs) done = true
        return done
    }

    /** The dump so far: codec config, then every frame from the first keyframe on. */
    fun bytes(): ByteArray = out.toByteArray()

    companion object {
        const val NAL_NON_IDR = 1
        const val NAL_IDR = 5
        const val NAL_SPS = 7
        const val NAL_PPS = 8

        /**
         * Where the Annex B data of a media payload starts: 0 when it begins with a start code, 8 when
         * an 8-byte timestamp comes first (DATA), -1 when neither.
         */
        fun mediaStart(data: ByteArray, offset: Int, length: Int): Int = when {
            startCodeAt(data, offset, length) > 0 -> 0
            length > 8 && startCodeAt(data, offset + 8, length - 8) > 0 -> 8
            else -> -1
        }

        /** The nal_unit_type of every NAL unit in an Annex B buffer, in order. */
        fun nalTypes(data: ByteArray, offset: Int, length: Int): List<Int> {
            val types = ArrayList<Int>(4)
            val end = offset + length
            var i = offset
            while (i < end) {
                val code = startCodeAt(data, i, end - i)
                if (code > 0) {
                    val header = i + code
                    if (header < end) types += data[header].toInt() and 0x1f
                    i = header
                } else {
                    i++
                }
            }
            return types
        }

        /** Length of the start code at [at] (4 for 00 00 00 01, 3 for 00 00 01), or 0 if none. */
        private fun startCodeAt(data: ByteArray, at: Int, available: Int): Int {
            if (available >= 4 && data[at].toInt() == 0 && data[at + 1].toInt() == 0 &&
                data[at + 2].toInt() == 0 && data[at + 3].toInt() == 1) return 4
            if (available >= 3 && data[at].toInt() == 0 && data[at + 1].toInt() == 0 &&
                data[at + 2].toInt() == 1) return 3
            return 0
        }
    }
}
