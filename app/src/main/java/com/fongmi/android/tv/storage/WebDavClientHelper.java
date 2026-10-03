package com.fongmi.android.tv.storage;

import android.text.TextUtils;

import com.thegrizzlylabs.sardineandroid.DavResource;
import com.thegrizzlylabs.sardineandroid.Sardine;
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

public class WebDavClientHelper {

    private static final int MAX_ENTRIES = 2000;
    /** One client per endpoint: a fresh OkHttpClient per listing leaked a connection pool each time. */
    private static final Map<String, OkHttpClient> CLIENTS = new ConcurrentHashMap<>();

    private final NetworkStorage storage;

    public WebDavClientHelper(NetworkStorage storage) {
        this.storage = storage;
    }

    public List<NetworkEntry> list(String relativePath) throws IOException {
        Sardine sardine = create();
        String cleanPath;
        try {
            cleanPath = NetworkPathPolicy.cleanRelative(relativePath);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
        String url = joinUrl(storage.webDavBaseUrl(), cleanPath);
        if (!url.endsWith("/")) url = url + "/";
        List<DavResource> resources = sardine.list(url);
        List<NetworkEntry> result = new ArrayList<>();
        String basePath = URI.create(url).getPath();
        if (basePath == null) basePath = "/";
        if (!basePath.endsWith("/")) basePath = basePath + "/";
        int accepted = 0;
        for (DavResource resource : resources) {
            if (accepted >= MAX_ENTRIES) break;
            String hrefPath = resource.getPath();
            if (hrefPath == null) continue;
            String normalizedHref = hrefPath.endsWith("/") && hrefPath.length() > 1
                    ? hrefPath.substring(0, hrefPath.length() - 1) : hrefPath;
            String normalizedBase = basePath.endsWith("/") && basePath.length() > 1
                    ? basePath.substring(0, basePath.length() - 1) : basePath;
            if (normalizedHref.equals(normalizedBase) || hrefPath.equals(basePath)) continue;
            String name = resource.getName();
            if (TextUtils.isEmpty(name) || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")
                    || NetworkPathPolicy.containsControl(name)) continue;
            String childRel;
            try {
                childRel = NetworkPathPolicy.cleanRelative(joinRel(cleanPath, name));
            } catch (IllegalArgumentException e) {
                continue;
            }
            long size = resource.getContentLength() == null ? 0 : resource.getContentLength();
            result.add(new NetworkEntry(name, childRel, resource.isDirectory(), size));
            accepted++;
        }
        Collections.sort(result, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        return result;
    }

    private Sardine create() throws IOException {
        if (!storage.isValid()) throw new IOException("Invalid WebDAV endpoint");
        if (!storage.isHttps() && storage.hasCredentials() && !storage.isAllowInsecureAuth()) {
            throw new IOException("HTTP Basic authentication requires explicit insecure-auth permission");
        }
        URI origin = URI.create(storage.webDavBaseUrl());
        OkHttpSardine sardine = new OkHttpSardine(clientFor(origin));
        if (storage.hasCredentials()) {
            sardine.setCredentials(storage.getUsername(), storage.getPassword());
        }
        return sardine;
    }

    private static OkHttpClient clientFor(URI origin) {
        String key = origin.getScheme() + "://" + origin.getHost() + ":" + effectivePort(origin);
        return CLIENTS.computeIfAbsent(key, ignored -> new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .addNetworkInterceptor(chain -> {
                    URI target = URI.create(chain.request().url().toString());
                    if (!sameOrigin(origin, target)) throw new IOException("Cross-origin WebDAV redirect blocked");
                    return chain.proceed(chain.request());
                })
                .build());
    }

    private static boolean sameOrigin(URI expected, URI actual) {
        if (!expected.getScheme().equalsIgnoreCase(actual.getScheme())) return false;
        if (expected.getHost() == null || actual.getHost() == null || !expected.getHost().equalsIgnoreCase(actual.getHost())) return false;
        return effectivePort(expected) == effectivePort(actual);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String joinUrl(String base, String relativePath) {
        String b = base;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        String rel = relativePath == null ? "" : relativePath;
        while (rel.startsWith("/")) rel = rel.substring(1);
        if (rel.isEmpty()) return b;
        StringBuilder sb = new StringBuilder(b);
        for (String part : rel.split("/")) {
            if (part.isEmpty()) continue;
            sb.append('/').append(android.net.Uri.encode(part));
        }
        return sb.toString();
    }

    private static String joinRel(String parent, String name) {
        String p = parent == null ? "" : parent;
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.isEmpty()) return name;
        return p + "/" + name;
    }
}
