package com.fongmi.android.tv.player.exo;

import androidx.media3.common.MediaItem;
import androidx.annotation.NonNull;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.engine.PlaybackRecoveryPolicy;
import com.fongmi.android.tv.player.util.HlsPngTsPrepare;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.media.MediaItemFactory;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.setting.AudioSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.Task;


/** Owns one ExoPlayer instance and all resources whose lifecycle must match that instance. */
final class ExoPlayerSession {

    private final ExoVolumeGain volumeGain;
    private final ExoPlayerEffect effect;
    private final PreCache preCache;
    private final ExoPlayer player;
    private final int decode;
    private final boolean live;

    private PlaySpec spec;
    private String originalUrl;
    private int attempts;
    private int startGeneration;
    private boolean pngProbeAttempted;
    private boolean released;
    /**
     * Set when Media3 opened a software video decoder even though hardware was requested, i.e.
     * its own renderer fallback fired. Consumed once per item by the UI notice.
     */
    private volatile boolean softwareFallbackSeen;
    private boolean softwareFallbackReported;
    private volatile String activeVideoDecoder;

    ExoPlayerSession(int decode, Player.Listener listener) {
        this(decode, false, listener);
    }

    ExoPlayerSession(int decode, boolean live, Player.Listener listener) {
        this.decode = decode == PlayerEngine.SOFT ? PlayerEngine.SOFT : PlayerEngine.HARD;
        this.live = live;
        if (AudioSetting.hasEffect(8)) PlayerSetting.putAudioPassThrough(false);
        this.effect = new ExoPlayerEffect(!PlayerSetting.isAudioPassThrough());
        this.player = ExoUtil.buildPlayer(this.decode, listener, effect.getAudioProcessor(), live, this::onVideoDecoderInitialized);
        this.effect.setPlayer(player);
        this.player.addListener(effectListener);
        this.preCache = new PreCache();
        this.volumeGain = new ExoVolumeGain();
        this.volumeGain.attach(player);
    }

    ExoPlayer player() {
        return player;
    }

    /**
     * Consumes the "hardware decoder unavailable, software in use" notice for this item.
     * Returns true at most once per item so the UI does not repeat itself.
     */
    boolean consumeSoftwareFallbackNotice() {
        if (!softwareFallbackSeen || softwareFallbackReported) return false;
        softwareFallbackReported = true;
        return true;
    }

    /**
     * Records the video decoder Media3 actually opened. A software decoder name while hardware was
     * requested means the renderer fell back, which is otherwise invisible: playback simply works.
     * Decoder names follow MediaCodec: vendor hardware is `c2.<vendor>.*`/`OMX.<vendor>.*`, while
     * AOSP software decoders are `c2.android.*`/`OMX.google.*`.
     */
    void onVideoDecoderInitialized(String decoderName) {
        activeVideoDecoder = decoderName;
        if (decode == PlayerEngine.SOFT || decoderName == null || decoderName.isEmpty()) return;
        softwareFallbackSeen = isSoftwareDecoder(decoderName);
    }

    private static boolean isSoftwareDecoder(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("c2.android.") || lower.startsWith("omx.google.")
                || lower.contains(".sw.") || lower.contains("software");
    }

    ExoPlayerEffect effect() {
        return effect;
    }

    void setVolumeGain(float gain) {
        volumeGain.setGain(gain);
    }

    void start(PlaySpec spec, long startPositionMs) {
        start(spec, startPositionMs, true);
    }

    void start(PlaySpec spec, long startPositionMs, boolean playWhenReady) {
        // Keep the retry budget when the same URL is restarted after a failure;
        // only a new item (or explicit reset) may clear attempts. Otherwise a
        // dead endpoint loops forever through start() -> attempts=0.
        String url = spec == null ? null : spec.getUrl();
        if (this.spec == null || !java.util.Objects.equals(originalUrl, url)) {
            attempts = 0;
            pngProbeAttempted = false;
        }
        originalUrl = url;
        this.spec = spec;
        startGeneration++;
        // Normal EXO HLS must not fetch the playlist and first segment twice. The rare
        // PNG-prefixed TS workaround is tried only after a parsing failure.
        startInternal(startPositionMs, playWhenReady);
    }

    void preload(PlaySpec spec, long startPositionMs) {
        if (spec != null) preCache.preload(MediaItemFactory.from(spec, decode), startPositionMs);
    }

    void clearPreload() {
        preCache.clearPreload();
    }

    void stop() {
        startGeneration++;
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
        startGeneration++;
        preCache.release();
        volumeGain.release();
        player.removeListener(effectListener);
        effect.release();
        player.release();
        spec = null;
        originalUrl = null;
    }

    private void startInternal(long positionMs, boolean playWhenReady) {
        if (released || spec == null) return;
        MediaItem item = MediaItemFactory.from(spec, decode);
        player.setMediaItem(item, positionMs);
        preCache.start(player, item);
        player.setPlayWhenReady(playWhenReady);
        player.prepare();
    }

    private PlayerEngine.ErrorAction seekToDefaultPosition() {
        if (attempts++ >= PlaybackRecoveryPolicy.MAX_ATTEMPTS) return PlayerEngine.ErrorAction.FATAL;
        player.seekToDefaultPosition();
        player.prepare();
        return PlayerEngine.ErrorAction.RECOVERED;
    }

    private PlayerEngine.ErrorAction retryFormat(int errorCode) {
        if (spec == null) return PlayerEngine.ErrorAction.FATAL;
        String url = spec.getUrl();
        boolean parsing = errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                || errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                || errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
                || errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED;
        if (!parsing || pngProbeAttempted || url == null
                || !url.toLowerCase(java.util.Locale.ROOT).contains(".m3u8")) return requestRetry();
        pngProbeAttempted = true;
        attempts++;
        long position = Math.max(0, player.getCurrentPosition());
        int generation = ++startGeneration;
        PlaySpec failed = spec;
        player.stop();
        // Optional network probe is off the application thread and never publishes into a
        // newer item. If the playlist is normal, retry unchanged rather than guessing MIME.
        Task.submit(() -> {
            PlaySpec prepared = HlsPngTsPrepare.prepare(failed);
            App.post(() -> {
                if (released || generation != startGeneration) return;
                spec = prepared;
                startInternal(position, player.getPlayWhenReady());
            });
        });
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
