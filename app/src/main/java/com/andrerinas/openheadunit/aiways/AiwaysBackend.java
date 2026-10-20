package com.andrerinas.openheadunit.aiways;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.json.JSONObject;

/**
 * HTTP to the own backend with the launcher's API key, as the launcher's Updater does it on
 * Android 4.4: TLS 1.2 switched on per socket (off by default), the newer root CAs bundled in
 * assets/aiways-certs next to the system ones, and certificates checked at the T-Box-corrected
 * time instead of the head unit's wrong clock.
 */
final class AiwaysBackend {
    private final Context context;
    private SSLSocketFactory factory;

    AiwaysBackend(Context context) {
        this.context = context.getApplicationContext();
    }

    String base() {
        return AiwaysConfig.url(this.context);
    }

    /** GET; into a file when {@code to} is set, else the body as bytes. */
    byte[] get(String url, File to) throws Exception {
        HttpURLConnection c = open(url);
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code + " for " + url.replaceAll("^https?://[^/]+", ""));
            }
            InputStream in = c.getInputStream();
            OutputStream out = to != null ? new FileOutputStream(to) : new ByteArrayOutputStream();
            try {
                pipe(in, out);
            } finally {
                in.close();
                out.close();
            }
            return to != null ? null : ((ByteArrayOutputStream) out).toByteArray();
        } finally {
            c.disconnect();
        }
    }

    /** POST raw bytes; the JSON reply also for 409 (the log endpoint puts its size there). */
    JSONObject post(String url, byte[] body) throws Exception {
        HttpURLConnection c = open(url);
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(body.length);
        c.setRequestProperty("Content-Type", "application/octet-stream");
        try {
            OutputStream out = c.getOutputStream();
            out.write(body);
            out.close();
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            if (in != null) {
                pipe(in, buf);
                in.close();
            }
            if (code != 200 && code != 409) {
                throw new IOException("HTTP " + code + " for " + url.replaceAll("^https?://[^/]+|\\?.*$", ""));
            }
            return new JSONObject(buf.toString("UTF-8"));
        } finally {
            c.disconnect();
        }
    }

    private HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (c instanceof HttpsURLConnection) {
            ((HttpsURLConnection) c).setSSLSocketFactory(socketFactory());
        }
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setRequestProperty("X-Api-Key", AiwaysConfig.key(this.context));
        return c;
    }

    private static void pipe(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    // ---------------------------------------------------------------- TLS for Android 4.4

    private synchronized SSLSocketFactory socketFactory() throws Exception {
        if (this.factory == null) {
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, new TrustManager[] {new DatedTrustManager(anchors())}, null);
            this.factory = new Tls12Factory(ssl.getSocketFactory());
        }
        return this.factory;
    }

    /** The system's root CAs plus the bundled ones (newer roots Android 4.4 lacks). */
    private Set<TrustAnchor> anchors() throws Exception {
        Set<TrustAnchor> set = new HashSet<TrustAnchor>();
        try {
            KeyStore ks = KeyStore.getInstance("AndroidCAStore");
            ks.load(null, null);
            for (Enumeration<String> e = ks.aliases(); e.hasMoreElements(); ) {
                java.security.cert.Certificate c = ks.getCertificate(e.nextElement());
                if (c instanceof X509Certificate) {
                    set.add(new TrustAnchor((X509Certificate) c, null));
                }
            }
        } catch (Exception e) {
            AiwaysLog.event("backend", "system CAs: " + e);
        }
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        for (String f : this.context.getAssets().list("aiways-certs")) {
            InputStream in = this.context.getAssets().open("aiways-certs/" + f);
            try {
                set.add(new TrustAnchor((X509Certificate) cf.generateCertificate(in), null));
            } finally {
                in.close();
            }
        }
        return set;
    }

    /** Checks the server chain with PKIX at the real time, not the head unit's wrong clock. */
    private static final class DatedTrustManager implements X509TrustManager {
        private final Set<TrustAnchor> anchors;

        DatedTrustManager(Set<TrustAnchor> anchors) {
            this.anchors = anchors;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
            try {
                // the server may send its root too: leave the anchors out of the path
                List<X509Certificate> path = new ArrayList<X509Certificate>(Arrays.asList(chain));
                for (TrustAnchor a : this.anchors) {
                    path.remove(a.getTrustedCert());
                }
                PKIXParameters p = new PKIXParameters(this.anchors);
                p.setRevocationEnabled(false);
                p.setDate(new Date(AiwaysConfig.now()));
                CertPathValidator.getInstance("PKIX").validate(
                        CertificateFactory.getInstance("X.509").generateCertPath(path), p);
            } catch (Exception e) {
                throw new java.security.cert.CertificateException("server certificate not trusted: " + e.getMessage(), e);
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
            throw new java.security.cert.CertificateException("not a server");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    /** Turns TLS 1.2 (and 1.1) on for every socket; Android 4.4 supports it but leaves it off. */
    private static final class Tls12Factory extends SSLSocketFactory {
        private final SSLSocketFactory d;

        Tls12Factory(SSLSocketFactory d) {
            this.d = d;
        }

        private static Socket tls(Socket s) {
            if (s instanceof SSLSocket) {
                List<String> want = new ArrayList<String>();
                for (String p : ((SSLSocket) s).getSupportedProtocols()) {
                    if (p.equals("TLSv1.2") || p.equals("TLSv1.1")) {
                        want.add(p);
                    }
                }
                if (!want.isEmpty()) {
                    ((SSLSocket) s).setEnabledProtocols(want.toArray(new String[0]));
                }
            }
            return s;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return this.d.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return this.d.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return tls(this.d.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return tls(this.d.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
            return tls(this.d.createSocket(host, port, local, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return tls(this.d.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress local, int localPort) throws IOException {
            return tls(this.d.createSocket(address, port, local, localPort));
        }
    }
}
