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
    private static final int HARD_PERFORMANCE_DEFAULT = 2;
    private static final int MIN_BUFFER = 1;
    private static final int MAX_BUFFER = 15;
    private static final int DEFAULT_BUFFER = 5;
    public static final int LIVE_LATENCY_SMOOTH = 0;
    public static final int LIVE_LATENCY_LOW = 1;
    /** Legacy fork/dev: 0=SYS, 1=IJK, 2=EXO. Mapped to Exo/MPV below. */
    public static final int PLAYER_TYPE_FOLLOW = -1;
    public static final int PLAYER_TYPE_MPV = 1;
    public static final int PLAYER_TYPE_EXO = 2;
    private static final float MIN_SPEED = 2.0f;
    private static final float MAX_SPEED = 5.0f;

    /**
     * One-shot migration: soft decode must not be the sticky default.
     * Soft is only for unsupported codecs / hard-decode failure fallback.
     */
    public static void migrateDecodeDefaults() {
        if (Prefers.getBoolean("decode_defaults_migrated_v3")) return;
        if (Prefers.getInt("mpv_decode", HARD_DEFAULT) == 0) putMpvDecode(HARD_DEFAULT);
        // Soft must not stick as a scene default; hard-first with soft as last fallback only.
        if (Prefers.getInt("live_mpv_decode", HARD_PERFORMANCE_DEFAULT) == 0) {
            Prefers.put("live_mpv_decode", HARD_PERFORMANCE_DEFAULT);
        }
        if (Prefers.getInt("vod_mpv_decode", HARD_PERFORMANCE_DEFAULT) == 0) {
            Prefers.put("vod_mpv_decode", HARD_PERFORMANCE_DEFAULT);
        }
        // First install / no scene key: prefer performance hard for VOD MPV.
        if (!Prefers.getPrefers().contains("vod_mpv_decode")) {
            Prefers.put("vod_mpv_decode", HARD_PERFORMANCE_DEFAULT);
        }
        Prefers.put("decode_defaults_migrated_v3", true);
        Prefers.put("decode_defaults_migrated_v2", true);
        Prefers.put("decode_defaults_migrated", true);
    }

    public static int getEngine() {
        int legacy = Prefers.getInt("player_engine", ENGINE_EXO);
        return Math.clamp(Prefers.getInt("player_config_engine", legacy), ENGINE_EXO, ENGINE_MPV);
    }

    public static void putEngine(int engine) {
        Prefers.put("player_config_engine", Math.clamp(engine, ENGINE_EXO, ENGINE_MPV));
    }

    public static int getVodEngine() {
        return getEngine("player_engine_vod");
    }

    public static int getLiveEngine() {
        return getEngine("player_engine_live");
    }

    private static int getEngine(String key) {
        int legacy = Prefers.getInt("player_engine", ENGINE_EXO);
        return Math.clamp(Prefers.getInt(key, legacy), ENGINE_EXO, ENGINE_MPV);
    }

    public static void putVodEngine(int engine) {
        putEngine("player_engine_vod", engine);
    }

    public static void putLiveEngine(int engine) {
        putEngine("player_engine_live", engine);
    }

    private static void putEngine(String key, int engine) {
        Prefers.put(key, Math.clamp(engine, ENGINE_EXO, ENGINE_MPV));
        // MPV + TextureView frequently yields audio-only with MediaCodec; prefer SurfaceView.
        if (engine == ENGINE_MPV || isTunnel()) Prefers.put("render", RENDER_SURFACE);
    }

    public static boolean isMpv() {
        return getEngine() == ENGINE_MPV;
    }

    public static int getMpvDecode() {
        return Math.clamp(Prefers.getInt("mpv_decode", HARD_DEFAULT), 0, 2);
    }

    public static void putMpvDecode(int decode) {
        Prefers.put("mpv_decode", Math.clamp(decode, 0, 2));
    }

    public static int getDecode(boolean live, int engine) {
        String scene = live ? "live" : "vod";
        String player = engine == ENGINE_MPV ? "mpv" : "exo";
        int fallback;
        if (engine == ENGINE_MPV) {
            // Prefer zero-copy hard decode for both live and VOD; external subs force compatible later.
            fallback = HARD_PERFORMANCE_DEFAULT;
        } else {
            fallback = HARD_DEFAULT;
        }
        int max = engine == ENGINE_MPV ? 2 : 1;
        return Math.clamp(Prefers.getInt(scene + "_" + player + "_decode", fallback), 0, max);
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

    public static void putDecode(boolean live, int engine, int decode) {
        String scene = live ? "live" : "vod";
        String player = engine == ENGINE_MPV ? "mpv" : "exo";
        int max = engine == ENGINE_MPV ? 2 : 1;
        Prefers.put(scene + "_" + player + "_decode", Math.clamp(decode, 0, max));
    }

    public static boolean isMpvGpuNext() {
        return Prefers.getBoolean("mpv_gpu_next");
    }

    public static void putMpvGpuNext(boolean gpuNext) {
        Prefers.put("mpv_gpu_next", gpuNext);
    }

    public static boolean isMpvVulkan() {
        return Prefers.getBoolean("mpv_vulkan");
    }

    public static void putMpvVulkan(boolean vulkan) {
        Prefers.put("mpv_vulkan", vulkan);
    }

    public static int getRender() {
        return Math.clamp(Prefers.getInt("render", RENDER_SURFACE), RENDER_SURFACE, RENDER_TEXTURE);
    }

    public static void putRender(int render) {
        Prefers.put("render", Math.clamp(render, RENDER_SURFACE, RENDER_TEXTURE));
        if (!isMpv() && getRender() == RENDER_TEXTURE) {
            if (isTunnel()) Prefers.put("tunnel", false);
            if (isLiveTunnel()) Prefers.put("live_tunnel", false);
        }
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

    public static boolean isLiveTunnel() {
        return Prefers.getBoolean("live_tunnel", true);
    }

    public static void putLiveTunnel(boolean enabled) {
        Prefers.put("live_tunnel", enabled);
        if (!isMpv() && enabled) Prefers.put("render", RENDER_SURFACE);
    }

    public static boolean isTunnelingEnabled(boolean live) {
        boolean enabled = live ? isLiveTunnel() : isTunnel();
        return enabled && getRender() == RENDER_SURFACE;
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
        // Default: 小 (index 0) — denser poster grid.
        return Math.clamp(Prefers.getInt("size", 0), MIN_SIZE, MAX_SIZE);
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

    public static boolean isDv7HevcFallback() {
        return Prefers.getBoolean("dv7_hevc_fallback");
    }

    public static void putDv7HevcFallback(boolean fallback) {
        Prefers.put("dv7_hevc_fallback", fallback);
    }
}
