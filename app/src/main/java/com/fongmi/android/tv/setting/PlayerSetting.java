package com.fongmi.android.tv.setting;

import android.content.Intent;
import android.provider.Settings;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Prefers;

public class PlayerSetting {

    public static final int ENGINE_EXO = 0;
    public static final int ENGINE_MPV = 1;
    public static final int RENDER_SURFACE = 0;
    public static final int RENDER_TEXTURE = 1;
    public static final int MIN_SCALE = 0;
    public static final int MAX_SCALE = 4;
    private static final int MIN_SIZE = 0;
    private static final int MAX_SIZE = 3;
    private static final int MIN_BACKGROUND = 0;
    private static final int MAX_BACKGROUND = 2;
    private static final int HARD_DEFAULT = 1;
    private static final int MIN_BUFFER = 1;
    private static final int MAX_BUFFER = 15;
    private static final int DEFAULT_BUFFER = 5;
    public static final int LIVE_LATENCY_SMOOTH = 0;
    public static final int LIVE_LATENCY_LOW = 1;
    /** Legacy fork/dev: 0=SYS, 1=IJK, 2=EXO. Mapped to Exo/MPV below. */
    public static final int PLAYER_TYPE_FOLLOW = -1;
    public static final int PLAYER_TYPE_MPV = 1;
    public static final int PLAYER_TYPE_EXO = 2;
    private static final String EXO_DECODE_MIGRATION = "decode_defaults_migrated_v4";
    private static final float MIN_SPEED = 0.5f;
    private static final float MAX_SPEED = 5.0f;
    /** Playback and long-press speed choices. Clicking speed cycles this list. */
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
        // Settings UI toggles this key; playback reads scene keys (or legacy "player_engine").
        // Keep all of them in sync so switching engine in settings actually applies.
        Prefers.put("player_config_engine", engine);
        Prefers.put("player_engine", engine);
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
        // MPV + TextureView frequently yields audio-only with MediaCodec; prefer SurfaceView.
        if (engine == ENGINE_MPV || isTunnel()) Prefers.put("render", RENDER_SURFACE);
    }

    public static boolean isMpv() {
        return getEngine() == ENGINE_MPV;
    }

    /** Legacy MPV preference: old value 2 (direct output) is now ordinary automatic hard decode. */
    public static int getMpvDecode() {
        return Prefers.getInt("mpv_decode", HARD_DEFAULT) == 0 ? 0 : 1;
    }

    public static int getDecode(boolean live, int engine) {
        migrateLegacyExoDecodeDefaults();
        String scene = live ? "live" : "vod";
        String player = engine == ENGINE_MPV ? "mpv" : "exo";
        int fallback = engine == ENGINE_MPV ? getMpvDecode() : HARD_DEFAULT;
        // Read-time migration preserves intentional soft choices and never rewrites the legacy
        // EXO video_prefer/defaults keys. Persist only when the user explicitly selects a mode.
        return Prefers.getInt(scene + "_" + player + "_decode", fallback) == 0 ? 0 : 1;
    }

    /**
     * Older releases accidentally persisted an automatic EXO fallback as a scene-wide soft decode.
     * Migrate only the complete legacy v3 footprint and only when the explicit "prefer software
     * video" switch is off. Isolated live/VOD soft choices are treated as intentional and kept.
     */
    public static void migrateLegacyExoDecodeDefaults() {
        if (Prefers.getBoolean(EXO_DECODE_MIGRATION)) return;
        int liveExo = Prefers.getInt("live_exo_decode", HARD_DEFAULT);
        int vodExo = Prefers.getInt("vod_exo_decode", HARD_DEFAULT);
        int liveMpv = Prefers.getInt("live_mpv_decode", HARD_DEFAULT);
        int vodMpv = Prefers.getInt("vod_mpv_decode", HARD_DEFAULT);
        // The known legacy footprint has both EXO scenes stuck on soft while the previous MPV
        // migration already moved both MPV scenes to hard. Do not rewrite isolated scene choices.
        boolean legacyFootprint = Prefers.getBoolean("decode_defaults_migrated_v3")
                && liveExo == 0 && vodExo == 0 && liveMpv > 0 && vodMpv > 0;
        if (!isVideoPrefer() && legacyFootprint) {
            Prefers.put("live_exo_decode", HARD_DEFAULT);
            Prefers.put("vod_exo_decode", HARD_DEFAULT);
        }
        Prefers.put(EXO_DECODE_MIGRATION, true);
    }

    public static void putDecode(boolean live, int engine, int decode) {
        String scene = live ? "live" : "vod";
        String player = engine == ENGINE_MPV ? "mpv" : "exo";
        Prefers.put(scene + "_" + player + "_decode", Math.clamp(decode, 0, 1));
    }

    public static boolean isMpvGpuNext() {
        return Prefers.getBoolean("mpv_gpu_next");
    }

    public static void putMpvGpuNext(boolean gpuNext) {
        Prefers.put("mpv_gpu_next", gpuNext);
        if (gpuNext) Prefers.put("mpv_vulkan", false);
    }

    public static boolean isMpvVulkan() {
        return Prefers.getBoolean("mpv_vulkan");
    }

    public static void putMpvVulkan(boolean vulkan) {
        Prefers.put("mpv_vulkan", vulkan);
        if (vulkan) Prefers.put("mpv_gpu_next", false);
    }

    /**
     * Map config playerType onto current engines.
     * 1 (legacy IJK) → MPV; otherwise follow the user's live/vod engine setting.
     * Site {@code playerType=2 (EXO)} is no longer forced here: it fought the user's MPV
     * preference and mid-play engine switches. Hard requirements (DASH/DRM/SMB) still force
     * Exo in {@link com.fongmi.android.tv.player.engine.PlayerEngineFactory}.
     */
    public static int resolveEngine(boolean live, int playerType) {
        if (playerType == PLAYER_TYPE_MPV) return ENGINE_MPV;
        return live ? getLiveEngine() : getVodEngine();
    }

    /** Playback buffer target in seconds (mapped to Exo LoadControl and MPV demux cache). */
    public static int getBuffer() {
        int value = Prefers.getInt("exo_buffer", DEFAULT_BUFFER);
        return Math.clamp(value <= 0 ? DEFAULT_BUFFER : value, MIN_BUFFER, MAX_BUFFER);
    }

    public static void putBuffer(int buffer) {
        Prefers.put("exo_buffer", Math.clamp(buffer, MIN_BUFFER, MAX_BUFFER));
    }

    /** 0 = DefaultHttpDataSource, 1 = OkHttp (default). */
    public static int getHttp() {
        return Math.clamp(Prefers.getInt("exo_http", 1), 0, 1);
    }

    public static void putHttp(int http) {
        Prefers.put("exo_http", Math.clamp(http, 0, 1));
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

    public static int getRender() {
        return Math.clamp(Prefers.getInt("render", RENDER_SURFACE), RENDER_SURFACE, RENDER_TEXTURE);
    }

    /** False means an EXO tunnel requires SurfaceView; the previous setting is preserved. */
    public static boolean putRender(int render) {
        if (render == RENDER_TEXTURE && !isMpv() && isTunnel()) return false;
        Prefers.put("render", Math.clamp(render, RENDER_SURFACE, RENDER_TEXTURE));
        return true;
    }

    public static boolean isTunnel() {
        return Prefers.getBoolean("tunnel");
    }

    public static void putTunnel(boolean tunnel) {
        Prefers.put("tunnel", tunnel);
        if (!isMpv() && tunnel) Prefers.put("render", RENDER_SURFACE);
    }

    public static boolean isTunnelingEnabled() {
        return isTunnel() && getRender() == RENDER_SURFACE;
    }

    public static int getSize() {
        return Math.clamp(Prefers.getInt("size", 2), MIN_SIZE, MAX_SIZE);
    }

    public static void putSize(int size) {
        Prefers.put("size", Math.clamp(size, MIN_SIZE, MAX_SIZE));
    }

    public static int getScale() {
        return Math.clamp(Prefers.getInt("scale"), MIN_SCALE, MAX_SCALE);
    }

    public static void putScale(int scale) {
        Prefers.put("scale", Math.clamp(scale, MIN_SCALE, MAX_SCALE));
    }

    public static int getBackground() {
        return Math.clamp(Prefers.getInt("background", 2), MIN_BACKGROUND, MAX_BACKGROUND);
    }

    public static void putBackground(int background) {
        Prefers.put("background", Math.clamp(background, MIN_BACKGROUND, MAX_BACKGROUND));
    }

    public static boolean isBackgroundOff() {
        return getBackground() == 0;
    }

    public static boolean isBackgroundOn() {
        return getBackground() == 1 || getBackground() == 2;
    }

    public static boolean isBackgroundPiP() {
        return getBackground() == 2;
    }

    public static float getSpeed() {
        return Math.clamp(Prefers.getFloat("speed", 3), MIN_SPEED, MAX_SPEED);
    }

    public static void putSpeed(float speed) {
        Prefers.put("speed", Math.clamp(speed, MIN_SPEED, MAX_SPEED));
    }

    public static boolean isCaption() {
        return Prefers.getBoolean("caption");
    }

    public static void putCaption(boolean caption) {
        Prefers.put("caption", caption);
    }

    public static float getSubtitleTextSize() {
        return Prefers.getFloat("subtitle_text_size");
    }

    public static void putSubtitleTextSize(float value) {
        Prefers.put("subtitle_text_size", value);
    }

    public static float getVolumeGain() {
        return Math.clamp(Prefers.getFloat("volume_gain", 1f), 0f, 2f);
    }

    public static void putVolumeGain(float gain) {
        Prefers.put("volume_gain", Math.clamp(gain, 0f, 2f));
    }

    public static float getSubtitlePosition() {
        return Prefers.getFloat("subtitle_position");
    }

    public static void putSubtitlePosition(float value) {
        Prefers.put("subtitle_position", value);
    }

    public static boolean hasCaption() {
        return new Intent(Settings.ACTION_CAPTIONING_SETTINGS).resolveActivity(App.get().getPackageManager()) != null;
    }

    public static boolean isAudioPassThrough() {
        return Prefers.getBoolean("audio_pass_through", true);
    }

    public static void putAudioPassThrough(boolean audioPassThrough) {
        Prefers.put("audio_pass_through", audioPassThrough);
    }

    public static boolean isAudioPrefer() {
        return Prefers.getBoolean("audio_prefer");
    }

    public static void putAudioPrefer(boolean audioPrefer) {
        Prefers.put("audio_prefer", audioPrefer);
    }

    public static boolean isVideoPrefer() {
        return Prefers.getBoolean("video_prefer");
    }

    public static void putVideoPrefer(boolean videoPrefer) {
        Prefers.put("video_prefer", videoPrefer);
    }

    public static boolean isPreferAAC() {
        return Prefers.getBoolean("prefer_aac");
    }

    public static void putPreferAAC(boolean preferAAC) {
        Prefers.put("prefer_aac", preferAAC);
    }
}
