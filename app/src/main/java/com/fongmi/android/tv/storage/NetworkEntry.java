package com.fongmi.android.tv.storage;

import com.google.gson.annotations.SerializedName;

public class NetworkEntry {

    @SerializedName("name")
    private final String name;
    @SerializedName("path")
    private final String path;
    @SerializedName("directory")
    private final boolean directory;
    @SerializedName("size")
    private final long size;

    public NetworkEntry(String name, String path, boolean directory, long size) {
        this.name = name;
        this.path = path;
        this.directory = directory;
        this.size = size;
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }

    public boolean isDirectory() {
        return directory;
    }

    public long getSize() {
        return size;
    }
}
