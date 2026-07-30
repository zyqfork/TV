package com.fongmi.android.tv.player.exo;

import static androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheDataSink;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ts.TsExtractor;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.fongmi.android.tv.utils.FileUtil;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import java.io.File;
import java.util.Map;

public class MediaSourceFactory implements MediaSource.Factory {

    private static final int CACHE_SPACE_PERCENT = 80;
    /** Do not let media cache consume the last usable space on TV boxes. */
    private static final long CACHE_STORAGE_RESERVE_BYTES = 1024L * 1024L * 1024L;

    private static StandaloneDatabaseProvider databaseProvider;
    private static Cache cache;

    private final DefaultMediaSourceFactory defaultMediaSourceFactory;
    private final boolean cacheEnabled;
    private final boolean cacheWrites;
    private HttpDataSource.Factory httpDataSourceFactory;
    private DataSource.Factory dataSourceFactory;
    private ExtractorsFactory extractorsFactory;

    public MediaSourceFactory(boolean cacheEnabled, boolean cacheWrites) {
        this.cacheEnabled = cacheEnabled;
        this.cacheWrites = cacheWrites;
        defaultMediaSourceFactory = new DefaultMediaSourceFactory(getDataSourceFactory(), getExtractorsFactory());
    }

    static DataSource.Factory createUpstreamDataSourceFactory(Map<String, String> headers) {
        HttpDataSource.Factory factory = createHttpDataSourceFactory();
        factory.setDefaultRequestProperties(headers);
        return new DefaultDataSource.Factory(App.get(), factory);
    }

    /**
     * Creates a bounded-download source that reads and writes the same VOD cache as playback.
     * Live media deliberately never calls this method: caching a moving live edge wastes storage
     * and competes with the foreground stream for network and disk I/O.
     */
    static CacheDataSource createDownloadDataSource(Map<String, String> headers) {
        return createCacheDataSource(createUpstreamDataSourceFactory(headers), true, true)
                .createDataSourceForDownloading();
    }

    static synchronized Cache getCache() {
        if (cache != null) return cache;
        File dir = Path.exoCache();
        return cache = new SimpleCache(dir, new LeastRecentlyUsedCacheEvictor(getMaxCacheSize(dir)), getDatabaseProvider());
    }

    private static StandaloneDatabaseProvider getDatabaseProvider() {
        if (databaseProvider == null) databaseProvider = new StandaloneDatabaseProvider(App.get());
        return databaseProvider;
    }

    private static long getMaxCacheSize(File dir) {
        long usedBytes = FileUtil.getDirectorySize(dir);
        long availableBytes = Math.max(0, FileUtil.getAvailableStorageSpace(dir));
        long usableBytes = Math.max(0, usedBytes + availableBytes - CACHE_STORAGE_RESERVE_BYTES);
        long storageBudget = usableBytes * CACHE_SPACE_PERCENT / 100;
        return Math.min(PreloadSetting.getPreloadSizeBytes(), storageBudget);
    }

    @NonNull
    @Override
    public MediaSource.Factory setDrmSessionManagerProvider(@NonNull DrmSessionManagerProvider drmSessionManagerProvider) {
        return this;
    }

    @NonNull
    @Override
    public MediaSource.Factory setLoadErrorHandlingPolicy(@NonNull LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
        return this;
    }

    @NonNull
    @Override
    public @C.ContentType int[] getSupportedTypes() {
        return defaultMediaSourceFactory.getSupportedTypes();
    }

    @NonNull
    @Override
    public MediaSource createMediaSource(@NonNull MediaItem mediaItem) {
        getHttpDataSourceFactory().setDefaultRequestProperties(ExoUtil.extractHeaders(mediaItem));
        return defaultMediaSourceFactory.createMediaSource(mediaItem);
    }

    private ExtractorsFactory getExtractorsFactory() {
        if (extractorsFactory == null) extractorsFactory = new DefaultExtractorsFactory().setTsExtractorFlags(FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS).setTsExtractorTimestampSearchBytes(TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES * 10);
        return extractorsFactory;
    }

    private DataSource.Factory getDataSourceFactory() {
        if (dataSourceFactory == null) {
            DataSource.Factory upstream = new DefaultDataSource.Factory(App.get(), getHttpDataSourceFactory());
            // Stable live URLs may point to a new edge after reconnect, so disabling writes alone
            // is insufficient: live playback bypasses both cache reads and writes.
            dataSourceFactory = cacheEnabled
                    ? () -> createCacheDataSource(upstream, cacheWrites, false).createDataSource()
                    : upstream;
        }
        return dataSourceFactory;
    }

    private static CacheDataSource.Factory createCacheDataSource(DataSource.Factory upstreamFactory,
                                                                  boolean writeCache,
                                                                  boolean blockOnCache) {
        CacheDataSource.Factory factory = new CacheDataSource.Factory()
                .setCache(getCache())
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
                        | (blockOnCache ? CacheDataSource.FLAG_BLOCK_ON_CACHE : 0));
        if (writeCache) {
            factory.setCacheWriteDataSinkFactory(new CacheDataSink.Factory().setCache(getCache()));
        }
        return factory;
    }

    private HttpDataSource.Factory getHttpDataSourceFactory() {
        if (httpDataSourceFactory == null) httpDataSourceFactory = createHttpDataSourceFactory();
        return httpDataSourceFactory;
    }

    private static HttpDataSource.Factory createHttpDataSourceFactory() {
        if (PlayerSetting.getHttp() == 0) {
            return new DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true);
        }
        return new OkHttpDataSource.Factory(OkHttp.player());
    }
}
