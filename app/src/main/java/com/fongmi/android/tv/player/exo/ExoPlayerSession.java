package com.fongmi.android.tv.player.exo;

import androidx.media3.common.MediaItem;
import androidx.annotation.NonNull;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;

import com.fongmi.android.tv.player.engine.PlaybackRecoveryPolicy;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.media.MediaItemFactory;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.setting.AudioSetting;
import com.fongmi.android.tv.setting.PlayerSetting;


/** Owns one ExoPlayer instance and all resources whose lifecycle must match that instance. */
final class ExoPlayerSession {

    private final ExoVolumeGain volumeGain;
    private final ExoPlayerEffect effect;
    private final PreCache preCache;
    private final ExoPlayer player;
    private final int decode;
    private final boolean live;

    private PlaySpec spec;
    private int attempts;
    private boolean released;

    ExoPlayerSession(int decode, Player.Listener listener) {
        this(decode, false, listener);
    }

    ExoPlayerSession(int decode, boolean live, Player.Listener listener) {
        this.decode = decode == PlayerEngine.SOFT ? PlayerEngine.SOFT : PlayerEngine.HARD;
        this.live = live;
        if (AudioSetting.hasEffect(8)) PlayerSetting.putAudioPassThrough(false);
        this.effect = new ExoPlayerEffect(!PlayerSetting.isAudioPassThrough());
        this.player = ExoUtil.buildPlayer(this.decode, listener, effect.getAudioProcessor(), live);
        this.effect.setPlayer(player);
        this.player.addListener(effectListener);
        this.preCache = new PreCache();
        this.volumeGain = new ExoVolumeGain();
        this.volumeGain.attach(player);
    }

    ExoPlayer player() {
        return player;
    }

    ExoPlayerEffect effect() {
        return effect;
    }

    void setVolumeGain(float gain) {
        volumeGain.setGain(gain);
    }

    void start(PlaySpec spec, long startPositionMs) {
        // Keep the retry budget when the same URL is restarted after a failure;
        // only a new item (or explicit reset) may clear attempts. Otherwise a
        // dead endpoint loops forever through start() -> attempts=0.
        if (this.spec == null || spec == null
                || !java.util.Objects.equals(this.spec.getUrl(), spec.getUrl())) {
            attempts = 0;
        }
        this.spec = spec;
        startInternal(startPositionMs);
    }

    void preload(PlaySpec spec, long startPositionMs) {
        if (spec != null) preCache.preload(MediaItemFactory.from(spec, decode), startPositionMs);
    }

    void clearPreload() {
        preCache.clearPreload();
    }

    void stop() {
        preCache.stop();
        player.stop();
    }

    /** Called when playback reaches READY, i.e. the current attempt recovered. */
    void resetErrorBudget() {
        attempts = 0;
    }

    boolean isLive() {
        if (player.isCurrentMediaItemLive()) return true;
        return player.getDuration() == androidx.media3.common.C.TIME_UNSET && live;
    }

    boolean isVod() {
        return !isLive();
    }

    PlayerEngine.ErrorAction handleError(PlaybackException error) {
        PlaybackRecoveryPolicy.Action action = PlaybackRecoveryPolicy.decide(error.errorCode, attempts);
        return switch (action) {
            case SEEK_DEFAULT -> seekToDefaultPosition();
            case SWITCH_DECODE -> PlayerEngine.ErrorAction.DECODE;
            case RETRY_FORMAT -> retryFormat(error.errorCode);
            case RETRY_TRANSIENT -> requestRetry();
            case FATAL -> PlayerEngine.ErrorAction.FATAL;
        };
    }

    void release() {
        if (released) return;
        released = true;
        preCache.release();
        volumeGain.release();
        player.removeListener(effectListener);
        effect.release();
        player.release();
        spec = null;
    }

    private void startInternal(long positionMs) {
        if (released || spec == null) return;
        MediaItem item = MediaItemFactory.from(spec, decode);
        player.setMediaItem(item, positionMs);
        preCache.start(player, item);
        player.prepare();
        player.play();
    }

    private PlayerEngine.ErrorAction seekToDefaultPosition() {
        if (attempts++ >= PlaybackRecoveryPolicy.MAX_ATTEMPTS) return PlayerEngine.ErrorAction.FATAL;
        player.seekToDefaultPosition();
        player.prepare();
        return PlayerEngine.ErrorAction.RECOVERED;
    }

    private PlayerEngine.ErrorAction retryFormat(int errorCode) {
        if (spec == null) return PlayerEngine.ErrorAction.FATAL;
        attempts++;
        spec.setFormat(ExoUtil.getMimeType(errorCode));
        startInternal(player.getCurrentPosition());
        return PlayerEngine.ErrorAction.RECOVERED;
    }

    /**
     * Reports a transient transport failure upward instead of retrying here. PlayerManager owns the
     * schedule (delay, toast, budget, fatal), and retrying in both places restarted the stream
     * twice for a single failure.
     */
    private PlayerEngine.ErrorAction requestRetry() {
        attempts++;
        return PlayerEngine.ErrorAction.RETRY;
    }

    private final Player.Listener effectListener = new Player.Listener() {
        @Override
        public void onTracksChanged(@NonNull Tracks tracks) {
            effect.applyAudioEffect();
            effect.applyVideoEffect();
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_READY) {
                effect.applyAudioEffect();
                effect.applyVideoEffect();
            }
        }
    };
}
