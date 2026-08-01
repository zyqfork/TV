package com.fongmi.android.tv.player.exo;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import com.fongmi.android.tv.storage.NetworkPlayResolver;
import com.fongmi.android.tv.storage.NetworkStorage;
import com.fongmi.android.tv.storage.SmbClientHelper;
import com.hierynomus.smbj.share.File;

import java.io.IOException;

public class SmbDataSource extends BaseDataSource {

    public static final class Factory implements DataSource.Factory {

        @Nullable
        private TransferListener listener;

        public Factory setTransferListener(@Nullable TransferListener listener) {
            this.listener = listener;
            return this;
        }

        @Override
        public DataSource createDataSource() {
            SmbDataSource source = new SmbDataSource();
            if (listener != null) source.addTransferListener(listener);
            return source;
        }
    }

    private SmbClientHelper helper;
    private File file;
    private Uri uri;
    private long bytesRemaining;
    private long readPosition;
    private boolean opened;

    public SmbDataSource() {
        super(true);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        try {
            uri = dataSpec.uri;
            NetworkStorage storage = NetworkPlayResolver.requireStorage(uri.toString());
            if (!storage.isSmb()) throw new IOException("Not an SMB url");
            helper = new SmbClientHelper(storage);
            file = helper.openRead(NetworkPlayResolver.relativePath(uri.toString()));
            long length = file.getFileInformation().getStandardInformation().getEndOfFile();
            long position = dataSpec.position;
            if (position > length) throw new IOException("Position out of range");
            readPosition = position;
            if (dataSpec.length != C.LENGTH_UNSET) {
                bytesRemaining = dataSpec.length;
            } else {
                bytesRemaining = length - position;
            }
            opened = true;
            transferStarted(dataSpec);
            return bytesRemaining;
        } catch (Exception e) {
            close();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (bytesRemaining == 0) return C.RESULT_END_OF_INPUT;
        try {
            int toRead = length;
            if (bytesRemaining != C.LENGTH_UNSET) toRead = (int) Math.min(toRead, bytesRemaining);
            int read = file.read(buffer, readPosition, offset, toRead);
            if (read < 0) return C.RESULT_END_OF_INPUT;
            if (read == 0) return C.RESULT_END_OF_INPUT;
            readPosition += read;
            if (bytesRemaining != C.LENGTH_UNSET) bytesRemaining -= read;
            bytesTransferred(read);
            return read;
        } catch (Exception e) {
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    @Nullable
    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() {
        uri = null;
        try {
            if (file != null) file.close();
        } catch (Exception ignored) {
        }
        file = null;
        if (helper != null) helper.close();
        helper = null;
        if (opened) {
            opened = false;
            transferEnded();
        }
    }
}
