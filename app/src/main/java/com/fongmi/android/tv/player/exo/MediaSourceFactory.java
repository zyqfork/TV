package com.fongmi.android.tv.player.exo;

import static androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ts.TsExtractor;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import java.io.File;
import java.util.Map;

public class MediaSourceFactory implements MediaSource.Factory {

    private static final int CACHE_SPACE_PERCENT = 80;

    private static StandaloneDatabaseProvider databaseProvider;
    private static Cache cache;

    private final DefaultMediaSourceFactory defaultMediaSourceFactory;
    private ExtractorsFactory extractorsFactory;
    @Nullable private DrmSessionManagerProvider drmProvider;
    @Nullable private LoadErrorHandlingPolicy loadErrorPolicy;

    public MediaSourceFactory() {
        defaultMediaSourceFactory = new DefaultMediaSourceFactory(createUpstreamDataSourceFactory(Map.of()), getExtractorsFactory());
    }

    static DataSource.Factory createUpstreamDataSourceFactory(Map<String, String> headers) {
        HttpDataSource.Factory factory = createHttpDataSourceFactory();
        factory.setDefaultRequestProperties(headers == null ? Map.of() : Map.copyOf(headers));
        return new DefaultDataSource.Factory(App.get(), factory);
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
        long usedBytes = Path.size(dir);
        long availableBytes = Math.max(0, Path.available(dir));
        long storageBudget = (usedBytes + availableBytes) * CACHE_SPACE_PERCENT / 100;
        return Math.min(PreloadSetting.getPreloadSizeBytes(), storageBudget);
    }

    @NonNull
    @Override
    public MediaSource.Factory setDrmSessionManagerProvider(@NonNull DrmSessionManagerProvider drmSessionManagerProvider) {
        drmProvider = drmSessionManagerProvider;
        defaultMediaSourceFactory.setDrmSessionManagerProvider(drmSessionManagerProvider);
        return this;
    }

    @NonNull
    @Override
    public MediaSource.Factory setLoadErrorHandlingPolicy(@NonNull LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
        loadErrorPolicy = loadErrorHandlingPolicy;
        defaultMediaSourceFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy);
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
        Uri uri = mediaItem.localConfiguration != null ? mediaItem.localConfiguration.uri : Uri.EMPTY;
        if ("smb".equalsIgnoreCase(uri.getScheme())) {
            ProgressiveMediaSource.Factory factory = new ProgressiveMediaSource.Factory(
                    new SmbDataSource.Factory(), getExtractorsFactory());
            if (drmProvider != null) factory.setDrmSessionManagerProvider(drmProvider);
            if (loadErrorPolicy != null) factory.setLoadErrorHandlingPolicy(loadErrorPolicy);
            return factory.createMediaSource(mediaItem);
        }
        // A shared mutable HTTP factory leaks headers between current playback and preload sources.
        // Build a lightweight per-item factory so Authorization/Referer remain bound to this item.
        Map<String, String> headers = ExoUtil.extractHeaders(mediaItem);
        DataSource.Factory upstream = createUpstreamDataSourceFactory(headers);
        // Media3's default cache key is only the URL. Never let authenticated responses share
        // spans with a later request that happens to use the same URL and different credentials.
        // RTMP is a streaming protocol, not an HTTP file. Never wrap its non-seekable
        // transport in the URL-keyed disk cache; DefaultDataSource loads the RTMP extension.
        DataSource.Factory source = "rtmp".equalsIgnoreCase(uri.getScheme()) || hasSensitiveHeaders(headers)
                ? upstream
                : () -> getCacheDataSource(upstream).createDataSource();
        DefaultMediaSourceFactory factory = new DefaultMediaSourceFactory(source, getExtractorsFactory());
        if (drmProvider != null) factory.setDrmSessionManagerProvider(drmProvider);
        if (loadErrorPolicy != null) factory.setLoadErrorHandlingPolicy(loadErrorPolicy);
        return factory.createMediaSource(mediaItem);
    }

    private ExtractorsFactory getExtractorsFactory() {
        if (extractorsFactory == null) extractorsFactory = new DefaultExtractorsFactory().setTsExtractorFlags(FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS).setTsExtractorTimestampSearchBytes(TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES * 10);
        return extractorsFactory;
    }

    private CacheDataSource.Factory getCacheDataSource(DataSource.Factory upstreamFactory) {
        return new CacheDataSource.Factory().setCache(getCache()).setUpstreamDataSourceFactory(upstreamFactory).setCacheWriteDataSinkFactory(null).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    public static boolean hasSensitiveHeaders(Map<String, String> headers) {
        if (headers == null) return false;
        for (String name : headers.keySet()) {
            if (name == null) continue;
            if ("authorization".equalsIgnoreCase(name)
                    || "proxy-authorization".equalsIgnoreCase(name)
                    || "cookie".equalsIgnoreCase(name)
                    || "set-cookie".equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static HttpDataSource.Factory createHttpDataSourceFactory() {
        if (PlayerSetting.getHttp() == 0) {
            // Do not allow HTTPS credentials to follow a protocol downgrade. OkHttp separately
            // strips Authorization on cross-origin redirects.
            return new DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(false);
        }
        return new OkHttpDataSource.Factory(OkHttp.player());
    }
}
