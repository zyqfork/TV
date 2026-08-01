package com.fongmi.android.tv.storage;

public class NetworkEntry {

    private final String name;
    private final String path;
    private final boolean directory;
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
