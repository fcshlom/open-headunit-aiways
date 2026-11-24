package com.andrerinas.openheadunit.aiways;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Tells the driver, on the launcher's screen, when the phone's Android Auto head unit server has
 * stopped answering, and what helps.
 *
 * On 24 November 2026 the app reached the phone's server 47 times in twelve minutes and every time
 * it accepted the connection and said nothing ("peer_silent", see {@code UnresponsivePeerPolicy}).
 * Nothing on the head unit can clear that; plugging the phone in by USB once did, at once, and
 * wireless worked again afterwards. The app's own banner for it ({@code ConnectionIssue
 * .HEADUNIT_SERVER_NOT_ANSWERING}) sits on its main screen, which nobody sees while the launcher is
 * in front, so the whole drive went by without a hint.
 *
 * After {@link #FAILURES_BEFORE_HINT} silent handshakes in a row with a head unit server (port
 * 5277, whatever address the phone has: on 24 November it was on the T-Box WiFi and on its own
 * hotspot in turn), the launcher is asked to show the hint ({@link #ACTION}, signature permission
 * {@link AiwaysCarPower#PERMISSION}); again every {@link #REPEAT_MS} while it lasts. Any session that
 * gets through its handshake, by USB or wireless, ends the streak. The broadcast names no package:
 * the car's launcher is the com.adayo.radio.preview build, and the permission is what keeps it
 * between the two apps.
 */
public final class AiwaysServerHint {
    static final String ACTION = "com.adayo.radio.AA_SERVER_STUCK";
    static final int FAILURES_BEFORE_HINT = 3;
    static final long REPEAT_MS = 5L * 60 * 1000;

    /** The one streak of this process: CommManager is one per process too. */
    private static final Streak STREAK = new Streak();

    private AiwaysServerHint() {
    }

    /** After a failed handshake; {@code silent} = the peer accepted and never sent a byte. */
    public static void noteFailedHandshake(Context context, boolean silent, String endpoint) {
        int count = STREAK.onFailedHandshake(silent, endpoint, SystemClock.elapsedRealtime());
        if (count == 0) {
            return;
        }
        AiwaysLog.event("hint", "the phone's head unit server at " + endpoint + " accepted " + count
                + " connections in a row without answering: asking the launcher to show the USB hint");
        try {
            context.sendBroadcast(new Intent(ACTION)
                    .putExtra("failures", count)
                    .putExtra("endpoint", endpoint), AiwaysCarPower.PERMISSION);
        } catch (Exception e) {
            AiwaysLog.event("hint", "broadcast failed: " + e);
        }
    }

    /** A handshake went through: whatever was wrong with the server is over. */
    public static void noteAnswered() {
        STREAK.onAnswered();
    }

    /**
     * Silent handshakes in a row with a head unit server, and when the hint last went out. Its own
     * object, every method under its lock, so a test builds a fresh one instead of sharing this
     * process's.
     */
    static final class Streak {
        private int failures;
        private boolean hinted;
        private long hintedAt;

        /** The streak's length if the hint is due now, else 0. */
        synchronized int onFailedHandshake(boolean silent, String endpoint, long now) {
            if (!silent) {
                // the peer said something, so it is not the deaf server
                failures = 0;
                hinted = false;
                return 0;
            }
            if (endpoint == null || !endpoint.endsWith(":5277")) {
                return 0;
            }
            failures++;
            if (failures < FAILURES_BEFORE_HINT || (hinted && now - hintedAt < REPEAT_MS)) {
                return 0;
            }
            hinted = true;
            hintedAt = now;
            return failures;
        }

        synchronized void onAnswered() {
            failures = 0;
            hinted = false;
        }
    }
}
