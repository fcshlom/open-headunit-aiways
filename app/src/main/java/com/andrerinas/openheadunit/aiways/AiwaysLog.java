package com.andrerinas.openheadunit.aiways;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * Day logs for the backend, like the launcher's: one file per day,
 * Android/data/com.aiways.headunit/files/logs/headunit-YYYY-MM-DD.log, every line AppLog lets
 * through (its level, INFO by default) plus this package's own events. Uploaded by
 * {@link AiwaysSyncService}; the launcher repo's car-logs job copies them to GitHub.
 * Kept 7 days. AppLog's lines stop at {@link #MAX_TEE_BYTES} a day: the projection's own debug lines (an ack per
 * video frame) filled that by the first drive of the day, and after it this package's events were lost too, the
 * turn card snapshots of 23 November among them (4.0.0-aiways.13). This package's own events go on up to
 * {@link #MAX_BYTES_PER_DAY} (the backend takes 20 MB a day file). Writes happen on their own thread.
 * AppLog's DEBUG and VERBOSE lines pass a {@link DayLogThrottle} first, so the per-frame ones no longer use up
 * the tee's share before the day's session ends are written (4.0.0-aiways.16).
 */
public final class AiwaysLog {
    private static final int KEEP_DAYS = 7;
    /** AppLog's lines (the tee), per day. */
    private static final long MAX_TEE_BYTES = 5L * 1024 * 1024;
    /** This package's own events, per day: room after the tee is full. */
    private static final long MAX_BYTES_PER_DAY = 15L * 1024 * 1024;
    private static volatile File dir;
    private static volatile Handler writer;
    /** Called on the writer thread; synchronized itself as well. */
    private static final DayLogThrottle throttle = new DayLogThrottle();

    private AiwaysLog() {
    }

    public static synchronized void init(Context context) {
        if (writer != null) {
            return;
        }
        File base = context.getExternalFilesDir("logs");
        if (base == null) {
            base = new File(context.getFilesDir(), "logs");
        }
        //noinspection ResultOfMethodCallIgnored
        base.mkdirs();
        dir = base;
        HandlerThread t = new HandlerThread("aiways-log");
        t.start();
        writer = new Handler(t.getLooper());
        prune();
        String version = "?";
        try {
            version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        event("start", context.getPackageName() + " " + version + " | Android " + Build.VERSION.RELEASE + " | " + Build.MODEL);
    }

    static File dir() {
        return dir;
    }

    /** One of this package's own events. */
    public static void event(String tag, String message) {
        write("[" + tag + "] " + message, MAX_BYTES_PER_DAY);
    }

    /**
     * Tee from AppLog: every line it writes, with its priority letter. DEBUG and VERBOSE lines that repeat (per
     * video frame, per GPS fix) are thinned out by {@link DayLogThrottle}.
     */
    public static void line(int priority, final String message) {
        if (priority >= Log.INFO) {
            write(level(priority) + " " + message, MAX_TEE_BYTES);
            return;
        }
        final Handler h = writer;
        if (h == null) {
            return;
        }
        final long now = AiwaysConfig.now();
        final String letter = level(priority);
        h.post(new Runnable() {
            @Override
            public void run() {
                String admitted = throttle.admit(message, now);
                if (admitted != null) {
                    append(now, letter + " " + admitted, MAX_TEE_BYTES);
                }
            }
        });
    }

    private static String level(int priority) {
        switch (priority) {
            case Log.VERBOSE: return "V";
            case Log.DEBUG: return "D";
            case Log.INFO: return "I";
            case Log.WARN: return "W";
            case Log.ERROR: return "E";
            default: return "A";
        }
    }

    private static void write(final String text, final long cap) {
        final Handler h = writer;
        if (h == null) {
            return;
        }
        final long now = AiwaysConfig.now();
        h.post(new Runnable() {
            @Override
            public void run() {
                append(now, text, cap);
            }
        });
    }

    private static void append(long now, String text, long cap) {
        File f = new File(dir, "headunit-" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(now)) + ".log");
        if (f.length() > cap) {
            return;
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f, true);
            out.write((new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(now)) + " " + text + "\n").getBytes("UTF-8"));
        } catch (Exception ignored) {
            // storage gone: nothing to do
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Day files written before the launcher sent the T-Box time carry the head unit's wrong date
     * (2019-...) and would never be uploaded. Once the real time is known, their lines are moved
     * into today's file, behind a marker, and the old files deleted. Runs on the writer thread, so
     * it cannot interleave with new lines.
     */
    static void adoptStaleFiles() {
        final Handler h = writer;
        if (h == null) {
            return;
        }
        h.post(new Runnable() {
            @Override
            public void run() {
                File[] files = dir.listFiles();
                if (files == null) {
                    return;
                }
                Arrays.sort(files);
                long now = AiwaysConfig.now();
                for (File f : files) {
                    if (!f.getName().matches("headunit-(19|20[01]|202[0-3])\\d*-\\d{2}-\\d{2}\\.log")) {
                        continue;
                    }
                    try {
                        append(now, "[log] --- lines from " + f.getName() + ", written before the T-Box time was known ---", MAX_BYTES_PER_DAY);
                        File today = new File(dir, "headunit-" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(now)) + ".log");
                        // copied in chunks, whole: a stale day file can hold up to MAX_BYTES_PER_DAY of own events
                        // now, more than is wise to read into memory at once on the head unit
                        java.io.FileInputStream in = new java.io.FileInputStream(f);
                        try {
                            FileOutputStream out = new FileOutputStream(today, true);
                            try {
                                byte[] buf = new byte[64 * 1024];
                                long left = Math.min(f.length(), MAX_BYTES_PER_DAY);
                                int n;
                                while (left > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, left))) > 0) {
                                    out.write(buf, 0, n);
                                    left -= n;
                                }
                            } finally {
                                out.close();
                            }
                        } finally {
                            in.close();
                        }
                        append(now, "[log] --- end of " + f.getName() + " ---", MAX_BYTES_PER_DAY);
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    } catch (Exception ignored) {
                        // try again on the next round
                    }
                }
            }
        });
    }

    /** Deletes the oldest day files beyond KEEP_DAYS (by name: the clock can be off). */
    private static void prune() {
        writer.post(new Runnable() {
            @Override
            public void run() {
                File[] files = dir.listFiles();
                if (files == null) {
                    return;
                }
                Arrays.sort(files);
                for (int i = 0; i < files.length - KEEP_DAYS; i++) {
                    //noinspection ResultOfMethodCallIgnored
                    files[i].delete();
                }
            }
        });
    }
}
