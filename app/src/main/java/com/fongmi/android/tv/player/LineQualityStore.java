package com.fongmi.android.tv.player;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LineQualityStore {

    private static final String KEY = "live_line_quality_v1";
    private static final int MAX_ENTRIES = 200;
    private static final Type TYPE = new TypeToken<Map<String, LineScore>>() {}.getType();

    private static Map<String, LineScore> load() {
        try {
            String json = Prefers.getString(KEY, "");
            if (TextUtils.isEmpty(json)) return new HashMap<>();
            Map<String, LineScore> map = App.gson().fromJson(json, TYPE);
            return map == null ? new HashMap<>() : map;
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private static void save(Map<String, LineScore> map) {
        trim(map);
        Prefers.put(KEY, App.gson().toJson(map));
    }

    /** Keep the most recently used entries (ok/fail timestamps). */
    private static void trim(Map<String, LineScore> map) {
        if (map.size() <= MAX_ENTRIES) return;
        List<Map.Entry<String, LineScore>> entries = new ArrayList<>(map.entrySet());
        entries.sort(Comparator.comparingLong((Map.Entry<String, LineScore> e) -> Math.max(e.getValue().lastOkAt, e.getValue().lastFailAt)).reversed());
        map.clear();
        for (int i = 0; i < Math.min(MAX_ENTRIES, entries.size()); i++) {
            map.put(entries.get(i).getKey(), entries.get(i).getValue());
        }
    }

    public static void recordSuccess(String rawUrl, long openMs) {
        String key = normalize(rawUrl);
        if (key.isEmpty()) return;
        Map<String, LineScore> map = load();
        LineScore score = map.getOrDefault(key, new LineScore());
        score.ok += 1;
        score.lastOkAt = System.currentTimeMillis();
        if (openMs > 0) {
            score.openCount += 1;
            score.openMsAvg = score.openMsAvg <= 0 ? openMs : (score.openMsAvg * 4 + openMs) / 5;
        }
        if (score.fail > 0) score.fail -= 1;
        map.put(key, score);
        save(map);
    }

    public static void recordFailure(String rawUrl) {
        String key = normalize(rawUrl);
        if (key.isEmpty()) return;
        Map<String, LineScore> map = load();
        LineScore score = map.getOrDefault(key, new LineScore());
        score.fail += 1;
        score.lastFailAt = System.currentTimeMillis();
        map.put(key, score);
        save(map);
    }

    public static int bestIndex(List<String> urls, int fallbackIndex) {
        if (urls == null || urls.isEmpty()) return Math.max(fallbackIndex, 0);
        Map<String, LineScore> map = load();
        long best = Long.MIN_VALUE;
        int index = Math.max(0, Math.min(fallbackIndex, urls.size() - 1));
        for (int i = 0; i < urls.size(); i++) {
            LineScore s = map.get(normalize(urls.get(i)));
            long score = qualityScore(s);
            if (score > best) {
                best = score;
                index = i;
            }
        }
        return index;
    }

    private static long qualityScore(LineScore s) {
        if (s == null) return 0;
        long base = (long) s.ok * 1000L - (long) s.fail * 2000L;
        long speedBonus = s.openMsAvg > 0 ? Math.max(0, 3000L - s.openMsAvg) : 0;
        long freshBonus = s.lastOkAt > s.lastFailAt ? 300 : -300;
        return base + speedBonus + freshBonus;
    }

    public static String normalize(String rawUrl) {
        if (rawUrl == null) return "";
        return rawUrl.split("\\$")[0].trim();
    }

    private static class LineScore {
        int ok;
        int fail;
        int openCount;
        long openMsAvg;
        long lastOkAt;
        long lastFailAt;
    }
}
