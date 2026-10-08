package com.fongmi.android.tv.player.mpv;

import android.content.pm.PackageManager;
import android.text.TextUtils;

import androidx.media3.common.Player;
import androidx.media3.common.util.Util;
import androidx.media3.mpvplayer.MpvAutomaticOutputPolicy;
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
import com.fongmi.android.tv.setting.VideoSetting;
import com.github.catvod.utils.Path;

import java.io.File;
import java.util.List;
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
    private static final String VALUE_OPENGL = "opengl";
    private static final String VALUE_VULKAN = "vulkan";
    private static final String VALUE_YES = "yes";
    private static final String OPT_PROXY_URL = "proxy-url";
    private static final int VULKAN_1_2 = 0x00402000;

    public static List<String> getManagedOptionNames() {
        return List.of("vo", "hwdec");
    }

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

    /** 2 is an internal live zero-copy candidate, never a persisted/user-selected decode mode. */
    public static int internalDecode(int decode, boolean live) {
        if (!isAutomaticCandidate(decode, live)) return decode;
        return MpvAutomaticOutputPolicy.acceptsUserOptions(MpvConfigFiles.readGlobalOptions(),
                PlayerSetting.isMpvGpuNext(), PlayerSetting.isMpvVulkan()) ? 2 : decode;
    }

    private static boolean isAutomaticCandidate(int decode, boolean live) {
        return MpvAutomaticOutputPolicy.direct(live, decode == 1, VideoSetting.isEnabled(),
                false, false, PlayerSetting.getRender() == PlayerSetting.RENDER_SURFACE);
    }

    private static int internalDecode(int decode, boolean live, Map<String, String> userOptions) {
        return isAutomaticCandidate(decode, live)
                && MpvAutomaticOutputPolicy.acceptsUserOptions(userOptions,
                        PlayerSetting.isMpvGpuNext(), PlayerSetting.isMpvVulkan()) ? 2 : decode;
    }

    public static MpvPlayer buildPlayer(int decode, boolean live, Player.Listener listener) {
        // The eligibility check and config builder must see the same parsed mpv.conf snapshot.
        Map<String, String> userOptions = MpvConfigFiles.readGlobalOptions();
        int internal = internalDecode(decode, live, userOptions);
        MpvPlayer player = new MpvPlayer.Builder(App.get()).setDecode(internal).setLive(live)
                .setConfig(buildConfig(internal, live, userOptions)).build();
        player.addListener(listener);
        return player;
    }

    public static void setSubtitleStyle(MpvPlayer player) {
        player.setSubtitleOptions(buildSubtitleConfig());
    }

    private static MpvPlayerConfig buildConfig(int decode, boolean live,
                                               Map<String, String> userOptions) {
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
        // Persistent HLS connections break on a number of IPTV/CDN servers that rotate hosts.
        if (!userOptions.containsKey("user-agent")) builder.setDefaultUserAgent(getDefaultUserAgent());
        if (!userOptions.containsKey("hls-http-persistent")) builder.setHlsHttpPersistent(false);
        if (!userOptions.containsKey(OPT_PROXY_URL)) {
            builder.addPreInitStringOption(OPT_PROXY_URL, Server.get().getAddress(true) + "/proxy?");
        }
        // A silent libmpv software fallback would leave the hard-decode setting looking active.
        if (!userOptions.containsKey("hwdec-software-fallback")) {
            builder.addPreInitStringOption("hwdec-software-fallback", "no");
        }
        if (decode == 2) {
            builder.addPreInitStringOption("vo", MpvAutomaticOutputPolicy.VO_MEDIACODEC_EMBED)
                    .addPreInitStringOption("hwdec", MpvAutomaticOutputPolicy.HWDEC_MEDIACODEC);
        } else {
            addVideoOutputOptions(builder, userOptions);
        }
        addStreamOptions(builder, userOptions, live);
        addPreloadOptions(builder, userOptions, live);
    }

    private static void addStreamOptions(MpvPlayerConfig.Builder builder, Map<String, String> userOptions, boolean live) {
        if (!userOptions.containsKey("network-timeout")) {
            builder.addPreInitStringOption("network-timeout", live ? "15" : "30");
        }
        if (!userOptions.containsKey("framedrop")) builder.addPreInitStringOption("framedrop", "vo");
        if (live && !userOptions.containsKey("video-sync")) builder.addPreInitStringOption("video-sync", "audio");
        if (!userOptions.containsKey("demuxer-max-bytes")) {
            // Low latency is a live setting. Applying it to VOD shrinks the cache of every title
            // whenever the live page is set to low delay.
            int mb = live && PlayerSetting.isLiveLowLatency()
                    ? Math.max(8, PlayerSetting.getBuffer() * 2)
                    : Math.max(15, PlayerSetting.getBuffer() * 3);
            builder.addPreInitStringOption("demuxer-max-bytes", mb + "MiB");
        }
        if (live) {
            if (!userOptions.containsKey("demuxer-max-back-bytes")) {
                builder.addPreInitStringOption("demuxer-max-back-bytes", "0");
            }
            if (!userOptions.containsKey("cache-secs")) {
                int seconds = PlayerSetting.isLiveLowLatency()
                        ? Math.min(2, Math.max(1, PlayerSetting.getBuffer() / 2))
                        : Math.max(1, PlayerSetting.getBuffer());
                builder.addPreInitStringOption("cache-secs", Integer.toString(seconds));
            }
            if (!userOptions.containsKey("demuxer-lavf-o")) {
                builder.addPreInitStringOption("demuxer-lavf-o", PlayerSetting.isLiveLowLatency()
                        ? "analyzeduration=1000000,probesize=524288"
                        : "analyzeduration=2500000,probesize=1048576");
            }
            if (!userOptions.containsKey("rtsp-transport")) {
                builder.addPreInitStringOption("rtsp-transport", "tcp");
            }
        } else {
            // VOD used mpv's short default read-ahead even when the user selected a larger
            // playback buffer. On high-bitrate remote MKVs this repeatedly emptied the cache
            // after a seek (observed on home-rk as 0% -> ~1s -> 0%). Do not override explicit
            // mpv.conf values or the separate disk-preload policy.
            if (!userOptions.containsKey("cache-secs")) {
                builder.addPreInitStringOption("cache-secs", Integer.toString(PlayerSetting.getBuffer()));
            }
            if (!userOptions.containsKey("demuxer-max-back-bytes")) {
                builder.addPreInitStringOption("demuxer-max-back-bytes", "8MiB");
            }
        }
        if (!userOptions.containsKey("stream-lavf-o")) {
            builder.addPreInitStringOption("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=5");
        }
    }

    private static String getVideoOutputDriver(Map<String, String> userOptions, int decode) {
        if (decode == 2) return MpvAutomaticOutputPolicy.VO_MEDIACODEC_EMBED;
        // Vulkan and gpu-next are alternate choices. Vulkan keeps vo=gpu with an androidvk context.
        if (PlayerSetting.isMpvVulkan() && isVulkanAvailable()) {
            return userOptions.containsKey("vo") ? null : VALUE_GPU;
        }
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
        if (VALUE_VULKAN.equals(userOptions.get(OPT_GPU_API))) {
            if (isVulkanAvailable()) {
                if (!userOptions.containsKey(OPT_GPU_CONTEXT)) builder.addPreInitStringOption(OPT_GPU_CONTEXT, VALUE_ANDROID_VK);
            } else {
                // A copied mpv.conf must not bypass the build/device capability guard.
                builder.addPreInitStringOption(OPT_GPU_API, VALUE_OPENGL)
                        .addPreInitStringOption(OPT_GPU_CONTEXT, VALUE_ANDROID)
                        .addPreInitStringOption(OPT_OPENGL_ES, VALUE_YES);
            }
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

    private static void addPreloadOptions(MpvPlayerConfig.Builder builder, Map<String, String> userOptions, boolean live) {
        // Persisting a moving live window wastes flash and can contend with decoder I/O.
        if (live || !PreloadSetting.isPreload()) return;
        File mediaCache = new File(Path.mpvCache(), "media");
        if (!userOptions.containsKey("cache")) builder.addPreInitStringOption("cache", "yes");
        if (!userOptions.containsKey("cache-on-disk")) builder.addPreInitStringOption("cache-on-disk", "yes");
        if (!userOptions.containsKey("demuxer-cache-dir")) {
            builder.addPreInitStringOption("demuxer-cache-dir", mediaCache.getAbsolutePath());
        }
        // cache-secs and demuxer-max-bytes stay on the playback buffer. Preload only places the disk cache.
    }

    private static void addSubtitleStyleOptions(MpvPlayerConfig.Builder builder) {
        builder.addAndroidSubtitleOptions(App.get(), PlayerSetting.isCaption(), getSubtitlePosition(), getSubtitleScale());
        // fonts.conf already lists Path.font(). A TTC face is selected with fontconfig's style,
        // because sub-font alone only matches the family and ignores faceIndex.
        com.fongmi.android.tv.player.subtitle.ExternalFont.Entry entry = com.fongmi.android.tv.setting.SubtitleSetting.getFontEntry();
        String font = entry == null ? com.fongmi.android.tv.setting.SubtitleSetting.getFontFamily() : entry.mpvFont();
        if (!TextUtils.isEmpty(font)) builder.addPostInitStringOption("sub-font", font);
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

    public static boolean isVulkanSupported() {
        return isVulkanAvailable();
    }

    private static boolean isVulkanAvailable() {
        PackageManager manager = App.get().getPackageManager();
        return com.fongmi.media3.compat.BuildConfig.LIBMPV_VULKAN
                && manager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VULKAN_1_2);
    }

    private static double getSubtitleScale() {
        float textSize = PlayerSetting.getSubtitleTextSize();
        if (textSize == 0) return DEFAULT_SUB_SCALE;
        return Util.constrainValue(textSize / SubtitleView.DEFAULT_TEXT_SIZE_FRACTION, MIN_SUB_SCALE, MAX_SUB_SCALE);
    }
}
