package com.fongmi.android.tv.player.mpv;

import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.mpvplayer.MpvPlayer;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.player.effect.PlayerEffect;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.media.MediaItemFactory;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.player.util.HlsPngTsPrepare;
import com.fongmi.android.tv.utils.Task;

public class MpvPlayerEngine implements PlayerEngine {

    private final MpvErrorMsgProvider provider;
    private final Player.Listener listener;
    private MpvPlayer player;
    private MpvPlayerEffect effect;
    private PlaySpec spec;
    private int decode;
    private int startGeneration;
    private boolean live;

    public MpvPlayerEngine(int decode, Player.Listener listener) {
        this(decode, false, listener);
    }

    public MpvPlayerEngine(int decode, boolean live, Player.Listener listener) {
        this.decode = decode;
        this.live = live;
        this.listener = listener;
        this.player = MpvUtil.buildPlayer(decode, live, listener);
        this.effect = new MpvPlayerEffect(player);
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
    public PlayerEffect getEffect() {
        return effect;
    }

    @Override
    public void release() {
        startGeneration++;
        player.release();
    }

    @Override
    public Player rebuild() {
        startGeneration++;
        player.release();
        player = MpvUtil.buildPlayer(decode, live, listener);
        effect = new MpvPlayerEffect(player);
        return player;
    }

    @Override
    public void setSubtitleStyle() {
        MpvUtil.setSubtitleStyle(player);
    }

    @Override
    public void setVolumeGain(float gain) {
        // Must not go through Player.setVolume: Media3 caps volume at 1 and rejects a 1.5x gain
        // with an IllegalArgumentException, which previously crashed PlaybackService on startup
        // whenever the MPV engine was selected with a persisted gain.
        player.setVolumeGain(Math.clamp(gain, 0f, 2f));
    }

    @Override
    public boolean supportsEmbeddedSecondarySubtitle() {
        return true;
    }

    @Override
    public void setEmbeddedSecondarySubtitle(@Nullable TrackSelectionOverride selection) {
        player.setSecondaryTextTrackSelectionOverride(selection);
    }

    @Override
    public void setEmbeddedSecondarySubtitleOffset(long offsetMs) {
        player.setSecondarySubtitleDelayMs(offsetMs);
    }

    @Override
    public boolean addSubtitle(Sub sub) {
        if (sub == null || sub.isEmpty() || player.getCurrentMediaItem() == null) return false;
        if (player.getPlaybackState() == Player.STATE_IDLE || player.getPlaybackState() == Player.STATE_ENDED) return false;
        player.addSubtitle(MediaItemFactory.buildSubConfig(sub));
        return true;
    }

    @Override
    public boolean setDecode(int decode) {
        this.decode = decode;
        // The user toggles only soft/hard. A live hard session may have an embedded VO;
        // always rebuild once rather than mutate its codec and then loadfile it again.
        return true;
    }

    @Override
    public void setLiveMode(boolean live) {
        this.live = live;
    }

    @Override
    public void start(PlaySpec spec, long startPositionMs) {
        this.spec = spec;
        long position = startPositionMs == C.TIME_UNSET ? 0 : Math.max(0, startPositionMs);
        int generation = ++startGeneration;
        Task.submit(() -> {
            PlaySpec prepared = HlsPngTsPrepare.prepare(spec);
            App.post(() -> {
                if (generation != startGeneration) return;
                this.spec = prepared;
                startInternal(position);
            });
        });
    }

    private void startInternal(long position) {
        effect.applyVideoEffect();
        // Must go through the effect, not setAudioFilter(""): clearing here left the configured
        // audio chain unapplied for the whole session. applyAudioEffect() also emits "" when the
        // effects are off, so it still resets stale state.
        effect.applyAudioEffect();
        player.setMediaItem(MediaItemFactory.from(spec), position);
        player.prepare();
        player.play();
    }

    @Override
    public boolean isLive() {
        return live;
    }

    @Override
    public boolean isVod() {
        return !live;
    }

    @Override
    public String getErrorMessage(PlaybackException e) {
        return provider.get(e);
    }

    @Override
    public ErrorAction handleError(PlaybackException e) {
        return switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED -> ErrorAction.DECODE;
            case PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> retryHls();
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                 PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                 PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                 PlaybackException.ERROR_CODE_TIMEOUT -> ErrorAction.RETRY;
            default -> ErrorAction.FATAL;
        };
    }

    private ErrorAction retryHls() {
        if (spec == null || MimeTypes.APPLICATION_M3U8.equals(spec.getFormat())) return ErrorAction.FATAL;
        spec.setFormat(MimeTypes.APPLICATION_M3U8);
        startInternal(player.getCurrentPosition());
        return ErrorAction.RECOVERED;
    }
}
