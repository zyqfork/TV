package com.fongmi.android.tv.storage;

import android.text.TextUtils;
import android.util.Log;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import com.hierynomus.smbj.share.Share;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
        // Prefer encryption first: Apple SMB often accepts auth without it, then hangs on list/read.
        for (boolean encrypt : new boolean[]{true, false}) {
            client = newClient(encrypt);
            try {
                Log.i(TAG, "session host=" + storage.getHost() + " port=" + storage.getPort() + " user=" + storage.getUsername() + " encrypt=" + encrypt);
                if (storage.getPort() > 0) connection = client.connect(storage.getHost(), storage.getPort());
                else connection = client.connect(storage.getHost());
                session = connection.authenticate(buildAuth());
                Log.i(TAG, "session ok encrypt=" + encrypt);
                return;
            } catch (Exception e) {
                last = e;
                Log.e(TAG, "session failed encrypt=" + encrypt, e);
                close();
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
            Log.i(TAG, "connect share=" + shareName);
            Share connected = session.connectShare(shareName);
            if (!(connected instanceof DiskShare)) {
                connected.close();
                throw new IOException("Not a disk share: " + shareName);
            }
            share = (DiskShare) connected;
            connectedShare = shareName;
            Log.i(TAG, "connected ok share=" + shareName);
        } catch (Exception e) {
            Log.e(TAG, "connect share failed: " + shareName, e);
            closeShareOnly();
            throw new IOException(rootMessage(e), e);
        }
    }

    public List<String> listShareNames() throws IOException {
        ensureSession();
        Set<String> found = new LinkedHashSet<>();
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
            try {
                Share s = session.connectShare(name);
                try {
                    if (s instanceof DiskShare) found.add(name);
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
                        found.add(name);
                    }
                }
            }
        }
        List<String> result = new ArrayList<>(found);
        Collections.sort(result, String.CASE_INSENSITIVE_ORDER);
        Log.i(TAG, "listShareNames=" + result);
        return result;
    }

    public List<NetworkEntry> list(String relativePath) throws IOException {
        String path = relativePath == null ? "" : relativePath;
        while (path.startsWith("/")) path = path.substring(1);

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
        Log.i(TAG, "list begin share=" + shareName + " dir=" + dir);
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
            Log.e(TAG, "list failed share=" + shareName + " dir=" + dir, e);
            throw new IOException(rootMessage(e), e);
        }
        Collections.sort(result, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        Log.i(TAG, "list done share=" + shareName + " count=" + result.size());
        return result;
    }

    public File openRead(String relativePath) throws IOException {
        String path = relativePath == null ? "" : relativePath;
        while (path.startsWith("/")) path = path.substring(1);
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
        String text = msg == null ? "" : msg;
        if (text.toUpperCase(Locale.US).contains("STATUS_BAD_NETWORK_NAME") || text.contains("0xc00000cc")) {
            return "共享不存在，请检查共享名或留空后自动列出";
        }
        if (text.toUpperCase(Locale.US).contains("STATUS_LOGON_FAILURE") || text.contains("0xc000006d")) {
            return "账号或密码错误";
        }
        if (text.toUpperCase(Locale.US).contains("STATUS_ACCESS_DENIED") || text.contains("0xc0000022")) {
            return "没有访问权限";
        }
        String type = cur.getClass().getSimpleName();
        if (TextUtils.isEmpty(text)) return type;
        return type + ": " + text;
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
