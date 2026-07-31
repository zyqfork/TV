package com.fongmi.android.tv.setting;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.fongmi.android.tv.dlna.DlnaNetwork;
import com.github.catvod.Init;

import io.github.jqssun.airplay.Prefs;

public class AirPlaySetting {

    // Prefs.KEY_* are Kotlin vals; MediaFormat string values are stable for SharedPreferences keys.
    private static final String KEY_ALLOW_FRAME_DROP = "allow-frame-drop";
    private static final String KEY_PRIORITY = "priority";
    private static final String KEY_OPERATING_RATE = "operating-rate";
    private static final int AUDIO_ADAPTIVE_MAX = 4;

    private static SharedPreferences prefs() {
        return Init.context().getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        return prefs().getBoolean(Prefs.SERVER_ENABLED, Prefs.DEF_SERVER_ENABLED);
    }

    public static void putEnabled(boolean enabled) {
        prefs().edit().putBoolean(Prefs.SERVER_ENABLED, enabled).apply();
    }

    public static String getName() {
        return prefs().getString(Prefs.SERVER_NAME, Prefs.DEF_SERVER_NAME);
    }

    public static void putName(String name) {
        String value = name == null ? "" : name.trim();
        if (TextUtils.isEmpty(value)) value = Prefs.DEF_SERVER_NAME;
        prefs().edit().putString(Prefs.SERVER_NAME, value).apply();
    }

    public static String getDisplayName() {
        String name = getName();
        return TextUtils.isEmpty(name) ? Prefs.DEF_SERVER_NAME : name;
    }

    public static int getPort() {
        return Math.clamp(prefs().getInt(Prefs.SERVER_PORT, Prefs.DEF_SERVER_PORT), 1, 65535);
    }

    public static void putPort(int port) {
        if (port < 1 || port > 65535) port = Prefs.DEF_SERVER_PORT;
        prefs().edit().putInt(Prefs.SERVER_PORT, port).apply();
    }

    public static String getInterface() {
        return prefs().getString(Prefs.IFACE_NAME, Prefs.DEF_IFACE_NAME);
    }

    public static void putInterface(String name) {
        prefs().edit().putString(Prefs.IFACE_NAME, name == null ? "" : name.trim()).apply();
    }

    /** If user never chose an interface, pick system default once at startup. */
    public static void ensureDefaultInterface() {
        if (!TextUtils.isEmpty(getInterface())) return;
        String name = DlnaNetwork.resolveInterfaceName("");
        if (!TextUtils.isEmpty(name)) putInterface(name);
    }

    public static String resolveInterfaceName() {
        ensureDefaultInterface();
        return DlnaNetwork.resolveInterfaceName(getInterface());
    }

    public static DlnaNetwork.Iface resolveIface() {
        return DlnaNetwork.resolve(resolveInterfaceName());
    }

    public static boolean isRequirePin() {
        return prefs().getBoolean(Prefs.REQUIRE_PIN, Prefs.DEF_REQUIRE_PIN);
    }

    public static void putRequirePin(boolean value) {
        prefs().edit().putBoolean(Prefs.REQUIRE_PIN, value).apply();
    }

    public static boolean isAllowNewConn() {
        return prefs().getBoolean(Prefs.ALLOW_NEW_CONN, Prefs.DEF_ALLOW_NEW_CONN);
    }

    public static void putAllowNewConn(boolean value) {
        prefs().edit().putBoolean(Prefs.ALLOW_NEW_CONN, value).apply();
    }

    public static boolean isAdvertiseVideo() {
        return prefs().getBoolean(Prefs.ADVERTISE_VIDEO, Prefs.DEF_ADVERTISE_VIDEO);
    }

    public static void putAdvertiseVideo(boolean value) {
        prefs().edit().putBoolean(Prefs.ADVERTISE_VIDEO, value).apply();
    }

    public static boolean isAdvertiseAudio() {
        return prefs().getBoolean(Prefs.ADVERTISE_AUDIO, Prefs.DEF_ADVERTISE_AUDIO);
    }

    public static void putAdvertiseAudio(boolean value) {
        prefs().edit().putBoolean(Prefs.ADVERTISE_AUDIO, value).apply();
    }

    public static boolean isH265() {
        return prefs().getBoolean(Prefs.H265_ENABLED, Prefs.DEF_H265_ENABLED);
    }

    public static void putH265(boolean value) {
        prefs().edit().putBoolean(Prefs.H265_ENABLED, value).apply();
    }

    public static boolean isEnforceSdr() {
        return prefs().getBoolean(Prefs.ENFORCE_SDR, Prefs.DEF_ENFORCE_SDR);
    }

    public static void putEnforceSdr(boolean value) {
        prefs().edit().putBoolean(Prefs.ENFORCE_SDR, value).apply();
    }

    public static String getResolution() {
        String value = prefs().getString(Prefs.RESOLUTION, Prefs.DEF_RESOLUTION);
        return TextUtils.isEmpty(value) ? Prefs.DEF_RESOLUTION : value;
    }

    public static void putResolution(String value) {
        String res = value == null ? Prefs.DEF_RESOLUTION : value.trim();
        if (TextUtils.isEmpty(res)) res = Prefs.DEF_RESOLUTION;
        prefs().edit().putString(Prefs.RESOLUTION, res).putBoolean(Prefs.AUTO_RES, false).apply();
    }

    public static int getMaxFps() {
        return Math.clamp(prefs().getInt(Prefs.MAX_FPS, Prefs.DEF_MAX_FPS), 1, 240);
    }

    public static void putMaxFps(int value) {
        prefs().edit().putInt(Prefs.MAX_FPS, Math.clamp(value, 1, 240)).apply();
    }

    public static boolean isOverscanned() {
        return prefs().getBoolean(Prefs.OVERSCANNED, Prefs.DEF_OVERSCANNED);
    }

    public static void putOverscanned(boolean value) {
        prefs().edit().putBoolean(Prefs.OVERSCANNED, value).apply();
    }

    public static boolean isLowLatency() {
        return prefs().getBoolean(Prefs.LOW_LATENCY, Prefs.DEF_LOW_LATENCY);
    }

    public static void putLowLatency(boolean value) {
        prefs().edit().putBoolean(Prefs.LOW_LATENCY, value).apply();
    }

    public static boolean isAllowFrameDrop() {
        return prefs().getBoolean(KEY_ALLOW_FRAME_DROP, Prefs.DEF_KEY_ALLOW_FRAME_DROP);
    }

    public static void putAllowFrameDrop(boolean value) {
        prefs().edit().putBoolean(KEY_ALLOW_FRAME_DROP, value).apply();
    }

    public static boolean isRealtimePriority() {
        return prefs().getBoolean(KEY_PRIORITY, Prefs.DEF_KEY_PRIORITY);
    }

    public static void putRealtimePriority(boolean value) {
        prefs().edit().putBoolean(KEY_PRIORITY, value).apply();
    }

    public static boolean isOperatingRate() {
        return prefs().getBoolean(KEY_OPERATING_RATE, Prefs.DEF_KEY_OPERATING_RATE);
    }

    public static void putOperatingRate(boolean value) {
        prefs().edit().putBoolean(KEY_OPERATING_RATE, value).apply();
    }

    public static boolean isAlignedPacing() {
        return prefs().getBoolean(Prefs.SCHEDULED_OUTPUT_BUFFER_RELEASE, Prefs.DEF_SCHEDULED_OUTPUT_BUFFER_RELEASE);
    }

    public static void putAlignedPacing(boolean value) {
        prefs().edit().putBoolean(Prefs.SCHEDULED_OUTPUT_BUFFER_RELEASE, value).apply();
    }

    public static boolean isAac() {
        return prefs().getBoolean(Prefs.AAC_ENABLED, Prefs.DEF_AAC_ENABLED);
    }

    public static void putAac(boolean value) {
        prefs().edit().putBoolean(Prefs.AAC_ENABLED, value).apply();
    }

    public static boolean isAlac() {
        return prefs().getBoolean(Prefs.ALAC_ENABLED, Prefs.DEF_ALAC_ENABLED);
    }

    public static void putAlac(boolean value) {
        prefs().edit().putBoolean(Prefs.ALAC_ENABLED, value).apply();
    }

    public static boolean isForceSwAlac() {
        return prefs().getBoolean(Prefs.FORCE_SW_ALAC, Prefs.DEF_FORCE_SW_ALAC);
    }

    public static void putForceSwAlac(boolean value) {
        prefs().edit().putBoolean(Prefs.FORCE_SW_ALAC, value).apply();
    }

    public static int getAudioLatencyMs() {
        return prefs().getInt(Prefs.AUDIO_LATENCY_MS, Prefs.DEF_AUDIO_LATENCY_MS);
    }

    public static void putAudioLatencyMs(int value) {
        prefs().edit().putInt(Prefs.AUDIO_LATENCY_MS, value < 0 ? Prefs.DEF_AUDIO_LATENCY_MS : Math.min(value, 5000)).apply();
    }

    public static boolean isAudioAutoBuffer() {
        return prefs().getBoolean(Prefs.AUDIO_AUTO_BUFFER, Prefs.DEF_AUDIO_AUTO_BUFFER);
    }

    public static void putAudioAutoBuffer(boolean value) {
        prefs().edit().putBoolean(Prefs.AUDIO_AUTO_BUFFER, value).apply();
    }

    public static int getAudioCushionMs() {
        return Math.clamp(prefs().getInt(Prefs.AUDIO_CUSHION_MS, Prefs.DEF_AUDIO_CUSHION_MS), 1, 1000);
    }

    public static void putAudioCushionMs(int value) {
        prefs().edit().putInt(Prefs.AUDIO_CUSHION_MS, Math.clamp(value, 1, 1000)).apply();
    }

    public static int getAudioAdaptiveStep() {
        return Math.clamp(prefs().getInt(Prefs.AUDIO_ADAPTIVE_STEP, Prefs.DEF_AUDIO_ADAPTIVE_STEP), 0, AUDIO_ADAPTIVE_MAX);
    }

    public static void putAudioAdaptiveStep(int value) {
        prefs().edit().putInt(Prefs.AUDIO_ADAPTIVE_STEP, Math.clamp(value, 0, AUDIO_ADAPTIVE_MAX)).apply();
    }

    public static int getOboeBufferFrames() {
        return Math.clamp(prefs().getInt(Prefs.OBOE_BUFFER_FRAMES, Prefs.DEF_OBOE_BUFFER_FRAMES), 0, 8192);
    }

    public static void putOboeBufferFrames(int value) {
        prefs().edit().putInt(Prefs.OBOE_BUFFER_FRAMES, Math.clamp(value, 0, 8192)).apply();
    }

    public static boolean isAutoFullscreen() {
        return prefs().getBoolean(Prefs.AUTO_FULLSCREEN, Prefs.DEF_AUTO_FULLSCREEN);
    }

    public static void putAutoFullscreen(boolean value) {
        prefs().edit().putBoolean(Prefs.AUTO_FULLSCREEN, value).apply();
    }

    public static boolean isIdlePreview() {
        return prefs().getBoolean(Prefs.IDLE_PREVIEW, Prefs.DEF_IDLE_PREVIEW);
    }

    public static void putIdlePreview(boolean value) {
        prefs().edit().putBoolean(Prefs.IDLE_PREVIEW, value).apply();
    }

    public static boolean isDebugOverlay() {
        return prefs().getBoolean(Prefs.DEBUG_ENABLED, Prefs.DEF_DEBUG_ENABLED);
    }

    public static void putDebugOverlay(boolean value) {
        prefs().edit().putBoolean(Prefs.DEBUG_ENABLED, value).apply();
    }

    public static boolean isBenchmarkLog() {
        return prefs().getBoolean(Prefs.BENCHMARK_LOG, Prefs.DEF_BENCHMARK_LOG);
    }

    public static void putBenchmarkLog(boolean value) {
        prefs().edit().putBoolean(Prefs.BENCHMARK_LOG, value).apply();
    }
}
