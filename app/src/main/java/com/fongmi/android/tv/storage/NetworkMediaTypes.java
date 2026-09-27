package com.fongmi.android.tv.storage;

import android.text.TextUtils;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * File-type gate for network browse: only hand playable media to VideoActivity.
 *
 * <p>This is an allow list on purpose — pushing an arbitrary file (a {@code .txt}, a subtitle, a
 * directory listing) into the player used to crash it. Anything unknown is therefore rejected
 * rather than forwarded, so a missing extension here means "cannot play", never "might crash".
 */
public final class NetworkMediaTypes {

    private static final Set<String> VIDEO = new HashSet<>(Arrays.asList(
            "mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "m2ts", "mts", "flv",
            "wmv", "mpg", "mpeg", "3gp", "3g2", "ogv", "ogm", "vob", "f4v", "asf", "rm", "rmvb", "divx",
            "m2v", "m2p", "mp2v", "tp", "trp", "mxf", "wtv", "dav", "rec", "rmx", "iso", "img"
    ));

    private static final Set<String> AUDIO = new HashSet<>(Arrays.asList(
            "mp3", "flac", "aac", "m4a", "m4b", "ogg", "oga", "wav", "wma", "opus", "ape", "ac3", "dts", "mka",
            "mp2", "aif", "aiff", "amr", "ra", "au", "mpc", "tak", "tta", "wv"
    ));

    private static final Set<String> STREAM = new HashSet<>(Arrays.asList(
            "m3u8", "m3u", "mpd", "strm", "pls"
    ));

    private static final Set<String> SUBTITLE = new HashSet<>(Arrays.asList(
            "srt", "ass", "ssa", "vtt", "sub"
    ));

    private NetworkMediaTypes() {
    }

    public static String extensionOf(String name) {
        if (TextUtils.isEmpty(name)) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    public static boolean isPlayable(String name) {
        String ext = extensionOf(name);
        return VIDEO.contains(ext) || AUDIO.contains(ext) || STREAM.contains(ext);
    }

    public static boolean isSubtitle(String name) {
        return SUBTITLE.contains(extensionOf(name));
    }

    /** Human-readable list for the unsupported-file toast; localized like the toast around it. */
    public static String supportedLabel() {
        return ResUtil.getString(R.string.network_storage_supported_types);
    }
}
