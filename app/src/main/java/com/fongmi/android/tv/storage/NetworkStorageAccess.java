package com.fongmi.android.tv.storage;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/** Single protocol dispatch for browsing and seekable file reads; WebDAV keeps HTTP playback. */
public final class NetworkStorageAccess {
    private NetworkStorageAccess() { }

    public record Listing(List<NetworkEntry> entries, boolean shareRediscovered) { }

    public static Listing list(NetworkStorage storage, String path) throws Exception {
        if (storage == null || !storage.isValid()) throw new IOException("Invalid network storage");
        if (storage.isWebDav()) return new Listing(new WebDavClientHelper(storage).list(path), false);
        if (storage.isFtp()) {
            try (FtpClientHelper helper = new FtpClientHelper(storage)) {
                return new Listing(helper.list(path), false);
            }
        }
        if (!storage.isSmb()) throw new IOException("Unsupported storage protocol");
        try (SmbClientHelper helper = new SmbClientHelper(storage)) {
            return new Listing(helper.list(path), false);
        } catch (Exception e) {
            if (path != null && !path.isEmpty() || storage.getShare().isEmpty() || !SmbClientHelper.isMissingShare(e)) throw e;
            NetworkStorage copy = NetworkStorage.create(NetworkStorage.TYPE_SMB);
            copy.setId(storage.getId()); copy.setHost(storage.getHost()); copy.setPort(storage.getPort());
            copy.setUsername(storage.getUsername()); copy.setPassword(storage.getPassword());
            copy.setAllowSmbEncryptionDowngrade(storage.isAllowSmbEncryptionDowngrade());
            try (SmbClientHelper helper = new SmbClientHelper(copy)) {
                List<NetworkEntry> shares = helper.list("");
                storage.setShare("");
                NetworkStorageStore.save(storage);
                return new Listing(shares, true);
            }
        }
    }

    public interface Reader extends Closeable {
        long length();
        int read(byte[] buffer, int offset, int length) throws IOException;
    }

    public static Reader open(NetworkStorage storage, String path, long position) throws IOException {
        if (storage.isFtp()) {
            FtpClientHelper helper = new FtpClientHelper(storage);
            long length = helper.open(path, position);
            return new Reader() {
                public long length() { return length; }
                public int read(byte[] b, int o, int l) throws IOException { return helper.read(b, o, l); }
                public void close() { helper.close(); }
            };
        }
        if (!storage.isSmb()) throw new IOException("Not a direct file storage");
        SmbClientHelper helper = new SmbClientHelper(storage);
        com.hierynomus.smbj.share.File openedFile = null;
        try {
            openedFile = helper.openRead(path);
            final com.hierynomus.smbj.share.File file = openedFile;
            long length = file.getFileInformation().getStandardInformation().getEndOfFile();
            if (position < 0 || position > length) { file.close(); throw new IOException("Position out of range"); }
            return new Reader() {
                private long offset = position;
                public long length() { return length; }
                public int read(byte[] b, int o, int l) throws IOException {
                    int n = file.read(b, offset, o, l);
                    if (n > 0) offset += n;
                    return n <= 0 ? -1 : n;
                }
                public void close() { try { file.close(); } catch (Exception ignored) { } finally { helper.close(); } }
            };
        } catch (Exception e) {
            try { if (openedFile != null) openedFile.close(); } catch (Exception ignored) { }
            helper.close(); throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }
}
