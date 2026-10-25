package com.fongmi.android.tv.player.exo;

import androidx.annotation.Nullable;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.ContentMetadata;
import androidx.media3.datasource.cache.ContentMetadataMutations;

import java.io.File;
import java.util.NavigableSet;
import java.util.Set;

/** Records cache keys touched by one preload without releasing the shared playback cache. */
final class PreloadKeyCache implements Cache {

    private final Cache delegate;
    private final String watchId;

    PreloadKeyCache(Cache delegate, String watchId) {
        this.delegate = delegate;
        this.watchId = watchId;
    }

    private void note(String key) {
        PreloadDiagnostics.noteWatchedKey(watchId, key);
    }

    @Override
    public long getUid() {
        return delegate.getUid();
    }

    @Override
    public void release() {
    }

    @Override
    public NavigableSet<CacheSpan> addListener(String key, Listener listener) {
        return delegate.addListener(key, listener);
    }

    @Override
    public void removeListener(String key, Listener listener) {
        delegate.removeListener(key, listener);
    }

    @Override
    public NavigableSet<CacheSpan> getCachedSpans(String key) {
        return delegate.getCachedSpans(key);
    }

    @Override
    public Set<String> getKeys() {
        return delegate.getKeys();
    }

    @Override
    public long getCacheSpace() {
        return delegate.getCacheSpace();
    }

    @Override
    public CacheSpan startReadWrite(String key, long position, long length) throws InterruptedException, CacheException {
        note(key);
        return delegate.startReadWrite(key, position, length);
    }

    @Override
    public @Nullable CacheSpan startReadWriteNonBlocking(String key, long position, long length) throws CacheException {
        note(key);
        return delegate.startReadWriteNonBlocking(key, position, length);
    }

    @Override
    public File startFile(String key, long position, long length) throws CacheException {
        note(key);
        return delegate.startFile(key, position, length);
    }

    @Override
    public void commitFile(File file, long length) throws CacheException {
        delegate.commitFile(file, length);
    }

    @Override
    public void releaseHoleSpan(CacheSpan holeSpan) {
        delegate.releaseHoleSpan(holeSpan);
    }

    @Override
    public void removeResource(String key) {
        delegate.removeResource(key);
    }

    @Override
    public void removeSpan(CacheSpan span) {
        delegate.removeSpan(span);
    }

    @Override
    public boolean isCached(String key, long position, long length) {
        return delegate.isCached(key, position, length);
    }

    @Override
    public long getCachedLength(String key, long position, long length) {
        return delegate.getCachedLength(key, position, length);
    }

    @Override
    public long getCachedBytes(String key, long position, long length) {
        return delegate.getCachedBytes(key, position, length);
    }

    @Override
    public void applyContentMetadataMutations(String key, ContentMetadataMutations mutations) throws CacheException {
        delegate.applyContentMetadataMutations(key, mutations);
    }

    @Override
    public ContentMetadata getContentMetadata(String key) {
        return delegate.getContentMetadata(key);
    }
}
