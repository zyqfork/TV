package com.fongmi.android.tv.dlna;

import android.text.TextUtils;

public class DlnaPin {

    private String key;
    private String uuid;
    private String serverName;
    private String objectId;
    private String title;
    private boolean container;
    private String url;
    private String mime;

    public DlnaPin() {
    }

    public static DlnaPin folder(String uuid, String serverName, String objectId, String title) {
        DlnaPin pin = new DlnaPin();
        pin.uuid = uuid;
        pin.serverName = serverName;
        pin.objectId = objectId;
        pin.title = title;
        pin.container = true;
        pin.key = keyOf(uuid, objectId, "");
        return pin;
    }

    public static DlnaPin item(String uuid, String serverName, String objectId, String title, String url, String mime) {
        DlnaPin pin = new DlnaPin();
        pin.uuid = uuid;
        pin.serverName = serverName;
        pin.objectId = objectId;
        pin.title = title;
        pin.container = false;
        pin.url = url;
        pin.mime = mime;
        pin.key = keyOf(uuid, objectId, url);
        return pin;
    }

    public static String keyOf(String uuid, String objectId, String url) {
        String u = uuid == null ? "" : uuid;
        String o = objectId == null ? "" : objectId;
        String r = url == null ? "" : url;
        return u + "|" + o + "|" + r;
    }

    public String getKey() {
        if (!TextUtils.isEmpty(key)) return key;
        return keyOf(uuid, objectId, url);
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getUuid() {
        return uuid == null ? "" : uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getServerName() {
        return serverName == null ? "" : serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getObjectId() {
        return objectId == null ? "" : objectId;
    }

    public void setObjectId(String objectId) {
        this.objectId = objectId;
    }

    public String getTitle() {
        return TextUtils.isEmpty(title) ? getObjectId() : title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public boolean isContainer() {
        return container;
    }

    public void setContainer(boolean container) {
        this.container = container;
    }

    public String getUrl() {
        return url == null ? "" : url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getMime() {
        return mime == null ? "" : mime;
    }

    public void setMime(String mime) {
        this.mime = mime;
    }

    public boolean hasUrl() {
        return !TextUtils.isEmpty(url);
    }
}
