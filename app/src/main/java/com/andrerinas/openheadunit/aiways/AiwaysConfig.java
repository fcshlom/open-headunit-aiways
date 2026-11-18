package com.andrerinas.openheadunit.aiways;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Backend settings handed over by the Aiways launcher (its HeadunitLink broadcast, see
 * {@link AiwaysConfigReceiver}): server URL, API key and the T-Box clock correction. Nothing is
 * entered on this app; until the launcher has sent them, updates and log uploads wait.
 */
public final class AiwaysConfig {
    public static final String DEFAULT_URL = "https://aiways.fcshlom.com";
    private static final String PREFS = "aiways_backend";

    private static volatile long clockOffsetMs;

    private AiwaysConfig() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Called once from App.onCreate: the last offset the launcher sent, until it sends again. */
    public static void load(Context context) {
        clockOffsetMs = prefs(context).getLong("clock_offset_ms", 0L);
    }

    static void store(Context context, String url, String key, boolean clockCorrected, long offsetMs) {
        SharedPreferences.Editor e = prefs(context).edit()
                .putString("url", url == null || url.trim().length() == 0 ? DEFAULT_URL : url.trim())
                .putString("key", key == null ? "" : key.trim());
        if (clockCorrected) {
            e.putLong("clock_offset_ms", offsetMs);
            clockOffsetMs = offsetMs;
        }
        e.apply();
    }

    static String url(Context context) {
        return prefs(context).getString("url", DEFAULT_URL).replaceAll("/+$", "");
    }

    static String key(Context context) {
        return prefs(context).getString("key", "");
    }

    static boolean configured(Context context) {
        return key(context).length() > 0;
    }

    /** Whether the launcher has sent a T-Box clock correction (TLS needs the real date). */
    static boolean clockKnown(Context context) {
        return prefs(context).contains("clock_offset_ms");
    }

    /** Real time: the head unit's clock is often years off (seen: 2019-01-01). */
    public static long now() {
        return System.currentTimeMillis() + clockOffsetMs;
    }
}
