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

public class WebDavClientHelper {

    private final NetworkStorage storage;

    public WebDavClientHelper(NetworkStorage storage) {
        this.storage = storage;
    }

    public List<NetworkEntry> list(String relativePath) throws IOException {
        Sardine sardine = create();
        String url = joinUrl(storage.webDavBaseUrl(), relativePath);
        if (!url.endsWith("/")) url = url + "/";
        List<DavResource> resources = sardine.list(url);
        List<NetworkEntry> result = new ArrayList<>();
        String basePath = URI.create(url).getPath();
        if (basePath == null) basePath = "/";
        if (!basePath.endsWith("/")) basePath = basePath + "/";
        for (DavResource resource : resources) {
            String hrefPath = resource.getPath();
            if (hrefPath == null) continue;
            String normalizedHref = hrefPath.endsWith("/") && hrefPath.length() > 1
                    ? hrefPath.substring(0, hrefPath.length() - 1) : hrefPath;
            String normalizedBase = basePath.endsWith("/") && basePath.length() > 1
                    ? basePath.substring(0, basePath.length() - 1) : basePath;
            if (normalizedHref.equals(normalizedBase) || hrefPath.equals(basePath)) continue;
            String name = resource.getName();
            if (TextUtils.isEmpty(name)) continue;
            String childRel = joinRel(relativePath, name);
            long size = resource.getContentLength() == null ? 0 : resource.getContentLength();
            result.add(new NetworkEntry(name, childRel, resource.isDirectory(), size));
        }
        Collections.sort(result, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        return result;
    }

    private Sardine create() {
        OkHttpSardine sardine = new OkHttpSardine();
        if (!TextUtils.isEmpty(storage.getUsername()) || !TextUtils.isEmpty(storage.getPassword())) {
            sardine.setCredentials(storage.getUsername(), storage.getPassword());
        }
        return sardine;
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
