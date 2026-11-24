package com.andrerinas.openheadunit.aiways;

/**
 * The plain parts of {@link AiwaysHebrewKeyboard}: the key layout, editing, and where the phone is.
 * Kept free of Android classes so they run in JVM tests.
 */
final class AiwaysHebrewKeys {

    /** TCP port of the phone app's typing service ({@code CarTypingService} in aiwaysapp). */
    static final int PHONE_PORT = 47110;

    /** Longest text sent at once; the phone cuts at the same length. */
    static final int MAX_LENGTH = 500;

    static final String SPACE = "space";
    static final String BACKSPACE = "backspace";

    /**
     * Digits, then the Israeli standard layout (SI 1452) as on a phone, then punctuation and space.
     * Every key is one character except the two named keys.
     */
    static final String[][] ROWS = {
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
            {"'", "ק", "ר", "א", "ט", "ו", "ן", "ם", "פ", BACKSPACE},
            {"ש", "ד", "ג", "כ", "ע", "י", "ח", "ל", "ך", "ף"},
            {"ז", "ס", "ב", "ה", "נ", "מ", "צ", "ת", "ץ", "\""},
            {",", ".", "-", SPACE, "?", "!"},
    };

    private AiwaysHebrewKeys() {
    }

    /** The text after pressing {@code key}. */
    static String press(String text, String key) {
        if (BACKSPACE.equals(key)) {
            return text.isEmpty() ? text : text.substring(0, text.offsetByCodePoints(text.length(), -1));
        }
        String add = SPACE.equals(key) ? " " : key;
        if (text.length() + add.length() > MAX_LENGTH) {
            return text;
        }
        return text + add;
    }

    /**
     * The phone's address from the endpoint the session dialled ({@code ip:port}, see
     * {@code CommManager.lastAttemptedEndpoint}), or null when there is none (USB).
     */
    static String phoneHost(String endpoint) {
        if (endpoint == null) {
            return null;
        }
        int colon = endpoint.lastIndexOf(':');
        String host = colon > 0 ? endpoint.substring(0, colon) : endpoint;
        host = host.trim();
        return host.isEmpty() ? null : host;
    }
}
