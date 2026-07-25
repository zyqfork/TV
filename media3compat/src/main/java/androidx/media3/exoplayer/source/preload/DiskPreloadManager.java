package androidx.media3.exoplayer.source.preload;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PriorityTaskManager;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.RenderersFactory;

/**
 * Compatibility implementation for the private disk preloader used upstream.
 *
 * <p>Playback remains fully functional when preloading is unavailable. The
 * class preserves the original API and can later be replaced by a cache writer
 * without changing application code.
 */
public final class DiskPreloadManager {

    private DiskPreloadManager() {
    }

    public void start(ExoPlayer player, MediaItem mediaItem, Options options) {
        // The player's normal CacheDataSource populates the same cache during playback.
    }

    public void release() {
    }

    public static final class Builder {

        public Builder(Cache cache, DataSource.Factory dataSourceFactory, RenderersFactory renderersFactory) {
        }

        public Builder setPriorityTaskManager(PriorityTaskManager priorityTaskManager) {
            return this;
        }

        public DiskPreloadManager build() {
            return new DiskPreloadManager();
        }
    }

    public static final class Options {

        private final long durationMs;
        private final int maxThreads;

        private Options(long durationMs, int maxThreads) {
            this.durationMs = durationMs;
            this.maxThreads = maxThreads;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {

            private long durationMs;
            private int maxThreads = 1;

            public Builder setDurationMs(long durationMs) {
                this.durationMs = durationMs;
                return this;
            }

            public Builder setMaxThreads(int maxThreads) {
                this.maxThreads = maxThreads;
                return this;
            }

            public Options build() {
                return new Options(durationMs, maxThreads);
            }
        }
    }
}
