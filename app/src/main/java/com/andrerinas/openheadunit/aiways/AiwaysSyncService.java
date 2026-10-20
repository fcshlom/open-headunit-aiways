package com.andrerinas.openheadunit.aiways;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import org.json.JSONObject;

/**
 * One round against the own backend, started by every launcher broadcast (about every 15 min):
 * uploads what the day logs gained (POST /launcher/logs, files headunit-YYYY-MM-DD.log), and at
 * most every 6 h asks for a newer build (channel "headunit": GET /app/latest?abi=headunit).
 *
 * A newer build is downloaded, checked against its sha256 and installed without a tap through
 * root (`su -c pm install -r`, the unit has /system/xbin/su); where that fails, Android's installer
 * opens and asks once. The installer refuses a build not signed with this app's key.
 */
public class AiwaysSyncService extends IntentService {
    private static final String CHANNEL = "headunit";
    private static final long UPDATE_EVERY_MS = 6 * 3600 * 1000L;
    private static final String PREF_LOG_SENT = "log_sent_";   // + file name: bytes the server has
    private static final String PREF_LAST_CHECK = "update_checked_at";
    private static final int LOG_CHUNK = 512 * 1024;

    public AiwaysSyncService() {
        super("aiways-sync");
    }

    static void start(Context context) {
        try {
            context.startService(new Intent(context, AiwaysSyncService.class));
        } catch (Exception e) {
            AiwaysLog.event("sync", "cannot start: " + e);
        }
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (!AiwaysConfig.configured(this) || !AiwaysConfig.clockKnown(this)) {
            return;   // the launcher has not sent the key and the T-Box time yet
        }
        AiwaysBackend backend = new AiwaysBackend(this);
        try {
            long bytes = uploadLogs(backend);
            if (bytes > 0) {
                AiwaysLog.event("sync", "logs uploaded (" + (bytes + 1023) / 1024 + " KB new)");
            }
        } catch (Exception e) {
            AiwaysLog.event("sync", "log upload failed: " + e);
        }
        SharedPreferences p = AiwaysConfig.prefs(this);
        long now = AiwaysConfig.now();
        long last = p.getLong(PREF_LAST_CHECK, 0L);
        if (now - last < UPDATE_EVERY_MS && now >= last) {
            return;
        }
        try {
            String result = checkUpdate(backend);
            p.edit().putLong(PREF_LAST_CHECK, now).apply();
            AiwaysLog.event("update", result);
        } catch (Exception e) {
            AiwaysLog.event("update", "failed: " + e);
        }
    }

    // ---------------------------------------------------------------- logs

    /** @return bytes the server took this round */
    private long uploadLogs(AiwaysBackend backend) throws Exception {
        File dir = AiwaysLog.dir();
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) {
            return 0L;
        }
        SharedPreferences p = AiwaysConfig.prefs(this);
        long total = 0L;
        Arrays.sort(files);
        for (File f : files) {
            String name = f.getName();
            // headunit-YYYY-MM-DD.log; days from before the T-Box time was known (2019...) stay here
            if (!name.matches("headunit-20(2[4-9]|[3-9]\\d)-\\d{2}-\\d{2}\\.log")) {
                continue;
            }
            long sent = p.getLong(PREF_LOG_SENT + name, 0L);
            int rounds = 0;
            while (sent < f.length() && rounds++ < 40) {
                int n = (int) Math.min(LOG_CHUNK, f.length() - sent);
                byte[] chunk = new byte[n];
                RandomAccessFile in = new RandomAccessFile(f, "r");
                try {
                    in.seek(sent);
                    in.readFully(chunk);
                } finally {
                    in.close();
                }
                JSONObject r = backend.post(backend.base() + "/launcher/logs?file=" + name + "&offset=" + sent, chunk);
                long server = r.optLong("size", -1L);
                if (server < 0 || server > f.length()) {
                    break;   // the server has a different file (e.g. kept after a reinstall): leave it
                }
                total += Math.max(0L, server - sent);
                sent = server;
                p.edit().putLong(PREF_LOG_SENT + name, sent).apply();
            }
        }
        // forget the counters of day files already deleted here
        SharedPreferences.Editor ed = null;
        for (String k : p.getAll().keySet()) {
            if (k.startsWith(PREF_LOG_SENT) && !new File(dir, k.substring(PREF_LOG_SENT.length())).exists()) {
                ed = ed == null ? p.edit() : ed;
                ed.remove(k);
            }
        }
        if (ed != null) {
            ed.apply();
        }
        return total;
    }

    // ---------------------------------------------------------------- update

    private String checkUpdate(AiwaysBackend backend) throws Exception {
        JSONObject latest = new JSONObject(new String(backend.get(backend.base() + "/app/latest?abi=" + CHANNEL, null), "UTF-8"));
        int build = latest.getInt("build");
        String name = latest.optString("versionName", "?");
        int mine = installedCode();
        if (build <= mine) {
            return "up to date (" + mine + ", server " + build + ")";
        }
        String sha = latest.getString("sha256").toLowerCase(Locale.US);
        File dir = getExternalFilesDir("updates");
        if (dir == null) {
            throw new IOException("no external storage");
        }
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File apk = new File(dir, "headunit-" + build + ".apk");
        if (!apk.isFile() || !sha.equals(sha256(apk))) {
            File tmp = new File(dir, "download.tmp");
            backend.get(backend.base() + latest.optString("url", "/app/download?abi=" + CHANNEL), tmp);
            if (!sha.equals(sha256(tmp))) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                throw new IOException("checksum mismatch");
            }
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
            if (!tmp.renameTo(apk)) {
                throw new IOException("cannot store the download");
            }
            File[] old = dir.listFiles();
            if (old != null) {
                for (File f : old) {
                    if (!f.equals(apk)) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        apk.setReadable(true, false);   // pm and the package installer read it from here
        AiwaysLog.event("update", "installing " + name + " (" + mine + " -> " + build + ")");
        String root = installAsRoot(apk);
        if (root == null) {
            return "installed " + name + " through root";   // usually not reached: pm restarts us
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        return "root install not possible (" + root + "), installer opened for " + name;
    }

    /** null on success, else why not. */
    private static String installAsRoot(File apk) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", "pm install -r " + apk.getAbsolutePath()).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null && out.length() < 2000) {
                out.append(line.trim()).append(' ');
            }
            int code = p.waitFor();
            String text = out.toString().trim();
            return code == 0 && text.contains("Success") ? null : "exit " + code + ": " + text;
        } catch (Exception e) {
            return String.valueOf(e);
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    private int installedCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        } finally {
            in.close();
        }
        StringBuilder b = new StringBuilder();
        for (byte x : md.digest()) {
            b.append(String.format("%02x", x & 0xff));
        }
        return b.toString();
    }
}
