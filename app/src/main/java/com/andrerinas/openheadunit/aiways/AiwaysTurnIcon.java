package com.andrerinas.openheadunit.aiways;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * The nav app's own roundabout icon, cut from the Android Auto screen for the launcher's driver's display
 * (4.0.0-aiways.14).
 *
 * Why: before a roundabout Waze sends the car only "enter roundabout" and one generic picture, the same for every
 * roundabout (three roundabouts of 23 November, one picture), while the icon on its own turn card on the Android
 * Auto screen already shows the real exit (the snapshots of AiwaysTurnCard: an exit to the left at the first two,
 * to the right at the third, from 1.7 km and 450 m on). The real exit picture reaches the car only inside the
 * roundabout.
 *
 * Where: Waze's turn card is in the screen's top-left corner, right of Android Auto's rail; its icon, left of the
 * distance, sat at x 62..88, y 26..46 of the 960 x 330 snapshots (the TextureView scaled down), i.e. about
 * 52 x 40 pixels on the screen. The crop is that box with a margin, as fractions of the view, so it does not
 * depend on the view's size.
 *
 * When: only while the next maneuver is a roundabout, once a second on the main thread (TextureView.getBitmap must
 * run there). The navigation callback only posts the new event to the main thread, so all of this class's state
 * lives on the main thread; the PNG is compressed and broadcast on a worker thread. The view is read at half its size into one reused bitmap ({@link #SCALE}): a quarter of the work of a
 * full-size copy on the head unit's main thread, and the size of the snapshots the crop was checked on, where the
 * icons are clear. The crop's pixels are checked in a reused array; an icon is
 * compressed only when it differs from the last one sent, and an unchanged one is sent again only every
 * {@link #RESEND_MS}, inside the launcher's 3 s freshness window. A crop counts only when it looks like the card's
 * icon: a dark border all round (the card) and a share of bright pixels inside (the white icon); Waze can shrink the card for an alert,
 * and then nothing is sent. Sent as {@link #ACTION} with the PNG in {@link #EXTRA_ICON}, under the same signature
 * permission as the navigation broadcast. Logged as [turnicon] when a roundabout's icon is first found, or not.
 */
public final class AiwaysTurnIcon {
    public static final String ACTION = "com.aiways.headunit.TURN_ICON";
    public static final String EXTRA_ICON = "icon";
    public static final String EXTRA_EVENT = "event";
    private static final String PERMISSION = "com.andrerinas.headunitrevived.permission.NAVIGATION_UPDATE";
    private static final long PERIOD_MS = 1000L;
    /** An unchanged icon is sent again after this long: the launcher drops one older than 3 s. */
    private static final long RESEND_MS = 2000L;
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
    // main thread only
    private static int event = -1;
    private static boolean running;
    private static Bitmap full;
    /** The crop's pixels (reused), and those of the icon last sent: an unchanged icon is not compressed again. */
    private static int[] pixels;
    private static int[] sent;
    private static boolean haveSent;
    private static long sentAt;
    private static boolean loggedThisRoundabout;
    private static Handler worker;
    // worker thread only: the last icon compressed
    private static byte[] png;

    private AiwaysTurnIcon() {
    }

    /**
     * From the navigation broadcast, on any thread: every update of the next maneuver (-1 when the guidance ends).
     * It only hands the event to the main thread; nothing is read from the screen here.
     */
    public static void onGuidance(final int nextEvent) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                update(nextEvent);
            }
        });
    }

    static boolean isRoundabout(int e) {
        return e == EV_ROUNDABOUT_ENTER || e == EV_ROUNDABOUT_EXIT || e == EV_ROUNDABOUT_BOTH;
    }

    /** On the main thread: a roundabout starts the ticker, if it is not running yet. */
    private static void update(int e) {
        event = e;
        if (isRoundabout(e) && !running) {
            running = true;
            loggedThisRoundabout = false;
            haveSent = false;
            MAIN.post(TICK);
        }
    }

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
                pixels = null;
                sent = null;
                haveSent = false;
                return;
            }
            crop(e);
            MAIN.postDelayed(this, PERIOD_MS);
        }
    };

    /** On the main thread: reads the screen, checks the crop, and hands a new or due icon to the worker. */
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
            if (pixels == null || pixels.length != cw * ch) {
                pixels = new int[cw * ch];
                sent = new int[cw * ch];
                haveSent = false;
            }
            full.getPixels(pixels, 0, cw, x0, y0, cw, ch);
            String why = check(pixels, cw, ch);
            if (why != null) {
                if (!loggedThisRoundabout) {
                    loggedThisRoundabout = true;
                    AiwaysLog.event("turnicon", "no icon on the screen at event " + e + ": " + why);
                }
                return;
            }
            long now = SystemClock.uptimeMillis();
            boolean same = haveSent && Arrays.equals(pixels, sent);
            if (same && now - sentAt < RESEND_MS) {
                return;
            }
            final int[] fresh;
            if (same) {
                fresh = null;
            } else {
                System.arraycopy(pixels, 0, sent, 0, pixels.length);
                haveSent = true;
                fresh = sent.clone();
            }
            sentAt = now;
            final boolean log = !loggedThisRoundabout;
            loggedThisRoundabout = true;
            worker().post(new Runnable() {
                @Override
                public void run() {
                    send(c, fresh, cw, ch, e, log);
                }
            });
        } catch (RuntimeException | OutOfMemoryError ex) {
            if (!loggedThisRoundabout) {
                loggedThisRoundabout = true;
                AiwaysLog.event("turnicon", "cannot crop the screen: " + ex);
            }
        }
    }

    /** On the worker: compresses a new icon (fresh != null) and broadcasts the last one compressed. */
    private static void send(Context c, int[] fresh, int cw, int ch, int e, boolean log) {
        try {
            if (fresh != null) {
                Bitmap icon = Bitmap.createBitmap(fresh, cw, ch, Bitmap.Config.ARGB_8888);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                icon.compress(Bitmap.CompressFormat.PNG, 100, out);
                icon.recycle();
                png = out.toByteArray();
            }
            if (png == null) {
                return;
            }
            c.sendBroadcast(new Intent(ACTION).putExtra(EXTRA_ICON, png).putExtra(EXTRA_EVENT, e), PERMISSION);
            if (log) {
                AiwaysLog.event("turnicon", "icon " + cw + "x" + ch + " sent at event " + e);
            }
        } catch (RuntimeException | OutOfMemoryError ex) {
            if (log) {
                AiwaysLog.event("turnicon", "cannot send the icon: " + ex);
            }
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

    /** Null when the crop looks like the card's icon (dark border, white shape inside), else why not. */
    static String check(int[] px, int w, int h) {
        long border = 0;
        int borderN = 0;
        int bright = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = px[y * w + x];
                int lum = (Color.red(p) * 3 + Color.green(p) * 6 + Color.blue(p)) / 10;
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) {
                    border += lum;
                    borderN++;
                } else if (lum > 170) {
                    bright++;
                }
            }
        }
        int inner = Math.max(1, (w - 2) * (h - 2));
        long borderMean = borderN > 0 ? border / borderN : 255;
        int brightPct = bright * 100 / inner;
        if (borderMean > 70) {
            return "border not dark (" + borderMean + ")";
        }
        if (brightPct < 2 || brightPct > 50) {
            return "bright share " + brightPct + "%";
        }
        return null;
    }
}
