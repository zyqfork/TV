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
        return url.startsWith("smb://") || url.startsWith("webdav://");
    }

    public static String storageId(String url) {
        Uri uri = Uri.parse(url);
        return uri.getHost();
    }

    public static String relativePath(String url) {
        Uri uri = Uri.parse(url);
        String path = uri.getPath();
        if (path == null) return "";
        while (path.startsWith("/")) path = path.substring(1);
        return path;
    }

    public static NetworkStorage requireStorage(String url) {
        NetworkStorage storage = NetworkStorageStore.find(storageId(url));
        if (storage == null) throw new IllegalArgumentException("Network storage not found");
        return storage;
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
        if (!TextUtils.isEmpty(storage.getUsername()) || !TextUtils.isEmpty(storage.getPassword())) {
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
            headers = headers == null ? Collections.emptyMap() : headers;
        }
    }
}
