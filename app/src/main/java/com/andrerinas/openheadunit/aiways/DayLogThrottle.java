package com.andrerinas.openheadunit.aiways;

import java.util.HashMap;
import java.util.Map;

/**
 * Keeps repeating debug lines from filling the day log.
 *
 * The car runs the app's log at VERBOSE, so AppLog emits a few lines per received video frame
 * ("SSL Decrypt", "RECV: VIDEO Media Data", "VIDEO Codec/Media Data Ack"). On 23 and 24 November
 * 2026 those were 40,000 of the 46,000 lines of the day file: they used up its share of the day
 * ({@code AiwaysLog.MAX_TEE_BYTES}) in the first drive, and the end of every later session -- when
 * and how the projection stopped -- never reached the file.
 *
 * So a DEBUG or VERBOSE line is written only while its kind -- the line with its thread and every
 * number taken out -- has had fewer than {@link #PER_WINDOW} lines in the current
 * {@link #WINDOW_MS}; the rest are counted, and the next line of that kind in a later window says
 * how many were left out. INFO and above always go through. Used on the day log's writer thread
 * only, so it is not thread safe.
 */
final class DayLogThrottle {
    static final long WINDOW_MS = 10L * 60 * 1000;
    static final int PER_WINDOW = 20;
    /** Different kinds held at most; a road name in a nav line makes each one new. */
    static final int MAX_KINDS = 400;
    private static final int KIND_CHARS = 90;

    private static final class Kind {
        long windowStart;
        int written;
        int left;
    }

    private final Map<String, Kind> kinds = new HashMap<>();

    /**
     * The text to write for a DEBUG or VERBOSE line at {@code now}, or null to leave it out. A
     * line that follows left-out lines of its kind carries their count.
     */
    String admit(String message, long now) {
        String key = kindOf(message);
        Kind k = kinds.get(key);
        if (k == null) {
            if (kinds.size() >= MAX_KINDS) {
                forgetIdle(now);
            }
            k = new Kind();
            k.windowStart = now;
            kinds.put(key, k);
        } else if (now - k.windowStart >= WINDOW_MS || now < k.windowStart) {
            k.windowStart = now;
            k.written = 0;
        }
        if (k.written >= PER_WINDOW) {
            k.left++;
            return null;
        }
        k.written++;
        if (k.left == 0) {
            return message;
        }
        String text = message + "  (+" + k.left + " like this left out)";
        k.left = 0;
        return text;
    }

    /** Drops the kinds with nothing pending whose window is over; all of them if that is not enough. */
    private void forgetIdle(long now) {
        java.util.Iterator<Kind> it = kinds.values().iterator();
        while (it.hasNext()) {
            Kind k = it.next();
            if (k.left == 0 && now - k.windowStart >= WINDOW_MS) {
                it.remove();
            }
        }
        if (kinds.size() >= MAX_KINDS) {
            kinds.clear();
        }
    }

    /** "[76] AapSslContext.decrypt | SSL Decrypt: produced 842, consumed 871 bytes" -> "AapSslContext.decrypt | SSL Decrypt: produced #, consumed # bytes". */
    static String kindOf(String message) {
        int start = 0;
        if (message.startsWith("[")) {
            int close = message.indexOf("] ");
            if (close > 0) {
                start = close + 2;
            }
        }
        StringBuilder b = new StringBuilder(KIND_CHARS);
        boolean inNumber = false;
        for (int i = start; i < message.length() && b.length() < KIND_CHARS; i++) {
            char c = message.charAt(i);
            if (c >= '0' && c <= '9') {
                if (!inNumber) {
                    b.append('#');
                    inNumber = true;
                }
            } else {
                b.append(c);
                inNumber = false;
            }
        }
        return b.toString();
    }
}
