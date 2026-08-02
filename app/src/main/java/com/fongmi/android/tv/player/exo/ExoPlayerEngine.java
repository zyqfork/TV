package com.fongmi.android.tv.player.exo;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.media.MediaItemFactory;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.utils.Notify;

import java.util.concurrent.TimeUnit;

public class ExoPlayerEngine implements PlayerEngine {

    private final ErrorMsgProvider provider;
    private final Player.Listener listener;
    private final PreCache preCache;
    private boolean live;
    private boolean audioPassThrough;
    private boolean audioPassThroughFallbackUsed;
    private ExoPlayer player;
    private PlaySpec spec;
    private int decode;
    private final Runnable preCacheRunnable = this::startPreCache;

    public ExoPlayerEngine(int decode, Player.Listener listener) {
        this(decode, false, listener);
    }

    public ExoPlayerEngine(int decode, boolean live, Player.Listener listener) {
        decode = decode == SOFT ? SOFT : HARD;
        this.live = live;
        this.audioPassThrough = com.fongmi.android.tv.setting.PlayerSetting.isAudioPassThrough();
        this.player = ExoUtil.buildPlayer(decode, live, audioPassThrough, listener);
        this.provider = new ErrorMsgProvider();
        this.preCache = new PreCache();
        this.listener = listener;
        this.decode = decode;
    }

    @Override
    public Type getType() {
        return Type.EXO;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public void release() {
        App.removeCallbacks(preCacheRunnable);
        preCache.release();
        player.removeListener(listener);
        player.release();
    }

    @Override
    public Player rebuild() {
        App.removeCallbacks(preCacheRunnable);
        preCache.stop();
        player.removeListener(listener);
        player.release();
        return player = ExoUtil.buildPlayer(decode, live, audioPassThrough, listener);
    }

    @Override
    public boolean setDecode(int decode) {
        if (this.decode == decode) return false;
        this.decode = decode;
        return true;
    }

    public void setLive(boolean live) {
        this.live = live;
    }

    @Override
    public void start(PlaySpec spec, long startPositionMs) {
        this.spec = spec;
        startInternal(startPositionMs);
    }

    @Override
    public void stop() {
        App.removeCallbacks(preCacheRunnable);
        preCache.stop();
        player.stop();
    }

    @Override
    public boolean isLive() {
        long duration = player.getDuration();
        if (duration == C.TIME_UNSET) return player.isCurrentMediaItemLive();
        // Exclusive partition at 60s: <1min → live heuristic, >=1min → vod.
        return duration < TimeUnit.MINUTES.toMillis(1) || player.isCurrentMediaItemLive();
    }

    @Override
    public boolean isVod() {
        long duration = player.getDuration();
        if (duration == C.TIME_UNSET) return !player.isCurrentMediaItemLive();
        return duration >= TimeUnit.MINUTES.toMillis(1) && !player.isCurrentMediaItemLive();
    }

    @Override
    public String getErrorMessage(PlaybackException e) {
        return provider.get(e);
    }

    @Override
    public ErrorAction handleError(PlaybackException e) {
        return switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> seekToDefaultPosition();
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                 PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                 PlaybackException.ERROR_CODE_DECODING_FAILED -> ErrorAction.DECODE;
            case PlaybackException.ERROR_CODE_TIMEOUT,
                 PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                 PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                 PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> ErrorAction.RETRY;
            case PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                 PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                 PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                 PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                 PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> retryFormat(e.errorCode);
            case PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                 PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> fallbackAudioPassThrough();
            default -> ErrorAction.FATAL;
        };
    }

    private void startInternal(long position) {
        MediaItem item = MediaItemFactory.from(spec, decode);
        App.removeCallbacks(preCacheRunnable);
        preCache.stop();
        player.setMediaItem(item, position);
        player.prepare();
        player.play();
        // Foreground startup gets the connection and initial buffer first. Pre-cache remains
        // opportunistic and is cancelled on every switch, stop, rebuild or release.
        if (!live) App.post(preCacheRunnable, 1_500);
    }

    private void startPreCache() {
        if (!live && player.getPlaybackState() != Player.STATE_IDLE && player.getCurrentMediaItem() != null) {
            preCache.start(player.getCurrentMediaItem());
        }
    }

    private ErrorAction seekToDefaultPosition() {
        player.seekToDefaultPosition();
        player.prepare();
        return ErrorAction.RECOVERED;
    }

    private ErrorAction retryFormat(int errorCode) {
        spec.setFormat(ExoUtil.getMimeType(errorCode));
        startInternal(player.getCurrentPosition());
        return ErrorAction.RECOVERED;
    }

    private ErrorAction fallbackAudioPassThrough() {
        if (!audioPassThrough || audioPassThroughFallbackUsed || spec == null) return ErrorAction.FATAL;
        audioPassThroughFallbackUsed = true;
        audioPassThrough = false;
        Notify.show(R.string.player_audio_passthrough_fallback);
        long position = Math.max(0, player.getCurrentPosition());
        preCache.stop();
        player.release();
        player = ExoUtil.buildPlayer(decode, live, audioPassThrough, listener);
        startInternal(position);
        return ErrorAction.RECOVERED;
    }
}
