package com.fongmi.android.tv.player.exo;

import androidx.annotation.Nullable;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.TransferListener;

/** Compatibility name for callers; SMB and FTP now use the shared file data source. */
public class SmbDataSource extends NetworkFileDataSource {
    public static final class Factory implements DataSource.Factory {
        @Nullable private TransferListener listener;
        public Factory setTransferListener(@Nullable TransferListener listener) { this.listener = listener; return this; }
        @Override public DataSource createDataSource() {
            SmbDataSource source = new SmbDataSource();
            if (listener != null) source.addTransferListener(listener);
            return source;
        }
    }
}
