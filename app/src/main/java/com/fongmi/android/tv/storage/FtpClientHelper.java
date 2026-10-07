package com.fongmi.android.tv.storage;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.ResUtil;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** One operation owns one FTP session; never share a control socket between player loads. */
public final class FtpClientHelper implements Closeable {
    private final NetworkStorage storage;
    private final FTPClient client = new FTPClient();
    private InputStream input;
    private boolean finished;

    public FtpClientHelper(NetworkStorage storage) {
        this.storage = storage;
    }

    private void connect() throws IOException {
        if (!storage.isFtp() || !storage.isValid()) throw new IOException("Invalid FTP endpoint");
        if (storage.hasCredentials() && !storage.isAllowInsecureAuth())
            throw new IOException(ResUtil.getString(R.string.network_storage_ftp_auth_disabled));
        if (NetworkPathPolicy.containsControl(storage.getUsername()) || NetworkPathPolicy.containsControl(storage.getPassword()))
            throw new IOException("Invalid FTP credentials");
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Canceled");
        client.setConnectTimeout(15000);
        client.setDefaultTimeout(20000);
        client.setDataTimeout(20000);
        client.setControlEncoding("UTF-8");
        client.setAutodetectUTF8(true);
        client.setRemoteVerificationEnabled(true);
        client.setIpAddressFromPasvResponse(false);
        String host = storage.getHost();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        client.connect(host, storage.getPort() > 0 ? storage.getPort() : 21);
        client.setSoTimeout(20000);
        if (!FTPReply.isPositiveCompletion(client.getReplyCode())) throw error();
        String user = storage.getUsername().isEmpty() ? "anonymous" : storage.getUsername();
        String password = storage.hasCredentials() ? storage.getPassword() : "anonymous@";
        if (!client.login(user, password)) throw error();
        client.enterLocalPassiveMode();
        if (!client.setFileType(FTP.BINARY_FILE_TYPE)) throw error();
    }

    private IOException error() {
        int code = client.getReplyCode();
        int message = code == 530 ? R.string.network_storage_ftp_auth_failed
                : code == 550 ? R.string.network_storage_ftp_path_missing : R.string.network_storage_ftp_failed;
        // Replies can echo paths or account names; expose only the numeric status.
        return new IOException(ResUtil.getString(message) + " (" + code + ")");
    }

    private String remotePath(String relative) {
        String base = NetworkPathPolicy.cleanRelative(storage.getPath());
        String rel = NetworkPathPolicy.cleanRelative(relative);
        return "/" + base + (!base.isEmpty() && !rel.isEmpty() ? "/" : "") + rel;
    }

    public List<NetworkEntry> list(String relative) throws IOException {
        String rel = NetworkPathPolicy.cleanRelative(relative);
        String remote = remotePath(rel);
        try {
            connect();
            if (!client.changeWorkingDirectory(remote)) throw error();
            FTPFile[] files = client.mlistDir();
            if (!FTPReply.isPositiveCompletion(client.getReplyCode())) files = client.listFiles();
            if (!FTPReply.isPositiveCompletion(client.getReplyCode())) throw error();
            List<NetworkEntry> entries = new ArrayList<>();
            for (FTPFile file : files) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Canceled");
                String name = file.getName();
                if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                        || name.contains("/") || name.contains("\\") || NetworkPathPolicy.containsControl(name)
                        || (!file.isDirectory() && !file.isFile())) continue;
                entries.add(new NetworkEntry(name, rel.isEmpty() ? name : rel + "/" + name,
                        file.isDirectory(), Math.max(0, file.getSize())));
            }
            entries.sort(Comparator.comparing(NetworkEntry::isDirectory).reversed()
                    .thenComparing(NetworkEntry::getName, String.CASE_INSENSITIVE_ORDER));
            return entries;
        } finally { close(); }
    }

    public long open(String relative, long position) throws IOException {
        if (position < 0) throw new IOException("Negative FTP offset");
        String remote = remotePath(relative);
        try {
            connect();
            long size = -1;
            String reply = client.getSize(remote);
            if (reply != null) {
                try { size = Long.parseLong(reply.trim()); } catch (NumberFormatException ignored) { }
            }
            if (size < 0) {
                FTPFile info = client.mlistFile(remote);
                // FTPFile defaults its size to zero even when MLST omitted the size fact.
                // Only an explicit fact is evidence that an empty file really is empty.
                if (info != null && info.isFile() && info.getRawListing() != null
                        && java.util.regex.Pattern.compile("(?:^|;)size=[0-9]+;", java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(info.getRawListing()).find()) size = info.getSize();
            }
            if (size >= 0 && position > size) throw new IOException("FTP position out of range");
            if (size >= 0 && position == size) { finished = true; return size; }
            client.setRestartOffset(position);
            input = client.retrieveFileStream(remote);
            if (input == null) throw error();
            return size;
        } catch (IOException | RuntimeException e) { close(); throw e; }
    }

    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (finished) return -1;
        if (input == null) throw new IOException("FTP stream not open");
        int count = input.read(buffer, offset, length);
        if (count < 0) {
            input.close(); input = null;
            if (!client.completePendingCommand()) throw error();
            finished = true;
        }
        return count;
    }

    @Override public void close() {
        finished = true;
        try { if (input != null) input.close(); } catch (IOException ignored) { }
        input = null;
        // Partial reads/seeks deliberately abandon the session. Do not wait for 226 on close,
        // or send QUIT on a control channel still holding the preceding RETR completion reply.
        try { if (client.isConnected()) client.disconnect(); } catch (IOException ignored) { }
    }
}
