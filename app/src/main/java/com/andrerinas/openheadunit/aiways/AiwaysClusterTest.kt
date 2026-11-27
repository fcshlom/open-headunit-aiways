package com.andrerinas.openheadunit.aiways

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.aap.AapMessage
import com.andrerinas.openheadunit.aap.AapTransport
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.messages.VideoFocusEvent
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.andrerinas.openheadunit.app.AccPowerState
import com.google.protobuf.UnknownFieldSet
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Test (4.0.0-aiways.20): does the phone send a map for the driver's display?
 *
 * Android Auto can drive a second screen behind the steering wheel. The head unit announces an
 * extra video sink whose display_type is CLUSTER, and the phone's navigation app renders its own
 * map video for it (Google's Desktop Head Unit: `[display:cluster]`, `displaytype = cluster`;
 * DiAuto, an Open Headunit fork for BYD, shows Waze's map this way since PR #44). The launcher
 * already draws on the part of the head unit's second display that the driver's display shows
 * (launcher `ClusterNav`, 270 x 480), so that map could go there.
 *
 * This build only asks and listens. It announces the driver's display (video channel
 * [Channel.ID_CLUSTER_VID], input channel [Channel.ID_CLUSTER_INP], display 1), answers the phone
 * on that channel the way a car does, and writes to the day log under `[cluster]`:
 * - what it announced ([AiwaysClusterLadder] picks the description), and what the phone did with
 *   it: channel open, setup, video focus, start and stop;
 * - the stream: the first frame, and every 30 s frames, keyframes and kB;
 * - once per stream start (at most [DUMPS_PER_DAY] a day) a few seconds of the raw H.264 from the
 *   first keyframe, base64 in parts, to decode into pictures off the car. It shows the map, so where
 *   the car is, like the `[turncard]` snapshots.
 * Nothing is decoded or shown on the car yet.
 *
 * The phone's protocol messages arrive on the transport's reader thread; [onSessionEnd] on the
 * service's thread; the focus grant runs on the main thread; prefs and dumps on [worker].
 *
 * The proto fields the generated classes lack (MediaSinkService.display_id 6 and display_type 7,
 * InputSourceService.display_id 5; DisplayType CLUSTER = 1, as in aa-proxy-rs and DiAuto) are
 * written as unknown fields, which protobuf serializes like any other.
 */
internal object AiwaysClusterTest {
    private const val TAG = "cluster"
    private const val PREFS = "aiways_cluster_test"

    const val DISPLAY_ID = 1
    const val DISPLAY_TYPE_CLUSTER = 1
    const val FIELD_SINK_DISPLAY_ID = 6
    const val FIELD_SINK_DISPLAY_TYPE = 7
    const val FIELD_INPUT_DISPLAY_ID = 5

    /** aa-proxy-rs's cluster input: the D-pad keys (KEYCODE_DPAD_UP .. KEYCODE_DPAD_CENTER). */
    private val DPAD_KEYCODES = listOf(19, 20, 21, 22, 23)
    private const val VIEWING_DISTANCE_MM = 300

    /** A short window: a 30 fps map needs little in flight, and it must not crowd the main screen. */
    const val MAX_UNACKED = 4

    /**
     * Our own focus grant waits until the main screen's video has run this long. Granted earlier,
     * the phone can start the cluster first and leave the main screen on "Android Auto is starting"
     * (DiAuto; LIVI holds it the same way).
     */
    private const val FOCUS_DELAY_MS = 1_500L
    private const val STATS_EVERY_MS = 30_000L
    /** After this many frames the rung counts as streamed. */
    private const val STREAMED_AFTER_FRAMES = 10L
    private const val DUMPS_PER_DAY = 3
    /** 36 KB of H.264 per log line, 48 KB of base64. A multiple of 3, see [writeDump]. */
    private const val DUMP_PART_BYTES = 36 * 1024

    private val lock = Any()

    // One connection, guarded by [lock].
    private var generation = 0
    private var rung: AiwaysClusterLadder.Rung? = null
    private var discoveryAtMs = 0L
    private var startAtMs = -1L
    private var setupSeen = false
    private var focusGranted = false
    private var focusScheduled = false
    private var transport: WeakReference<AapTransport>? = null
    private var appContext: Context? = null

    /** Read on every main-screen video message, so only this one is checked without the lock. */
    @Volatile private var mainVideoSeen = true

    // The stream, on the reader thread.
    @Volatile private var frames = 0L
    @Volatile private var keyframes = 0L
    @Volatile private var bytes = 0L
    private var configs = 0
    private var windowStartMs = 0L
    private var windowFrames = 0
    private var windowBytes = 0L
    private var dump: AiwaysClusterDump? = null
    private var dumpTaken = false
    private var readErrorLogged = false

    private val main: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val worker: Handler by lazy {
        val t = HandlerThread("aiways-cluster")
        t.start()
        Handler(t.looper)
    }

    private fun log(message: String) = AiwaysLog.event(TAG, message)

    /**
     * Service discovery, the start of every connection: the services that announce the driver's
     * display, or none when the test is off for this version.
     */
    fun servicesForDiscovery(context: Context): List<Control.Service> {
        val app = context.applicationContext ?: context
        val state = loadState(app)
        val index = AiwaysClusterLadder.rungToAnnounce(state)
        synchronized(lock) {
            generation++
            appContext = app
            rung = if (index >= 0) AiwaysClusterLadder.RUNGS[index] else null
            discoveryAtMs = SystemClock.elapsedRealtime()
            startAtMs = -1L
            setupSeen = false
            focusGranted = false
            focusScheduled = false
            transport = null
            mainVideoSeen = false
            frames = 0L
            keyframes = 0L
            bytes = 0L
            configs = 0
            dump = null
            dumpTaken = false
            readErrorLogged = false
        }
        if (index < 0) {
            mainVideoSeen = true
            val why = prefs(app).getString(KEY_OFF_REASON, null) ?: "a strike on rung 0"
            log("driver's display not announced: the test is off in this version after $why")
            return emptyList()
        }
        val r = AiwaysClusterLadder.RUNGS[index]
        log("announcing the driver's display as a cluster: ${r.describe()}, video channel " +
            "${Channel.ID_CLUSTER_VID}, input channel ${Channel.ID_CLUSTER_INP}, display $DISPLAY_ID " +
            "(failed=${state.failed} streamed=${state.streamed})")
        return listOf(videoService(r), inputService())
    }

    internal fun videoService(r: AiwaysClusterLadder.Rung): Control.Service {
        val video = Control.Service.MediaSinkService.VideoConfiguration.newBuilder()
            .setCodecResolution(
                Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType.forNumber(r.codecResolution)
            )
            .setFrameRate(Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._30)
            .setMarginWidth(r.marginWidth)
            .setMarginHeight(r.marginHeight)
            .setDensity(r.density)
            .setRealDensity(r.density)
            .setDecoderAdditionalDepth(0)
            .setViewingDistance(VIEWING_DISTANCE_MM)
            .setPixelAspectRatioE4(10000)
            .setVideoCodecType(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
            .build()
        val sink = Control.Service.MediaSinkService.newBuilder()
            .setAvailableType(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
            .addVideoConfigs(video)
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(FIELD_SINK_DISPLAY_ID, varint(DISPLAY_ID))
                    .addField(FIELD_SINK_DISPLAY_TYPE, varint(DISPLAY_TYPE_CLUSTER))
                    .build()
            )
            .build()
        return Control.Service.newBuilder().setId(Channel.ID_CLUSTER_VID).setMediaSinkService(sink).build()
    }

    internal fun inputService(): Control.Service {
        val input = Control.Service.InputSourceService.newBuilder()
            .addAllKeycodesSupported(DPAD_KEYCODES)
            .setUnknownFields(
                UnknownFieldSet.newBuilder().addField(FIELD_INPUT_DISPLAY_ID, varint(DISPLAY_ID)).build()
            )
            .build()
        return Control.Service.newBuilder().setId(Channel.ID_CLUSTER_INP).setInputSourceService(input).build()
    }

    private fun varint(value: Int): UnknownFieldSet.Field =
        UnknownFieldSet.Field.newBuilder().addVarint(value.toLong()).build()

    /** The phone opened one of the two channels. */
    fun onChannelOpen(channel: Int, request: Control.ChannelOpenRequest) {
        log("the phone opened ${Channel.name(channel)} (channel $channel, service ${request.serviceId})")
    }

    /** A media control message on the cluster video channel (setup, start, stop, focus request). */
    fun control(aapTransport: AapTransport, message: AapMessage): Int {
        val channel = message.channel
        when (message.type) {
            Media.MsgType.MEDIA_MESSAGE_SETUP_VALUE -> {
                val request = message.parse(Media.MediaSetupRequest.newBuilder()).build()
                val config = Media.Config.newBuilder()
                    .setStatus(Media.Config.ConfigStatus.STATUS_READY)
                    .setMaxUnacked(MAX_UNACKED)
                    .addConfigurationIndices(0)
                    .build()
                aapTransport.send(AapMessage(channel, Media.MsgType.MEDIA_MESSAGE_CONFIG_VALUE, config))
                log("setup from the phone (codec ${request.type}): answered ready, max_unacked $MAX_UNACKED")
                synchronized(lock) {
                    setupSeen = true
                    transport = WeakReference(aapTransport)
                    maybeScheduleFocusLocked()
                }
            }
            Media.MsgType.MEDIA_MESSAGE_START_VALUE -> {
                val start = message.parse(Media.Start.newBuilder()).build()
                aapTransport.setSessionId(channel, start.sessionId)
                val now = SystemClock.elapsedRealtime()
                val dumpAllowed = dumpsLeftToday() > 0
                synchronized(lock) {
                    startAtMs = now
                    windowStartMs = now
                    windowFrames = 0
                    windowBytes = 0L
                    if (dump == null && !dumpTaken && dumpAllowed) dump = AiwaysClusterDump()
                }
                log("START: the phone starts the cluster stream (session ${start.sessionId}, " +
                    "config ${start.configurationIndex}, ${(now - discoveryAtMs) / 1000} s after discovery)")
            }
            Media.MsgType.MEDIA_MESSAGE_STOP_VALUE -> {
                log("STOP: the phone stopped the cluster stream after $frames frames ($keyframes key, ${bytes / 1024} kB)")
            }
            Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_REQUEST_VALUE -> {
                val request = message.parse(Media.VideoFocusRequestNotification.newBuilder()).build()
                val gain = request.mode != Media.VideoFocusMode.VIDEO_FOCUS_NATIVE
                // The cluster follows the phone. Never read this as the driver leaving Android Auto:
                // that is the main channel's NATIVE request, handled in AapControlMedia.
                aapTransport.send(VideoFocusEvent(gain = gain, unsolicited = false, channel = channel))
                synchronized(lock) { if (gain) focusGranted = true }
                log("video focus request: mode ${request.mode}, reason ${request.reason}, " +
                    "display channel ${if (request.hasDispChannelId()) request.dispChannelId else -1}: " +
                    "answered ${if (gain) "projected" else "native"}")
            }
            else -> log("message type 0x${Integer.toHexString(message.type)} on the cluster channel, ${message.size} B: ignored")
        }
        return 0
    }

    /** Main-screen video arrived. The first one, with setup seen, schedules our focus grant. */
    fun onMainVideo() {
        if (mainVideoSeen) return
        synchronized(lock) {
            if (mainVideoSeen) return
            mainVideoSeen = true
            maybeScheduleFocusLocked()
        }
    }

    private fun maybeScheduleFocusLocked() {
        if (!setupSeen || !mainVideoSeen || focusGranted || focusScheduled || rung == null) return
        focusScheduled = true
        val gen = generation
        main.postDelayed({ grantFocus(gen) }, FOCUS_DELAY_MS)
    }

    private fun grantFocus(gen: Int) {
        val t: AapTransport
        synchronized(lock) {
            if (gen != generation || focusGranted) return
            focusGranted = true
            t = transport?.get() ?: return
        }
        t.send(VideoFocusEvent(gain = true, unsolicited = true, channel = Channel.ID_CLUSTER_VID))
        log("granted the cluster video focus (projected), ${FOCUS_DELAY_MS / 1000.0} s after the main picture")
    }

    /**
     * A DATA or CODEC_CONFIG message on the cluster video channel, complete (the reassembler joins
     * the fragments of every channel but the main video). Counted and, if a dump is open, kept.
     * Never throws: this runs on the reader thread that also carries audio.
     */
    fun onMedia(message: AapMessage) {
        try {
            val data = message.data
            val offset = message.dataOffset
            val length = message.size - offset
            if (length <= 0) return
            val now = SystemClock.elapsedRealtime()
            if (message.type == Media.MsgType.MEDIA_MESSAGE_CODEC_CONFIG_VALUE) {
                configs++
                if (configs <= 3) {
                    log("codec config: $length B, NAL ${AiwaysClusterDump.nalTypes(data, offset, length)}")
                }
                dump?.onCodecConfig(data, offset, length)
                return
            }
            val start = AiwaysClusterDump.mediaStart(data, offset, length)
            val nals = if (start >= 0) AiwaysClusterDump.nalTypes(data, offset + start, length - start) else emptyList()
            frames++
            bytes += length
            windowFrames++
            windowBytes += length
            if (AiwaysClusterDump.NAL_IDR in nals) keyframes++
            if (frames == 1L) {
                log("first frame: $length B, " + if (start >= 0) "NAL $nals" else "no H.264 start code in its first bytes")
            }
            if (frames == STREAMED_AFTER_FRAMES) markStreamed()
            val d = dump
            if (d != null && start >= 0 && d.onFrame(data, offset + start, length - start, now)) {
                dump = null
                dumpTaken = true
                writeDump(d)
            }
            if (now - windowStartMs >= STATS_EVERY_MS) {
                log("stream: $windowFrames frames, ${windowBytes / 1024} kB in ${(now - windowStartMs) / 1000} s " +
                    "(total $frames frames, $keyframes key)")
                windowStartMs = now
                windowFrames = 0
                windowBytes = 0L
            }
        } catch (e: Exception) {
            if (!readErrorLogged) {
                readErrorLogged = true
                log("could not read a cluster frame: $e")
            }
        }
    }

    private fun markStreamed() {
        val r = synchronized(lock) { rung } ?: return
        val context = synchronized(lock) { appContext } ?: return
        worker.post {
            val state = loadState(context)
            if (state.streamed(r.index)) return@post
            val next = AiwaysClusterLadder.withStreamed(state, r.index)
            saveState(context, next, null)
            val nextRung = AiwaysClusterLadder.rungToAnnounce(next)
            log("rung ${r.index} streams. The next connection announces " +
                if (nextRung >= 0) AiwaysClusterLadder.RUNGS[nextRung].describe() else "nothing")
        }
    }

    private fun writeDump(d: AiwaysClusterDump) {
        val context = synchronized(lock) { appContext } ?: return
        worker.post {
            if (d.frames == 0) {
                log("no dump: the first keyframe alone is larger than the dump's limit")
                return@post
            }
            val raw = d.bytes()
            val parts = (raw.size + DUMP_PART_BYTES - 1) / DUMP_PART_BYTES
            val id = System.currentTimeMillis() / 1000
            log("dump $id: ${d.frames} frames from the first keyframe (${d.skippedBeforeKeyframe} before it), " +
                "${raw.size} B of H.264 in $parts parts. Join the parts in order, base64-decode to .h264, " +
                "then ffmpeg -i dump.h264 frame%02d.png")
            // One part at a time, so only one part's text is ever in memory. A part is a multiple of
            // 3 bytes, so the parts' base64 joined is the base64 of the whole dump.
            for (i in 0 until parts) {
                val from = i * DUMP_PART_BYTES
                val length = minOf(raw.size - from, DUMP_PART_BYTES)
                log("dump $id part ${i + 1}/$parts base64=" + Base64.encodeToString(raw, from, length, Base64.NO_WRAP))
            }
            countDump(context)
        }
    }

    /**
     * The session ended. A session that announced the driver's display and ended early counts as a
     * strike against its rung ([AiwaysClusterLadder.strikeReason]).
     */
    fun onSessionEnd(context: Context, state: String, reason: String) {
        val r: AiwaysClusterLadder.Rung
        val sinceDiscovery: Long
        val sinceStart: Long
        synchronized(lock) {
            r = rung ?: return
            rung = null
            generation++
            val now = SystemClock.elapsedRealtime()
            sinceDiscovery = now - discoveryAtMs
            sinceStart = if (startAtMs >= 0) now - startAtMs else -1L
        }
        mainVideoSeen = true
        log("session $state ($reason) after ${sinceDiscovery / 1000} s with rung ${r.index}: " +
            "$frames cluster frames ($keyframes key, ${bytes / 1024} kB)")
        val strike = AiwaysClusterLadder.strikeReason(reason, AccPowerState.isOff, sinceDiscovery, sinceStart) ?: return
        val app = context.applicationContext ?: context
        worker.post {
            val next = AiwaysClusterLadder.withFailed(loadState(app), r.index)
            val nextRung = AiwaysClusterLadder.rungToAnnounce(next)
            saveState(app, next, if (nextRung < 0) strike else null)
            log("strike against rung ${r.index}: $strike. " + if (nextRung < 0) {
                "The driver's display is no longer announced in this version"
            } else {
                "The next connection announces ${AiwaysClusterLadder.RUNGS[nextRung].describe()}"
            })
        }
    }

    // State in SharedPreferences.

    private const val KEY_VERSION = "version"
    private const val KEY_FAILED = "failed"
    private const val KEY_STREAMED = "streamed"
    private const val KEY_OFF_REASON = "off_reason"
    private const val KEY_DUMP_DAY = "dump_day"
    private const val KEY_DUMP_COUNT = "dump_count"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadState(context: Context): AiwaysClusterLadder.State {
        val p = prefs(context)
        val stored = AiwaysClusterLadder.State(p.getInt(KEY_VERSION, 0), p.getInt(KEY_FAILED, 0), p.getInt(KEY_STREAMED, 0))
        val state = AiwaysClusterLadder.forVersion(stored, BuildConfig.VERSION_CODE)
        if (state != stored) saveState(context, state, null)
        return state
    }

    private fun saveState(context: Context, state: AiwaysClusterLadder.State, offReason: String?) {
        val e = prefs(context).edit()
            .putInt(KEY_VERSION, state.version)
            .putInt(KEY_FAILED, state.failed)
            .putInt(KEY_STREAMED, state.streamed)
        if (offReason != null) e.putString(KEY_OFF_REASON, offReason)
        if (state.failed == 0) e.remove(KEY_OFF_REASON)
        e.apply()
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun dumpsLeftToday(): Int {
        val context = synchronized(lock) { appContext } ?: return 0
        val p = prefs(context)
        val count = if (p.getString(KEY_DUMP_DAY, null) == today()) p.getInt(KEY_DUMP_COUNT, 0) else 0
        return DUMPS_PER_DAY - count
    }

    private fun countDump(context: Context) {
        val p = prefs(context)
        val day = today()
        val count = if (p.getString(KEY_DUMP_DAY, null) == day) p.getInt(KEY_DUMP_COUNT, 0) else 0
        p.edit().putString(KEY_DUMP_DAY, day).putInt(KEY_DUMP_COUNT, count + 1).apply()
    }
}
