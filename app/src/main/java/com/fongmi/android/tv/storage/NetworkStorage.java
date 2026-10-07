package com.fongmi.android.tv.storage;

import android.net.Uri;
import android.text.TextUtils;

import com.google.gson.annotations.SerializedName;

import java.util.UUID;

/**
 * Persisted to preferences as JSON, so every field carries its wire name.
 *
 * Without {@code @SerializedName} R8 renames the fields in a release build and Gson then writes —
 * and reads — whatever the obfuscator chose. That round-trips inside a single build, which is why
 * it goes unnoticed, but the mapping changes as the code changes: saved storages (and the
 * credential-store keys derived from {@code id}) would silently stop loading after an update.
 */
public class NetworkStorage {

    public static final String TYPE_SMB = "smb";
    public static final String TYPE_WEBDAV = "webdav";
    public static final String TYPE_FTP = "ftp";

    @SerializedName("id")
    private String id;
    @SerializedName("type")
    private String type;
    @SerializedName("name")
    private String name;
    @SerializedName("host")
    private String host;
    @SerializedName("port")
    private int port;
    @SerializedName("share")
    private String share;
    @SerializedName("path")
    private String path;
    /** Never serialised here: credentials live in the keystore-backed NetworkCredentialStore. */
    @SerializedName("username")
    private transient String username;
    @SerializedName("password")
    private transient String password;
    @SerializedName("https")
    private boolean https;
    @SerializedName("allowInsecureAuth")
    private boolean allowInsecureAuth;
    @SerializedName("allowSmbEncryptionDowngrade")
    private boolean allowSmbEncryptionDowngrade;

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
        item.allowInsecureAuth = false;
        item.allowSmbEncryptionDowngrade = false;
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

    public boolean isFtp() { return TYPE_FTP.equalsIgnoreCase(getType()); }

    public int effectivePort() { return port > 0 ? port : isSmb() ? 445 : isFtp() ? 21 : https ? 443 : 80; }

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

    public boolean isAllowInsecureAuth() {
        return allowInsecureAuth;
    }

    public void setAllowInsecureAuth(boolean allowInsecureAuth) {
        this.allowInsecureAuth = allowInsecureAuth;
    }

    public boolean isAllowSmbEncryptionDowngrade() {
        return allowSmbEncryptionDowngrade;
    }

    public void setAllowSmbEncryptionDowngrade(boolean allowSmbEncryptionDowngrade) {
        this.allowSmbEncryptionDowngrade = allowSmbEncryptionDowngrade;
    }

    public boolean hasCredentials() {
        return !TextUtils.isEmpty(getUsername()) || !TextUtils.isEmpty(getPassword());
    }

    public String displayTitle() {
        if (!TextUtils.isEmpty(getName())) return getName();
        return getHost();
    }

    public String displaySubtitle() {
        if (isSmb()) {
            String share = getShare();
            // Never render a bare "smb://" for incomplete rows (empty host/share).
            if (TextUtils.isEmpty(getHost()) && TextUtils.isEmpty(share)) return "";
            // A non-default port is part of the endpoint: without it the row cannot be verified.
            String authority = getHost() + (port > 0 && port != 445 ? ":" + port : "");
            return "smb://" + authority + (TextUtils.isEmpty(share) ? "" : "/" + share);
        }
        if (isFtp()) {
            String host = getHost();
            if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
            return new Uri.Builder().scheme("ftp").encodedAuthority(host + (port > 0 && port != 21 ? ":" + port : ""))
                    .path("/" + NetworkPathPolicy.cleanRelative(getPath())).build().toString();
        }
        return webDavBaseUrl();
    }

    public String webDavBaseUrl() {
        String host = getHost();
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        String authority = host + (port > 0 ? ":" + port : "");
        Uri.Builder builder = new Uri.Builder().scheme(https ? "https" : "http").encodedAuthority(authority);
        String base = NetworkPathPolicy.cleanRelative(getPath());
        if (base.isEmpty()) return builder.path("/").build().toString();
        for (String part : base.split("/")) builder.appendPath(part);
        return builder.build().toString();
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
        if (!isSmb() && !isWebDav() && !isFtp()) return false;
        if (!NetworkPathPolicy.validHost(getHost()) || port < 0 || port > 65535) return false;
        try {
            NetworkPathPolicy.cleanRelative(getPath());
            if (isSmb() && (getShare().contains("/") || getShare().contains("\\") || NetworkPathPolicy.containsControl(getShare()))) return false;
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
