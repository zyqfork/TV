package com.fongmi.android.tv.player.mpv;

import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.mpvplayer.MpvPlayer;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.media.MediaItemFactory;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.utils.Task;

import java.util.concurrent.TimeUnit;

public class MpvPlayerEngine implements PlayerEngine {

    private final MpvErrorMsgProvider provider;
    private final Player.Listener listener;
    private MpvPlayer player;
    private boolean live;
    private int decode;
    private volatile int startGeneration;

    public MpvPlayerEngine(int decode, Player.Listener listener) {
        this(decode, false, listener);
    }

    public MpvPlayerEngine(int decode, boolean live, Player.Listener listener) {
        this.decode = decode;
        this.live = live;
        this.listener = listener;
        this.player = MpvUtil.buildPlayer(decode, live, listener);
        this.provider = new MpvErrorMsgProvider();
    }

    public static boolean isAvailable() {
        return MpvUtil.isAvailable();
    }

    @Override
    public Type getType() {
        return Type.MPV;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public void release() {
        startGeneration++;
        player.release();
        player = null;
    }

    @Override
    public Player rebuild() {
        startGeneration++;
        player.release();
        return player = MpvUtil.buildPlayer(decode, live, listener);
    }

    public void setLive(boolean live) {
        this.live = live;
    }

    @Override
    public void setSubtitleStyle() {
        MpvUtil.setSubtitleStyle(player);
    }

    @Override
    public boolean addSubtitle(Sub sub) {
        if (sub == null || player.getCurrentMediaItem() == null) return false;
        if (player.getPlaybackState() == Player.STATE_IDLE || player.getPlaybackState() == Player.STATE_ENDED) return false;
        player.addSubtitle(MediaItemFactory.buildSubConfig(sub));
        return true;
    }

    @Override
    public boolean setDecode(int decode) {
        if (this.decode == decode) return false;
        boolean rebuild = this.decode == HARD_PERFORMANCE || decode == HARD_PERFORMANCE;
        this.decode = decode;
        if (rebuild) return true;
        player.setDecode(decode);
        return false;
    }

    @Override
    public void start(PlaySpec spec, long startPositionMs) {
        long position = startPositionMs == C.TIME_UNSET ? 0 : Math.max(0, startPositionMs);
        int gen = ++startGeneration;
        Task.submit(() -> {
            PlaySpec play = MpvHlsPngTs.prepare(spec);
            App.post(() -> {
                if (gen != startGeneration) return;
                player.setMediaItem(MediaItemFactory.from(play), position);
                player.prepare();
                player.play();
            });
        });
    }

    @Override
    public boolean isLive() {
        if (player == null) return false;
        long duration = player.getDuration();
        if (duration == C.TIME_UNSET) return false;
        return duration < TimeUnit.MINUTES.toMillis(1);
    }

    @Override
    public boolean isVod() {
        if (player == null) return false;
        long duration = player.getDuration();
        if (duration == C.TIME_UNSET) return false;
        return duration >= TimeUnit.MINUTES.toMillis(1);
    }

    @Override
    public String getErrorMessage(PlaybackException e) {
        return provider.get(e);
    }

    @Override
    public ErrorAction handleError(PlaybackException e) {
        return switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                 PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                 PlaybackException.ERROR_CODE_DECODING_FAILED -> ErrorAction.DECODE;
            case PlaybackException.ERROR_CODE_TIMEOUT,
                 PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                 PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                 PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                 PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> ErrorAction.RETRY;
            default -> ErrorAction.FATAL;
        };
    }
}
