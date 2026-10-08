package com.fongmi.android.tv.player.exo;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PriorityTaskManager;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.preload.DiskPreloadManager;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.PreloadSetting;

import java.io.IOException;

/** Pre-caches the next episode into the same disk cache used by normal playback. */
public class PreCache {

    private final PriorityTaskManager priorityTaskManager;
    private DiskPreloadManager manager;
    private MediaItem preloadedItem;
    private ExoPlayer player;

    public PreCache() {
        this.priorityTaskManager = new PriorityTaskManager();
    }

    public void start(ExoPlayer player, MediaItem mediaItem) {
        this.player = player;
        // Keep matching cached spans alive. A non-matching request is cancelled without deleting
        // already downloaded spans, so cache eviction remains controlled by SimpleCache.
        if (preloadedItem != null && !preloadedItem.equals(mediaItem)) stopManager();
    }

    public void preload(MediaItem mediaItem, long startPositionMs) {
        if (player == null || mediaItem == null) {
            stopManager();
            return;
        }
        if (!PreloadSetting.isPreload()) {
            stopManager();
            PreloadDiagnostics.skipped(mediaItem, "disabled");
            return;
        }
        if (!canPreload(mediaItem)) {
            stopManager();
            PreloadDiagnostics.skipped(mediaItem, "unsupported_scheme");
            return;
        }
        if (MediaSourceFactory.hasSensitiveHeaders(ExoUtil.extractHeaders(mediaItem))) {
            stopManager();
            PreloadDiagnostics.skipped(mediaItem, "sensitive_headers");
            return;
        }
        PreloadPolicy.Decision decision = PreloadPolicy.evaluate(App.get());
        if (!decision.allowed()) {
            stopManager();
            PreloadDiagnostics.skipped(mediaItem, decision.reason());
            return;
        }
        if (mediaItem.equals(preloadedItem) && manager != null) return;
        stopManager();
        preloadedItem = mediaItem;
        manager = createManager(mediaItem, PreloadDiagnostics.beginWatch(mediaItem));
        player.setPriorityTaskManager(priorityTaskManager);
        DiskPreloadManager.Options options = createOptions(startPositionMs);
        PreloadDiagnostics.started(mediaItem, startPositionMs, PreloadSetting.getPreloadDurationMs());
        manager.start(player, mediaItem, options);
    }

    public void clearPreload() {
        stopManager();
    }

    public void stop() {
        stopManager();
        player = null;
    }

    public void release() {
        stop();
    }

    private void stopManager() {
        if (manager != null) manager.release();
        manager = null;
        preloadedItem = null;
        if (player != null) player.setPriorityTaskManager(null);
    }

    private DiskPreloadManager createManager(MediaItem mediaItem, String watchId) {
        androidx.media3.datasource.cache.Cache cache = MediaSourceFactory.getCache();
        if (watchId != null) cache = new PreloadKeyCache(cache, watchId);
        return new DiskPreloadManager.Builder(App.get(), cache, MediaSourceFactory.createUpstreamDataSourceFactory(ExoUtil.extractHeaders(mediaItem)), ExoUtil.buildRenderersFactory())
                .setPriorityTaskManager(priorityTaskManager)
                .setListener(new PreloadListener())
                .build();
    }

    private DiskPreloadManager.Options createOptions(long startPositionMs) {
        return DiskPreloadManager.Options.builder()
                .setStartPositionMs(startPositionMs)
                .setDurationMs(PreloadSetting.getPreloadDurationMs())
                .setMaxThreads(PreloadSetting.getPreloadThreads())
                .build();
    }

    private boolean canPreload(MediaItem mediaItem) {
        if (mediaItem.localConfiguration == null) return false;
        String scheme = mediaItem.localConfiguration.uri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private final class PreloadListener implements DiskPreloadManager.Listener {

        @Override
        public void onProgress(MediaItem mediaItem, long contentLength, long bytesDownloaded, float percentageDownloaded) {
            PreloadDiagnostics.progress(mediaItem, bytesDownloaded, percentageDownloaded);
        }

        @Override
        public void onCompleted(MediaItem mediaItem, long bytesDownloaded) {
            long cachedBytes = PreloadDiagnostics.cachedBytes(mediaItem);
            PreloadDiagnostics.completed(mediaItem, Math.max(0, cachedBytes));
        }

        @Override
        public void onCancelled(MediaItem mediaItem) {
            PreloadDiagnostics.cancelled(mediaItem);
        }

        @Override
        public void onError(MediaItem mediaItem, IOException error) {
            PreloadDiagnostics.failed(mediaItem, error);
        }
    }
}
