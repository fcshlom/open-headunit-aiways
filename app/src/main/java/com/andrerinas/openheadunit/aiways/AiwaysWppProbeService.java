package com.andrerinas.openheadunit.aiways;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.WppTcpTls;
import com.andrerinas.openheadunit.ssl.SslContextFactory;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingDeque;
import javax.net.ssl.SSLSocket;

/**
 * The listener of the wireless setup test (AIWAYS.md, stage B). It answers one question: once the
 * phone has scanned the setup QR, does Android Auto dial this unit by itself when the phone's
 * Bluetooth connects to the car?
 *
 * It waits on {@link AiwaysWirelessSetup#WPP_PORT}, the port the QR names, and only records who
 * dials. Like the phone expects, it then opens TLS as the client, which proves the caller is
 * Android Auto (its certificate is logged), and closes. It never starts a projection, so it cannot
 * disturb the unit or the Headunit Server mode in use today.
 *
 * Runs only while armed from the test screen; the launcher's CONFIG broadcast (at boot and every
 * 15 minutes) restarts it after a reboot. Every event also goes to the day log ([wpp-test]).
 */
public class AiwaysWppProbeService extends Service {
    private static final String PREFS = "aiways_wpp_probe";
    private static final String KEY_ARMED = "armed";
    private static final int MAX_EVENTS = 40;
    private static final int BIND_ATTEMPTS = 5;
    private static final long BIND_RETRY_MS = 1000L;
    private static final int TLS_TIMEOUT_MS = 10_000;
    private static final long HOLD_AFTER_TLS_MS = 3000L;

    /** Newest first. A thread-safe deque, so the screen's copy needs no lock of its own. */
    private static final LinkedBlockingDeque<String> EVENTS = new LinkedBlockingDeque<String>();
    private static volatile String status = "stopped";
    private static volatile int dials;

    private volatile boolean running;
    private volatile ServerSocket server;

    /** Arms or disarms the test; arming starts the listener now, disarming stops it. */
    public static void setArmed(Context context, boolean armed) {
        prefs(context).edit().putBoolean(KEY_ARMED, armed).apply();
        Intent intent = new Intent(context, AiwaysWppProbeService.class);
        if (armed) {
            context.startService(intent);
        } else {
            context.stopService(intent);
            event("test stopped");
        }
    }

    public static boolean isArmed(Context context) {
        return prefs(context).getBoolean(KEY_ARMED, false);
    }

    /** After a reboot: the launcher's broadcast brings the listener back if the test is armed. */
    public static void startIfArmed(Context context) {
        if (isArmed(context)) {
            context.startService(new Intent(context, AiwaysWppProbeService.class));
        }
    }

    /** The newest events first, for the test screen. */
    public static List<String> recentEvents() {
        return new ArrayList<String>(EVENTS);
    }

    public static String status() {
        return status;
    }

    public static int dialCount() {
        return dials;
    }

    /**
     * This unit's IPv4 address and MAC on the USB link to the T-Box, {ip, mac}; either may be null.
     * Falls back to any interface on the T-Box bridge, should the link carry another name.
     */
    public static String[] usbLink() {
        String[] best = {null, null};
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            List<NetworkInterface> list = all == null ? Collections.<NetworkInterface>emptyList() : Collections.list(all);
            for (NetworkInterface nif : list) {
                String ip = ipv4(nif);
                boolean usb = AiwaysWirelessSetup.USB_INTERFACE.equals(nif.getName());
                if (usb || (best[0] == null && AiwaysWirelessSetup.isOnTboxBridge(ip))) {
                    best[0] = ip;
                    best[1] = mac(nif);
                    if (usb) {
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
            // no interfaces readable; the screen shows the address as unknown
        }
        return best;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isArmed(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            running = true;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop();
                }
            }, "aiways-wpp-test");
            t.setDaemon(true);
            t.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        closeQuietly(server);
        status = "stopped";
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void acceptLoop() {
        String[] link = usbLink();
        ServerSocket s = null;
        for (int attempt = 1; attempt <= BIND_ATTEMPTS && running; attempt++) {
            try {
                s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(AiwaysWirelessSetup.WPP_PORT));
                break;
            } catch (IOException e) {
                closeQuietly(s);
                s = null;
                status = "cannot listen on port " + AiwaysWirelessSetup.WPP_PORT + ": " + e.getMessage();
                event(status + " (attempt " + attempt + ")");
                sleep(BIND_RETRY_MS);
            }
        }
        if (s == null) {
            running = false;
            return;
        }
        server = s;
        status = "listening on port " + AiwaysWirelessSetup.WPP_PORT + ", usb0 " + link[0];
        event("listening on port " + AiwaysWirelessSetup.WPP_PORT + "; usb0 ip=" + link[0] + " mac=" + link[1]);
        while (running) {
            final Socket socket;
            try {
                socket = s.accept();
            } catch (IOException e) {
                if (running) {
                    event("accept failed: " + e.getMessage());
                    sleep(BIND_RETRY_MS);
                }
                continue;
            }
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    handle(socket);
                }
            }, "aiways-wpp-dial");
            t.setDaemon(true);
            t.start();
        }
        closeQuietly(s);
    }

    /** One dial: record it, open TLS as the client the phone expects, log its certificate, close. */
    private void handle(Socket raw) {
        String from = raw.getInetAddress() == null ? "?" : raw.getInetAddress().getHostAddress();
        String to = raw.getLocalAddress() == null ? "?" : raw.getLocalAddress().getHostAddress();
        dials++;
        status = "DIALLED by " + from + " (" + dials + " so far)";
        event("DIAL from " + from + " to " + to + ":" + raw.getLocalPort());
        SSLSocket tls = null;
        try {
            raw.setSoTimeout(TLS_TIMEOUT_MS);
            tls = WppTcpTls.INSTANCE.clientSocket(SslContextFactory.INSTANCE.create(this).getSocketFactory(), raw);
            tls.startHandshake();
            event("TLS with " + from + " ok, " + tls.getSession().getProtocol() + ", phone certificate: "
                    + peerSubject(tls.getSession().getPeerCertificates()));
            sleep(HOLD_AFTER_TLS_MS);
        } catch (Exception e) {
            event("TLS with " + from + " failed: " + e);
        } finally {
            closeQuietly(tls);
            closeQuietly(raw);
        }
    }

    private static String peerSubject(Certificate[] chain) {
        if (chain == null || chain.length == 0) {
            return "none";
        }
        if (chain[0] instanceof X509Certificate) {
            return ((X509Certificate) chain[0]).getSubjectDN().getName();
        }
        return chain[0].getType();
    }

    private static void event(String message) {
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        EVENTS.addFirst(stamp + "  " + message);
        while (EVENTS.size() > MAX_EVENTS) {
            EVENTS.pollLast();
        }
        AiwaysLog.event("wpp-test", message);
    }

    private static String ipv4(NetworkInterface nif) {
        for (InetAddress a : Collections.list(nif.getInetAddresses())) {
            if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                return a.getHostAddress();
            }
        }
        return null;
    }

    private static String mac(NetworkInterface nif) {
        try {
            byte[] hw = nif.getHardwareAddress();
            if (hw == null) {
                return null;
            }
            StringBuilder b = new StringBuilder();
            for (byte x : hw) {
                b.append(String.format(Locale.US, "%02X", x));
            }
            return AiwaysWirelessSetup.normalizeMac(b.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(ServerSocket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (IOException ignored) {
            // unblocks accept(); an error here changes nothing
        }
    }

    private static void closeQuietly(Socket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (IOException ignored) {
            // closing a spent socket never matters
        }
    }
}
