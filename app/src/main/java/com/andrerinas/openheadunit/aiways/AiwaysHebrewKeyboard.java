package com.andrerinas.openheadunit.aiways;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hebrew typing for Android Auto (prototype).
 *
 * Android Auto draws its keyboard on the phone and has no Hebrew layout there, and the projection
 * protocol cannot carry text: its input channel sends key codes, which the phone maps to Latin
 * letters. So this unit shows its own Hebrew keyboard over the projection and sends the finished
 * text to the phone it projects from, at the address the session dialled, TCP
 * {@link AiwaysHebrewKeys#PHONE_PORT}. There the aiwaysapp's accessibility service
 * ({@code CarTypingService}) writes it into the field that has focus.
 *
 * A small round button opens the panel; it can be dragged anywhere and remembers where it was put,
 * and a long press hides it until the projection screen opens again. Opening the panel asks the
 * phone what it can see ("probe") and writes the answer to the day log: whether the phone's
 * accessibility service reaches Android Auto's text fields at all is what this prototype finds out.
 * The typed text itself is never logged, only its length.
 *
 * Works only on a wireless session: on USB there is no phone address.
 */
public final class AiwaysHebrewKeyboard {

    /** Where the phone is: {@code CommManager.lastAttemptedEndpoint} ({@code ip:port}, null on USB). */
    public interface EndpointSource {
        String endpoint();
    }

    private static final String TAG = "hebrew";
    private static final String PREFS = "aiways_hebrew_keyboard";
    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int READ_TIMEOUT_MS = 6000;

    private final Activity activity;
    private final FrameLayout container;
    private final EndpointSource source;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Sends, one at a time. The probe runs on its own thread so a send never waits behind it. */
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService probeIo = Executors.newSingleThreadExecutor();
    /** A send is on its way: further taps on Send are ignored until it answers. */
    private volatile boolean sending;

    private TextView button;
    private LinearLayout panel;
    private TextView preview;
    private TextView status;
    private String text = "";

    private AiwaysHebrewKeyboard(Activity activity, FrameLayout container, EndpointSource source) {
        this.activity = activity;
        this.container = container;
        this.source = source;
    }

    /** Puts the button on the projection screen. Call once, after the touch overlay is added. */
    public static AiwaysHebrewKeyboard attach(Activity activity, FrameLayout container, EndpointSource source) {
        AiwaysHebrewKeyboard k = new AiwaysHebrewKeyboard(activity, container, source);
        k.addButton();
        return k;
    }

    /** Raise the button (and an open panel) above views added later. */
    public void bringToFront() {
        if (panel != null) {
            panel.bringToFront();
        } else if (button != null) {
            button.bringToFront();
        }
    }

    public void release() {
        io.shutdownNow();
        probeIo.shutdownNow();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                activity.getResources().getDisplayMetrics()));
    }

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
    }

    // ---- The button -------------------------------------------------------------------------

    private void addButton() {
        final TextView b = new TextView(activity);
        b.setText("עב");
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x99000000);
        bg.setStroke(dp(1), 0x66FFFFFF);
        b.setBackground(bg);
        int size = dp(52);
        b.setLayoutParams(new FrameLayout.LayoutParams(size, size));
        b.setOnTouchListener(new DragOrTap());
        container.addView(b);
        button = b;
        container.post(new Runnable() {
            @Override
            public void run() {
                placeButton(prefs().getFloat("x", 0.97f), prefs().getFloat("y", 0.12f));
            }
        });
    }

    /** x and y are fractions of the free space, so the place survives a size change. */
    private void placeButton(float fx, float fy) {
        int freeX = Math.max(0, container.getWidth() - button.getWidth());
        int freeY = Math.max(0, container.getHeight() - button.getHeight());
        button.setX(clamp(fx) * freeX);
        button.setY(clamp(fy) * freeY);
    }

    private static float clamp(float f) {
        return Math.max(0f, Math.min(1f, f));
    }

    /** Tap opens the panel, a drag moves the button, a long press hides it until next time. */
    private final class DragOrTap implements View.OnTouchListener {
        private float downX;
        private float downY;
        private float startX;
        private float startY;
        private boolean dragging;
        private boolean longPressed;
        private final Runnable longPress = new Runnable() {
            @Override
            public void run() {
                longPressed = true;
                button.setVisibility(View.GONE);
                AiwaysLog.event(TAG, "button hidden until the projection screen opens again");
            }
        };

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = v.getX();
                    startY = v.getY();
                    dragging = false;
                    longPressed = false;
                    main.postDelayed(longPress, 1200);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX;
                    float dy = e.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) > slop) {
                        dragging = true;
                        main.removeCallbacks(longPress);
                    }
                    if (dragging) {
                        int freeX = Math.max(1, container.getWidth() - v.getWidth());
                        int freeY = Math.max(1, container.getHeight() - v.getHeight());
                        v.setX(Math.max(0, Math.min(freeX, startX + dx)));
                        v.setY(Math.max(0, Math.min(freeY, startY + dy)));
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    main.removeCallbacks(longPress);
                    if (dragging) {
                        int freeX = Math.max(1, container.getWidth() - v.getWidth());
                        int freeY = Math.max(1, container.getHeight() - v.getHeight());
                        prefs().edit()
                                .putFloat("x", v.getX() / freeX)
                                .putFloat("y", v.getY() / freeY)
                                .apply();
                    } else if (!longPressed) {
                        openPanel();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    main.removeCallbacks(longPress);
                    return true;
                default:
                    return true;
            }
        }
    }

    // ---- The panel --------------------------------------------------------------------------

    private void openPanel() {
        if (panel != null) {
            return;
        }
        text = "";
        int height = Math.max(dp(240), Math.round(container.getHeight() * 0.62f));

        LinearLayout p = new LinearLayout(activity);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundColor(0xF2202124);
        p.setPadding(dp(6), dp(6), dp(6), dp(6));
        // Touches in the gaps between keys must not reach Android Auto below.
        p.setClickable(true);
        p.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, height, Gravity.BOTTOM));

        p.addView(topBar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.2f));
        for (String[] row : AiwaysHebrewKeys.ROWS) {
            p.addView(keyRow(row), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        }

        container.addView(p);
        p.bringToFront();
        panel = p;
        button.setVisibility(View.GONE);
        refresh();
        setStatus("בודק חיבור לטלפון…");
        probe();
    }

    private void closePanel() {
        if (panel == null) {
            return;
        }
        container.removeView(panel);
        panel = null;
        preview = null;
        status = null;
        button.setVisibility(View.VISIBLE);
        button.bringToFront();
    }

    private View topBar() {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        bar.addView(action("✕", 0xFF5F6368, new Runnable() {
            @Override
            public void run() {
                closePanel();
            }
        }), weighted(0.8f));

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        texts.setPadding(dp(12), 0, dp(12), 0);
        preview = new TextView(activity);
        preview.setTextColor(Color.WHITE);
        preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        preview.setSingleLine(true);
        preview.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        status = new TextView(activity);
        status.setTextColor(0xFFBDC1C6);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setSingleLine(true);
        status.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        texts.addView(preview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        texts.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        bar.addView(texts, weighted(6f));

        bar.addView(action("נקה", 0xFF5F6368, new Runnable() {
            @Override
            public void run() {
                text = "";
                refresh();
            }
        }), weighted(1f));
        bar.addView(action("שלח", 0xFF1A73E8, new Runnable() {
            @Override
            public void run() {
                send();
            }
        }), weighted(1.4f));
        return bar;
    }

    private View keyRow(String[] keys) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        // Hebrew rows read right to left: the first key of a row is its rightmost.
        for (int i = keys.length - 1; i >= 0; i--) {
            final String key = keys[i];
            String label = AiwaysHebrewKeys.SPACE.equals(key) ? "רווח"
                    : AiwaysHebrewKeys.BACKSPACE.equals(key) ? "⌫" : key;
            float weight = AiwaysHebrewKeys.SPACE.equals(key) ? 4f : 1f;
            row.addView(action(label, 0xFF3C4043, new Runnable() {
                @Override
                public void run() {
                    text = AiwaysHebrewKeys.press(text, key);
                    refresh();
                }
            }), weighted(weight));
        }
        return row;
    }

    private LinearLayout.LayoutParams weighted(float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        return lp;
    }

    private TextView action(String label, int color, final Runnable onTap) {
        TextView t = new TextView(activity);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        t.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(color);
        t.setBackground(bg);
        t.setClickable(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onTap.run();
            }
        });
        return t;
    }

    private void refresh() {
        if (preview != null) {
            preview.setText(text.isEmpty() ? "" : text);
            preview.setHint("הקלד כאן");
            preview.setHintTextColor(0xFF80868B);
        }
    }

    private void setStatus(final String s) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (status != null) {
                    status.setText(s);
                }
            }
        });
    }

    // ---- Talking to the phone ---------------------------------------------------------------

    private void probe() {
        final String host = AiwaysHebrewKeys.phoneHost(source.endpoint());
        if (host == null) {
            setStatus("אין כתובת לטלפון (חיבור USB?). עובד רק בחיבור אלחוטי.");
            AiwaysLog.event(TAG, "probe: no phone address (endpoint " + source.endpoint() + ")");
            return;
        }
        probeIo.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject reply = request(host, new JSONObject().put("op", "probe"));
                    AiwaysLog.event(TAG, "probe " + host + ": " + reply);
                    String projection = reply.optString("projection");
                    setStatus(projection.startsWith("yes")
                            ? "הקש קודם על שדה הטקסט ב-Android Auto, ואז הקלד ושלח."
                            : "הטלפון עונה, אבל לא מזהה ש-Android Auto פעיל (" + projection + ")");
                } catch (Exception e) {
                    AiwaysLog.event(TAG, "probe " + host + " failed: " + e);
                    setStatus("אפליקציית Aiways בטלפון לא עונה. בטלפון: הגדרות ← נגישות ← "
                            + "Aiways: הקלדה בעברית");
                }
            }
        });
    }

    private void send() {
        final String typed = text;
        if (typed.isEmpty() || sending) {
            return;
        }
        final String host = AiwaysHebrewKeys.phoneHost(source.endpoint());
        if (host == null) {
            setStatus("אין כתובת לטלפון (חיבור USB?). עובד רק בחיבור אלחוטי.");
            return;
        }
        setStatus("שולח…");
        sending = true;
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject reply = request(host, new JSONObject()
                            .put("op", "type")
                            .put("mode", "replace")
                            .put("text", typed));
                    AiwaysLog.event(TAG, "sent " + typed.length() + " characters to " + host + ": " + reply);
                    if (reply.optBoolean("ok")) {
                        setStatus("נשלח ✓");
                        main.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                closePanel();
                            }
                        }, 700);
                    } else {
                        setStatus("לא הוכנס: " + reply.optString("detail"));
                    }
                } catch (Exception e) {
                    AiwaysLog.event(TAG, "send to " + host + " failed: " + e);
                    setStatus("השליחה נכשלה: " + e.getClass().getSimpleName());
                } finally {
                    sending = false;
                }
            }
        });
    }

    /** One request, one JSON line each way. */
    private static JSONObject request(String host, JSONObject body) throws Exception {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, AiwaysHebrewKeys.PHONE_PORT), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(READ_TIMEOUT_MS);
            Writer w = new OutputStreamWriter(s.getOutputStream(), "UTF-8");
            w.write(body.toString());
            w.write("\n");
            w.flush();
            String line = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8")).readLine();
            if (line == null) {
                throw new java.io.IOException("the phone closed the connection without an answer");
            }
            return new JSONObject(line);
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }
}
