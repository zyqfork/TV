package com.fongmi.android.tv.player.mpv;

import android.content.Context;
import android.net.Uri;

import com.fongmi.android.tv.utils.FileUtil;
import com.github.catvod.utils.Path;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MpvConfigFiles {

    private static final String MPV_CONF = "mpv.conf";
    private static final String FONTS_CONF = "fonts.conf";

    public static File file() {
        return Path.mpv(MPV_CONF);
    }

    public static String read() {
        String value = Path.read(file());
        if (value != null && !value.trim().isEmpty()) return value;
        return "# mpv.conf — user options override Android defaults.\n"
                + "# Playback mode still owns vo/hwdec. slang and sub-font follow the in-app subtitle settings.\n"
                + "# gpu-next and Vulkan are separate switches and override vo/gpu-api while enabled.\n"
                + "# Example:\n"
                + "# vo=gpu\n"
                + "# hwdec=mediacodec-copy\n";
    }

    public static boolean write(String content) {
        try {
            FileUtil.writeAtomically((content == null ? "" : content).getBytes(StandardCharsets.UTF_8), file());
            return true;
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    public static boolean importFrom(Context context, Uri uri) {
        try {
            FileUtil.copyAtomically(uri, file());
            return true;
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    public static List<String> findInterfaceManagedOptions(CharSequence content) {
        Set<String> options = getDefaultOptions(content == null ? "" : content.toString());
        return MpvUtil.getManagedOptionNames().stream()
                .filter(option -> options.contains(option) || options.contains("no-" + option))
                .toList();
    }

    public static Map<String, String> readGlobalOptions() {
        return parseGlobalOptions(read());
    }

    /** Same parser for playback and the managed-option warning, including imported UTF-8 BOM. */
    private static Map<String, String> parseGlobalOptions(String content) {
        Map<String, String> options = new LinkedHashMap<>();
        if (content == null || content.isEmpty()) return options;
        if (content.charAt(0) == '\uFEFF') content = content.substring(1);
        boolean global = true;
        for (String sourceLine : content.split("\\R")) {
            String line = stripComment(sourceLine).trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                // mpv applies the [default] profile without being asked, so options inside it are as
                // global as the ones before any section — getDefaultOptions() below already treats
                // them that way. Ignoring them here let the app overwrite a user's [default] vo/hwdec.
                String profile = line.substring(1, line.length() - 1).trim();
                global = profile.isEmpty() || "default".equals(profile);
                continue;
            }
            if (!global) continue;
            if (line.startsWith("--")) line = line.substring(2);
            int separator = line.indexOf('=');
            String key = separator < 0 ? line : line.substring(0, separator).trim();
            String value = separator < 0 ? "yes" : unquote(line.substring(separator + 1).trim());
            if (key.startsWith("no-") && separator < 0) {
                key = key.substring(3);
                value = "no";
            }
            if (!key.isEmpty() && !isReserved(key)) options.put(key, value);
        }
        return options;
    }

    public static void ensureAndroidFontsConfig(File cacheDir) {
        File config = Path.mpv(FONTS_CONF);
        String cache = escapeXml(cacheDir.getAbsolutePath());
        String fontsDir = escapeXml(Path.font().getAbsolutePath());
        String content = "<fontconfig>\n"
                + "  <dir>/system/fonts</dir>\n"
                + "  <dir>/product/fonts</dir>\n"
                + "  <dir>" + fontsDir + "</dir>\n"
                + "  <cachedir>" + cache + "</cachedir>\n"
                + "  <alias><family>serif</family><prefer><family>Noto Serif</family></prefer></alias>\n"
                + "  <alias><family>sans-serif</family><prefer><family>Roboto</family><family>Noto Sans</family></prefer></alias>\n"
                + "  <alias><family>monospace</family><prefer><family>Droid Sans Mono</family></prefer></alias>\n"
                + "</fontconfig>\n";
        if (!content.equals(Path.read(config))) Path.write(config, content.getBytes(StandardCharsets.UTF_8));
    }

    private static Set<String> getDefaultOptions(String content) {
        return parseGlobalOptions(content).keySet();
    }

    private static boolean isReserved(String key) {
        return "config".equals(key) || "config-dir".equals(key) || "include".equals(key);
    }

    private static String stripComment(String value) {
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if ((current == '\'' || current == '"') && (i == 0 || value.charAt(i - 1) != '\\')) {
                quote = quote == 0 ? current : quote == current ? 0 : quote;
            } else if (current == '#' && quote == 0) {
                return value.substring(0, i);
            }
        }
        return value;
    }

    private static String unquote(String value) {
        if (value.length() < 2) return value;
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        return first == last && (first == '\'' || first == '"')
                ? value.substring(1, value.length() - 1)
                : value;
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
