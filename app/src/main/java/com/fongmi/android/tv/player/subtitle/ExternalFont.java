package com.fongmi.android.tv.player.subtitle;

import android.graphics.Typeface;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.FileUtil;
import com.github.catvod.utils.Crypto;
import com.github.catvod.utils.Path;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * External font files for MPV/libass subtitle rendering.
 * Fonts live under Path.font() and are referenced from fonts.conf.
 */
public final class ExternalFont {

    private static final String TEMP_FILE_PREFIX = ".external-font-";
    private static final String TEMP_FILE_SUFFIX = ".tmp";
    private static final int COPY_BUFFER_SIZE = 64 * 1024;
    private static final long MAX_FILE_BYTES = 32L * 1024 * 1024;
    private static final String[] EXTENSIONS = {".ttf", ".otf", ".ttc"};

    public static File getDirectory() {
        return Path.font();
    }

    public static List<Entry> getAll() {
        File[] files = listSupportedFiles(getDirectory());
        if (files == null) return List.of();
        return Arrays.stream(files)
                .sorted((first, second) -> first.getName().compareToIgnoreCase(second.getName()))
                .flatMap(file -> getEntries(file).stream())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    @Nullable
    public static Entry getEntry(File file) {
        List<Entry> entries = getEntries(file);
        return entries.isEmpty() ? null : entries.get(0);
    }

    public static List<Entry> getEntries(File file) {
        if (file == null || !file.isFile() || file.length() <= 0 || file.length() > MAX_FILE_BYTES) return List.of();
        String fileName = file.getName();
        int dot = fileName.lastIndexOf('.');
        String display = dot > 0 ? fileName.substring(0, dot) : fileName;
        List<FontFamilyParser.Face> faces = FontFamilyParser.readFaces(file);
        List<Entry> entries = new ArrayList<>(faces.size());
        for (FontFamilyParser.Face face : faces) {
            String label = faces.size() > 1 ? display + " · " + face.family() : display;
            if (face.variable() && !face.instances().isEmpty()) label += " (" + String.join(", ", face.instances()) + ")";
            entries.add(new Entry(file.getAbsolutePath(), label, face.family(), face.index(), face.variable(), face.instances(), face.subfamily()));
        }
        return entries;
    }

    @Nullable
    public static String getFamilyName(File file) {
        return FontFamilyParser.read(file);
    }

    @Nullable
    public static String importFrom(android.content.Context context, android.net.Uri uri) {
        if (context == null || uri == null) return null;
        File dir = getDirectory();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return null;
        File temp = null;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) return null;
            String display = FileUtil.getDisplayName(uri);
            if (TextUtils.isEmpty(display)) {
                // Some pickers hand back a content URI with no name to derive one from.
                display = "font-" + Crypto.md5(String.valueOf(uri)) + ".ttf";
            } else if (!isSupportedName(display)) {
                // Allow octet-stream pickers that drop the real extension.
                display = display + ".ttf";
            }
            temp = File.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX, dir);
            try (FileOutputStream output = new FileOutputStream(temp)) {
                byte[] buffer = new byte[COPY_BUFFER_SIZE];
                long total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (Thread.interrupted()) throw new InterruptedIOException();
                    total += read;
                    if (total > MAX_FILE_BYTES) return null;
                    output.write(buffer, 0, read);
                }
                output.getFD().sync();
            }
            if (FontFamilyParser.readFaces(temp).isEmpty()) return null;
            File target = new File(dir, sanitizeName(display));
            if (!temp.renameTo(target)) {
                try {
                    Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) {
                    return null;
                }
            }
            temp = null;
            return target.getAbsolutePath();
        } catch (Exception e) {
            return null;
        } finally {
            if (temp != null && temp.exists()) temp.delete();
        }
    }

    private static File[] listSupportedFiles(File dir) {
        if (dir == null) return null;
        File[] files = dir.listFiles((folder, name) -> new File(folder, name).isFile() && isSupportedName(name));
        return files == null ? null : files;
    }

    private static boolean isSupportedName(String name) {
        if (TextUtils.isEmpty(name)) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : EXTENSIONS) if (lower.endsWith(extension)) return true;
        return false;
    }

    private static String sanitizeName(String name) {
        String value = name == null ? "" : name.trim();
        value = value.replaceAll("[\\\\/:*?\"<>|]", "_");
        return TextUtils.isEmpty(value) ? ("font-" + Crypto.md5(String.valueOf(System.nanoTime())) + ".ttf") : value;
    }

    public record Entry(String path, String name, String family, int faceIndex, boolean variable, List<String> instances, String subfamily) {

        /** fontconfig pattern. A non-regular face is selected with family:style=subfamily. */
        public String mpvFont() {
            if (family == null || family.isEmpty()) return "";
            if (subfamily == null) return family;
            String style = subfamily.trim();
            if (style.isEmpty() || style.equalsIgnoreCase("Regular") || style.equalsIgnoreCase("Normal") || "常规".equals(style) || "標準".equals(style)) return family;
            return family + ":style=" + style;
        }

        public Typeface typeface() {
            try {
                File file = new File(path);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    return new Typeface.Builder(file).setTtcIndex(faceIndex).build();
                }
                return Typeface.createFromFile(file);
            } catch (Exception e) {
                return Typeface.DEFAULT;
            }
        }
    }
}
