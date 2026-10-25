package com.fongmi.android.tv.player.exo;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UriUtil;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheKeyFactory;
import androidx.media3.datasource.cache.CacheSpan;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Process-local preload counters and last-event snapshot for diagnostics. */
public final class PreloadDiagnostics {

    private static final String TAG = "VodPreload";
    private static final int HLS_MAX_DEPTH = 2;
    private static final int MAX_WATCHED_ITEMS = 8;
    private static final int MAX_WATCHED_KEYS = 256;
    private static final Object WATCH = new Object();
    private static final Map<String, LinkedHashSet<String>> watchedKeys = new LinkedHashMap<>();
    private static final int HLS_MAX_PLAYLIST_BYTES = 2 * 1024 * 1024;
    private static final Pattern HLS_URI = Pattern.compile("URI=\"([^\"]+)\"");
    private static final AtomicLong started = new AtomicLong();
    private static final AtomicLong completed = new AtomicLong();
    private static final AtomicLong cancelled = new AtomicLong();
    private static final AtomicLong failed = new AtomicLong();
    private static final AtomicLong skipped = new AtomicLong();
    private static volatile Snapshot latest = new Snapshot("idle", "", 0, 0, "", System.currentTimeMillis());

    private PreloadDiagnostics() {
    }

    public static void started(MediaItem item, long startPositionMs, long durationMs) {
        started.incrementAndGet();
        update("started", item, 0, startPositionMs, durationMs + "ms");
    }

    public static void progress(MediaItem item, long bytes, float percentage) {
        if (percentage < 0 || ((int) percentage) % 10 != 0) return;
        update("progress", item, bytes, 0, Math.round(percentage) + "%");
    }

    public static void completed(MediaItem item, long bytes) {
        completed.incrementAndGet();
        update("completed", item, bytes, 0, "");
    }

    public static void cancelled(@Nullable MediaItem item) {
        cancelled.incrementAndGet();
        update("cancelled", item, 0, 0, "");
    }

    public static void failed(MediaItem item, Throwable error) {
        failed.incrementAndGet();
        String detail = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        update("failed", item, 0, 0, detail);
        Log.w(TAG, detail, error);
    }

    public static void skipped(MediaItem item, String reason) {
        skipped.incrementAndGet();
        update("skipped", item, 0, 0, reason);
    }

    public static Snapshot latest() {
        return latest;
    }

    public static Counters counters() {
        return new Counters(started.get(), completed.get(), cancelled.get(), failed.get(), skipped.get());
    }

    /** Clears process-local counters. Used by the settings diagnostics view and debug harnesses. */
    public static void reset() {
        started.set(0);
        completed.set(0);
        cancelled.set(0);
        failed.set(0);
        skipped.set(0);
        latest = new Snapshot("idle", "", 0, 0, "", System.currentTimeMillis());
    }

    /** Human readable snapshot for the settings diagnostics dialog. */
    public static String summary() {
        Counters c = counters();
        Snapshot s = latest();
        return "started=" + c.started()
                + "\ncompleted=" + c.completed()
                + "\ncancelled=" + c.cancelled()
                + "\nfailed=" + c.failed()
                + "\nskipped=" + c.skipped()
                + "\n\nlast=" + s.state()
                + "\nsource=" + s.source()
                + "\nbytes=" + s.bytes()
                + "\ndetail=" + s.detail();
    }

    /**
     * Bytes currently held in the shared playback cache for {@code item}. Because normal playback
     * only reads from that cache (the write sink is disabled), a positive value means the next
     * playback of this exact media item will be served from disk. Returns {@code -1} on failure.
     */
    public static long cachedBytes(@Nullable MediaItem item) {
        CacheFootprint footprint = cachedFootprint(item);
        return footprint.exactBytes() < 0 ? -1 : footprint.totalBytes();
    }

    /**
     * HLS segment bytes are added only when the playlist text is already cached and names those
     * segments. Walking every cache key is skipped: that scan stalls the preload callback once
     * the disk cache holds a large library.
     */
    public static CacheFootprint cachedFootprint(@Nullable MediaItem item) {
        if (item == null || item.localConfiguration == null) return new CacheFootprint(-1, 0, 0);
        try {
            Cache cache = MediaSourceFactory.getCache();
            Uri source = item.localConfiguration.uri;
            String exactKey = CacheKeyFactory.DEFAULT.buildCacheKey(new DataSpec(source));
            long exact = bytesForKey(cache, exactKey);
            if (!isAdaptiveManifest(source)) return new CacheFootprint(exact, 0, 0);
            CacheFootprint parsed = hlsFootprint(cache, source, exactKey, exact);
            if (parsed != null) return parsed;
            CacheFootprint watched = watchedFootprint(cache, exactKey);
            return watched != null ? watched : new CacheFootprint(exact, 0, 0);
        } catch (Exception e) {
            Log.w(TAG, "cache query failed", e);
            return new CacheFootprint(-1, 0, 0);
        }
    }

    /** Remember keys the preload cache wrapper actually opens. Capped so a long session cannot grow without bound. */
    @Nullable
    public static String beginWatch(@Nullable MediaItem item) {
        if (item == null || item.localConfiguration == null) return null;
        String id = CacheKeyFactory.DEFAULT.buildCacheKey(new DataSpec(item.localConfiguration.uri));
        synchronized (WATCH) {
            watchedKeys.remove(id);
            watchedKeys.put(id, new LinkedHashSet<>());
            while (watchedKeys.size() > MAX_WATCHED_ITEMS) {
                Iterator<String> iterator = watchedKeys.keySet().iterator();
                iterator.next();
                iterator.remove();
            }
        }
        return id;
    }

    public static void noteWatchedKey(@Nullable String watchId, @Nullable String key) {
        if (watchId == null || key == null || key.isEmpty()) return;
        synchronized (WATCH) {
            LinkedHashSet<String> keys = watchedKeys.get(watchId);
            if (keys == null || keys.size() >= MAX_WATCHED_KEYS) return;
            keys.add(key);
        }
    }

    @Nullable
    private static CacheFootprint watchedFootprint(Cache cache, String exactKey) {
        LinkedHashSet<String> keys;
        synchronized (WATCH) {
            LinkedHashSet<String> stored = watchedKeys.get(exactKey);
            if (stored == null || stored.isEmpty()) return null;
            keys = new LinkedHashSet<>(stored);
        }
        long related = 0;
        int relatedKeys = 0;
        for (String key : keys) {
            if (key.equals(exactKey)) continue;
            long bytes = bytesForKey(cache, key);
            if (bytes <= 0) continue;
            related += bytes;
            relatedKeys++;
        }
        return relatedKeys == 0 ? null : new CacheFootprint(bytesForKey(cache, exactKey), related, relatedKeys);
    }

    /** Sum cached segment/key URIs named by an HLS playlist, including a different CDN host. */
    @Nullable
    private static CacheFootprint hlsFootprint(Cache cache, Uri source, String exactKey, long exact) {
        String path = source.getPath();
        if (path == null || !path.toLowerCase(Locale.ROOT).endsWith(".m3u8")) return null;
        String text = readCachedText(cache, exactKey, HLS_MAX_PLAYLIST_BYTES);
        if (text == null || !text.contains("#EXTM3U")) return null;
        Set<String> keys = new LinkedHashSet<>();
        collectHlsKeys(cache, source.toString(), text, keys, 0);
        long related = 0;
        int relatedKeys = 0;
        for (String key : keys) {
            if (key.equals(exactKey)) continue;
            long bytes = bytesForKey(cache, key);
            if (bytes <= 0) continue;
            related += bytes;
            relatedKeys++;
        }
        return new CacheFootprint(exact, related, relatedKeys);
    }

    private static void collectHlsKeys(Cache cache, String playlistUrl, String text, Set<String> keys, int depth) {
        if (depth > HLS_MAX_DEPTH) return;
        for (String ref : hlsReferences(text)) {
            String resolved = UriUtil.resolve(playlistUrl, ref);
            if (resolved == null || resolved.isEmpty()) continue;
            String key = CacheKeyFactory.DEFAULT.buildCacheKey(new DataSpec(Uri.parse(resolved)));
            if (!keys.add(key)) continue;
            String childPath = Uri.parse(resolved).getPath();
            if (childPath == null || !childPath.toLowerCase(Locale.ROOT).endsWith(".m3u8")) continue;
            String child = readCachedText(cache, key, HLS_MAX_PLAYLIST_BYTES);
            if (child != null && child.contains("#EXTM3U")) collectHlsKeys(cache, resolved, child, keys, depth + 1);
        }
    }

    static List<String> hlsReferences(String text) {
        List<String> refs = new ArrayList<>();
        if (text == null || text.isEmpty()) return refs;
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) {
                Matcher matcher = HLS_URI.matcher(line);
                while (matcher.find()) refs.add(matcher.group(1));
                continue;
            }
            refs.add(line);
        }
        return refs;
    }

    @Nullable
    private static String readCachedText(Cache cache, String key, int maxBytes) {
        long expected = 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (CacheSpan span : cache.getCachedSpans(key)) {
            if (!span.isCached || span.file == null || span.position != expected) return null;
            expected += span.length;
            if (expected > maxBytes) return null;
            try (InputStream in = new FileInputStream(span.file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
            } catch (IOException e) {
                return null;
            }
        }
        if (expected == 0) return null;
        return out.toString(StandardCharsets.UTF_8);
    }

    private static long bytesForKey(Cache cache, String key) {
        long total = 0;
        for (CacheSpan span : cache.getCachedSpans(key)) total += span.length;
        return total;
    }

    private static boolean isAdaptiveManifest(Uri source) {
        String path = source.getPath();
        if (path == null) return false;
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".m3u8") || lower.endsWith(".mpd");
    }

    private static void update(String state, @Nullable MediaItem item, long bytes, long positionMs, String detail) {
        String source = source(item);
        latest = new Snapshot(state, source, bytes, positionMs, detail, System.currentTimeMillis());
        Log.d(TAG, state + " source=" + source + " bytes=" + bytes + " position=" + positionMs + " " + detail);
    }

    private static String source(@Nullable MediaItem item) {
        if (item == null || item.localConfiguration == null) return "";
        Uri uri = item.localConfiguration.uri;
        String host = uri.getHost();
        return uri.getScheme() + "://" + (host == null ? "local" : host);
    }

    public record Snapshot(String state, String source, long bytes, long positionMs, String detail, long timestampMs) {
    }

    public record Counters(long started, long completed, long cancelled, long failed, long skipped) {
    }

    public record CacheFootprint(long exactBytes, long relatedSegmentBytes, int relatedSegmentKeys) {
        public long totalBytes() {
            return Math.max(0, exactBytes) + Math.max(0, relatedSegmentBytes);
        }
    }
}
