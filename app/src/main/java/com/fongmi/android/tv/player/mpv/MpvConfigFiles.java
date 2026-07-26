package com.fongmi.android.tv.player.mpv;

import android.content.Context;
import android.net.Uri;

import com.github.catvod.utils.Path;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MpvConfigFiles {

    private static final String MPV_CONF = "mpv.conf";
    private static final String FONTS_CONF = "fonts.conf";

    public static File file() {
        return Path.mpv(MPV_CONF);
    }

    public static String read() {
        return Path.read(file());
    }

    public static void write(String content) {
        String value = content == null ? "" : content;
        Path.write(file(), value.getBytes(StandardCharsets.UTF_8));
    }

    public static void importFrom(Context context, Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            Path.write(file(), in);
        } catch (Exception ignored) {
        }
    }

    public static Map<String, String> readGlobalOptions() {
        Map<String, String> options = new LinkedHashMap<>();
        boolean global = true;
        for (String sourceLine : read().split("\\R")) {
            String line = stripComment(sourceLine).trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                global = false;
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
        String content = "<fontconfig>\n"
                + "  <dir>/system/fonts</dir>\n"
                + "  <dir>/product/fonts</dir>\n"
                + "  <cachedir>" + cache + "</cachedir>\n"
                + "  <alias><family>serif</family><prefer><family>Noto Serif</family></prefer></alias>\n"
                + "  <alias><family>sans-serif</family><prefer><family>Roboto</family><family>Noto Sans</family></prefer></alias>\n"
                + "  <alias><family>monospace</family><prefer><family>Droid Sans Mono</family></prefer></alias>\n"
                + "</fontconfig>\n";
        if (!content.equals(Path.read(config))) Path.write(config, content.getBytes(StandardCharsets.UTF_8));
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
