package com.fongmi.android.tv.player.subtitle;

import androidx.annotation.Nullable;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Bounds-checked OpenType/TrueType metadata reader with TTC and variable-font support. */
final class FontFamilyParser {

    private static final int TAG_TTCF = 0x74746366;
    private static final int TAG_NAME = 0x6E616D65;
    private static final int TAG_FVAR = 0x66766172;
    private static final int MAX_FONT_BYTES = 32 * 1024 * 1024;

    private FontFamilyParser() {
    }

    @Nullable
    static String read(File file) {
        List<Face> faces = readFaces(file);
        return faces.isEmpty() ? null : faces.get(0).family();
    }

    static List<Face> readFaces(File file) {
        if (file == null || !file.isFile() || file.length() <= 0 || file.length() > MAX_FONT_BYTES) return List.of();
        try {
            ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(file.toPath())).order(ByteOrder.BIG_ENDIAN);
            if (buffer.limit() < 12) return List.of();
            List<Face> faces = new ArrayList<>();
            if (buffer.getInt(0) == TAG_TTCF) readCollection(buffer, faces);
            else addFace(buffer, 0, 0, faces);
            return List.copyOf(faces);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static void readCollection(ByteBuffer buffer, List<Face> faces) {
        long count = uint(buffer, 8);
        if (count <= 0 || count > 256 || !contains(buffer, 12, count * 4)) return;
        for (int index = 0; index < count; index++) {
            long offset = uint(buffer, 12 + index * 4);
            if (offset <= Integer.MAX_VALUE) addFace(buffer, (int) offset, index, faces);
        }
    }

    private static void addFace(ByteBuffer buffer, int faceOffset, int faceIndex, List<Face> faces) {
        if (!contains(buffer, faceOffset, 12)) return;
        int tableCount = ushort(buffer, faceOffset + 4);
        if (tableCount <= 0 || tableCount > 4096 || !contains(buffer, faceOffset + 12L, tableCount * 16L)) return;
        Table name = null;
        Table fvar = null;
        for (int i = 0; i < tableCount; i++) {
            int record = faceOffset + 12 + i * 16;
            int tag = buffer.getInt(record);
            long offset = uint(buffer, record + 8);
            long length = uint(buffer, record + 12);
            if (offset > Integer.MAX_VALUE || length > Integer.MAX_VALUE || !contains(buffer, offset, length)) continue;
            if (tag == TAG_NAME) name = new Table((int) offset, (int) length);
            if (tag == TAG_FVAR) fvar = new Table((int) offset, (int) length);
        }
        if (name == null) return;
        List<NameValue> names = readNames(buffer, name);
        String family = bestName(names, 16, 1);
        if (family == null) return;
        String subfamily = bestName(names, 17, 2);
        String postScriptName = bestName(names, 6);
        String fullName = bestName(names, 4);
        List<String> instances = fvar == null ? List.of() : readInstances(buffer, fvar, names);
        faces.add(new Face(faceIndex, family, subfamily == null ? "" : subfamily, postScriptName == null ? "" : postScriptName, fullName == null ? "" : fullName, buffer.getInt(faceOffset) == 0x4F54544F, fvar != null, instances));
    }

    private static List<NameValue> readNames(ByteBuffer buffer, Table table) {
        if (table.length < 6) return List.of();
        int count = ushort(buffer, table.offset + 2);
        int strings = ushort(buffer, table.offset + 4);
        if (count <= 0 || count > 4096 || 6L + count * 12L > table.length) return List.of();
        List<NameValue> values = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int record = table.offset + 6 + i * 12;
            int platform = ushort(buffer, record);
            int encoding = ushort(buffer, record + 2);
            int language = ushort(buffer, record + 4);
            int nameId = ushort(buffer, record + 6);
            int length = ushort(buffer, record + 8);
            int offset = ushort(buffer, record + 10);
            long absolute = (long) table.offset + strings + offset;
            if (absolute < table.offset || absolute + length > (long) table.offset + table.length || !contains(buffer, absolute, length)) continue;
            String value = decode(buffer, (int) absolute, length, platform, encoding);
            if (value != null) values.add(new NameValue(nameId, platform, language, value));
        }
        return values;
    }

    private static List<String> readInstances(ByteBuffer buffer, Table table, List<NameValue> names) {
        if (table.length < 16) return List.of();
        int axesOffset = ushort(buffer, table.offset + 4);
        int axisCount = ushort(buffer, table.offset + 8);
        int axisSize = ushort(buffer, table.offset + 10);
        int instanceCount = ushort(buffer, table.offset + 12);
        int instanceSize = ushort(buffer, table.offset + 14);
        if (axisCount > 64 || instanceCount > 1024 || axisSize < 20 || instanceSize < 4 + axisCount * 4) return List.of();
        long instancesOffset = (long) table.offset + axesOffset + (long) axisCount * axisSize;
        if (!contains(buffer, instancesOffset, (long) instanceCount * instanceSize)) return List.of();
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i < instanceCount; i++) {
            int nameId = ushort(buffer, (int) instancesOffset + i * instanceSize);
            String name = bestName(names, nameId);
            if (name != null) result.add(name);
        }
        return List.copyOf(result);
    }

    @Nullable
    private static String bestName(List<NameValue> names, int... ids) {
        Candidate best = null;
        for (int idPriority = 0; idPriority < ids.length; idPriority++) {
            for (NameValue value : names) {
                if (value.nameId != ids[idPriority]) continue;
                int score = (ids.length - idPriority) * 100 + languageScore(value.platform, value.language);
                Candidate current = new Candidate(value.value, score);
                if (best == null || current.score > best.score) best = current;
            }
        }
        return best == null ? null : best.value;
    }

    @Nullable
    private static String decode(ByteBuffer buffer, int offset, int length, int platform, int encoding) {
        if (length <= 0) return null;
        try {
            byte[] value = new byte[length];
            ByteBuffer copy = buffer.duplicate();
            copy.position(offset);
            copy.get(value);
            Charset charset = platform == 0 || platform == 3
                    ? StandardCharsets.UTF_16BE
                    : platform == 1 ? Charset.forName("x-MacRoman") : StandardCharsets.ISO_8859_1;
            String text = new String(value, charset).replace("\u0000", "").trim();
            return text.isEmpty() ? null : text;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int languageScore(int platform, int language) {
        int score = platform == 0 || platform == 3 ? 20 : 0;
        String locale = Locale.getDefault().getLanguage();
        int primary = language & 0x3ff;
        if (("zh".equals(locale) && primary == 0x04)
                || ("ja".equals(locale) && primary == 0x11)
                || ("ko".equals(locale) && primary == 0x12)
                || ("en".equals(locale) && primary == 0x09)) score += 20;
        if (language == 0 || language == 0x0409) score += 5;
        return score;
    }

    private static int ushort(ByteBuffer buffer, int offset) {
        return Short.toUnsignedInt(buffer.getShort(offset));
    }

    private static long uint(ByteBuffer buffer, int offset) {
        return Integer.toUnsignedLong(buffer.getInt(offset));
    }

    private static boolean contains(ByteBuffer buffer, long offset, long length) {
        return offset >= 0 && length >= 0 && offset <= buffer.limit() && length <= buffer.limit() - offset;
    }

    record Face(int index, String family, String subfamily, String postScriptName, String fullName, boolean postScript, boolean variable, List<String> instances) {
    }

    private record Table(int offset, int length) {
    }

    private record NameValue(int nameId, int platform, int language, String value) {
    }

    private record Candidate(String value, int score) {
    }
}
