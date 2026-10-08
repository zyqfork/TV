package com.fongmi.android.tv.utils;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class Github {

    public static final String OWNER = "zyqfork";
    public static final String REPO = "TV";
    /**
     * Deliberately the release <em>list</em> and not {@code /releases/latest}: GitHub picks "latest"
     * by publish time, so a later manual/test release (the repo has {@code v0.0.0-manual.*} tags)
     * can take that slot. Its version parses to 0, which silently switched updates off for everyone.
     * Scanning the list and taking the highest version is independent of publish order.
     */
    public static final String API_RELEASES = "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases?per_page=30";

    public static String getJson() {
        return API_RELEASES;
    }

    /**
     * Downloads from the exact release tag instead of {@code releases/latest/download}, which
     * carries the same "latest may not be the newest version" problem as {@link #API_RELEASES}.
     */
    public static String getApk(String tag, String name) {
        return "https://github.com/" + OWNER + "/" + REPO + "/releases/download/" + tag + "/" + name + ".apk";
    }

    /** A release that is strictly newer than the installed build. */
    public record Release(String tag, String desc) {
    }

    /**
     * Picks the newest published, non-prerelease entry of a {@code /releases} payload.
     *
     * @param json          raw GitHub releases list
     * @param currentCode   installed VERSION_CODE (5.6.0 -&gt; 50600)
     * @param currentSource installed source revision, 0 when the build carries none
     * @return the newest release that is newer than the installed build, or {@code null} if none
     */
    @Nullable
    public static Release findNewer(String json, int currentCode, int currentSource) throws JSONException {
        JSONArray releases = new JSONArray(json);
        Release best = null;
        int bestCode = 0;
        int bestSource = 0;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject item = releases.optJSONObject(i);
            if (item == null || item.optBoolean("draft") || item.optBoolean("prerelease")) continue;
            String tag = item.optString("tag_name");
            if (tag.isEmpty()) tag = item.optString("name");
            int code = parseCode(tag);
            // A tag with no comparable version (v0.0.0-manual.N) is never an update candidate.
            if (code <= 0) continue;
            int source = parseSourceRevision(tag);
            if (compare(code, source, bestCode, bestSource) <= 0) continue;
            bestCode = code;
            bestSource = source;
            best = new Release(tag, item.optString("body"));
        }
        if (best == null) return null;
        return compare(bestCode, bestSource, currentCode, currentSource) > 0 ? best : null;
    }

    private static int compare(int codeA, int sourceA, int codeB, int sourceB) {
        if (codeA != codeB) return Integer.compare(codeA, codeB);
        return Integer.compare(sourceA, sourceB);
    }

    /**
     * Map a release tag to the VERSION_CODE stamped by CI (5.6.0 -&gt; 50600, 5.5.10 -&gt; 50510).
     * Two digits each for minor and patch, so 5.5.10 and 5.6.0 do not collide.
     * Accepts v5.6.1, 5.6.1, or v5.6.1-source.1 (suffix ignored).
     */
    public static int parseCode(String tag) {
        if (tag == null || tag.isEmpty()) return 0;
        String text = tag.trim();
        if (text.startsWith("v") || text.startsWith("V")) text = text.substring(1);
        text = text.split("-", 2)[0].split("\\+", 2)[0];
        String[] parts = text.split("\\.");
        try {
            int major = parts.length > 0 && !parts[0].isEmpty() ? Integer.parseInt(parts[0]) : 0;
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            return major * 10000 + minor * 100 + patch;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Source build number from tags like {@code v5.5.6-source.8}; 0 when absent.
     * Same X.Y.Z can ship several source drops — treat a higher source number as newer.
     */
    public static int parseSourceRevision(String tag) {
        if (tag == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)-source\\.(\\d+)")
                .matcher(tag.trim());
        if (!m.find()) return 0;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
