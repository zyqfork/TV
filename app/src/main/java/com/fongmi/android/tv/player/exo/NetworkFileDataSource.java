package com.fongmi.android.tv.player.exo;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import com.fongmi.android.tv.storage.NetworkPlayResolver;
import com.fongmi.android.tv.storage.NetworkStorageAccess;

import java.io.IOException;

/** Per-open SMB/FTP reader. Seeks reopen at the requested offset; no credential-shared disk cache. */
public class NetworkFileDataSource extends BaseDataSource {
    public static final class Factory implements DataSource.Factory {
        @Override public DataSource createDataSource() { return new NetworkFileDataSource(); }
    }
    private NetworkStorageAccess.Reader reader;
    private Uri uri;
    private long remaining;
    private boolean opened;

    public NetworkFileDataSource() { super(true); }

    @Override public long open(DataSpec spec) throws IOException {
        close();
        transferInitializing(spec);
        try {
            uri = spec.uri;
            reader = NetworkStorageAccess.open(NetworkPlayResolver.requireStorage(uri.toString()),
                    NetworkPlayResolver.relativePath(uri.toString()), spec.position);
            long length = reader.length();
            remaining = length >= 0 ? length - spec.position : C.LENGTH_UNSET;
            if (spec.length != C.LENGTH_UNSET) remaining = remaining == C.LENGTH_UNSET
                    ? spec.length : Math.min(remaining, spec.length);
            opened = true;
            transferStarted(spec);
            return remaining;
        } catch (Exception e) { close(); throw e instanceof IOException ? (IOException) e : new IOException(e); }
    }

    @Override public int read(byte[] b, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (remaining == 0) return C.RESULT_END_OF_INPUT;
        if (reader == null) throw new IOException("Network source not open");
        int count;
        try {
            count = reader.read(b, offset, remaining == C.LENGTH_UNSET ? length : (int) Math.min(length, remaining));
        } catch (RuntimeException e) { throw new IOException(e); }
        if (count < 0) {
            if (remaining > 0) throw new java.io.EOFException("Truncated network file");
            return C.RESULT_END_OF_INPUT;
        }
        if (count > 0) {
            if (remaining != C.LENGTH_UNSET) remaining -= count;
            bytesTransferred(count);
        }
        return count;
    }
    @Nullable @Override public Uri getUri() { return uri; }
    @Override public void close() {
        try { if (reader != null) reader.close(); } catch (IOException ignored) { }
        reader = null; uri = null; remaining = 0;
        if (opened) { opened = false; transferEnded(); }
    }
}
