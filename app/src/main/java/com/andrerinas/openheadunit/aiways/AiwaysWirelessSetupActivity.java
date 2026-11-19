package com.andrerinas.openheadunit.aiways;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ProjectionDeepLink;
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.SoftApBssidPolicy;
import com.andrerinas.openheadunit.utils.QrCodeGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The wireless setup test screen (AIWAYS.md, "Wireless the standard way", stage B). Its own icon in
 * the launcher's app list ("AA wireless test").
 *
 * It gathers what the setup QR names: the T-Box access point (name and key from tbox_daemon.conf,
 * BSSID from a Wi-Fi scan), this unit's address on the USB link to the T-Box, and the address of
 * Adayo's Bluetooth module. It shows Android Auto's setup QR for them and arms
 * {@link AiwaysWppProbeService}, then shows live whether the phone dials in.
 *
 * Every field can be corrected by hand before the QR is drawn. The key is never logged.
 */
public class AiwaysWirelessSetupActivity extends Activity {
    private static final int QR_SIZE_PX = 520;
    private static final long SCAN_SETTLE_MS = 3000L;
    private static final long BT_TIMEOUT_MS = 5000L;
    private static final int BG = 0xFF101418;
    private static final int FG = 0xFFECEFF1;
    private static final int DIM = 0xFFB0BEC5;
    private static final int OK = 0xFF66BB6A;
    private static final int BAD = 0xFFEF5350;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private EditText ssidEdit;
    private EditText keyEdit;
    private EditText bssidEdit;
    private EditText bluetoothEdit;
    private TextView linkView;
    private TextView noteView;
    private TextView statusView;
    private TextView eventsView;
    private ImageView qrView;
    private TextView qrCaption;
    private volatile String usbIp;
    private volatile String bluetoothName;
    private volatile boolean gathering;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            showProbe();
            ui.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
        gather();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
    }

    // ---- layout ----

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        ScrollView scroll = new ScrollView(this);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(left);
        root.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        TextView title = text("ניסוי: Android Auto אלחוטי בדרך הרגילה", 22, FG);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        left.addView(title);
        left.addView(text(
                "1. הרכב דולק והטלפון מחובר ל-Bluetooth של הרכב כרגיל.\n"
                        + "2. בודקים שכל השדות מלאים ולוחצים \"הצג QR והתחל\".\n"
                        + "3. סורקים את ה-QR במצלמה של הטלפון. נפתח Android Auto: מאשרים.\n"
                        + "4. מכבים ומדליקים את ה-Bluetooth בטלפון. הרכב נשאר דולק.\n"
                        + "5. הצלחה: מופיעה כאן שורה DIAL from 192.168.1.x. כלומר הטלפון התחבר לבד.\n"
                        + "ביטול: ב-Android Auto בטלפון, הגדרות > רכבים שחוברו בעבר > לשכוח את הרכב. ואז \"עצור\".",
                15, DIM));

        ssidEdit = field(left, "שם ה-Wi-Fi של ה-T-Box (SSID)", false);
        keyEdit = field(left, "הסיסמה שלו", true);
        bssidEdit = field(left, "BSSID של ה-T-Box (מסריקת Wi-Fi)", false);
        bluetoothEdit = field(left, "כתובת ה-Bluetooth של הרכב (המודול של Adayo)", false);
        linkView = text("", 15, DIM);
        left.addView(linkView);
        noteView = text("", 15, DIM);
        left.addView(noteView);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(button("קרא שוב", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                gather();
            }
        }));
        buttons.addView(button("הצג QR והתחל", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showQrAndArm();
            }
        }));
        buttons.addView(button("עצור את הניסוי", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AiwaysWppProbeService.setArmed(AiwaysWirelessSetupActivity.this, false);
                qrView.setImageBitmap(null);
                qrCaption.setText("");
            }
        }));
        left.addView(buttons);

        statusView = text("", 18, FG);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        left.addView(statusView);
        eventsView = text("", 14, DIM);
        eventsView.setTypeface(Typeface.MONOSPACE);
        left.addView(eventsView);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER_HORIZONTAL);
        right.setPadding(pad, 0, 0, 0);
        qrView = new ImageView(this);
        qrView.setBackgroundColor(Color.WHITE);
        right.addView(qrView, new LinearLayout.LayoutParams(QR_SIZE_PX, QR_SIZE_PX));
        qrCaption = text("", 14, DIM);
        right.addView(qrCaption);
        root.addView(right, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    private EditText field(LinearLayout parent, String label, boolean secret) {
        parent.addView(text(label, 14, DIM));
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setTextColor(FG);
        e.setTextSize(18);
        e.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : 0));
        parent.addView(e);
        return e;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---- gathering the values ----

    /** Reads every value on a worker thread; fields the user already filled are kept. */
    private void gather() {
        if (gathering) {
            return;
        }
        gathering = true;
        noteView.setText("קורא נתונים מה-T-Box, מסריקת Wi-Fi ומה-Bluetooth...");
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final StringBuilder notes = new StringBuilder();
                final String[] link = AiwaysWppProbeService.usbLink();
                usbIp = link[0];

                String confSsid = null;
                String confKey = null;
                try {
                    Map<String, String> conf = AiwaysWirelessSetup.parseConf(
                            AiwaysTboxFtp.read(AiwaysWirelessSetup.TBOX_HOST, AiwaysWirelessSetup.TBOX_CONF));
                    confSsid = conf.get("wifi_ssid");
                    confKey = conf.get("wifi_key");
                    if (confSsid == null || confKey == null) {
                        notes.append("ב-tbox_daemon.conf אין wifi_ssid או wifi_key. יש למלא ידנית.\n");
                    }
                } catch (Exception e) {
                    notes.append("קריאת tbox_daemon.conf נכשלה (").append(e.getMessage()).append("). יש למלא ידנית.\n");
                }

                List<AiwaysWirelessSetup.Ap> scan = scan(app);
                String ssid = confSsid != null ? confSsid : AiwaysWirelessSetup.tboxSsidFromScan(scan);
                String bssid = AiwaysWirelessSetup.strongestBssid(scan, ssid);
                if (bssid == null) {
                    notes.append("ה-T-Box לא נמצא בסריקת ה-Wi-Fi. ה-Wi-Fi של היחידה דלוק?\n");
                }

                AiwaysAdayoBt.Identity bt = AiwaysAdayoBt.read(app, BT_TIMEOUT_MS);
                String btMac = AiwaysWirelessSetup.normalizeMac(bt.address);
                bluetoothName = bt.name;
                if (btMac == null) {
                    notes.append("כתובת ה-Bluetooth לא נקראה (")
                            .append(bt.error != null ? bt.error : "raw=" + bt.address).append(").\n");
                }
                if (ProjectionDeepLink.INSTANCE.looksLikeDongle(bt.name)) {
                    notes.append("שם ה-Bluetooth של הרכב נראה כמו דונגל, ו-Android Auto עלול לטפל בו אחרת.\n");
                }
                AiwaysLog.event("wpp-test", "gathered: usb0 ip=" + link[0] + " mac=" + link[1]
                        + " ssid=" + ssid + " bssid=" + bssid + " key=" + (confKey == null ? "missing" : "read")
                        + " bluetooth=" + btMac + " (raw " + bt.address + ", name " + bt.name + ")"
                        + (bt.error != null ? " error=" + bt.error : ""));

                final String fSsid = ssid;
                final String fKey = confKey;
                final String fBssid = bssid;
                final String fBt = btMac;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        gathering = false;
                        fillIfEmpty(ssidEdit, fSsid);
                        fillIfEmpty(keyEdit, fKey);
                        fillIfEmpty(bssidEdit, fBssid);
                        fillIfEmpty(bluetoothEdit, fBt);
                        linkView.setText("הכתובת של היחידה מול ה-T-Box (usb0): "
                                + (link[0] == null ? "לא נמצאה" : link[0]) + "   MAC: " + link[1]
                                + "\nשם ה-Bluetooth של הרכב: " + bluetoothName);
                        noteView.setText(notes.length() == 0 ? "כל הנתונים נקראו." : notes.toString().trim());
                    }
                });
            }
        }, "aiways-wpp-gather").start();
    }

    /**
     * The access points around, for the T-Box's BSSID; empty when Wi-Fi is off.
     *
     * The results the system already holds come first. A new scan is asked for only when the T-Box
     * is not among them, and given a few seconds to land. This unit runs Android 4.4, where
     * startScan() is neither deprecated nor throttled (both came with Android 9); should a scan
     * still come back empty, the screen says so and the BSSID can be typed by hand.
     */
    private static List<AiwaysWirelessSetup.Ap> scan(Context context) {
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi == null || !wifi.isWifiEnabled()) {
                return new ArrayList<AiwaysWirelessSetup.Ap>();
            }
            List<AiwaysWirelessSetup.Ap> cached = results(wifi);
            if (AiwaysWirelessSetup.tboxSsidFromScan(cached) != null) {
                return cached;
            }
            wifi.startScan();
            Thread.sleep(SCAN_SETTLE_MS);
            return results(wifi);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // no scan; the screen asks for the BSSID by hand
        }
        return new ArrayList<AiwaysWirelessSetup.Ap>();
    }

    private static List<AiwaysWirelessSetup.Ap> results(WifiManager wifi) {
        List<AiwaysWirelessSetup.Ap> out = new ArrayList<AiwaysWirelessSetup.Ap>();
        List<ScanResult> results = wifi.getScanResults();
        if (results != null) {
            for (ScanResult r : results) {
                out.add(new AiwaysWirelessSetup.Ap(r.SSID, r.BSSID, r.level));
            }
        }
        return out;
    }

    private static void fillIfEmpty(EditText e, String value) {
        if (value != null && e.getText().toString().trim().isEmpty()) {
            e.setText(value);
        }
    }

    // ---- the QR ----

    private void showQrAndArm() {
        String ssid = ssidEdit.getText().toString().trim();
        String key = keyEdit.getText().toString();
        String bssid = AiwaysWirelessSetup.normalizeMac(bssidEdit.getText().toString());
        String bt = AiwaysWirelessSetup.normalizeMac(bluetoothEdit.getText().toString());
        String ip = usbIp;
        if (ip == null) {
            ip = AiwaysWppProbeService.usbLink()[0];
            usbIp = ip;
        }
        // Each field checked here, so the screen names the one to fix; build() checks them again.
        if (ssid.isEmpty() || key.isEmpty()) {
            showError("חסרים שם ה-Wi-Fi של ה-T-Box או הסיסמה שלו.");
            return;
        }
        if (bssid == null || !SoftApBssidPolicy.INSTANCE.isUsable(bssid)) {
            showError("ה-BSSID חסר או לא תקין (6 זוגות של ספרות הקסדצימליות, למשל AA:BB:CC:11:22:33).");
            return;
        }
        if (bt == null || !SoftApBssidPolicy.INSTANCE.isUsable(bt)) {
            showError("כתובת ה-Bluetooth חסרה או לא תקינה.");
            return;
        }
        if (ip == null) {
            showError("אין ליחידה כתובת מול ה-T-Box (usb0).");
            return;
        }
        ProjectionDeepLink.Result result = ProjectionDeepLink.INSTANCE.build(
                ssid, key, bssid, ip, AiwaysWirelessSetup.WPP_PORT, bt);
        if (result instanceof ProjectionDeepLink.Result.Failed) {
            showError(((ProjectionDeepLink.Result.Failed) result).getInvalid().getReason());
            return;
        }
        String url = ((ProjectionDeepLink.Result.Ok) result).getUrl();
        Bitmap qr = QrCodeGenerator.INSTANCE.generateQrCode(url, QR_SIZE_PX);
        if (qr == null) {
            showError("לא הצלחתי לצייר את ה-QR.");
            return;
        }
        qrView.setImageBitmap(qr);
        qrCaption.setText("לסרוק במצלמה של הטלפון\n" + ssid + "  →  " + ip + ":" + AiwaysWirelessSetup.WPP_PORT);
        AiwaysLog.event("wpp-test", "QR shown: "
                + AiwaysWirelessSetup.describe(ssid, bssid, ip, AiwaysWirelessSetup.WPP_PORT, bt));
        AiwaysWppProbeService.setArmed(this, true);
        noteView.setText("ה-QR מוכן והמאזין רץ. עכשיו סורקים, ואז מכבים ומדליקים את ה-Bluetooth בטלפון.");
        noteView.setTextColor(DIM);
    }

    private void showError(String message) {
        noteView.setText(message);
        noteView.setTextColor(BAD);
    }

    private void showProbe() {
        boolean armed = AiwaysWppProbeService.isArmed(this);
        int dials = AiwaysWppProbeService.dialCount();
        statusView.setText(armed ? "המאזין: " + AiwaysWppProbeService.status() : "הניסוי לא פעיל");
        statusView.setTextColor(dials > 0 ? OK : FG);
        StringBuilder b = new StringBuilder();
        for (String line : AiwaysWppProbeService.recentEvents()) {
            b.append(line).append('\n');
        }
        eventsView.setText(b.toString());
    }
}
