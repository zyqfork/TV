package com.fongmi.android.tv.playback.vod;

import android.text.TextUtils;

import androidx.media3.common.C;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Task;

public class VodHistoryPolicy {

    public History findOrCreate(String key, String mark, Vod item) {
        History history = History.find(key);
        history = history == null ? create(key, item) : history;
        if (!TextUtils.isEmpty(mark)) history.setVodRemarks(mark);
        if (Setting.isIncognito() && history.getKey().equals(key)) history.delete();
        history.setVodName(item.getName());
        return history;
    }

    private History create(String key, Vod item) {
        History history = new History();
        history.setKey(key);
        history.setCid(VodConfig.getCid());
        history.setVodName(item.getName());
        history.findEpisode(item.getFlags());
        return history;
    }

    public void save(History history) {
        save(history, false);
    }

    public void save(History history, boolean exit) {
        if (history != null && history.canSave() && !Setting.isIncognito()) Task.execute(() -> {
            history.merge().save();
            if (exit) RefreshEvent.history();
        });
    }

    public void sync(History history) {
        if (history != null && !Setting.isIncognito()) Task.execute(history::save);
    }

    public void updateEpisode(History history, Flag flag, Episode episode) {
        if (history == null || flag == null || episode == null) return;
        history.setPosition(episode.matchesName(history.getEpisode()) ? history.getPosition() : C.TIME_UNSET);
        history.setVodFlag(flag.getFlag());
        history.setVodRemarks(episode.getName());
        history.setEpisodeUrl(episode.getUrl());
    }

    public void updateTime(History history, long time, long position, long duration) {
        if (history == null || position < 0 || duration <= 0) return;
        synchronized (history) {
            history.setCreateTime(time);
            history.setPosition(position);
            history.setDuration(duration);
            if (history.canSave() && history.canSync()) sync(history);
        }
    }

    public long startPositionMs(History history) {
        if (history == null) return C.TIME_UNSET;
        // Treat TIME_UNSET / negative as 0 so replay-after-clear does not pass UNSET through Math.max.
        long opening = history.getOpening() > 0 ? history.getOpening() : 0;
        long position = history.getPosition() > 0 ? history.getPosition() : 0;
        long start = Math.max(opening, position);
        return start > 0 ? start : C.TIME_UNSET;
    }
}
