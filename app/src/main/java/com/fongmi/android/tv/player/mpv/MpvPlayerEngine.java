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
    private java.util.Map<String, Object> builtConfig;
    private boolean live;

    public MpvPlayerEngine(int decode, Player.Listener listener) {
        this(decode, false, listener);
    }

    public MpvPlayerEngine(int decode, boolean live, Player.Listener listener) {
        this.decode = decode;
        this.live = live;
        this.listener = listener;
        this.builtConfig = MpvUtil.playbackConfig(decode, live);
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
    public void stop() {
        // A playlist/segment probe may have already queued its main-thread reply.
        // Invalidate it before stopping so it cannot load an item after the user exits.
        startGeneration++;
        player.stop();
    }

    @Override
    public Player rebuild() {
        startGeneration++;
        player.release();
        builtConfig = MpvUtil.playbackConfig(decode, live);
        player = MpvUtil.buildPlayer(decode, live, listener);
        effect = new MpvPlayerEffect(player);
        return player;
    }

    @Override
    public boolean refreshConfig() {
        return !MpvUtil.playbackConfig(decode, live).equals(builtConfig);
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
        start(spec, startPositionMs, true);
    }

    @Override
    public void start(PlaySpec spec, long startPositionMs, boolean playWhenReady) {
        this.spec = spec;
        long position = startPositionMs == C.TIME_UNSET ? 0 : Math.max(0, startPositionMs);
        int generation = ++startGeneration;
        // Establish this request's initial intent before asynchronous preparation. Stop
        // the old item first; a pause during the probe must not be overwritten later.
        player.stop();
        player.setPlayWhenReady(playWhenReady);
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
        boolean playWhenReady = player.getPlayWhenReady();
        effect.applyVideoEffect();
        // Must go through the effect, not setAudioFilter(""): clearing here left the configured
        // audio chain unapplied for the whole session. applyAudioEffect() also emits "" when the
        // effects are off, so it still resets stale state.
        effect.applyAudioEffect();
        player.setMediaItem(MediaItemFactory.from(spec), position);
        player.prepare();
        player.setPlayWhenReady(playWhenReady);
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
            case PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> retryHls(e);
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                 PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                 PlaybackException.ERROR_CODE_TIMEOUT -> ErrorAction.RETRY;
            default -> ErrorAction.FATAL;
        };
    }

    private ErrorAction retryHls(PlaybackException e) {
        if (spec == null || MimeTypes.APPLICATION_M3U8.equals(spec.getFormat()) || !shouldHintHls(e)) return ErrorAction.FATAL;
        spec.setFormat(MimeTypes.APPLICATION_M3U8);
        startInternal(player.getCurrentPosition());
        return ErrorAction.RECOVERED;
    }

    /**
     * One HLS format hint when the URL or the error already says HLS.
     * A live address with no container suffix gets the same hint. VOD without those signs does not.
     */
    private boolean shouldHintHls(PlaybackException e) {
        String url = spec.getUrl() == null ? "" : spec.getUrl().toLowerCase(java.util.Locale.ROOT);
        int query = url.indexOf('?');
        String path = query >= 0 ? url.substring(0, query) : url;
        if (endsWithContainer(path)) return false;
        String format = spec.getFormat();
        if (format != null && !format.isEmpty()) {
            String lower = format.toLowerCase(java.util.Locale.ROOT);
            return lower.contains("mpegurl") || lower.contains("m3u8") || lower.contains("hls");
        }
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
        int slash = path.lastIndexOf('/');
        String leaf = slash >= 0 ? path.substring(slash + 1) : path;
        if (leaf.contains(".m3u8") || message.contains("m3u8") || message.contains("hls")) return true;
        return live && !leaf.contains(".");
    }

    private static boolean endsWithContainer(String path) {
        return path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".webm") || path.endsWith(".flv")
                || path.endsWith(".mpd") || path.endsWith(".avi") || path.endsWith(".mov") || path.endsWith(".ts");
    }
}
