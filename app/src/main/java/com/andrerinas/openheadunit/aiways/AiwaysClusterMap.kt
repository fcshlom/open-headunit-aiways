package com.andrerinas.openheadunit.aiways

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager

/**
 * The phone's cluster map on the driver's display (4.0.0-aiways.21).
 *
 * The launcher decides what the driver's display shows during guidance: its own turn card ("arrows") or
 * this map with its banner on top ("map"). It asks with [ACTION_SHOW] (`show` true or false) every few
 * seconds while guidance runs; without a request for [REQUEST_STALE_MS] the map goes, so a launcher
 * that died cannot leave it standing. This app answers with [ACTION_STATE] (`showing`, `reason`) on
 * every change and every [STATE_EVERY_MS]: `showing` only while frames really reach the panel. Both
 * go under the signature permission [AiwaysCarPower.PERMISSION].
 *
 * The window: a TextureView of the panel's size ([AiwaysClusterGeometry]) at the top-left corner of
 * the head unit's second display, the part the driver's display shows (as the launcher's ClusterNav).
 * TYPE_SYSTEM_OVERLAY puts it above Adayo's page (TYPE_SYSTEM_ALERT) and below the launcher's window
 * (TYPE_SYSTEM_ERROR), whose banner is drawn over the map. It takes no touch and no focus.
 *
 * The stream comes from [AiwaysClusterTest] (channel 14). A decoder joining a running stream needs a
 * keyframe, and the phone sends one only every minute or so, so the window asks for one with a focus
 * release and regain on channel 14, as the main screen does ([AiwaysClusterTest.requestKeyframe]); at
 * most every [KEYFRAME_RETRY_MS] while the decoder waits. A STOP from the phone (also the answer to that
 * release) keeps the window: the map is only reported as not showing until frames come again.
 *
 * Window and state live on the main thread; frames arrive on the transport's reader thread and go
 * straight to the decoder's queue. Logged as `[clustermap]`.
 */
internal object AiwaysClusterMap {
    const val ACTION_SHOW = "com.aiways.headunit.CLUSTER_MAP"
    const val ACTION_STATE = "com.adayo.radio.CLUSTER_MAP_STATE"
    private const val TAG = "clustermap"

    private const val REQUEST_STALE_MS = 10_000L
    /** No frame on the panel for this long: not showing (the launcher draws its full card again). */
    private const val FRAME_STALE_MS = 3_000L
    private const val STATE_EVERY_MS = 2_000L
    private const val KEYFRAME_RETRY_MS = 5_000L
    /** After a decoder or window failure, no new try for this long. */
    private const val RETRY_AFTER_FAILURE_MS = 60_000L
    private const val TICK_MS = 1_000L
    private const val STATS_EVERY_MS = 30_000L

    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null

    // main thread
    private var requested = false
    private var requestAt = 0L
    private var wm: WindowManager? = null
    private var view: TextureView? = null
    private var surface: Surface? = null
    private var showing = false
    private var stateAt = 0L
    private var keyframeAt = 0L
    private var failedAt = 0L
    private var ticking = false
    private var statsAt = 0L
    private var statsRendered = 0L
    private var noDisplayLogged = false

    @Volatile private var decoder: AiwaysClusterDecoder? = null
    @Volatile private var renderedAt = 0L
    // the stream, from the reader thread
    @Volatile private var codecConfig: ByteArray? = null
    @Volatile private var streamSeen = false

    /** Registered by AapService next to the car power receiver. */
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_SHOW) return
            onRequest(context.applicationContext ?: context, intent.getBooleanExtra("show", false))
        }
    }

    private fun log(message: String) = AiwaysLog.event(TAG, message)

    private fun onRequest(ctx: Context, show: Boolean) {
        context = ctx
        if (show != requested) log(if (show) "the launcher asks for the map" else "the launcher asks for the arrows")
        requested = show
        requestAt = SystemClock.elapsedRealtime()
        if (!ticking) {
            ticking = true
            main.post(tick)
        }
        update()
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (requested && now - requestAt > REQUEST_STALE_MS) {
                requested = false
                log("no request from the launcher for ${REQUEST_STALE_MS / 1000} s: map off")
            }
            update()
            val d = decoder
            if (d != null && d.waitingForKeyframe() && now - keyframeAt >= KEYFRAME_RETRY_MS) {
                keyframeAt = now
                if (AiwaysClusterTest.requestKeyframe()) log("asked the phone for a keyframe")
            }
            if (d != null && now - statsAt >= STATS_EVERY_MS) {
                log("map: ${d.rendered - statsRendered} frames to the panel in ${(now - statsAt) / 1000} s, " +
                    "${d.dropped} dropped in all")
                statsAt = now
                statsRendered = d.rendered
            }
            report(now)
            if (requested || view != null) {
                main.postDelayed(this, TICK_MS)
            } else {
                ticking = false
            }
        }
    }

    /** Tells the launcher whether the map is on the panel: on a change, and every STATE_EVERY_MS. */
    private fun report(now: Long) {
        val on = view != null && decoder != null && renderedAt > 0 && now - renderedAt < FRAME_STALE_MS
        if (on == showing && now - stateAt < STATE_EVERY_MS) return
        val reason = when {
            on -> "frames"
            !requested -> "not asked"
            !streamSeen -> "no stream from the phone"
            view == null -> "no window"
            renderedAt == 0L -> "waiting for the first frame"
            else -> "no frame for ${(now - renderedAt) / 1000} s"
        }
        if (on != showing) log(if (on) "map on the driver's display" else "map not showing: $reason")
        showing = on
        stateAt = now
        val ctx = context ?: return
        try {
            ctx.sendBroadcast(Intent(ACTION_STATE).putExtra("showing", on).putExtra("reason", reason),
                AiwaysCarPower.PERMISSION)
        } catch (e: Exception) {
            log("state broadcast failed: $e")
        }
    }

    /** Opens or closes the window to match the request and the stream. Main thread. */
    private fun update() {
        val want = requested && streamSeen && AiwaysClusterTest.currentRung() != null
        if (want && view == null) {
            if (failedAt > 0 && SystemClock.elapsedRealtime() - failedAt < RETRY_AFTER_FAILURE_MS) return
            open()
        } else if (!want && view != null) {
            close(if (!requested) "the launcher shows the arrows" else "the stream ended")
        }
    }

    private fun open() {
        val ctx = context ?: return
        val rung = AiwaysClusterTest.currentRung() ?: return
        val displays = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = try {
            displays.displays.let { if (it.size > 1) it[1] else null }
        } catch (e: RuntimeException) {
            null
        }
        if (display == null) {
            if (!noDisplayLogged) log("no second display: nothing to draw on")
            noDisplayLogged = true
            return
        }
        noDisplayLogged = false
        try {
            // a context bound to that display: its WindowManager adds windows there, as Presentation does
            val dc = ctx.createDisplayContext(display)
            val w = dc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val tv = TextureView(dc)
            tv.surfaceTextureListener = textureListener
            @Suppress("DEPRECATION")
            val lp = WindowManager.LayoutParams(
                AiwaysClusterGeometry.PANEL_W, AiwaysClusterGeometry.PANEL_H,
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE,
            )
            lp.gravity = Gravity.TOP or Gravity.LEFT
            lp.x = 0
            lp.y = 0
            lp.title = "AiwaysClusterMap"
            w.addView(tv, lp)
            wm = w
            view = tv
            renderedAt = 0L
            statsAt = SystemClock.elapsedRealtime()
            statsRendered = 0L
            log("window added on display #${display.displayId} for ${rung.describe()}")
        } catch (e: RuntimeException) {
            failedAt = SystemClock.elapsedRealtime()
            log("cannot add the window: $e")
        }
    }

    private fun close(reason: String) {
        decoder?.stop()
        decoder = null
        surface?.release()
        surface = null
        val tv = view
        if (tv != null) {
            try {
                wm?.removeView(tv)
            } catch (_: RuntimeException) {
                // the display is gone, and its windows with it
            }
            log("window removed: $reason")
        }
        view = null
        wm = null
        renderedAt = 0L
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
            val rung = AiwaysClusterTest.currentRung() ?: return
            val t = AiwaysClusterGeometry.transform(rung, width, height)
            view?.setTransform(Matrix().apply {
                setScale(t.scaleX, t.scaleY)
                postTranslate(t.translateX, t.translateY)
            })
            val s = Surface(st)
            surface = s
            val d = AiwaysClusterDecoder(s, rung.codecWidth, rung.codecHeight, decoderListener)
            decoder = d
            d.start(codecConfig)
            log("decoder started for ${rung.codecWidth}x${rung.codecHeight} into a ${width}x$height view" +
                if (codecConfig == null) ", no codec config yet" else "")
            keyframeAt = SystemClock.elapsedRealtime()
            if (AiwaysClusterTest.requestKeyframe()) log("asked the phone for a keyframe")
        }

        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            decoder?.stop()
            decoder = null
            surface?.release()
            surface = null
            return true
        }

        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
            if (renderedAt == 0L) log("first frame on the panel")
            renderedAt = SystemClock.elapsedRealtime()
        }
    }

    private val decoderListener = object : AiwaysClusterDecoder.Listener {
        override fun onRendered() {}

        override fun onFailed(why: String) {
            main.post {
                log("decoder failed: $why")
                failedAt = SystemClock.elapsedRealtime()
                close("decoder failed")
            }
        }
    }

    // From AiwaysClusterTest, on the transport's reader thread.

    /** The phone started the cluster stream (START on channel 14). */
    fun onStreamStart() {
        streamSeen = true
        main.post { update() }
    }

    /** A CODEC_CONFIG payload: kept for the next decoder, and given to the running one. */
    fun onCodecConfig(data: ByteArray, offset: Int, length: Int) {
        codecConfig = data.copyOfRange(offset, offset + length)
        decoder?.offerConfig(data, offset, length)
    }

    /** A DATA payload's Annex B part. */
    fun onFrame(data: ByteArray, offset: Int, length: Int, keyframe: Boolean) {
        decoder?.offer(data, offset, length, keyframe)
    }

    /** The session ended: the window goes, the stream is forgotten. */
    fun onSessionEnd() {
        streamSeen = false
        codecConfig = null
        main.post { if (view != null) close("the session ended") }
    }
}
