package com.andrerinas.openheadunit.aiways;

/**
 * Reads a roundabout's exit direction from the nav app's own turn icon, cut from the Android Auto screen
 * (4.0.0-aiways.15). Pure Java on brightness values, so it runs in plain unit tests.
 *
 * Waze draws a roundabout as a ring with the entry as a stem from below and the exit as an arrow leaving the ring
 * in the exit's direction (the snapshots of 23 November: left, right and straight on). The icon is only about
 * 34 x 28 pixels on the screen, too small to show enlarged, so the launcher draws its own icon at the angle read
 * here instead.
 *
 * How: the ring is the circle whose rim is brightest against a dark centre (a search over centre and radius). The
 * stem must be bright straight below it. The exit is the brightest ray outside the ring, away from the stem, and no
 * other ray may be bright: that rejects whatever else can be in that corner (another app's page, a notification, a
 * plain turn arrow). The angle is in degrees anticlockwise from the entry, as the launcher's icons take it
 * (right 90, straight 180, left 270), rounded to 45.
 */
final class AiwaysRoundaboutReader {
    /** The crop the thresholds were set on: 34 x 28 pixels (the screen read at 960 pixels wide). */
    static final int BASE_WIDTH = 34;
    static final int MIN_RING = 90;
    static final int MAX_INSIDE = 50;
    static final int MIN_STEM = 100;
    static final int MIN_EXIT = 160;
    static final int MAX_OTHER = 40;

    private static final int RING_POINTS = 24;
    private static final int INSIDE_POINTS = 8;
    private static final double[] RAY_STEPS = {1.5, 1.75, 2.0, 2.25};

    private AiwaysRoundaboutReader() {
    }

    /** What was read: the exit angle, or -1 and why not. */
    static final class Result {
        int ring;
        int inside;
        int stem;
        int exit;
        int other;
        int rawDeg = -1;
        int exitDeg = -1;
        String why;

        @Override
        public String toString() {
            return (exitDeg >= 0 ? "exit " + exitDeg + " (" + rawDeg + ")" : "no exit: " + why)
                    + " ring " + ring + " inside " + inside + " stem " + stem + " out " + exit + "/" + other;
        }
    }

    /** The brightness (0..255) of an ARGB pixel. */
    static int lum(int argb) {
        int r = (argb >> 16) & 0xff;
        int g = (argb >> 8) & 0xff;
        int b = argb & 0xff;
        return (r * 3 + g * 6 + b) / 10;
    }

    /** Reads [lum], w x h brightness values row by row. */
    static Result read(int[] lum, int w, int h) {
        Result res = new Result();
        double s = w / (double) BASE_WIDTH;
        double[] cosR = new double[RING_POINTS];
        double[] sinR = new double[RING_POINTS];
        for (int i = 0; i < RING_POINTS; i++) {
            double t = 2 * Math.PI * i / RING_POINTS;
            cosR[i] = Math.cos(t);
            sinR[i] = Math.sin(t);
        }
        double[] cosI = new double[INSIDE_POINTS];
        double[] sinI = new double[INSIDE_POINTS];
        for (int i = 0; i < INSIDE_POINTS; i++) {
            double t = 2 * Math.PI * i / INSIDE_POINTS;
            cosI[i] = Math.cos(t);
            sinI[i] = Math.sin(t);
        }
        // the ring: centre in half pixels, radius in tenths
        double best = -1e9;
        double bx = 0;
        double by = 0;
        double br = 0;
        double bRing = 0;
        double bInside = 0;
        int rMin = (int) (30 * s);
        int rMax = (int) (75 * s);
        for (int cx2 = (int) (6 * s * 2); cx2 < (int) (28 * s * 2); cx2++) {
            double cx = cx2 / 2.0;
            for (int cy2 = (int) (4 * s * 2); cy2 < (int) (20 * s * 2); cy2++) {
                double cy = cy2 / 2.0;
                for (int r10 = rMin; r10 <= rMax; r10 += 5) {
                    double r = r10 / 10.0;
                    double ring = 0;
                    for (int i = 0; i < RING_POINTS; i++) {
                        ring += at(lum, w, h, cx + r * cosR[i], cy + r * sinR[i]);
                    }
                    ring /= RING_POINTS;
                    double inside = at(lum, w, h, cx, cy);
                    for (int i = 0; i < INSIDE_POINTS; i++) {
                        inside += at(lum, w, h, cx + 0.4 * r * cosI[i], cy + 0.4 * r * sinI[i]);
                    }
                    inside /= INSIDE_POINTS + 1;
                    if (ring - inside > best) {
                        best = ring - inside;
                        bx = cx;
                        by = cy;
                        br = r;
                        bRing = ring;
                        bInside = inside;
                    }
                }
            }
        }
        res.ring = (int) Math.round(best);
        res.inside = (int) Math.round(bInside);
        // rays outside the ring, every 5 degrees (screen angles, y down: 90 is straight down, the entry)
        double[] ray = new double[72];
        for (int i = 0; i < 72; i++) {
            double t = Math.toRadians(i * 5);
            double v = 0;
            for (double k : RAY_STEPS) {
                v += at(lum, w, h, bx + k * br * Math.cos(t), by + k * br * Math.sin(t));
            }
            ray[i] = v / RAY_STEPS.length;
        }
        double stem = 0;
        int exitAt = -1;
        for (int i = 0; i < 72; i++) {
            int fromEntry = apart(i * 5, 90);
            if (fromEntry <= 20) {
                stem = Math.max(stem, ray[i]);
            }
            if (fromEntry > 35 && (exitAt < 0 || ray[i] > ray[exitAt])) {
                exitAt = i;
            }
        }
        double other = 0;
        for (int i = 0; i < 72; i++) {
            if (apart(i * 5, 90) > 35 && apart(i * 5, exitAt * 5) > 60) {
                other = Math.max(other, ray[i]);
            }
        }
        res.stem = (int) Math.round(stem);
        res.exit = (int) Math.round(ray[exitAt]);
        res.other = (int) Math.round(other);
        res.rawDeg = ((90 - exitAt * 5) % 360 + 360) % 360;
        if (res.ring < MIN_RING) {
            res.why = "no ring";
        } else if (res.inside > MAX_INSIDE) {
            res.why = "ring not dark inside";
        } else if (res.stem < MIN_STEM) {
            res.why = "no entry";
        } else if (res.exit < MIN_EXIT) {
            res.why = "no exit";
        } else if (res.other > MAX_OTHER) {
            res.why = "more than one exit";
        } else {
            int q = (int) (Math.round(res.rawDeg / 45.0) * 45) % 360;
            if (q == 0) {
                res.why = "exit at the entry";
            } else {
                res.exitDeg = q;
            }
        }
        return res;
    }

    /** The difference of two angles in degrees, 0..180. */
    private static int apart(int a, int b) {
        int d = Math.abs(a - b) % 360;
        return d > 180 ? 360 - d : d;
    }

    private static double at(int[] lum, int w, int h, double x, double y) {
        int xi = (int) Math.round(x);
        int yi = (int) Math.round(y);
        if (xi < 0 || yi < 0 || xi >= w || yi >= h) {
            return 0;
        }
        return lum[yi * w + xi];
    }
}
