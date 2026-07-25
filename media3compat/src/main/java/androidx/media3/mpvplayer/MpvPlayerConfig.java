package androidx.media3.mpvplayer;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration accepted by the MPV compatibility player.
 *
 * <p>Options are retained so a native MPV backend can consume the same API.
 * The bundled compatibility backend currently maps playback to Media3.
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
            return addPostInitStringOption("user-agent", value);
        }

        public Builder setHlsHttpPersistent(boolean value) {
            return addPostInitStringOption("hls-http-persistent", value ? "yes" : "no");
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
            return addPreInitStringOption("cache-dir", cacheDir.getAbsolutePath());
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
            addPostInitStringOption("cache-dir", cacheDir.getAbsolutePath());
            addPostInitStringOption("demuxer-readahead-secs", Integer.toString(seconds));
            return addPostInitStringOption("demuxer-max-bytes", sizeMb + "MiB");
        }

        public Builder addAndroidSubtitleOptions(Context context, boolean caption, double position, double scale) {
            addPostInitStringOption("sub-visibility", caption ? "yes" : "no");
            addPostInitStringOption("sub-pos", Double.toString(position));
            return addPostInitStringOption("sub-scale", Double.toString(scale));
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
