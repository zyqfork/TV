package com.fongmi.android.tv.api.parser;

import java.util.Locale;

/** Shared folding for live channel names and XMLTV display names. */
public final class EpgName {

    /** Quality words stripped only at lookup, never while indexing a channel. */
    private static final String[] QUALITY = {
            "高清", "超清", "标清", "综合", "綜合", "HD", "FHD", "UHD", "HDR", "HEVC", "H265"
    };

    private EpgName() {
    }

    /**
     * Punctuation and width fold. Keeps 频道/頻道 and 4K/8K so "电影频道" and "CCTV-4K"
     * stay distinct keys.
     */
    public static String fold(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder builder = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '０' && c <= '９') c = (char) ('0' + (c - '０'));
            if (Character.isWhitespace(c) || c == '-' || c == '_' || c == '·' || c == '—' || c == '－' || c == '.' || c == '/') continue;
            builder.append(Character.toUpperCase(c));
        }
        return builder.toString();
    }

    /** Quality suffix removed from an already folded name. */
    public static String stripQuality(String folded) {
        if (folded == null || folded.isEmpty()) return "";
        String out = folded;
        boolean trimmed;
        do {
            trimmed = false;
            for (String suffix : QUALITY) {
                String upper = suffix.toUpperCase(Locale.ROOT);
                if (out.endsWith(upper) && out.length() > upper.length()) {
                    out = out.substring(0, out.length() - upper.length());
                    trimmed = true;
                }
            }
        } while (trimmed);
        return out;
    }

    /** Fold plus quality-suffix strip. Indexing uses {@link #fold(String)} alone. */
    public static String normalize(String raw) {
        return stripQuality(fold(raw));
    }
}
