package com.fongmi.android.tv.player.parse;

import java.util.HashMap;
import java.util.Map;

/** Main-thread-owned intent while a new source is being resolved. */
public final class ParsePlaybackState {

    private boolean pending;
    private boolean playWhenReady;

    public void begin() {
        pending = true;
        playWhenReady = true;
    }

    public boolean isPending() {
        return pending;
    }

    public void setPlayWhenReady(boolean value) {
        if (pending) playWhenReady = value;
    }

    public boolean complete() {
        boolean result = playWhenReady;
        pending = false;
        return result;
    }

    public void cancel() {
        pending = false;
        playWhenReady = false;
    }

    /** Resolver/config maps may be shared or read-only. Never mutate them for playback. */
    public static Map<String, String> playbackHeaders(Map<String, String> headers) {
        Map<String, String> copy = headers == null ? new HashMap<>() : new HashMap<>(headers);
        // Media transports manage byte ranges themselves; HTTP header names are case-insensitive.
        copy.keySet().removeIf(key -> "Range".equalsIgnoreCase(key));
        return copy;
    }
}
