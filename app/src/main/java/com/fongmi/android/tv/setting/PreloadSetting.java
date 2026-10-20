package com.fongmi.android.tv.setting;

import com.github.catvod.utils.Prefers;

/** Next-episode preload is opt-in; resource limits are application policy, not user tuning. */
public class PreloadSetting {
    public static boolean isPreload() {
        return Prefers.getBoolean("preload");
    }

    public static void putPreload(boolean enabled) {
        Prefers.put("preload", enabled);
    }

    public static long getPreloadSizeBytes() {
        return 128L * 1024 * 1024;
    }

    public static long getPreloadDurationMs() {
        return 20_000L;
    }
}
