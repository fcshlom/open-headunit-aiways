package com.andrerinas.openheadunit.aiways;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.TextureView;
import java.io.ByteArrayOutputStream;

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
 * run there; it reuses one full-size bitmap). A crop counts only when it looks like the card's icon: a dark border
 * all round (the card) and a share of bright pixels inside (the white icon); Waze can shrink the card for an alert,
 * and then nothing is sent. Sent as {@link #ACTION} with the PNG in {@link #EXTRA_ICON}, under the same signature
 * permission as the navigation broadcast. Logged as [turnicon] when a roundabout's icon is first found, or not.
 */
public final class AiwaysTurnIcon {
    public static final String ACTION = "com.aiways.headunit.TURN_ICON";
    public static final String EXTRA_ICON = "icon";
    public static final String EXTRA_EVENT = "event";
    private static final String PERMISSION = "com.andrerinas.headunitrevived.permission.NAVIGATION_UPDATE";
    private static final long PERIOD_MS = 1000L;
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
    private static volatile int event = -1;
    // main thread only
    private static boolean running;
    private static Bitmap full;
    private static boolean loggedThisRoundabout;

    private AiwaysTurnIcon() {
    }

    /** From the navigation broadcast: every update of the next maneuver (-1 when the guidance ends). */
    public static void onGuidance(int nextEvent) {
        event = nextEvent;
        if (isRoundabout(nextEvent)) {
            MAIN.post(START);
        }
    }

    static boolean isRoundabout(int e) {
        return e == EV_ROUNDABOUT_ENTER || e == EV_ROUNDABOUT_EXIT || e == EV_ROUNDABOUT_BOTH;
    }

    private static final Runnable START = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                running = true;
                loggedThisRoundabout = false;
                MAIN.post(TICK);
            }
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

    private static void crop(int e) {
        TextureView v = AiwaysTurnCard.view();
        if (v == null || !v.isAvailable() || v.getWidth() <= 0 || v.getHeight() <= 0) {
            return;
        }
        Bitmap icon = null;
        try {
            int w = v.getWidth();
            int h = v.getHeight();
            if (full == null || full.getWidth() != w || full.getHeight() != h) {
                if (full != null) {
                    full.recycle();
                }
                full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
            v.getBitmap(full);
            int x0 = Math.round(LEFT * w);
            int y0 = Math.round(TOP * h);
            int cw = Math.max(1, Math.round((RIGHT - LEFT) * w));
            int ch = Math.max(1, Math.round((BOTTOM - TOP) * h));
            icon = Bitmap.createBitmap(full, x0, y0, cw, ch);
            String why = check(icon);
            if (why != null) {
                if (!loggedThisRoundabout) {
                    loggedThisRoundabout = true;
                    AiwaysLog.event("turnicon", "no icon on the screen at event " + e + ": " + why);
                }
                return;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            icon.compress(Bitmap.CompressFormat.PNG, 100, out);
            Context c = v.getContext().getApplicationContext();
            c.sendBroadcast(new Intent(ACTION).putExtra(EXTRA_ICON, out.toByteArray()).putExtra(EXTRA_EVENT, e), PERMISSION);
            if (!loggedThisRoundabout) {
                loggedThisRoundabout = true;
                AiwaysLog.event("turnicon", "icon " + cw + "x" + ch + " sent at event " + e);
            }
        } catch (RuntimeException | OutOfMemoryError ex) {
            if (!loggedThisRoundabout) {
                loggedThisRoundabout = true;
                AiwaysLog.event("turnicon", "cannot crop the screen: " + ex);
            }
        } finally {
            if (icon != null && icon != full) {
                icon.recycle();
            }
        }
    }

    /** Null when the crop looks like the card's icon (dark border, white shape inside), else why not. */
    static String check(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();
        long border = 0;
        int borderN = 0;
        int bright = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = b.getPixel(x, y);
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
