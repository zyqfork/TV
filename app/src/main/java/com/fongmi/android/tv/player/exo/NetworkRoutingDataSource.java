package com.fongmi.android.tv.player.exo;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import com.fongmi.android.tv.storage.NetworkPlayResolver;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** FTP manifests may reference FTP segments or normal HTTP(S) URLs. Never forward FTP credentials. */
final class NetworkRoutingDataSource implements DataSource {
    private final DataSource network = new NetworkFileDataSource();
    private final DataSource upstream;
    private DataSource current;

    NetworkRoutingDataSource(DataSource upstream) { this.upstream = upstream; }
    @Override public void addTransferListener(TransferListener listener) {
        network.addTransferListener(listener); upstream.addTransferListener(listener);
    }
    @Override public long open(DataSpec spec) throws IOException {
        close();
        current = NetworkPlayResolver.isNetworkPlayUrl(spec.uri.toString()) ? network : upstream;
        try { return current.open(spec); } catch (IOException | RuntimeException e) {
            try { close(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }
    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        if (current == null) throw new IOException("Source not open");
        return current.read(buffer, offset, length);
    }
    @Nullable @Override public Uri getUri() { return current == null ? null : current.getUri(); }
    @Override public Map<String, List<String>> getResponseHeaders() { return current == null ? Map.of() : current.getResponseHeaders(); }
    @Override public void close() throws IOException {
        if (current != null) { DataSource closing = current; current = null; closing.close(); }
    }
}
