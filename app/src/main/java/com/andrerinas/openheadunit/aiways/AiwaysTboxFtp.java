package com.andrerinas.openheadunit.aiways;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Reads one small text file from the T-Box over its FTP server, with the factory login the
 * launcher (TboxClock) and the provisioning app use too. Passive mode, read only.
 */
final class AiwaysTboxFtp {
    private static final int PORT = 21;
    private static final String USER = "root";
    private static final String PASS = "oelinux123";
    private static final int TIMEOUT_MS = 4000;
    private static final int MAX_BYTES = 64 * 1024;

    private AiwaysTboxFtp() {
    }

    /** The file's contents. Throws when the T-Box is not reachable or refuses the file. */
    static String read(String host, String path) throws IOException {
        Socket control = new Socket();
        try {
            control.connect(new InetSocketAddress(host, PORT), TIMEOUT_MS);
            control.setSoTimeout(TIMEOUT_MS);
            BufferedReader in = new BufferedReader(new InputStreamReader(control.getInputStream(), "ISO-8859-1"));
            OutputStream out = control.getOutputStream();
            expect(in, null, out, 220);
            expect(in, "USER " + USER, out, 331);
            expect(in, "PASS " + PASS, out, 230);
            expect(in, "TYPE I", out, 200);
            String pasv = expect(in, "PASV", out, 227);
            int dataPort = AiwaysWirelessSetup.pasvPort(pasv);
            if (dataPort <= 0) {
                throw new IOException("FTP PASV: " + pasv);
            }
            Socket data = new Socket();
            try {
                data.connect(new InetSocketAddress(host, dataPort), TIMEOUT_MS);
                data.setSoTimeout(TIMEOUT_MS);
                send(out, "RETR " + path);
                String retr = reply(in);
                if (!retr.startsWith("150") && !retr.startsWith("125")) {
                    throw new IOException("FTP RETR: " + retr);
                }
                InputStream din = data.getInputStream();
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = din.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                    if (buf.size() > MAX_BYTES) {
                        throw new IOException("FTP RETR: " + path + " is larger than " + MAX_BYTES + " bytes");
                    }
                }
                return buf.toString("UTF-8");
            } finally {
                closeQuietly(data);
            }
        } finally {
            closeQuietly(control);
        }
    }

    private static String expect(BufferedReader in, String cmd, OutputStream out, int code) throws IOException {
        if (cmd != null) {
            send(out, cmd);
        }
        String line = reply(in);
        if (!line.startsWith(String.valueOf(code))) {
            String what = cmd == null ? "greeting" : cmd.split(" ")[0];
            throw new IOException("FTP " + what + ": " + line);
        }
        return line;
    }

    private static void send(OutputStream out, String cmd) throws IOException {
        out.write((cmd + "\r\n").getBytes("ISO-8859-1"));
        out.flush();
    }

    /** One FTP reply; a multi-line one ("230-...") ends with its code and a space. Returns the last line. */
    private static String reply(BufferedReader in) throws IOException {
        String line = in.readLine();
        if (line == null) {
            throw new IOException("FTP connection closed");
        }
        if (line.length() >= 4 && line.charAt(3) == '-') {
            String end = line.substring(0, 3) + " ";
            while (!line.startsWith(end)) {
                line = in.readLine();
                if (line == null) {
                    throw new IOException("FTP connection closed");
                }
            }
        }
        return line;
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // nothing left to do with a socket we are discarding
        }
    }
}
