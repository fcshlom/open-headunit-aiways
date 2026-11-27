package com.andrerinas.openheadunit.aiways

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Decodes the phone's cluster map (H.264) into [surface]: a second hardware decoder next to the main
 * screen's, on a thread of its own, with the synchronous MediaCodec API (the unit runs Android 4.4).
 *
 * Frames come from the transport's reader thread through [offer], which never blocks: the queue holds
 * [QUEUE] frames, and when it is full the backlog is dropped and decoding waits for the next keyframe,
 * so the map never smears. It also waits for a keyframe at the start. The codec config (SPS and PPS)
 * goes in first, flagged as such.
 */
internal class AiwaysClusterDecoder(
    private val surface: Surface,
    private val width: Int,
    private val height: Int,
    private val listener: Listener,
) {
    interface Listener {
        /** A frame went to the surface (decoder thread). */
        fun onRendered()

        /** The decoder failed and stopped (decoder thread). */
        fun onFailed(why: String)
    }

    private class Chunk(val data: ByteArray, val config: Boolean)

    private val queue = ArrayBlockingQueue<Chunk>(QUEUE)
    @Volatile private var running = true
    @Volatile private var needKeyframe = true
    private var thread: Thread? = null

    // counted on the decoder thread, read for the log line
    @Volatile var rendered = 0L
        private set
    @Volatile var dropped = 0L
        private set

    fun start(codecConfig: ByteArray?) {
        if (codecConfig != null) queue.offer(Chunk(codecConfig, true))
        thread = Thread({ run() }, "aiways-cluster-decoder").also { it.start() }
    }

    /** A codec config (SPS and PPS) from the phone; from the reader thread. */
    fun offerConfig(data: ByteArray, offset: Int, length: Int) {
        if (!running) return
        if (!queue.offer(Chunk(data.copyOfRange(offset, offset + length), true))) {
            queue.clear()
            needKeyframe = true
            queue.offer(Chunk(data.copyOfRange(offset, offset + length), true))
        }
    }

    /** One frame's Annex B data, [keyframe] when it holds an IDR; from the reader thread. Never blocks. */
    fun offer(data: ByteArray, offset: Int, length: Int, keyframe: Boolean) {
        if (!running) return
        if (needKeyframe && !keyframe) {
            dropped++
            return
        }
        needKeyframe = false
        if (!queue.offer(Chunk(data.copyOfRange(offset, offset + length), false))) {
            // behind: drop the backlog and start again at the next keyframe
            dropped += queue.size + 1
            queue.clear()
            needKeyframe = true
        }
    }

    /** True while the decoder waits for a keyframe (at the start, after a backlog). */
    fun waitingForKeyframe(): Boolean = needKeyframe

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun run() {
        var codec: MediaCodec? = null
        try {
            val c = MediaCodec.createDecoderByType(MIME)
            codec = c
            val format = MediaFormat.createVideoFormat(MIME, width, height)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT)
            c.configure(format, surface, null, 0)
            c.start()
            val inputs = c.inputBuffers
            val info = MediaCodec.BufferInfo()
            var pts = 0L
            while (running) {
                val unit = queue.poll(20, TimeUnit.MILLISECONDS)
                if (unit != null) {
                    val index = c.dequeueInputBuffer(INPUT_WAIT_US)
                    if (index >= 0) {
                        val buf = inputs[index]
                        buf.clear()
                        val n = minOf(unit.data.size, buf.capacity())
                        buf.put(unit.data, 0, n)
                        val flags = if (unit.config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                        c.queueInputBuffer(index, 0, n, pts, flags)
                        if (!unit.config) pts += FRAME_US
                    } else {
                        dropped++
                    }
                }
                drain(c, info)
            }
        } catch (e: InterruptedException) {
            // stop()
        } catch (e: Exception) {
            if (running) listener.onFailed(e.toString())
        } finally {
            running = false
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun drain(c: MediaCodec, info: MediaCodec.BufferInfo) {
        while (running) {
            val index = c.dequeueOutputBuffer(info, 0)
            if (index < 0) return   // try later, format or buffers changed: nothing to render
            c.releaseOutputBuffer(index, true)
            rendered++
            listener.onRendered()
        }
    }

    companion object {
        private const val MIME = "video/avc"
        private const val QUEUE = 30
        private const val MAX_INPUT = 512 * 1024
        private const val INPUT_WAIT_US = 10_000L
        private const val FRAME_US = 33_333L
    }
}
