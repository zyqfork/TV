package com.fongmi.android.tv.bean;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.db.AppDatabase;
import com.github.catvod.utils.Prefers;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.google.gson.annotations.SerializedName;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.HashSet;

public class Backup {

    @SerializedName("site")
    private List<Site> site;
    @SerializedName("live")
    private List<Live> live;
    @SerializedName("keep")
    private List<Keep> keep;
    @SerializedName("config")
    private List<Config> config;
    @SerializedName("history")
    private List<History> history;
    @SerializedName("prefers")
    private Map<String, ?> prefers;

    public static Backup create() {
        Backup backup = new Backup();
        backup.setPrefers(Prefers.getPrefers().getAll());
        backup.setSite(AppDatabase.get().getSiteDao().findAll());
        backup.setLive(AppDatabase.get().getLiveDao().findAll());
        backup.setKeep(AppDatabase.get().getKeepDao().findAll());
        backup.setConfig(AppDatabase.get().getConfigDao().findAll());
        backup.setHistory(AppDatabase.get().getHistoryDao().findAll());
        return backup;
    }

    public static Backup objectFrom(String json) {
        try {
            Gson gson = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LAZILY_PARSED_NUMBER).create();
            Backup backup = gson.fromJson(json, Backup.class);
            return backup == null ? new Backup() : backup;
        } catch (Exception e) {
            return new Backup();
        }
    }

    public void restore() {
        AppDatabase db = AppDatabase.get();
        SharedPreferences preferences = Prefers.getPrefers();
        Map<String, ?> previous = new HashMap<>(preferences.getAll());
        // Validate and stage preferences before deleting anything. Keep legacy merge semantics.
        SharedPreferences.Editor editor = stagePreferences(preferences.edit(), getPrefers(), previous);
        boolean[] preferencesAttempted = {false};
        try {
            db.runInTransaction(() -> {
                // Device and Track are not backed up. Never touch them, even during rollback.
                db.getSiteDao().deleteAllForRestore();
                db.getLiveDao().deleteAllForRestore();
                db.getKeepDao().deleteAllForRestore();
                db.getConfigDao().deleteAllForRestore();
                db.getHistoryDao().delete();
                db.getSiteDao().insertOrUpdate(getSite());
                db.getLiveDao().insertOrUpdate(getLive());
                db.getKeepDao().insertOrUpdate(getKeep());
                db.getConfigDao().insertOrUpdate(getConfig());
                db.getHistoryDao().insertOrUpdate(getHistory());
                preferencesAttempted[0] = true;
                if (!editor.commit()) throw new IllegalStateException("Unable to persist restored preferences");
            });
        } catch (RuntimeException failure) {
            // Preferences and SQLite have different transactions; compensate if prefs were written.
            if (preferencesAttempted[0] &&
                    !stagePreferences(preferences.edit().clear(), previous, previous).commit()) {
                failure.addSuppressed(new IllegalStateException("Unable to roll back preferences"));
            }
            throw failure;
        }
    }

    private static SharedPreferences.Editor stagePreferences(SharedPreferences.Editor editor,
                                                             Map<String, ?> values, Map<String, ?> previous) {
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || value == null) throw new IllegalArgumentException("Invalid preference");
            if (value instanceof String v) editor.putString(key, v);
            else if (value instanceof Boolean v) editor.putBoolean(key, v);
            else if (value instanceof Float v) editor.putFloat(key, v);
            else if (value instanceof Long v) editor.putLong(key, v);
            else if (value instanceof Number v) {
                if (previous.get(key) instanceof Long) editor.putLong(key, v.longValue());
                else if (previous.get(key) instanceof Float || v.toString().contains(".")) editor.putFloat(key, v.floatValue());
                else if (v.longValue() > Integer.MAX_VALUE || v.longValue() < Integer.MIN_VALUE) editor.putLong(key, v.longValue());
                else editor.putInt(key, v.intValue());
            } else if (value instanceof Collection<?> collection) {
                HashSet<String> strings = new HashSet<>();
                for (Object item : collection) {
                    if (!(item instanceof String)) throw new IllegalArgumentException("Invalid preference set");
                    strings.add((String) item);
                }
                editor.putStringSet(key, strings);
            } else throw new IllegalArgumentException("Unsupported preference value");
        }
        return editor;
    }

    public List<Site> getSite() {
        return site == null ? Collections.emptyList() : site;
    }

    public void setSite(List<Site> site) {
        this.site = site;
    }

    public List<Live> getLive() {
        return live == null ? Collections.emptyList() : live;
    }

    public void setLive(List<Live> live) {
        this.live = live;
    }

    public List<Keep> getKeep() {
        return keep == null ? Collections.emptyList() : keep;
    }

    public void setKeep(List<Keep> keep) {
        this.keep = keep;
    }

    public List<Config> getConfig() {
        return config == null ? Collections.emptyList() : config;
    }

    public void setConfig(List<Config> config) {
        this.config = config;
    }

    public List<History> getHistory() {
        return history == null ? Collections.emptyList() : history;
    }

    public void setHistory(List<History> history) {
        this.history = history;
    }

    public Map<String, ?> getPrefers() {
        return prefers == null ? new HashMap<>() : prefers;
    }

    public void setPrefers(Map<String, ?> prefers) {
        this.prefers = prefers;
    }

    @NonNull
    @Override
    public String toString() {
        return App.gson().toJson(this);
    }
}
