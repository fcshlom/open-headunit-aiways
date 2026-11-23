package com.andrerinas.openheadunit.aiways;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Base64;
import android.view.TextureView;
import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Snapshots of the Android Auto screen while a nav app guides, to find where the nav app draws its turn card
 * (step 1 of showing that card on the driver's display; 4.0.0-aiways.12).
 *
 * Why: Waze sends the car only "enter roundabout" and one picture (no exit number, no lanes, no angle: every turn
 * in the launcher's log of 22 November), and that picture showed a left-exit roundabout with its exit on the right,
 * while Waze's own card on the Android Auto screen showed it right, with the lanes too. The launcher's driver's
 * display can show that card itself once its place on the screen is known: these snapshots show it.
 *
 * A snapshot is the projection's TextureView scaled to {@link #WIDTH} pixels wide, JPEG, written to the log as
 * {@code [turncard] frame WxH event=<n> in <m> m jpeg=<base64>}. Taken when the next maneuver changes, and at a
 * roundabout also when it comes within 800, 300 and 100 m and at 0 m (inside), at most {@link #MAX_PER_DAY} a day:
 * a frame shows the map, so where the car is, and the logs are copied to the (private) car-logs branch.
 * Only with the TextureView projection (the Aiways default); the others draw where a view cannot read back.
 */
public final class AiwaysTurnCard {
    static final int WIDTH = 960;
    static final int MAX_PER_DAY = 40;
    private static final int JPEG_QUALITY = 60;
    // NextTurnDetail.NextEvent: the roundabout events
    private static final int EV_ROUNDABOUT_ENTER = 11;
    private static final int EV_ROUNDABOUT_EXIT = 12;
    private static final int EV_ROUNDABOUT_BOTH = 13;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile WeakReference<TextureView> view = new WeakReference<>(null);
    private static Handler worker;
    private static String lastKey = "";
    private static String day = "";
    private static int shotsToday;

    private AiwaysTurnCard() {
    }

    /** The projection's TextureView (null when the projection uses another kind of view). */
    public static void attach(TextureView v) {
        view = new WeakReference<>(v);
    }

    /** From the navigation broadcast: every update of the next maneuver. */
    public static synchronized void onGuidance(final int event, final Integer distanceMeters) {
        if (event < 0) {
            lastKey = "";
            return;
        }
        boolean roundabout = event == EV_ROUNDABOUT_ENTER || event == EV_ROUNDABOUT_EXIT || event == EV_ROUNDABOUT_BOTH;
        int d = distanceMeters != null ? distanceMeters : -1;
        int band = !roundabout || d < 0 || d > 800 ? 0 : d > 300 ? 1 : d > 100 ? 2 : d > 0 ? 3 : 4;
        String key = event + "|" + band;
        if (key.equals(lastKey)) {
            return;
        }
        lastKey = key;
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        if (!today.equals(day)) {
            day = today;
            shotsToday = 0;
        }
        if (shotsToday >= MAX_PER_DAY) {
            return;
        }
        shotsToday++;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                snap(event, d);
            }
        });
    }

    /** On the main thread (TextureView.getBitmap must be); the JPEG is made on a worker thread. */
    private static void snap(final int event, final int distance) {
        TextureView v = view.get();
        if (v == null || !v.isAvailable() || v.getWidth() <= 0 || v.getHeight() <= 0) {
            return;
        }
        final Bitmap b;
        try {
            int h = Math.max(1, Math.round(WIDTH * v.getHeight() / (float) v.getWidth()));
            b = v.getBitmap(WIDTH, h);
        } catch (RuntimeException | OutOfMemoryError e) {
            AiwaysLog.event("turncard", "cannot read the screen: " + e);
            return;
        }
        if (b == null) {
            return;
        }
        worker().post(new Runnable() {
            @Override
            public void run() {
                try {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    b.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
                    AiwaysLog.event("turncard", "frame " + b.getWidth() + "x" + b.getHeight() + " event=" + event + " in "
                            + distance + " m jpeg=" + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
                } catch (RuntimeException | OutOfMemoryError e) {
                    AiwaysLog.event("turncard", "cannot encode the snapshot: " + e);
                } finally {
                    b.recycle();
                }
            }
        });
    }

    private static synchronized Handler worker() {
        if (worker == null) {
            HandlerThread t = new HandlerThread("aiways-turncard");
            t.start();
            worker = new Handler(t.getLooper());
        }
        return worker;
    }
}
