package com.andrerinas.openheadunit.aiways

/**
 * Which driver's-display description the cluster test announces, and when it stops announcing one.
 * Pure, so the rules are unit-tested; [AiwaysClusterTest] keeps the state in SharedPreferences.
 *
 * Two rungs, tried in order, one per connection:
 * - **0, safe:** 1280 x 720, no margins, density 160, the defaults of aa-proxy-rs. A phone accepted
 *   exactly this cluster description in DiAuto's tests (DiAuto PR #43, third run), so it comes first.
 * - **1, fit:** 800 x 480 with a 530 px width margin. The phone then draws into the middle
 *   270 x 480, which is the part of the head unit's second display the driver's display shows
 *   (launcher `ClusterNav.PANEL_W` x `PANEL_H`), pixel for pixel. Tried only once rung 0 streamed.
 *
 * The safety rule: a session that announced the driver's display and ended in a way that points at
 * the announcement counts as a strike against its rung (see [strikeReason]). A strike on rung 1 goes
 * back to rung 0; a strike on rung 0 stops announcing. Strikes are kept for this app version only:
 * the next build tries again from a clean state.
 */
internal object AiwaysClusterLadder {

    /** A session that ends this soon after service discovery counts as a strike. */
    const val EARLY_END_MS = 120_000L

    /** A session the phone itself ends this soon after it started the cluster stream counts as one too. */
    const val AFTER_START_MS = 30_000L

    /** Cluster frames after which the phone has accepted the description (as [AiwaysClusterTest] counts it streamed). */
    const val ACCEPTED_AFTER_FRAMES = 10L

    data class Rung(
        val index: Int,
        val name: String,
        /** VideoCodecResolutionType value. */
        val codecResolution: Int,
        val codecWidth: Int,
        val codecHeight: Int,
        val marginWidth: Int,
        val marginHeight: Int,
        val density: Int,
    ) {
        val contentWidth: Int get() = codecWidth - marginWidth
        val contentHeight: Int get() = codecHeight - marginHeight

        fun describe(): String =
            "rung $index ($name): codec ${codecWidth}x$codecHeight, margins ${marginWidth}x$marginHeight, " +
                "content ${contentWidth}x$contentHeight, density $density"
    }

    val RUNGS = listOf(
        Rung(0, "safe", codecResolution = 2, codecWidth = 1280, codecHeight = 720,
            marginWidth = 0, marginHeight = 0, density = 160),
        Rung(1, "fit", codecResolution = 1, codecWidth = 800, codecHeight = 480,
            marginWidth = 530, marginHeight = 0, density = 160),
    )

    /** What is kept between connections: bit n of [failed] / [streamed] is rung n. */
    data class State(val version: Int, val failed: Int, val streamed: Int) {
        fun failed(rung: Int): Boolean = failed and (1 shl rung) != 0
        fun streamed(rung: Int): Boolean = streamed and (1 shl rung) != 0
    }

    /** [stored] as it applies to app [version]: another version starts clean. */
    fun forVersion(stored: State, version: Int): State =
        if (stored.version == version) stored else State(version, 0, 0)

    /** The rung to announce now, or -1 when the test is off for this version. */
    fun rungToAnnounce(state: State): Int = when {
        state.failed(0) -> -1
        state.streamed(0) && !state.failed(1) -> 1
        else -> 0
    }

    fun withStreamed(state: State, rung: Int): State = state.copy(streamed = state.streamed or (1 shl rung))

    fun withFailed(state: State, rung: Int): State = state.copy(failed = state.failed or (1 shl rung))

    /**
     * Why a session that announced the driver's display counts as a strike, or null when it does not.
     *
     * - It ended within [EARLY_END_MS] of service discovery before the phone had sent
     *   [ACCEPTED_AFTER_FRAMES] cluster frames: the phone may have refused the description (DiAuto saw
     *   phones close the session about a second after discovery). Once the frames flow, the phone has
     *   accepted it, and an early end is the link's: on 27 November the first session of
     *   4.0.0-aiways.21 ended 4 s in when the phone's USB connection dropped, after 33 cluster frames,
     *   and the old rule switched the driver's display off for the whole version.
     * - The phone itself ended it ([REASON_PHONE_LEFT]) within [AFTER_START_MS] of a START on the cluster
     *   channel ([sinceStartMs], -1 when none): DiAuto saw Google Maps restart the projection right when
     *   guidance began with a cluster announced, and the cluster stream starts with guidance.
     *
     * The driver leaving Android Auto, a settings restart and the car going off never count.
     */
    fun strikeReason(
        reason: String,
        carOff: Boolean,
        sinceDiscoveryMs: Long,
        sinceStartMs: Long,
        clusterFrames: Long,
    ): String? = when {
        reason == REASON_USER_EXIT || reason == REASON_SETTINGS_RESTART -> null
        carOff -> null
        sinceDiscoveryMs in 0 until EARLY_END_MS && clusterFrames < ACCEPTED_AFTER_FRAMES ->
            "the session ended ${sinceDiscoveryMs / 1000} s after service discovery with $clusterFrames cluster frames ($reason)"
        reason == REASON_PHONE_LEFT && sinceStartMs in 0 until AFTER_START_MS ->
            "the phone ended the session ${sinceStartMs / 1000} s after the cluster stream started"
        else -> null
    }

    // The same strings as HeadUnitIntent.SessionState in the contract module.
    const val REASON_USER_EXIT = "user_exit"
    const val REASON_SETTINGS_RESTART = "settings_restart"
    const val REASON_PHONE_LEFT = "phone_left"
}
