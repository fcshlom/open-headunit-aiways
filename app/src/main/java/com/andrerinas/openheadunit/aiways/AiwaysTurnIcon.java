package com.andrerinas.openheadunit.aiways;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A roundabout's exit direction read from the nav app's own turn icon on the Android Auto screen, for the launcher's
 * driver's display (4.0.0-aiways.14; since 4.0.0-aiways.15 the angle instead of the picture).
 *
 * Why: before a roundabout Waze sends the car only "enter roundabout" and one generic picture, the same for every
 * roundabout, while the icon on its own turn card on the Android Auto screen already shows the real exit (the
 * snapshots of AiwaysTurnCard). In 4.0.0-aiways.14 that icon itself was sent, but it is only about 34 x 28 pixels on
 * the screen and looked blurred on the driver's display, and when another app's page or a notification covered
 * the corner, that was sent instead (the drive of 23 November). Now {@link AiwaysRoundaboutReader} reads the exit's
 * angle from the icon, and the launcher draws its own sharp icon at that angle.
 *
 * Where: Waze's turn card is in the screen's top-left corner, right of Android Auto's rail; its icon, left of the
 * distance, sat at x 62..88, y 26..46 of the 960 x 330 snapshots (the TextureView scaled down). The crop is that box
 * with a margin, as fractions of the view, so it does not depend on the view's size.
 *
 * When: only while the next maneuver is a roundabout, once a second. The navigation callback only posts the event to
 * the main thread, which reads the screen (TextureView.getBitmap must run there) at half size into one reused
 * bitmap and copies the crop's pixels out; the reading and the broadcast run on a worker thread.
 *
 * Which angle: a read counts only when two reads in a row agree, and it is kept for the rest of that roundabout,
 * also while another app or a notification covers the corner. It is forgotten when the maneuver changes: when the
 * next maneuver is no roundabout, after the roundabout itself (event 12), or when the distance jumps up (the next
 * roundabout). While an angle is known it is sent every second as {@link #ACTION} with the angle in
 * {@link #EXTRA_EXIT} (degrees anticlockwise from the entry: right 90, straight 180, left 270), under the same
 * signature permission as the navigation broadcast. Logged as [turnicon] when the angle is found or changes, and
 * once per roundabout when the screen shows none.
 */
public final class AiwaysTurnIcon {
    public static final String ACTION = "com.aiways.headunit.TURN_ICON";
    public static final String EXTRA_EXIT = "exit_deg";
    public static final String EXTRA_EVENT = "event";
    private static final String PERMISSION = "com.andrerinas.headunitrevived.permission.NAVIGATION_UPDATE";
    private static final long PERIOD_MS = 1000L;
    /** A distance this much larger than the last one is the next roundabout. */
    private static final int NEW_ROUNDABOUT_M = 150;
    /** The view is read at this fraction of its size. */
    private static final float SCALE = 0.5f;
    // the icon's box as fractions of the view (from x 58..92, y 22..50 of 960 x 330)
    private static final float LEFT = 58f / 960f;
    private static final float TOP = 22f / 330f;
    private static final float RIGHT = 92f / 960f;
    private static final float BOTTOM = 50f / 330f;
    // NextTurnDetail.NextEvent: the roundabout events
    private static final int EV_ROUNDABOUT_ENTER = 11;
    private static final int EV_ROUNDABOUT_EXIT = 12;
    private static final int EV_ROUNDABOUT_BOTH = 13;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** The crop's pixel array, handed back by the worker after a read, so the next tick reuses it. */
    private static final AtomicReference<int[]> SPARE = new AtomicReference<>();
    // main thread only
    private static int event = -1;
    private static int distance = -1;
    private static boolean running;
    private static Bitmap full;
    private static Handler worker;
    private static boolean loggedError;
    // worker thread only
    private static int candidate = -1;
    private static int confirmed = -1;
    private static boolean loggedNone;

    private AiwaysTurnIcon() {
    }

    /**
     * From the navigation broadcast, on any thread: every update of the next maneuver (-1 when the guidance ends).
     * It only hands the event to the main thread; nothing is read from the screen here.
     */
    public static void onGuidance(final int nextEvent, final Integer distanceMeters) {
        final int d = distanceMeters != null ? distanceMeters : -1;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                update(nextEvent, d);
            }
        });
    }

    static boolean isRoundabout(int e) {
        return e == EV_ROUNDABOUT_ENTER || e == EV_ROUNDABOUT_EXIT || e == EV_ROUNDABOUT_BOTH;
    }

    /** On the main thread: forgets the angle when the maneuver changes, and starts the ticker at a roundabout. */
    private static void update(int e, int d) {
        boolean next = !isRoundabout(e)
                || (event == EV_ROUNDABOUT_EXIT && e != EV_ROUNDABOUT_EXIT)
                || (d >= 0 && distance >= 0 && d > distance + NEW_ROUNDABOUT_M);
        if (next && worker != null) {
            worker.post(RESET);
        }
        event = e;
        distance = d;
        if (isRoundabout(e) && !running) {
            running = true;
            loggedError = false;
            MAIN.post(TICK);
        }
    }

    private static final Runnable RESET = new Runnable() {
        @Override
        public void run() {
            candidate = -1;
            confirmed = -1;
            loggedNone = false;
        }
    };

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            int e = event;
            if (!isRoundabout(e)) {
                running = false;
                if (full != null) {
                    full.recycle();
                    full = null;
                }
                return;
            }
            crop(e);
            MAIN.postDelayed(this, PERIOD_MS);
        }
    };

    /** On the main thread: reads the screen and hands the crop's pixels to the worker. */
    private static void crop(final int e) {
        try {
            int[] size = AiwaysTurnCard.viewSize();
            if (size == null) {
                return;
            }
            int w = Math.max(1, Math.round(size[0] * SCALE));
            int h = Math.max(1, Math.round(size[1] * SCALE));
            if (full == null || full.getWidth() != w || full.getHeight() != h) {
                if (full != null) {
                    full.recycle();
                }
                full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
            final Context c = AiwaysTurnCard.readScreen(full);
            if (c == null) {
                return;
            }
            int x0 = Math.round(LEFT * w);
            int y0 = Math.round(TOP * h);
            final int cw = Math.max(1, Math.min(w - x0, Math.round((RIGHT - LEFT) * w)));
            final int ch = Math.max(1, Math.min(h - y0, Math.round((BOTTOM - TOP) * h)));
            int[] spare = SPARE.getAndSet(null);
            final int[] pixels = spare != null && spare.length == cw * ch ? spare : new int[cw * ch];
            full.getPixels(pixels, 0, cw, x0, y0, cw, ch);
            worker().post(new Runnable() {
                @Override
                public void run() {
                    read(c, pixels, cw, ch, e);
                }
            });
        } catch (RuntimeException | OutOfMemoryError ex) {
            if (!loggedError) {
                loggedError = true;
                AiwaysLog.event("turnicon", "cannot read the screen: " + ex);
            }
        }
    }

    /** On the worker: reads the exit, confirms it on two reads in a row, and sends the confirmed one. */
    private static void read(Context c, int[] pixels, int cw, int ch, int e) {
        try {
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = AiwaysRoundaboutReader.lum(pixels[i]);
            }
            AiwaysRoundaboutReader.Result r = AiwaysRoundaboutReader.read(pixels, cw, ch);
            if (r.exitDeg >= 0) {
                if (r.exitDeg == candidate && r.exitDeg != confirmed) {
                    confirmed = r.exitDeg;
                    AiwaysLog.event("turnicon", "event " + e + ": " + r);
                }
                candidate = r.exitDeg;
            } else {
                candidate = -1;
                if (confirmed < 0 && !loggedNone) {
                    loggedNone = true;
                    AiwaysLog.event("turnicon", "event " + e + ": " + r);
                }
            }
            if (confirmed >= 0) {
                c.sendBroadcast(new Intent(ACTION).putExtra(EXTRA_EXIT, confirmed).putExtra(EXTRA_EVENT, e),
                        PERMISSION);
            }
        } catch (RuntimeException ex) {
            AiwaysLog.event("turnicon", "cannot read the exit: " + ex);
        } finally {
            SPARE.set(pixels);
        }
    }

    private static Handler worker() {
        if (worker == null) {
            HandlerThread t = new HandlerThread("aiways-turnicon");
            t.start();
            worker = new Handler(t.getLooper());
        }
        return worker;
    }
}
