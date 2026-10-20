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
 * Kept 7 days, at most 5 MB a day. Writes happen on their own thread.
 */
public final class AiwaysLog {
    private static final int KEEP_DAYS = 7;
    private static final long MAX_BYTES_PER_DAY = 5L * 1024 * 1024;
    private static volatile File dir;
    private static volatile Handler writer;

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
        write("[" + tag + "] " + message);
    }

    /** Tee from AppLog: every line it writes, with its priority letter. */
    public static void line(int priority, String message) {
        write(level(priority) + " " + message);
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

    private static void write(final String text) {
        final Handler h = writer;
        if (h == null) {
            return;
        }
        final long now = AiwaysConfig.now();
        h.post(new Runnable() {
            @Override
            public void run() {
                append(now, text);
            }
        });
    }

    private static void append(long now, String text) {
        File f = new File(dir, "headunit-" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(now)) + ".log");
        if (f.length() > MAX_BYTES_PER_DAY) {
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
                        byte[] body = new byte[(int) Math.min(f.length(), MAX_BYTES_PER_DAY)];
                        java.io.FileInputStream in = new java.io.FileInputStream(f);
                        try {
                            int off = 0;
                            int n;
                            while (off < body.length && (n = in.read(body, off, body.length - off)) > 0) {
                                off += n;
                            }
                        } finally {
                            in.close();
                        }
                        append(now, "[log] --- lines from " + f.getName() + ", written before the T-Box time was known ---");
                        File today = new File(dir, "headunit-" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(now)) + ".log");
                        FileOutputStream out = new FileOutputStream(today, true);
                        try {
                            out.write(body);
                        } finally {
                            out.close();
                        }
                        append(now, "[log] --- end of " + f.getName() + " ---");
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
