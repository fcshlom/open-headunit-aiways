package com.andrerinas.openheadunit.aiways;

import android.content.Context;
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
 * {@code [turncard] frame WxH event=<n> in <m> m jpeg=<base64>}. Taken when the next maneuver changes, and for every
 * maneuver also when it comes within 300 and 100 m and at 0 m, where the lanes and junction details show
 * (4.0.0-aiways.13; before, only roundabouts were snapped that close); a roundabout also at 800 m. At most
 * {@link #MAX_PER_DAY} a day:
 * a frame shows the map, so where the car is, and the logs are copied to the (private) car-logs branch.
 * Only with the TextureView projection (the Aiways default); the others draw where a view cannot read back.
 */
public final class AiwaysTurnCard {
    static final int WIDTH = 960;
    static final int MAX_PER_DAY = 80;
    private static final int JPEG_QUALITY = 60;
    // NextTurnDetail.NextEvent: the roundabout events
    private static final int EV_ROUNDABOUT_ENTER = 11;
    private static final int EV_ROUNDABOUT_EXIT = 12;
    private static final int EV_ROUNDABOUT_BOTH = 13;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Guards lastKey, day, shotsToday and worker: the navigation thread decides, the main thread snaps. */
    private static final Object LOCK = new Object();
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

    /** Main thread only: the projection view's size {width, height}, or null when there is no readable view. */
    static int[] viewSize() {
        TextureView v = view.get();
        if (v == null || !v.isAvailable() || v.getWidth() <= 0 || v.getHeight() <= 0) {
            return null;
        }
        return new int[] {v.getWidth(), v.getHeight()};
    }

    /**
     * Main thread only (TextureView.getBitmap must run there): copies the projection into {@code into}, scaled to
     * its size, for AiwaysTurnIcon. Returns the application context for its broadcast, or null when there is no
     * readable view. The view itself stays private to this class.
     */
    static Context readScreen(Bitmap into) {
        TextureView v = view.get();
        if (v == null || !v.isAvailable()) {
            return null;
        }
        v.getBitmap(into);
        return v.getContext().getApplicationContext();
    }

    /** From the navigation broadcast: every update of the next maneuver. */
    public static void onGuidance(final int event, final Integer distanceMeters) {
        final int d = distanceMeters != null ? distanceMeters : -1;
        synchronized (LOCK) {
            if (event < 0) {
                lastKey = "";
                return;
            }
            boolean roundabout = event == EV_ROUNDABOUT_ENTER || event == EV_ROUNDABOUT_EXIT || event == EV_ROUNDABOUT_BOTH;
            int band = band(d, roundabout);
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
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                snap(event, d);
            }
        });
    }

    /**
     * How close the maneuver is, as the snapshot points: 0 far away or unknown, 1 a roundabout within 800 m,
     * 2 within 300 m, 3 within 100 m, 4 at the turn. A new band (or a new maneuver) takes a snapshot.
     */
    static int band(int distance, boolean roundabout) {
        if (distance < 0 || distance > 800) {
            return 0;
        }
        if (distance > 300) {
            return roundabout ? 1 : 0;
        }
        if (distance > 100) {
            return 2;
        }
        if (distance > 0) {
            return 3;
        }
        return 4;
    }

    /**
     * On the main thread: TextureView.getBitmap must be called there, and the bitmap is compressed there too, so
     * it never crosses threads (getBitmap(int, int) allocates a new one; it is recycled right after). Only the JPEG
     * bytes go to the worker, which encodes them for the log.
     */
    private static void snap(final int event, final int distance) {
        TextureView v = view.get();
        if (v == null || !v.isAvailable() || v.getWidth() <= 0 || v.getHeight() <= 0) {
            return;
        }
        Bitmap b = null;
        final byte[] jpeg;
        final int w;
        final int h;
        try {
            int height = Math.max(1, Math.round(WIDTH * v.getHeight() / (float) v.getWidth()));
            b = v.getBitmap(WIDTH, height);
            if (b == null) {
                return;
            }
            w = b.getWidth();
            h = b.getHeight();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
            jpeg = out.toByteArray();
        } catch (RuntimeException | OutOfMemoryError e) {
            AiwaysLog.event("turncard", "cannot read the screen: " + e);
            return;
        } finally {
            if (b != null) {
                b.recycle();
            }
        }
        worker().post(new Runnable() {
            @Override
            public void run() {
                AiwaysLog.event("turncard", "frame " + w + "x" + h + " event=" + event + " in " + distance + " m jpeg="
                        + Base64.encodeToString(jpeg, Base64.NO_WRAP));
            }
        });
    }

    private static Handler worker() {
        synchronized (LOCK) {
            if (worker == null) {
                HandlerThread t = new HandlerThread("aiways-turncard");
                t.start();
                worker = new Handler(t.getLooper());
            }
            return worker;
        }
    }
}
