package androidx.media3.mpvplayer;

import android.content.Context;
import android.graphics.Color;
import android.view.accessibility.CaptioningManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Configuration applied to the native libmpv-backed Media3 player.
 */
public final class MpvPlayerConfig {

    public static final String VIDEO_OUTPUT_GPU_NEXT = "gpu-next";

    final Map<String, String> preInitOptions;
    final Map<String, String> postInitOptions;

    private MpvPlayerConfig(Map<String, String> preInitOptions, Map<String, String> postInitOptions) {
        this.preInitOptions = preInitOptions;
        this.postInitOptions = postInitOptions;
    }

    public static final class Builder {

        private final Map<String, String> preInitOptions = new LinkedHashMap<>();
        private final Map<String, String> postInitOptions = new LinkedHashMap<>();

        public Builder setDefaultUserAgent(String value) {
            return addPreInitStringOption("user-agent", value);
        }

        public Builder setHlsHttpPersistent(boolean value) {
            return addPreInitStringOption("hls-http-persistent", value ? "yes" : "no");
        }

        public Builder addConfigDirectory(File directory) {
            return addPreInitStringOption("config-dir", directory.getAbsolutePath());
        }

        public Builder addAndroidFontConfig(File configDir, File cacheDir) {
            addPreInitStringOption("fontconfig-config-dir", configDir.getAbsolutePath());
            return addPreInitStringOption("fontconfig-cache-dir", cacheDir.getAbsolutePath());
        }

        public Builder addAndroidDefaults(String videoOutput, File cacheDir) {
            if (videoOutput != null) addPreInitStringOption("vo", videoOutput);
            return addPreInitStringOption("gpu-shader-cache-dir", cacheDir.getAbsolutePath());
        }

        public Builder addTlsCaFileFromAsset(Context context, String assetName, File destination) {
            if (!destination.exists()) {
                File parent = destination.getParentFile();
                if (parent != null) parent.mkdirs();
                try (InputStream input = context.getAssets().open(assetName);
                     FileOutputStream output = new FileOutputStream(destination)) {
                    input.transferTo(output);
                } catch (Exception ignored) {
                }
            }
            return addPreInitStringOption("tls-ca-file", destination.getAbsolutePath());
        }

        public Builder addDiskCacheOptions(File cacheDir, int seconds, int sizeMb) {
            addPreInitStringOption("cache", "yes");
            addPreInitStringOption("cache-on-disk", "yes");
            addPreInitStringOption("demuxer-cache-dir", cacheDir.getAbsolutePath());
            addPreInitStringOption("cache-secs", Integer.toString(seconds));
            addPreInitStringOption("demuxer-max-bytes", sizeMb + "MiB");
            return addPreInitStringOption("demuxer-max-back-bytes", "0");
        }

        public Builder addAndroidSubtitleOptions(Context context, boolean caption, double position, double scale) {
            int foreground = Color.WHITE;
            int background = Color.TRANSPARENT;
            int edgeColor = Color.BLACK;
            int edgeType = CaptioningManager.CaptionStyle.EDGE_TYPE_NONE;
            if (caption) {
                addPostInitStringOption("embeddedfonts", "no");
                addPostInitStringOption("sub-ass-override", "force");
                CaptioningManager manager =
                        (CaptioningManager) context.getSystemService(Context.CAPTIONING_SERVICE);
                CaptioningManager.CaptionStyle style = manager == null ? null : manager.getUserStyle();
                if (style != null) {
                    if (style.hasForegroundColor()) foreground = style.foregroundColor;
                    if (style.hasBackgroundColor()) background = style.backgroundColor;
                    if (style.hasEdgeColor()) edgeColor = style.edgeColor;
                    if (style.hasEdgeType()) edgeType = style.edgeType;
                }
            }
            addPostInitStringOption("sub-color", color(foreground));
            addPostInitStringOption("sub-back-color", color(background));
            addPostInitStringOption("sub-outline-color", color(edgeColor));
            addPostInitStringOption("sub-border-style",
                    Color.alpha(background) > 0 ? "background-box" : "outline-and-shadow");
            if (edgeType == CaptioningManager.CaptionStyle.EDGE_TYPE_DROP_SHADOW) {
                addPostInitStringOption("sub-back-color", color(edgeColor));
                addPostInitStringOption("sub-border-style", "outline-and-shadow");
                addPostInitStringOption("sub-outline-size", "0");
                addPostInitStringOption("sub-shadow-offset", "2");
            } else if (edgeType == CaptioningManager.CaptionStyle.EDGE_TYPE_NONE) {
                addPostInitStringOption("sub-outline-size", caption ? "0" : "1.65");
                addPostInitStringOption("sub-shadow-offset", "0");
            } else {
                addPostInitStringOption("sub-outline-size", "1.65");
                addPostInitStringOption("sub-shadow-offset", "0");
            }
            addPostInitStringOption("sub-pos", Double.toString(position));
            return addPostInitStringOption("sub-scale", Double.toString(scale));
        }

        private static String color(int value) {
            return String.format(Locale.US, "#%02X%02X%02X%02X",
                    Color.alpha(value), Color.red(value), Color.green(value), Color.blue(value));
        }

        public Builder addPreInitStringOption(String key, String value) {
            if (key != null && value != null) preInitOptions.put(key, value);
            return this;
        }

        public Builder addPostInitStringOption(String key, String value) {
            if (key != null && value != null) postInitOptions.put(key, value);
            return this;
        }

        public MpvPlayerConfig build() {
            return new MpvPlayerConfig(Map.copyOf(preInitOptions), Map.copyOf(postInitOptions));
        }
    }
}
