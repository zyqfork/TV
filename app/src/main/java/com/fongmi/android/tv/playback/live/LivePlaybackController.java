package com.fongmi.android.tv.playback.live;

import android.text.TextUtils;

import androidx.media3.common.C;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.EpgData;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.player.LineQualityStore;

public class LivePlaybackController {

    private static final long SWITCH_DEBOUNCE_MS = 300L;

    private final LiveNavigationPolicy navigationPolicy;
    private final LiveFallbackPolicy fallbackPolicy;
    private final LivePlaybackState state;
    private final LivePlaybackHost host;
    private final Runnable channelRefreshRunnable;
    private final Runnable lineRefreshRunnable;
    private long pendingStartPositionMs;
    private boolean pendingKeepLine;
    private boolean channelRefreshScheduled;

    public LivePlaybackController(LivePlaybackHost host, LivePlaybackState state) {
        this.state = state;
        this.host = host;
        this.navigationPolicy = new LiveNavigationPolicy(this, state, host);
        this.fallbackPolicy = new LiveFallbackPolicy(this, state, host);
        this.channelRefreshRunnable = () -> {
            channelRefreshScheduled = false;
            refreshNow();
        };
        this.lineRefreshRunnable = this::refreshNow;
        this.pendingStartPositionMs = C.TIME_UNSET;
    }

    public void reset() {
        App.removeCallbacks(channelRefreshRunnable);
        App.removeCallbacks(lineRefreshRunnable);
        channelRefreshScheduled = false;
        state.reset();
    }

    public void selectGroup(Group group) {
        state.setGroup(group);
        host.renderGroupSelection(group);
        host.renderGroupChannels(group);
    }

    public void selectChannel(Channel channel) {
        if (channel == null) return;
        state.setChannel(channel);
        host.renderChannelSelection(channel);
        scheduleRefresh(C.TIME_UNSET, false);
    }

    public boolean selectEpg(EpgData data) {
        return selectEpg(data, C.TIME_UNSET);
    }

    public boolean selectEpg(EpgData data, long startPositionMs) {
        Channel channel = state.getChannel();
        if (channel == null || data == null) return false;
        if (data.isSelected()) {
            requestCatchup(data, startPositionMs);
            return true;
        } else if (channel.hasCatchup() || channel.isRtsp()) {
            host.showCatchupReady(channel, data);
            host.renderEpgSelection(channel, data);
            requestCatchup(data, C.TIME_UNSET);
            return true;
        }
        return false;
    }

    public void refresh() {
        scheduleRefresh(C.TIME_UNSET, false);
    }

    public void refresh(long startPositionMs) {
        scheduleRefresh(startPositionMs, false);
    }

    private void scheduleRefresh(long startPositionMs, boolean keepLine) {
        pendingStartPositionMs = startPositionMs;
        if (!keepLine) {
            // Channel change: cancel pending line-only refresh and pick best line.
            App.removeCallbacks(lineRefreshRunnable);
            pendingKeepLine = false;
            channelRefreshScheduled = true;
            App.post(channelRefreshRunnable, SWITCH_DEBOUNCE_MS);
            return;
        }
        // Line change: if channel refresh already queued, keep the user's line on that channel.
        pendingKeepLine = true;
        if (channelRefreshScheduled) return;
        App.removeCallbacks(lineRefreshRunnable);
        App.post(lineRefreshRunnable, SWITCH_DEBOUNCE_MS);
    }

    private void refreshNow() {
        Channel channel = state.getChannel();
        if (channel == null) return;
        if (!pendingKeepLine && !channel.isOnly()) {
            int bestIndex = LineQualityStore.bestIndex(channel.getUrls(), channel.getIndex());
            channel.setIndex(bestIndex);
            host.renderLineSelection(channel, false);
        }
        LiveConfig.get().setKeep(channel);
        LivePlayRequest request = LivePlayRequest.live(channel, pendingStartPositionMs);
        state.setPendingRequest(request);
        host.requestUrl(request);
        host.showProgress();
        host.stopPlaybackForRefresh();
        pendingStartPositionMs = C.TIME_UNSET;
        pendingKeepLine = false;
    }

    public void onUrlResult(Result result) {
        LivePlayRequest request = state.getPendingRequest();
        if (request == null) {
            if (result.hasMsg()) host.resetPlaybackForError(result.getMsg());
            return;
        }
        if (!request.matches(state.getChannel())) return;
        String realUrl = result.getRealUrl();
        if (TextUtils.isEmpty(realUrl)) {
            state.clearPendingRequest();
            playbackError(result.getMsg());
            return;
        }
        long position = result.hasPosition() ? result.getPosition() : request.getPosition();
        state.setResult(result);
        state.clearPendingRequest();
        host.startPlayback(result, position, request.getChannel());
    }

    public void reclaim(long position) {
        Result result = state.getResult();
        Channel channel = state.getChannel();
        if (result == null || channel == null) return;
        host.startPlayback(result, position, channel);
    }

    public void playbackError(String msg) {
        host.resetPlaybackForError(msg);
        fallbackPolicy.playbackError();
    }

    public void playbackEnded() {
        fallbackPolicy.playbackEnded();
    }

    public void prevChannel() {
        navigationPolicy.moveChannel(-1);
    }

    public void nextChannel() {
        navigationPolicy.moveChannel(1);
    }

    public void prevLine() {
        switchLine(false, true);
    }

    public void nextLine(boolean show) {
        switchLine(true, show);
    }

    public void nextBestLine(boolean show) {
        Channel channel = state.getChannel();
        if (channel == null || channel.isOnly()) return;
        int current = channel.getIndex();
        int best = LineQualityStore.bestIndex(channel.getUrls(), current);
        channel.setIndex(best);
        if (channel.getIndex() == current) channel.switchLine(true);
        host.renderLineSelection(channel, show);
        scheduleRefresh(C.TIME_UNSET, true);
    }

    private void switchLine(boolean next, boolean show) {
        Channel channel = state.getChannel();
        if (channel == null || channel.isOnly()) return;
        channel.switchLine(next);
        host.renderLineSelection(channel, show);
        scheduleRefresh(C.TIME_UNSET, true);
    }

    private void requestCatchup(EpgData data, long startPositionMs) {
        App.removeCallbacks(channelRefreshRunnable);
        App.removeCallbacks(lineRefreshRunnable);
        channelRefreshScheduled = false;
        Channel channel = state.getChannel();
        if (channel == null) return;
        LivePlayRequest request = LivePlayRequest.catchup(channel, data, startPositionMs);
        state.setPendingRequest(request);
        host.requestCatchupUrl(request);
        host.stopPlaybackForRefresh();
    }
}
