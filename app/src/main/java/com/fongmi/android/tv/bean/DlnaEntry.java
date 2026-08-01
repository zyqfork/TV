package com.fongmi.android.tv.bean;

import android.text.TextUtils;

public class DlnaEntry {

    private final String id;
    private final String title;
    private final boolean container;
    private final String url;
    private final String mime;

    public DlnaEntry(String id, String title, boolean container, String url, String mime) {
        this.id = id == null ? "" : id;
        this.title = TextUtils.isEmpty(title) ? id : title;
        this.container = container;
        this.url = url == null ? "" : url;
        this.mime = mime == null ? "" : mime;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public boolean isContainer() {
        return container;
    }

    public String getUrl() {
        return url;
    }

    public String getMime() {
        return mime;
    }

    public boolean hasUrl() {
        return !TextUtils.isEmpty(url);
    }
}
