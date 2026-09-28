package com.fongmi.android.tv.player.subtitle;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.github.catvod.utils.Prefers;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Remembers a bounded set of per-title secondary-subtitle selections and offsets. */
public final class SecondarySubtitleStore {

    private static final String PREF_KEY = "secondary_subtitle_memory_v1";
    private static final int MAX_ENTRIES = 50;
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, Entry>>() { }.getType();

    private SecondarySubtitleStore() {
    }

    public static String keyOf(@Nullable PlaySpec spec) {
        if (spec == null) return "";
        String identity = !TextUtils.isEmpty(spec.getKey()) ? spec.getKey() : spec.getUrl();
        if (spec.getMetadata() != null && spec.getMetadata().title != null) identity += "\n" + spec.getMetadata().title;
        return TextUtils.isEmpty(identity) ? "" : sha256(identity);
    }

    @Nullable
    public static Entry get(String key) {
        if (TextUtils.isEmpty(key)) return null;
        return read().get(key);
    }

    public static void put(String key, @Nullable Sub sub, long offsetMs) {
        if (TextUtils.isEmpty(key)) return;
        LinkedHashMap<String, Entry> entries = read();
        entries.remove(key);
        if (sub != null && !sub.isEmpty()) entries.put(key, new Entry(sub, offsetMs));
        while (entries.size() > MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
        Prefers.put(PREF_KEY, App.gson().toJson(entries));
    }

    public static void clear() {
        Prefers.put(PREF_KEY, "");
    }

    private static LinkedHashMap<String, Entry> read() {
        try {
            Map<String, Entry> parsed = App.gson().fromJson(Prefers.getString(PREF_KEY), MAP_TYPE);
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (RuntimeException ignored) {
            return new LinkedHashMap<>();
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte item : digest) builder.append(String.format("%02x", item));
            return builder.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }

    public static final class Entry {
        /** Remembered in preferences as JSON, so the names must survive R8 obfuscation. */
        @SerializedName("sub")
        private Sub sub;
        @SerializedName("offsetMs")
        private long offsetMs;

        private Entry(Sub sub, long offsetMs) {
            this.sub = sub;
            this.offsetMs = offsetMs;
        }

        public Sub sub() {
            return sub;
        }

        public long offsetMs() {
            return offsetMs;
        }
    }
}
