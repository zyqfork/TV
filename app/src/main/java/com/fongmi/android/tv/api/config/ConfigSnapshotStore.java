package com.fongmi.android.tv.api.config;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.utils.FileUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Private per-source snapshots. Keep large config bodies out of SQLite CursorWindow rows. */
public final class ConfigSnapshotStore {

    private static final String PREFIX = "@source-snapshot-v1/";
    private static final int MAX_EXPANDED_BYTES = 32 * 1024 * 1024;

    private ConfigSnapshotStore() {
    }

    private static String name(Config config) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                (config.getType() + "\n" + config.getUrl()).getBytes(StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder();
        for (byte value : digest) name.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return name + ".gz";
    }

    private static File file(String name) {
        return new File(new File(App.get().getFilesDir(), "source-snapshots"), name);
    }

    public static String read(Config config) throws Exception {
        String stored = config.getJson();
        if (stored == null || !stored.startsWith(PREFIX)) return stored;
        String name = name(config);
        // A restored or modified database cannot point at another source or arbitrary file.
        if (!stored.equals(PREFIX + name)) throw new java.io.IOException("Snapshot/source mismatch");
        try (FileInputStream source = new FileInputStream(file(name));
             GZIPInputStream input = new GZIPInputStream(source);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Canceled");
                if (output.size() + count > MAX_EXPANDED_BYTES) throw new java.io.IOException("Snapshot too large");
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    public static void save(Config config, String body) {
        try {
            if (Thread.currentThread().isInterrupted()) return;
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            if (data.length > MAX_EXPANDED_BYTES) return;
            String name = name(config);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(output)) { gzip.write(data); }
            FileUtil.writeAtomically(output.toByteArray(), file(name));
            config.setJson(PREFIX + name);
        } catch (Exception e) {
            // A failed cache write must not fail a valid source or erase its last good snapshot.
            e.printStackTrace();
        }
    }
}
