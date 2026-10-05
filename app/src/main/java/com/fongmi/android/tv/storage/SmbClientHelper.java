package com.fongmi.android.tv.storage;

import android.text.TextUtils;
import android.util.Log;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mserref.NtStatus;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import com.hierynomus.smbj.share.Share;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.ResUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class SmbClientHelper implements AutoCloseable {

    private static final String TAG = "SmbClientHelper";
    private static final String[] COMMON_SHARES = {
            "DCIM", "Documents", "downloads", "Downloads", "download", "Download",
            "share", "Share", "public", "Public", "media", "Media", "video", "Video",
            "movies", "Movies", "music", "Music", "homes", "home", "Home", "data", "Data",
            "disk", "Disk", "volume1", "Volume1", "usb", "USB", "files", "Files", "nas",
            "NAS", "jd", "JD", "root", "samba", "smb", "tv", "TV", "movie", "Movie",
            "Photos", "Photo", "iPhone", "影视", "电影", "视频", "下载", "共享", "文件"
    };

    private final NetworkStorage storage;
    private final String shareOverride;
    private SMBClient client;
    private Connection connection;
    private Session session;
    private DiskShare share;
    private String connectedShare;

    public SmbClientHelper(NetworkStorage storage) {
        this(storage, null);
    }

    public SmbClientHelper(NetworkStorage storage, String shareOverride) {
        this.storage = storage;
        this.shareOverride = shareOverride == null ? "" : shareOverride.trim();
    }

    private SMBClient newClient(boolean encrypt) {
        return new SMBClient(SmbConfig.builder()
                .withTimeout(15, TimeUnit.SECONDS)
                .withSoTimeout(15, TimeUnit.SECONDS)
                .withReadTimeout(15, TimeUnit.SECONDS)
                .withTransactTimeout(15, TimeUnit.SECONDS)
                .withMultiProtocolNegotiate(true)
                .withEncryptData(encrypt)
                .withSigningEnabled(true)
                .build());
    }

    public synchronized void ensureSession() throws IOException {
        if (session != null) return;
        Exception last = null;
        // Never silently downgrade transport confidentiality. Legacy servers require explicit opt-in.
        boolean[] modes = storage.isAllowSmbEncryptionDowngrade()
                ? new boolean[]{true, false} : new boolean[]{true};
        for (boolean encrypt : modes) {
            client = newClient(encrypt);
            boolean connected = false;
            try {
                Log.i(TAG, "opening SMB session encrypt=" + encrypt);
                if (storage.getPort() > 0) connection = client.connect(storage.getHost(), storage.getPort());
                else connection = client.connect(storage.getHost());
                connected = true;
                session = connection.authenticate(buildAuth());
                Log.i(TAG, "session ok encrypt=" + encrypt);
                return;
            } catch (Exception e) {
                last = e;
                Log.w(TAG, "SMB session failed encrypt=" + encrypt + " type=" + e.getClass().getSimpleName());
                close();
                // An unreachable host fails identically with and without encryption, so the
                // downgrade retry only doubles the wait. Encryption negotiation problems, by
                // contrast, happen after the TCP connection is up.
                if (!connected) break;
            }
        }
        throw new IOException(rootMessage(last), last);
    }

    public synchronized void connect() throws IOException {
        String shareName = resolveConfiguredShare();
        if (TextUtils.isEmpty(shareName)) throw new IOException("SMB share is empty");
        connectShare(shareName);
    }

    public synchronized void connectShare(String shareName) throws IOException {
        shareName = cleanShare(shareName);
        if (TextUtils.isEmpty(shareName)) throw new IOException("SMB share is empty");
        if (share != null && shareName.equalsIgnoreCase(connectedShare)) return;
        closeShareOnly();
        ensureSession();
        try {
            Log.i(TAG, "connecting SMB share");
            Share connected = session.connectShare(shareName);
            if (!(connected instanceof DiskShare)) {
                connected.close();
                throw new IOException("Not a disk share: " + shareName);
            }
            share = (DiskShare) connected;
            connectedShare = shareName;
            Log.i(TAG, "SMB share connected");
        } catch (Exception e) {
            Log.w(TAG, "SMB share connection failed type=" + e.getClass().getSimpleName());
            closeShareOnly();
            throw new IOException(rootMessage(e), e);
        }
    }

    public List<String> listShareNames() throws IOException {
        ensureSession();
        // SMB share names are case-insensitive. Probing both "share" and "Share" must
        // not surface two rows for the same share — keep the first spelling we saw.
        Map<String, String> found = new java.util.LinkedHashMap<>();
        String configured = cleanShare(storage.getShare());
        List<String> candidates = new ArrayList<>();
        if (!TextUtils.isEmpty(configured)) candidates.add(configured);
        String user = storage.getUsername() == null ? "" : storage.getUsername().trim();
        if (!TextUtils.isEmpty(user) && !candidates.contains(user)) candidates.add(user);
        for (String name : COMMON_SHARES) {
            if (!candidates.contains(name)) candidates.add(name);
        }
        for (String name : candidates) {
            if (Thread.currentThread().isInterrupted()) break;
            String key = name.toLowerCase(Locale.US);
            if (found.containsKey(key)) continue;
            try {
                Share s = session.connectShare(name);
                try {
                    if (s instanceof DiskShare) found.put(key, name);
                } finally {
                    try {
                        s.close();
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null) {
                    String upper = msg.toUpperCase(Locale.US);
                    // Share exists but current account cannot access it.
                    if (upper.contains("STATUS_ACCESS_DENIED") || upper.contains("0xC0000022")) {
                        found.put(key, name);
                    }
                }
            }
        }
        List<String> result = new ArrayList<>(found.values());
        Collections.sort(result, String.CASE_INSENSITIVE_ORDER);
        Log.i(TAG, "SMB shares found count=" + result.size());
        return result;
    }

    public List<NetworkEntry> list(String relativePath) throws IOException {
        String path;
        try {
            path = NetworkPathPolicy.cleanRelative(relativePath);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }

        String shareName = resolveConfiguredShare();
        String dirInsideShare = path;

        if (TextUtils.isEmpty(shareName)) {
            if (TextUtils.isEmpty(path)) {
                List<NetworkEntry> shares = new ArrayList<>();
                for (String name : listShareNames()) {
                    shares.add(new NetworkEntry(name, name, true, 0));
                }
                if (shares.isEmpty()) throw new IOException("No SMB shares found");
                return shares;
            }
            int slash = path.indexOf('/');
            if (slash < 0) {
                shareName = path;
                dirInsideShare = "";
            } else {
                shareName = path.substring(0, slash);
                dirInsideShare = path.substring(slash + 1);
            }
        }

        connectShare(shareName);
        String dir = normalizeDir(dirInsideShare);
        Log.i(TAG, "listing SMB directory");
        List<NetworkEntry> result = new ArrayList<>();
        try {
            for (FileIdBothDirectoryInformation info : share.list(dir)) {
                String name = info.getFileName();
                if (".".equals(name) || "..".equals(name)) continue;
                boolean directory = (info.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
                String childInside = dir.isEmpty() ? name : dir + "/" + name;
                String playPath = TextUtils.isEmpty(resolveConfiguredShare())
                        ? shareName + "/" + childInside
                        : childInside;
                result.add(new NetworkEntry(name, playPath.replace('\\', '/'), directory, info.getEndOfFile()));
            }
        } catch (Exception e) {
            Log.w(TAG, "SMB directory listing failed type=" + e.getClass().getSimpleName());
            throw new IOException(rootMessage(e), e);
        }
        Collections.sort(result, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        Log.i(TAG, "SMB directory entries count=" + result.size());
        return result;
    }

    public File openRead(String relativePath) throws IOException {
        String path;
        try {
            path = NetworkPathPolicy.cleanRelative(relativePath);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
        String shareName = resolveConfiguredShare();
        String fileInside = path;
        if (TextUtils.isEmpty(shareName)) {
            int slash = path.indexOf('/');
            if (slash <= 0) throw new IOException("SMB path missing share: " + path);
            shareName = path.substring(0, slash);
            fileInside = path.substring(slash + 1);
        }
        connectShare(shareName);
        String smbPath = normalizeFile(fileInside);
        return share.openFile(smbPath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null);
    }

    private String resolveConfiguredShare() {
        if (!TextUtils.isEmpty(shareOverride)) return cleanShare(shareOverride);
        return cleanShare(storage.getShare());
    }

    private AuthenticationContext buildAuth() {
        if (TextUtils.isEmpty(storage.getUsername())) {
            return AuthenticationContext.guest();
        }
        String user = storage.getUsername().trim();
        String domain = "";
        int slash = user.indexOf('\\');
        if (slash < 0) slash = user.indexOf('/');
        if (slash > 0) {
            domain = user.substring(0, slash);
            user = user.substring(slash + 1);
        } else if (user.contains("@")) {
            int at = user.indexOf('@');
            domain = user.substring(at + 1);
            user = user.substring(0, at);
        }
        return new AuthenticationContext(user, storage.getPassword().toCharArray(), domain);
    }

    private static String cleanShare(String shareName) {
        if (shareName == null) return "";
        String value = shareName.trim();
        while (value.startsWith("/") || value.startsWith("\\")) value = value.substring(1);
        while (value.endsWith("/") || value.endsWith("\\")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        String msg = e.getMessage();
        while (cur.getCause() != null) {
            cur = cur.getCause();
            if (!TextUtils.isEmpty(cur.getMessage())) msg = cur.getMessage();
        }

        // Prefer the typed SMB status over the exception text. Servers do not agree on which
        // code means "that share is not there": impacket answers STATUS_OBJECT_PATH_NOT_FOUND
        // (0xC000003A) and some appliances answer STATUS_BAD_NETWORK_PATH (0xC00000B3), neither
        // of which contains the string "STATUS_BAD_NETWORK_NAME" the old check looked for -- so
        // the friendly message never appeared and the raw exception name leaked into the toast.
        NtStatus status = smbStatus(e);
        if (status != null) {
            switch (status) {
                case STATUS_BAD_NETWORK_NAME:
                case STATUS_BAD_NETWORK_PATH:
                case STATUS_OBJECT_PATH_NOT_FOUND:
                case STATUS_OBJECT_NAME_NOT_FOUND:
                case STATUS_NO_SUCH_FILE:
                    return ResUtil.getString(R.string.network_storage_smb_share_missing);
                case STATUS_LOGON_FAILURE:
                    return ResUtil.getString(R.string.network_storage_smb_auth_failed);
                case STATUS_ACCESS_DENIED:
                    return ResUtil.getString(R.string.network_storage_smb_access_denied);
                default:
                    break;
            }
        }

        String text = msg == null ? "" : msg;
        String upper = text.toUpperCase(Locale.US);
        if (upper.contains("STATUS_BAD_NETWORK_NAME") || text.contains("0xc00000cc")) {
            return ResUtil.getString(R.string.network_storage_smb_share_missing);
        }
        if (upper.contains("STATUS_LOGON_FAILURE") || text.contains("0xc000006d")) {
            return ResUtil.getString(R.string.network_storage_smb_auth_failed);
        }
        if (upper.contains("STATUS_ACCESS_DENIED") || text.contains("0xc0000022")) {
            return ResUtil.getString(R.string.network_storage_smb_access_denied);
        }
        // A dead host surfaces as a raw transport exception (usually EOFException). Printing the
        // class name tells the user nothing, so map it to something actionable.
        if (cur instanceof java.io.IOException
                || upper.contains("TRANSPORT")
                || upper.contains("CONNECTION")
                || upper.contains("TIMEOUT")
                || upper.contains("REFUSED")) {
            return ResUtil.getString(R.string.network_storage_smb_unreachable);
        }
        return cur.getClass().getSimpleName();
    }

    /**
     * The typed NtStatus of the first {@link SMBApiException} in the cause chain, or null when
     * the failure never reached the SMB layer (transport error, auth handshake, ...).
     */
    static NtStatus smbStatus(Throwable e) {
        for (Throwable cur = e; cur != null; cur = cur.getCause()) {
            if (cur instanceof SMBApiException) return ((SMBApiException) cur).getStatus();
        }
        return null;
    }

    /** Whether the chain says the requested share does not exist on the server. */
    public static boolean isMissingShare(Throwable e) {
        NtStatus status = smbStatus(e);
        if (status != null) {
            switch (status) {
                case STATUS_BAD_NETWORK_NAME:
                case STATUS_BAD_NETWORK_PATH:
                case STATUS_OBJECT_PATH_NOT_FOUND:
                case STATUS_OBJECT_NAME_NOT_FOUND:
                case STATUS_NO_SUCH_FILE:
                    return true;
                default:
                    return false;
            }
        }
        for (Throwable cur = e; cur != null; cur = cur.getCause()) {
            String msg = cur.getMessage();
            if (msg == null) continue;
            String upper = msg.toUpperCase(Locale.US);
            if (upper.contains("STATUS_BAD_NETWORK_NAME") || upper.contains("0XC00000CC")
                    || upper.contains("STATUS_BAD_NETWORK_PATH") || upper.contains("0XC00000B3")
                    || upper.contains("STATUS_OBJECT_PATH_NOT_FOUND") || upper.contains("0XC000003A")
                    || msg.contains("共享不存在")) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeDir(String relativePath) {
        if (TextUtils.isEmpty(relativePath) || "/".equals(relativePath)) return "";
        String path = relativePath.replace('/', '\\');
        while (path.startsWith("\\")) path = path.substring(1);
        while (path.endsWith("\\")) path = path.substring(0, path.length() - 1);
        return path;
    }

    private static String normalizeFile(String relativePath) {
        return normalizeDir(relativePath);
    }

    private void closeShareOnly() {
        try {
            if (share != null) share.close();
        } catch (Exception ignored) {
        }
        share = null;
        connectedShare = null;
    }

    @Override
    public synchronized void close() {
        closeShareOnly();
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
        }
        session = null;
        try {
            if (connection != null) connection.close();
        } catch (Exception ignored) {
        }
        connection = null;
        try {
            if (client != null) client.close();
        } catch (Exception ignored) {
        }
        client = null;
    }
}
