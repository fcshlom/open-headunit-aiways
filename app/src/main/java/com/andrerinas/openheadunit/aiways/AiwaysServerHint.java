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

    private static int failures;
    private static boolean hinted;
    private static long hintedAt;

    private AiwaysServerHint() {
    }

    /** After a failed handshake; {@code silent} = the peer accepted and never sent a byte. */
    public static void noteFailedHandshake(Context context, boolean silent, String endpoint) {
        int count;
        synchronized (AiwaysServerHint.class) {
            if (!silent) {
                // the peer said something, so it is not the deaf server
                failures = 0;
                hinted = false;
                return;
            }
            if (endpoint == null || !endpoint.endsWith(":5277")) {
                return;
            }
            failures++;
            long now = SystemClock.elapsedRealtime();
            if (!due(failures, hinted, hintedAt, now)) {
                return;
            }
            hinted = true;
            hintedAt = now;
            count = failures;
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
    public static synchronized void noteAnswered() {
        failures = 0;
        hinted = false;
    }

    static boolean due(int failures, boolean hinted, long hintedAt, long now) {
        return failures >= FAILURES_BEFORE_HINT && (!hinted || now - hintedAt >= REPEAT_MS);
    }
}
