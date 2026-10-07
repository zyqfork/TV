package com.fongmi.android.tv.storage;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class NetworkPlayResolver {

    private NetworkPlayResolver() {
    }

    public static boolean isNetworkPlayUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            boolean candidate = (NetworkStorage.TYPE_SMB.equals(scheme) || NetworkStorage.TYPE_WEBDAV.equals(scheme) || NetworkStorage.TYPE_FTP.equals(scheme))
                    && !TextUtils.isEmpty(uri.getHost()) && TextUtils.isEmpty(uri.getUserInfo())
                    && TextUtils.isEmpty(uri.getQuery()) && TextUtils.isEmpty(uri.getFragment());
            if (!candidate || !NetworkStorage.TYPE_FTP.equals(scheme)) return candidate;
            // Do not intercept ordinary ftp://server/file links previously handled by MPV.
            // Our private URLs use saved IDs, not actual server addresses.
            return uri.getHost().matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                    || NetworkStorageStore.find(uri.getHost()) != null;
        } catch (Exception e) {
            return false;
        }
    }

    public static String storageId(String url) {
        Uri uri = Uri.parse(url);
        return uri.getHost();
    }

    public static String relativePath(String url) {
        if (!isNetworkPlayUrl(url)) throw new IllegalArgumentException("Invalid network playback URL");
        return NetworkPathPolicy.cleanRelative(Uri.parse(url).getPath());
    }

    public static NetworkStorage requireStorage(String url) {
        if (!isNetworkPlayUrl(url)) throw new IllegalArgumentException("Invalid network playback URL");
        NetworkStorage storage = NetworkStorageStore.find(storageId(url));
        if (storage == null || !storage.isValid()) throw new IllegalArgumentException("Network storage not found");
        String scheme = Uri.parse(url).getScheme();
        if (!storage.getType().equals(scheme)) throw new IllegalArgumentException("Network storage type mismatch");
        return storage;
    }

    public static String displayTitle(String url) {
        if (!isNetworkPlayUrl(url)) return "";
        try {
            return requireStorage(url).displayTitle();
        } catch (Exception e) {
            return "";
        }
    }

    public static String fileName(String url) {
        String path = relativePath(url);
        if (TextUtils.isEmpty(path)) return "";
        int index = path.lastIndexOf('/');
        return index < 0 ? path : path.substring(index + 1);
    }

    public static String protocolLabel(String url) {
        if (!isNetworkPlayUrl(url)) return "";
        try {
            NetworkStorage storage = requireStorage(url);
            return storage.isFtp() ? "FTP" : storage.isWebDav() ? "WebDAV" : "SMB";
        } catch (Exception e) {
            return url.startsWith("ftp://") ? "FTP" : url.startsWith("webdav://") ? "WebDAV" : "SMB";
        }
    }

    /** Display path like /DCIM/Movies/a.mp4 (share + relative when applicable). */
    public static String displayPath(String url) {
        if (!isNetworkPlayUrl(url)) return "";
        String path = relativePath(url);
        try {
            NetworkStorage storage = requireStorage(url);
            if (storage.isSmb()) {
                String share = storage.getShare();
                if (!TextUtils.isEmpty(share)) {
                    if (path.isEmpty()) path = share;
                    else if (!path.equals(share) && !path.startsWith(share + "/")) path = share + "/" + path;
                }
            }
        } catch (Exception ignored) {
        }
        if (path.isEmpty()) return "/";
        return path.startsWith("/") ? path : "/" + path;
    }

    /** Resolve webdav://id/... to http(s) URL + Basic auth headers for PlayerManager. */
    public static ResolvedWebDav resolveWebDav(String url) {
        NetworkStorage storage = requireStorage(url);
        if (!storage.isWebDav()) throw new IllegalArgumentException("Not a WebDAV storage");
        String rel = relativePath(url);
        String base = storage.webDavBaseUrl();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String httpUrl = base + (rel.isEmpty() ? "" : "/" + encodeRel(rel));
        Map<String, String> headers = new HashMap<>();
        if (!storage.isHttps() && storage.hasCredentials() && !storage.isAllowInsecureAuth()) {
            throw new IllegalArgumentException("HTTP Basic authentication is disabled for this endpoint");
        }
        if (storage.hasCredentials()) {
            String token = storage.getUsername() + ":" + storage.getPassword();
            String basic = Base64.encodeToString(token.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            headers.put("Authorization", "Basic " + basic);
        }
        return new ResolvedWebDav(httpUrl, headers);
    }

    private static String encodeRel(String rel) {
        StringBuilder sb = new StringBuilder();
        String[] parts = rel.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(Uri.encode(parts[i]));
        }
        return sb.toString();
    }

    public record ResolvedWebDav(String url, Map<String, String> headers) {
        public ResolvedWebDav {
            headers = headers == null ? Collections.emptyMap() : Collections.unmodifiableMap(new HashMap<>(headers));
        }
    }
}
