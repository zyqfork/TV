package com.fongmi.android.tv.player.mpv;

import android.text.TextUtils;

import androidx.media3.common.Player;
import androidx.media3.common.util.Util;
import androidx.media3.mpvplayer.MpvPlayer;
import androidx.media3.mpvplayer.MpvPlayerConfig;
import androidx.media3.ui.SubtitleView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.track.LangUtil;
import com.fongmi.android.tv.player.util.PlayerHelper;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.utils.Path;

import java.io.File;
import java.util.Map;

public final class MpvUtil {

    private static final String ASSET_CA_FILE = "cacert.pem";
    private static final double DEFAULT_SUB_POS = 100.0;
    private static final double DEFAULT_SUB_SCALE = 1.0;
    private static final double MIN_SUB_SCALE = 0.5;
    private static final double MAX_SUB_SCALE = 3.0;
    private static final double MIN_SUB_POS = 0.0;
    private static final double MAX_SUB_POS = 150.0;
    private static final String OPT_GPU_API = "gpu-api";
    private static final String OPT_GPU_CONTEXT = "gpu-context";
    private static final String OPT_OPENGL_ES = "opengl-es";
    private static final String OPT_SUB_LANG = "slang";
    private static final String VALUE_ANDROID = "android";
    private static final String VALUE_ANDROID_VK = "androidvk";
    private static final String VALUE_GPU = "gpu";
    private static final String VALUE_MEDIACODEC = "mediacodec";
    private static final String VALUE_MEDIACODEC_EMBED = "mediacodec_embed";
    private static final String VALUE_VULKAN = "vulkan";
    private static final String VALUE_YES = "yes";
    private static final String OPT_PROXY_URL = "proxy-url";

    public static boolean isAvailable() {
        try {
            return MpvPlayer.isAvailable();
        } catch (Throwable e) {
            return false;
        }
    }

    public static MpvPlayer buildPlayer(int decode, Player.Listener listener) {
        return buildPlayer(decode, false, listener);
    }

    public static MpvPlayer buildPlayer(int decode, boolean live, Player.Listener listener) {
        MpvPlayer player = new MpvPlayer.Builder(App.get()).setDecode(decode).setConfig(buildConfig(decode, live)).build();
        player.addListener(listener);
        return player;
    }

    public static void setSubtitleStyle(MpvPlayer player) {
        player.setSubtitleOptions(buildSubtitleConfig());
    }

    private static MpvPlayerConfig buildConfig(int decode, boolean live) {
        Map<String, String> userOptions = MpvConfigFiles.readGlobalOptions();
        MpvPlayerConfig.Builder builder = new MpvPlayerConfig.Builder();
        addAndroidOptions(builder, userOptions, decode);
        addUserOptions(builder, userOptions);
        addApplicationOptions(builder, userOptions, decode, live);
        addTrackLanguageOptions(builder);
        addSubtitleStyleOptions(builder);
        return builder.build();
    }

    private static MpvPlayerConfig buildSubtitleConfig() {
        MpvPlayerConfig.Builder builder = new MpvPlayerConfig.Builder();
        addSubtitleStyleOptions(builder);
        return builder.build();
    }

    private static void addAndroidOptions(MpvPlayerConfig.Builder builder, Map<String, String> userOptions, int decode) {
        addAndroidDefaultOptions(builder, userOptions, decode);
        addTlsCaFile(builder);
    }

    private static void addAndroidDefaultOptions(MpvPlayerConfig.Builder builder, Map<String, String> userOptions, int decode) {
        File configDir = Path.mpv();
        File cacheDir = Path.mpvCache();
        MpvConfigFiles.ensureAndroidFontsConfig(cacheDir);
        builder.addConfigDirectory(configDir)
                .addAndroidFontConfig(configDir, cacheDir)
                .addAndroidDefaults(getVideoOutputDriver(userOptions, decode), cacheDir);
    }

    private static void addTlsCaFile(MpvPlayerConfig.Builder builder) {
        builder.addTlsCaFileFromAsset(App.get(), ASSET_CA_FILE, Path.files(ASSET_CA_FILE));
    }

    private static void addTrackLanguageOptions(MpvPlayerConfig.Builder builder) {
        builder.addPostInitStringOption(OPT_SUB_LANG, LangUtil.getPreferredTextLanguageList());
    }

    private static void addUserOptions(MpvPlayerConfig.Builder builder, Map<String, String> options) {
        for (Map.Entry<String, String> option : options.entrySet()) {
            builder.addPreInitStringOption(option.getKey(), option.getValue());
        }
    }

    private static void addApplicationOptions(MpvPlayerConfig.Builder builder, Map<String, String> userOptions, int decode, boolean live) {
        builder.setDefaultUserAgent(getDefaultUserAgent()).setHlsHttpPersistent(true);
        if (!userOptions.containsKey(OPT_PROXY_URL)) {
            builder.addPreInitStringOption(OPT_PROXY_URL, Server.get().getAddress(true) + "/proxy?");
        }
        if (decode == com.fongmi.android.tv.player.engine.PlayerEngine.HARD_PERFORMANCE) {
            builder.addPreInitStringOption("vo", VALUE_MEDIACODEC_EMBED)
                    .addPreInitStringOption("hwdec", VALUE_MEDIACODEC);
        } else {
            addVideoOutputOptions(builder, userOptions);
        }
        addIjkBehaviorOptions(builder, userOptions, live);
        addPreloadOptions(builder);
    }

    /**
     * Port useful IJK live behaviors without bringing the IJK engine:
     * reconnect, framedrop, low-latency demux / unbounded buffer for live.
     */
    private static void addIjkBehaviorOptions(MpvPlayerConfig.Builder builder, Map<String, String> userOptions, boolean live) {
        if (!userOptions.containsKey("framedrop")) {
            builder.addPreInitStringOption("framedrop", "vo");
        }
        if (!userOptions.containsKey("video-sync") && live) {
            builder.addPreInitStringOption("video-sync", "audio");
        }
        if (!userOptions.containsKey("demuxer-max-bytes")) {
            int mb = PlayerSetting.isLiveLowLatency() ? Math.max(8, PlayerSetting.getBuffer() * 2) : Math.max(15, PlayerSetting.getBuffer() * 3);
            builder.addPreInitStringOption("demuxer-max-bytes", mb + "MiB");
        }
        if (live) {
            if (!userOptions.containsKey("cache-secs")) {
                int cacheSec = PlayerSetting.isLiveLowLatency() ? Math.min(2, Math.max(1, PlayerSetting.getBuffer() / 2)) : Math.max(1, PlayerSetting.getBuffer());
                builder.addPreInitStringOption("cache-secs", Integer.toString(cacheSec));
            }
            // Prefer reconnect over aggressive nobuffer — mid-GOP live TS needs SPS/PPS.
            // Mild analyzeduration helps first open without starving the live join.
            if (!userOptions.containsKey("demuxer-lavf-o")) {
                builder.addPreInitStringOption("demuxer-lavf-o",
                        "analyzeduration=5000000,probesize=2000000");
            }
            if (!userOptions.containsKey("stream-lavf-o")) {
                builder.addPreInitStringOption("stream-lavf-o",
                        "reconnect=1,reconnect_streamed=1,reconnect_delay_max=5");
            }
            if (!userOptions.containsKey("rtsp-transport")) {
                builder.addPreInitStringOption("rtsp-transport", "tcp");
            }
        } else if (!userOptions.containsKey("stream-lavf-o")) {
            builder.addPreInitStringOption("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=5");
        }
    }

    private static String getVideoOutputDriver(Map<String, String> userOptions, int decode) {
        if (decode == com.fongmi.android.tv.player.engine.PlayerEngine.HARD_PERFORMANCE) return VALUE_MEDIACODEC_EMBED;
        if (PlayerSetting.isMpvGpuNext()) return MpvPlayerConfig.VIDEO_OUTPUT_GPU_NEXT;
        return userOptions.containsKey("vo") ? null : VALUE_GPU;
    }

    private static void addVideoOutputOptions(MpvPlayerConfig.Builder builder, Map<String, String> userOptions) {
        // Match mpv-android: Android GLES context is required for vo=gpu, otherwise audio-only.
        if (PlayerSetting.isMpvVulkan() && isVulkanAvailable()) {
            builder.addPreInitStringOption(OPT_GPU_API, VALUE_VULKAN)
                    .addPreInitStringOption(OPT_GPU_CONTEXT, VALUE_ANDROID_VK);
            return;
        }
        if (VALUE_VULKAN.equals(userOptions.get(OPT_GPU_API))
                && !userOptions.containsKey(OPT_GPU_CONTEXT)) {
            builder.addPreInitStringOption(OPT_GPU_CONTEXT, VALUE_ANDROID_VK);
            return;
        }
        if (!userOptions.containsKey(OPT_GPU_CONTEXT)) {
            builder.addPreInitStringOption(OPT_GPU_CONTEXT, VALUE_ANDROID);
        }
        if (!userOptions.containsKey(OPT_OPENGL_ES)
                && !VALUE_VULKAN.equals(userOptions.get(OPT_GPU_API))) {
            builder.addPreInitStringOption(OPT_OPENGL_ES, VALUE_YES);
        }
    }

    private static void addPreloadOptions(MpvPlayerConfig.Builder builder) {
        if (!PreloadSetting.isPreload()) return;
        builder.addDiskCacheOptions(Path.mpvCache(), PreloadSetting.getPreloadTimeSeconds(), PreloadSetting.getPreloadSizeMb());
    }

    private static void addSubtitleStyleOptions(MpvPlayerConfig.Builder builder) {
        builder.addAndroidSubtitleOptions(App.get(), PlayerSetting.isCaption(), getSubtitlePosition(), getSubtitleScale());
    }

    private static String getDefaultUserAgent() {
        String userAgent = Setting.getUa();
        return TextUtils.isEmpty(userAgent) ? PlayerHelper.getDefaultUa() : userAgent;
    }

    private static double getSubtitlePosition() {
        float position = PlayerSetting.getSubtitlePosition();
        if (position == 0) return DEFAULT_SUB_POS;
        return Util.constrainValue(DEFAULT_SUB_POS - position * 100.0, MIN_SUB_POS, MAX_SUB_POS);
    }

    /**
     * Vulkan VO requires both Android Vulkan hardware AND libmpv compiled with vulkan support.
     * Current libmpv build does not include vulkan; this will return false until rebuilt with it.
     */
    private static boolean isVulkanAvailable() {
        if (!App.get().getPackageManager().hasSystemFeature("android.hardware.vulkan.level")) return false;
        try {
            // After MPVLib.init(), mpv-version property embeds feature flags; but before init
            // we cannot query. Instead rely on build-time constant from the media3compat module.
            return is.xyz.mpv.MPVLib.hasFeature("vulkan");
        } catch (Throwable e) {
            return false;
        }
    }

    private static double getSubtitleScale() {
        float textSize = PlayerSetting.getSubtitleTextSize();
        if (textSize == 0) return DEFAULT_SUB_SCALE;
        return Util.constrainValue(textSize / SubtitleView.DEFAULT_TEXT_SIZE_FRACTION, MIN_SUB_SCALE, MAX_SUB_SCALE);
    }
}
