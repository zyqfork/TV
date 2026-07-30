package com.fongmi.android.tv.player.exo;

import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.CacheWriter;

import com.fongmi.android.tv.setting.PreloadSetting;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class PreCache {

    private static final long MAX_PRELOAD_BYTES = 16L * 1024 * 1024;

    private final ExecutorService executor;
    private Future<?> future;
    private CacheWriter writer;
    private MediaItem mediaItem;

    public PreCache() {
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "exo-vod-precache");
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
    }

    /**
     * Warm only the opening bytes of progressive VOD. HLS/DASH are intentionally excluded: their
     * manifests and signed segments change independently, and foreground playback is a better
     * cache producer than a competing speculative downloader.
     */
    public void start(MediaItem mediaItem) {
        this.mediaItem = mediaItem;
        restart();
    }

    public void stop() {
        stopWriter();
        mediaItem = null;
    }

    public void release() {
        stop();
        executor.shutdownNow();
    }

    private void restart() {
        stopWriter();
        if (mediaItem == null) return;
        if (!PreloadSetting.isPreload()) return;
        if (!canPreload(mediaItem)) return;
        long length = Math.min(MAX_PRELOAD_BYTES, PreloadSetting.getPreloadSizeBytes());
        DataSpec dataSpec = new DataSpec.Builder()
                .setUri(mediaItem.localConfiguration.uri)
                .setLength(length)
                .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
                .build();
        writer = new CacheWriter(MediaSourceFactory.createDownloadDataSource(
                ExoUtil.extractHeaders(mediaItem)), dataSpec, null, null);
        CacheWriter activeWriter = writer;
        future = executor.submit(() -> {
            try {
                activeWriter.cache();
            } catch (Exception ignored) {
                // Preload is opportunistic. Foreground playback owns error reporting and retry.
            }
        });
    }

    private void stopWriter() {
        if (writer != null) writer.cancel();
        if (future != null) future.cancel(true);
        writer = null;
        future = null;
    }

    private boolean canPreload(MediaItem mediaItem) {
        if (mediaItem.localConfiguration == null) return false;
        String scheme = mediaItem.localConfiguration.uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;
        String uri = mediaItem.localConfiguration.uri.toString().toLowerCase(Locale.US);
        return !uri.contains(".m3u8") && !uri.contains("=m3u8") && !uri.contains(".mpd");
    }
}
