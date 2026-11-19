package com.andrerinas.openheadunit.aiways;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The pure part of the wireless setup test (AIWAYS.md, "Wireless the standard way", stage B):
 * reading the values the setup QR names. No Android types, so the host tests cover all of it.
 *
 * The QR names the T-Box's access point as the network, this unit's address on the T-Box bridge as
 * the WPP endpoint, and Adayo's Bluetooth module as the Bluetooth identity, since the phone is
 * already connected to that module for calls.
 */
public final class AiwaysWirelessSetup {

    /** Where the test listener waits for the phone, and the port the QR names. Upstream's WPP port. */
    public static final int WPP_PORT = 5299;

    /** The T-Box, on the bridge it shares with its access point and this unit's USB link. */
    public static final String TBOX_HOST = "192.168.1.1";

    /** tbox_daemon's config, which holds the access point's name and key (wifi_ssid, wifi_key). */
    public static final String TBOX_CONF = "/usrdata/tbox_daemon.conf";

    /** This unit's USB network link to the T-Box. */
    public static final String USB_INTERFACE = "usb0";

    private AiwaysWirelessSetup() {
    }

    /** One access point from a Wi-Fi scan. */
    public static final class Ap {
        final String ssid;
        final String bssid;
        final int level;

        public Ap(String ssid, String bssid, int level) {
            this.ssid = ssid;
            this.bssid = bssid;
            this.level = level;
        }
    }

    /**
     * A Bluetooth or Wi-Fi address in the form Android Auto checks: six upper-case hex pairs joined
     * by colons. Accepts any separator or none ("001122aabbcc", "00-11-22-AA-BB-CC"). Null when the
     * input does not hold exactly 12 hex digits.
     */
    public static String normalizeMac(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder hex = new StringBuilder(12);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.digit(c, 16) >= 0) {
                hex.append(Character.toUpperCase(c));
            } else if (c != ':' && c != '-' && c != '.' && c != ' ') {
                return null;
            }
        }
        if (hex.length() != 12) {
            return null;
        }
        StringBuilder out = new StringBuilder(17);
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) {
                out.append(':');
            }
            out.append(hex, i, i + 2);
        }
        return out.toString();
    }

    /**
     * The key = value lines of tbox_daemon.conf, read the way the daemon reads them (load_config):
     * leading blanks skipped, a line starting with '#' or ';' is a comment, key and value trimmed,
     * no quoting and no comment after a value. A later line wins.
     */
    public static Map<String, String> parseConf(String text) {
        Map<String, String> out = new HashMap<String, String>();
        if (text == null) {
            return out;
        }
        // Any line ending: LF (the T-Box), CRLF (a file edited on Windows) or a lone CR.
        for (String line : text.split("\r\n|\n|\r")) {
            String p = line.replaceFirst("^[ \t]+", "");
            if (p.isEmpty() || p.charAt(0) == '#' || p.charAt(0) == ';') {
                continue;
            }
            int eq = p.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = p.substring(0, eq).replaceFirst("[ \t]+$", "");
            String value = p.substring(eq + 1).replaceFirst("^[ \t]+", "").replaceFirst("[ \t]+$", "");
            out.put(key, value);
        }
        return out;
    }

    /** The BSSID of the strongest access point named [ssid], upper case with colons, or null. */
    public static String strongestBssid(List<Ap> scan, String ssid) {
        if (scan == null || ssid == null || ssid.isEmpty()) {
            return null;
        }
        Ap best = null;
        for (Ap ap : scan) {
            if (ap == null || !ssid.equals(stripQuotes(ap.ssid)) || normalizeMac(ap.bssid) == null) {
                continue;
            }
            if (best == null || ap.level > best.level) {
                best = ap;
            }
        }
        return best == null ? null : normalizeMac(best.bssid);
    }

    /** The first scanned network whose name starts like the T-Box's ("MyAiways-"), strongest first. */
    public static String tboxSsidFromScan(List<Ap> scan) {
        if (scan == null) {
            return null;
        }
        Ap best = null;
        for (Ap ap : scan) {
            String name = ap == null ? null : stripQuotes(ap.ssid);
            if (name == null || !name.startsWith("MyAiways-")) {
                continue;
            }
            if (best == null || ap.level > best.level) {
                best = ap;
            }
        }
        return best == null ? null : stripQuotes(best.ssid);
    }

    /** Whether [ip] is on the T-Box bridge (192.168.1.0/24) and is not the T-Box itself. */
    public static boolean isOnTboxBridge(String ip) {
        return ip != null && ip.startsWith("192.168.1.") && !ip.equals(TBOX_HOST)
                && ip.length() > "192.168.1.".length();
    }

    /**
     * The data port of an FTP passive-mode reply, "227 Entering Passive Mode (h1,h2,h3,h4,p1,p2)".
     * The host part is ignored: the T-Box is dialled at the address the control connection used.
     * Returns -1 when the reply is not a 227 with six numbers.
     */
    public static int pasvPort(String reply) {
        if (reply == null || !reply.startsWith("227")) {
            return -1;
        }
        int open = reply.indexOf('(');
        int close = reply.indexOf(')', open + 1);
        if (open < 0 || close < 0) {
            return -1;
        }
        String[] parts = reply.substring(open + 1, close).split(",");
        if (parts.length != 6) {
            return -1;
        }
        try {
            int hi = Integer.parseInt(parts[4].trim());
            int lo = Integer.parseInt(parts[5].trim());
            if (hi < 0 || hi > 255 || lo < 0 || lo > 255) {
                return -1;
            }
            return hi * 256 + lo;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** A value safe for a log line: the passkey never goes to the logs, which reach GitHub. */
    public static String describe(String ssid, String bssid, String ip, int port, String bluetoothMac) {
        return String.format(Locale.US, "ssid=%s bssid=%s endpoint=%s:%d bluetooth=%s",
                ssid, bssid, ip, port, bluetoothMac);
    }

    /** Android 4.4 reports scanned SSIDs bare, newer versions quoted; compare them bare. */
    static String stripQuotes(String s) {
        if (s != null && s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
