package com.fongmi.android.tv.setting;

import android.content.Intent;
import android.provider.Settings;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.mpv.MpvConfigFiles;
import com.github.catvod.utils.Prefers;

public class PlayerSetting {

    public static final int ENGINE_EXO = 0;
    public static final int ENGINE_MPV = 1;
    public static final int MIN_SCALE = 0;
    public static final int MAX_SCALE = 4;
    private static final int HARD_DEFAULT = 1;
    private static final int DEFAULT_BUFFER = 5;
    public static final int AUDIO_FOLLOW = 0;
    public static final int AUDIO_MANDARIN = 1;
    public static final int AUDIO_CANTONESE = 2;
    public static final int AUDIO_ENGLISH = 3;
    public static final int QUALITY_AUTO = 0;
    public static final int QUALITY_1080 = 1080;
    public static final int QUALITY_4K = 2160;
    public static final int LIVE_LATENCY_SMOOTH = 0;
    public static final int LIVE_LATENCY_LOW = 1;
    public static final int PLAYER_TYPE_FOLLOW = -1;
    public static final int PLAYER_TYPE_MPV = 1;
    public static final int PLAYER_TYPE_EXO = 2;
    private static final String EXO_DECODE_MIGRATION = "decode_defaults_migrated_v4";
    private static final String SIMPLE_DECODE_MIGRATION = "playback_decode_migrated_v5";
    public static final float[] SPEED_PRESETS = {0.5f, 0.8f, 1.0f, 1.2f, 1.5f, 2.0f, 3.0f, 5.0f};

    public static float nextSpeed(float current) {
        for (float preset : SPEED_PRESETS) if (preset > current + 0.01f) return preset;
        return SPEED_PRESETS[0];
    }

    public static int nearestSpeedIndex(float value) {
        int best = 0;
        for (int i = 1; i < SPEED_PRESETS.length; i++) {
            if (Math.abs(SPEED_PRESETS[i] - value) < Math.abs(SPEED_PRESETS[best] - value)) best = i;
        }
        return best;
    }

    public static int getEngine() {
        int legacy = Prefers.getInt("player_engine", ENGINE_EXO);
        return Math.clamp(Prefers.getInt("player_config_engine", legacy), ENGINE_EXO, ENGINE_MPV);
    }

    public static void putEngine(int engine) {
        engine = Math.clamp(engine, ENGINE_EXO, ENGINE_MPV);
        syncGlobalEngine(engine);
        putEngine("player_engine_vod", engine);
        putEngine("player_engine_live", engine);
    }

    public static int getVodEngine() {
        return getEngine("player_engine_vod");
    }

    public static int getLiveEngine() {
        return getEngine("player_engine_live");
    }

    private static int getEngine(String key) {
        int fallback = Prefers.getInt("player_config_engine", Prefers.getInt("player_engine", ENGINE_EXO));
        return Math.clamp(Prefers.getInt(key, fallback), ENGINE_EXO, ENGINE_MPV);
    }

    public static void putVodEngine(int engine) {
        putEngine("player_engine_vod", engine);
        syncGlobalEngine(engine);
    }

    public static void putLiveEngine(int engine) {
        putEngine("player_engine_live", engine);
        syncGlobalEngine(engine);
    }

    private static void syncGlobalEngine(int engine) {
        Prefers.put("player_config_engine", engine);
        Prefers.put("player_engine", engine);
    }

    private static void putEngine(String key, int engine) {
        Prefers.put(key, Math.clamp(engine, ENGINE_EXO, ENGINE_MPV));
    }

    public static boolean isMpv() {
        return getEngine() == ENGINE_MPV;
    }

    public static int getMpvDecode() {
        return Prefers.getInt("mpv_decode", HARD_DEFAULT) == 0 ? 0 : 1;
    }

    public static int getDecode(boolean live, int engine) {
        migrateLegacyExoDecodeDefaults();
        String scene = live ? "live" : "vod";
        String player = engine == ENGINE_MPV ? "mpv" : "exo";
        int fallback = engine == ENGINE_MPV ? getMpvDecode() : HARD_DEFAULT;
        return Prefers.getInt(scene + "_" + player + "_decode", fallback) == 0 ? 0 : 1;
    }

    /** Remove settings whose UI and readers were deleted; keep mpv_gpu_next, used again below. */
    private static void migrateRetiredKeys() {
        if (Prefers.getBoolean("retired_keys_cleaned_v1")) return;
        for (String key : new String[]{"mpv_vulkan", "render", "exo_http", "tunnel", "audio_prefer", "prefer_aac", "video_prefer"}) {
            Prefers.remove(key);
        }
        Prefers.put("retired_keys_cleaned_v1", true);
    }

    public static int getAudioLanguage() { return Math.clamp(Prefers.getInt("audio_language"), AUDIO_FOLLOW, AUDIO_ENGLISH); }
    public static void putAudioLanguage(int value) { Prefers.put("audio_language", Math.clamp(value, AUDIO_FOLLOW, AUDIO_ENGLISH)); }

    public static int getMaxVideoHeight() {
        int value = Prefers.getInt("max_video_height");
        return value == QUALITY_1080 || value == QUALITY_4K ? value : QUALITY_AUTO;
    }
    public static void putMaxVideoHeight(int value) { Prefers.put("max_video_height", value == QUALITY_1080 || value == QUALITY_4K ? value : QUALITY_AUTO); }

    public static boolean isMpvDeband() { return Prefers.getBoolean("mpv_deband"); }
    public static void putMpvDeband(boolean enabled) { Prefers.put("mpv_deband", enabled); }

    public static boolean isMpvGpuNext() { return Prefers.getBoolean("mpv_gpu_next"); }
    public static void putMpvGpuNext(boolean enabled) { Prefers.put("mpv_gpu_next", enabled); }

    public static void migrateLegacyExoDecodeDefaults() {
        if (!Prefers.getBoolean(EXO_DECODE_MIGRATION)) {
            boolean legacyFootprint = Prefers.getBoolean("decode_defaults_migrated_v3")
                    && Prefers.getInt("live_exo_decode", HARD_DEFAULT) == 0
                    && Prefers.getInt("vod_exo_decode", HARD_DEFAULT) == 0
                    && Prefers.getInt("live_mpv_decode", HARD_DEFAULT) > 0
                    && Prefers.getInt("vod_mpv_decode", HARD_DEFAULT) > 0;
            if (!Prefers.getBoolean("video_prefer") && legacyFootprint) {
                putDecode(true, ENGINE_EXO, HARD_DEFAULT);
                putDecode(false, ENGINE_EXO, HARD_DEFAULT);
            }
            Prefers.put(EXO_DECODE_MIGRATION, true);
        }
        if (!Prefers.getBoolean(SIMPLE_DECODE_MIGRATION)) {
            // Preserve an intentional legacy software-video choice in the single remaining control.
            if (Prefers.getBoolean("video_prefer")) {
                putDecode(true, ENGINE_EXO, 0);
                putDecode(false, ENGINE_EXO, 0);
            }
            Prefers.put(SIMPLE_DECODE_MIGRATION, true);
        }
        migrateRetiredKeys();
    }

    public static void putDecode(boolean live, int engine, int decode) {
        Prefers.put((live ? "live" : "vod") + "_" + (engine == ENGINE_MPV ? "mpv" : "exo") + "_decode",
                Math.clamp(decode, 0, 1));
    }

    public static int resolveEngine(boolean live, int playerType) {
        if (playerType == PLAYER_TYPE_MPV) return ENGINE_MPV;
        return live ? getLiveEngine() : getVodEngine();
    }

    /** Two bounded targets; byte limits still take precedence over duration. */
    public static int getBuffer() {
        return Prefers.getInt("exo_buffer", DEFAULT_BUFFER) > DEFAULT_BUFFER ? 10 : DEFAULT_BUFFER;
    }

    public static void putBuffer(int buffer) {
        Prefers.put("exo_buffer", buffer > DEFAULT_BUFFER ? 10 : DEFAULT_BUFFER);
    }

    public static int getLiveLatency() {
        return Math.clamp(Prefers.getInt("live_latency", LIVE_LATENCY_SMOOTH), LIVE_LATENCY_SMOOTH, LIVE_LATENCY_LOW);
    }

    public static void putLiveLatency(int mode) {
        Prefers.put("live_latency", Math.clamp(mode, LIVE_LATENCY_SMOOTH, LIVE_LATENCY_LOW));
    }

    public static boolean isLiveLowLatency() {
        return getLiveLatency() == LIVE_LATENCY_LOW;
    }

    public static int getSize() {
        return Math.clamp(Prefers.getInt("size", 2), 0, 3);
    }

    public static void putSize(int size) {
        Prefers.put("size", Math.clamp(size, 0, 3));
    }

    public static int getScale() {
        return Math.clamp(Prefers.getInt("scale"), MIN_SCALE, MAX_SCALE);
    }

    public static void putScale(int scale) {
        Prefers.put("scale", Math.clamp(scale, MIN_SCALE, MAX_SCALE));
    }

    public static int getBackground() {
        return Math.clamp(Prefers.getInt("background", 2), 0, 2);
    }

    public static void putBackground(int background) {
        Prefers.put("background", Math.clamp(background, 0, 2));
    }

    public static boolean isBackgroundOff() { return getBackground() == 0; }
    public static boolean isBackgroundOn() { return getBackground() != 0; }
    public static boolean isBackgroundPiP() { return getBackground() == 2; }

    public static float getSpeed() {
        return Math.clamp(Prefers.getFloat("speed", 3), 0.5f, 5.0f);
    }

    public static void putSpeed(float speed) {
        Prefers.put("speed", Math.clamp(speed, 0.5f, 5.0f));
    }

    public static boolean isCaption() { return Prefers.getBoolean("caption"); }
    public static void putCaption(boolean caption) { Prefers.put("caption", caption); }
    public static float getSubtitleTextSize() { return Prefers.getFloat("subtitle_text_size"); }
    public static void putSubtitleTextSize(float value) { Prefers.put("subtitle_text_size", value); }
    public static float getSubtitlePosition() { return Prefers.getFloat("subtitle_position"); }
    public static void putSubtitlePosition(float value) { Prefers.put("subtitle_position", value); }

    public static float getVolumeGain() {
        return Math.clamp(Prefers.getFloat("volume_gain", 1f), 0f, 2f);
    }

    public static void putVolumeGain(float gain) {
        Prefers.put("volume_gain", Math.clamp(gain, 0f, 2f));
    }

    public static boolean hasCaption() {
        return new Intent(Settings.ACTION_CAPTIONING_SETTINGS).resolveActivity(App.get().getPackageManager()) != null;
    }

    /** Optional YouTube-style stats block in the top-right corner; off by default. */
    public static boolean isPlaybackStatsVisible() { return Prefers.getBoolean("playback_stats"); }
    public static void putPlaybackStatsVisible(boolean visible) { Prefers.put("playback_stats", visible); }

    /** Automatic respects device capabilities; false requests compatible decoded PCM. */
    public static boolean isAudioPassThrough() { return Prefers.getBoolean("audio_pass_through", true); }
    public static void putAudioPassThrough(boolean enabled) { Prefers.put("audio_pass_through", enabled); }

    public static void restorePlaybackDefaults() {
        // Run migration first so a retired key cannot undo the explicit reset later.
        migrateLegacyExoDecodeDefaults();
        putEngine(ENGINE_EXO);
        for (int engine : new int[]{ENGINE_EXO, ENGINE_MPV}) {
            putDecode(false, engine, HARD_DEFAULT);
            putDecode(true, engine, HARD_DEFAULT);
        }
        Prefers.put("mpv_decode", HARD_DEFAULT);
        putBuffer(DEFAULT_BUFFER);
        putLiveLatency(LIVE_LATENCY_SMOOTH);
        putAudioPassThrough(true);
        putMpvGpuNext(false);
        putAudioLanguage(AUDIO_FOLLOW);
        putMaxVideoHeight(QUALITY_AUTO);
        putMpvDeband(false);
        PreloadSetting.putPreload(false);
        MpvConfigFiles.setEnabled(false);
    }
}
