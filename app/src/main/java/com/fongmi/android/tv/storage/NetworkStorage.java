package com.fongmi.android.tv.storage;

import android.net.Uri;
import android.text.TextUtils;

import java.util.UUID;

public class NetworkStorage {

    public static final String TYPE_SMB = "smb";
    public static final String TYPE_WEBDAV = "webdav";

    private String id;
    private String type;
    private String name;
    private String host;
    private int port;
    private String share;
    private String path;
    private String username;
    private String password;
    private boolean https;

    public static NetworkStorage create(String type) {
        NetworkStorage item = new NetworkStorage();
        item.id = UUID.randomUUID().toString();
        item.type = type;
        item.name = "";
        item.host = "";
        item.port = 0;
        item.share = "";
        item.path = "/";
        item.username = "";
        item.password = "";
        item.https = TYPE_WEBDAV.equals(type);
        return item;
    }

    public String getId() {
        return id == null ? "" : id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type == null ? TYPE_SMB : type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public boolean isSmb() {
        return TYPE_SMB.equalsIgnoreCase(getType());
    }

    public boolean isWebDav() {
        return TYPE_WEBDAV.equalsIgnoreCase(getType());
    }

    public String getName() {
        return name == null ? "" : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHost() {
        return host == null ? "" : host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getShare() {
        return share == null ? "" : share;
    }

    public void setShare(String share) {
        this.share = share;
    }

    public String getPath() {
        return TextUtils.isEmpty(path) ? "/" : path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getUsername() {
        return username == null ? "" : username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password == null ? "" : password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isHttps() {
        return https;
    }

    public void setHttps(boolean https) {
        this.https = https;
    }

    public String displayTitle() {
        if (!TextUtils.isEmpty(getName())) return getName();
        return getHost();
    }

    public String displaySubtitle() {
        if (isSmb()) {
            String share = getShare();
            return "smb://" + getHost() + (TextUtils.isEmpty(share) ? "" : "/" + share);
        }
        return webDavBaseUrl();
    }

    public String webDavBaseUrl() {
        StringBuilder sb = new StringBuilder(https ? "https://" : "http://");
        sb.append(getHost());
        if (port > 0) sb.append(':').append(port);
        String base = getPath();
        if (!base.startsWith("/")) base = "/" + base;
        while (base.length() > 1 && base.endsWith("/")) base = base.substring(0, base.length() - 1);
        sb.append(base.isEmpty() ? "/" : base);
        return sb.toString();
    }

    public String toPlayUrl(String relativePath) {
        String rel = relativePath == null ? "" : relativePath;
        while (rel.startsWith("/")) rel = rel.substring(1);
        Uri.Builder builder = new Uri.Builder().scheme(getType()).encodedAuthority(getId());
        if (!rel.isEmpty()) {
            for (String part : rel.split("/")) {
                if (!part.isEmpty()) builder.appendPath(part);
            }
        }
        return builder.build().toString();
    }

    public boolean isValid() {
        return !TextUtils.isEmpty(getHost());
    }
}
