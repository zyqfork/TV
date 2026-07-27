package com.fongmi.android.tv.player;

import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.MediaChapter;
import androidx.media3.common.MediaEdition;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.ui.danmaku.DanmakuConfig;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.impl.ParseCallback;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.engine.PlayerEngineFactory;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.player.parse.ParseJob;
import com.fongmi.android.tv.player.track.TrackUtil;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.google.common.net.HttpHeaders;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PlayerManager implements ParseCallback {

    private final Runnable runnable;
    private final Runnable firstFrameRunnable;
    private final Callback callback;
    private PlayerEngine engine;
    private VideoSize videoSize;
    private ParseJob parseJob;
    private PlaySpec spec;
    private Player player;

    private DanmakuConfig danmakuConfig;
    private long pendingStartPositionMs;
    private boolean danmakuEnabled;
    private boolean initTrack;
    private boolean mpvFallbackUsed;
    private boolean subtitleDecodeHintShown;
    private boolean openReported;
    private long playStartRealtimeMs;
    private int retry;
    private int sourceRetry;
    private int decode;
    private int preferredEngine;
    private boolean liveMode;

    public PlayerManager(Callback callback) {
        this.callback = callback;
        this.runnable = this::onPlayTimeout;
        this.firstFrameRunnable = this::onFirstFrameTimeout;
        this.preferredEngine = PlayerSetting.getVodEngine();
        this.decode = PlayerSetting.getDecode(false, preferredEngine);
        this.engine = PlayerEngineFactory.create(decode, preferredEngine, false, listener);
        this.player = engine.getPlayer();
        this.pendingStartPositionMs = C.TIME_UNSET;
        this.danmakuConfig = DanmakuSetting.getConfig();
        this.danmakuEnabled = DanmakuSetting.isShow();
    }

    public static MediaMetadata buildMetadata(String title, String artist, String artUri) {
        Uri artwork = TextUtils.isEmpty(artUri) ? null : Uri.parse(artUri);
        return new MediaMetadata.Builder().setTitle(title).setArtist(artist).setArtworkUri(artwork).build();
    }

    public void release() {
        App.removeCallbacks(runnable, firstFrameRunnable);
        if (player != null) player.removeListener(listener);
        if (engine != null) engine.release();
        engine = null;
        player = null;
    }

    public Player getPlayer() {
        return player;
    }

    public Tracks getCurrentTracks() {
        return player.getCurrentTracks();
    }

    public List<MediaChapter> getCurrentMediaChapters() {
        return player.getCurrentMediaChapters();
    }

    public List<MediaEdition> getCurrentMediaEditions() {
        return player.getCurrentMediaEditions();
    }

    public MediaItem getCurrentMediaItem() {
        return player.getCurrentMediaItem();
    }

    public int getPlaybackState() {
        return player.getPlaybackState();
    }

    public boolean isPlaying() {
        return player.isPlaying();
    }

    public boolean isReleased() {
        return player == null;
    }

    public String getUrl() {
        return spec != null ? spec.getUrl() : null;
    }

    public String getKey() {
        return spec != null ? spec.getKey() : null;
    }

    public List<Danmaku> getDanmakus() {
        return spec != null ? spec.getDanmakus() : null;
    }

    private void setDanmakus(List<Danmaku> items) {
        if (spec != null) spec.setDanmaku(getSelectedDanmaku(items));
        notifyDanmakuSourceChanged();
    }

    private void notifyDanmakuSourceChanged() {
        callback.onDanmakuSourceChanged(getSelectedDanmakuUri());
    }

    public MediaMetadata getMetadata() {
        return spec != null ? spec.getMetadata() : null;
    }

    public void setMetadata(MediaMetadata data) {
        if (spec != null) spec.setMetadata(data);
        MediaItem current = player.getCurrentMediaItem();
        if (current != null) player.replaceMediaItem(player.getCurrentMediaItemIndex(), current.buildUpon().setMediaMetadata(data).build());
    }

    public Map<String, String> getHeaders() {
        return spec == null || spec.getHeaders() == null ? new HashMap<>() : spec.getHeaders();
    }

    public float getSpeed() {
        return player.getPlaybackParameters().speed;
    }

    public boolean isEmpty() {
        return spec == null || TextUtils.isEmpty(spec.getUrl());
    }

    public boolean isPortrait() {
        return getVideoHeight() > getVideoWidth();
    }

    public boolean isLandscape() {
        return getVideoWidth() > getVideoHeight();
    }

    public boolean isLive() {
        return engine.isLive();
    }

    public boolean isVod() {
        return engine.isVod();
    }

    public boolean haveTrack(int type) {
        return TrackUtil.count(getCurrentTracks(), type) > 0;
    }

    public boolean haveEdition() {
        return !getCurrentMediaEditions().isEmpty();
    }

    public boolean haveChapter() {
        return !getCurrentMediaChapters().isEmpty();
    }

    public boolean haveDanmaku() {
        return !getSelectedDanmaku().isEmpty();
    }

    public boolean canSetOpening(long position, long duration) {
        return position > 0 && duration > 0 && position <= Constant.getOpEdLimit(duration);
    }

    public boolean canSetEnding(long position, long duration) {
        return position > 0 && duration > 0 && duration - position <= Constant.getOpEdLimit(duration);
    }

    public int getVideoWidth() {
        return videoSize == null ? 0 : videoSize.width;
    }

    public int getVideoHeight() {
        return videoSize == null ? 0 : videoSize.height;
    }

    public long getPosition() {
        return player.getCurrentPosition();
    }

    public String getSizeText() {
        return (getVideoWidth() == 0 && getVideoHeight() == 0) ? "" : getVideoWidth() + " x " + getVideoHeight();
    }

    public String getSpeedText() {
        return String.format(Locale.getDefault(), "%.2f", getSpeed());
    }

    public String getDecodeText() {
        return ResUtil.getStringArray(R.array.select_decode)[decode];
    }

    public int getEngine() {
        return engine.getType() == PlayerEngine.Type.MPV ? PlayerSetting.ENGINE_MPV : PlayerSetting.ENGINE_EXO;
    }

    public void setEngine(int targetEngine) {
        setEngine(targetEngine, true);
    }

    /**
     * @param persist when false, apply for current playback only (config playerType override).
     */
    public void setEngine(int targetEngine, boolean persist) {
        targetEngine = Math.clamp(targetEngine, PlayerSetting.ENGINE_EXO, PlayerSetting.ENGINE_MPV);
        if (preferredEngine == targetEngine) return;
        preferredEngine = targetEngine;
        if (persist) {
            if (liveMode) PlayerSetting.putLiveEngine(targetEngine);
            else PlayerSetting.putVodEngine(targetEngine);
        }
        decode = PlayerSetting.getDecode(liveMode, targetEngine);
        callback.onDecodeChanged();
        if (isEmpty()) return;
        startCurrent();
    }

    public void setLiveMode(boolean liveMode) {
        boolean modeChanged = this.liveMode != liveMode;
        this.liveMode = liveMode;
        int target = liveMode ? PlayerSetting.getLiveEngine() : PlayerSetting.getVodEngine();
        int targetDecode = PlayerSetting.getDecode(liveMode, target);
        boolean engineChanged = preferredEngine != target;
        preferredEngine = target;
        if (engine instanceof com.fongmi.android.tv.player.mpv.MpvPlayerEngine mpv) {
            mpv.setLive(liveMode);
        }
        if (engine instanceof com.fongmi.android.tv.player.exo.ExoPlayerEngine exo) {
            exo.setLive(liveMode);
        }
        if (decode == targetDecode && !modeChanged) return;
        boolean needRebuild = decode != targetDecode || modeChanged;
        decode = targetDecode;
        if (engineChanged || engine == null) return;
        if (needRebuild && (engine.setDecode(decode) || modeChanged)) {
            setPlayer(engine.rebuild());
        }
    }

    public String getPositionTime(long delta) {
        return Util.timeMs(Math.clamp(getPosition() + delta, 0, Math.max(0, getDuration())));
    }

    public long getDuration() {
        return player.getDuration();
    }

    public String getDurationTime() {
        return Util.timeMs(Math.max(0, getDuration()));
    }

    public void setSub(Sub sub) {
        if (spec != null) spec.setSub(sub);
        ensureDecodeForSubs(spec);
        if (engine.addSubtitle(sub)) play();
        else startCurrent();
    }

    public void setFormat(String format) {
        if (spec != null) spec.setFormat(format);
        startCurrent();
    }

    public void selectChapter(MediaChapter chapter) {
        player.selectChapter(chapter);
    }

    public void selectEdition(MediaEdition edition) {
        player.selectEdition(edition);
    }

    public void setDanmakuConfig(DanmakuConfig config) {
        danmakuConfig = config;
        callback.onDanmakuConfigChanged(danmakuConfig);
    }

    public void setDanmakuEnabled(boolean enabled) {
        if (danmakuEnabled == enabled) return;
        danmakuEnabled = enabled;
        callback.onDanmakuEnabledChanged(danmakuEnabled);
    }

    public void sendDanmaku(String text) {
        callback.onDanmakuSent(text);
    }

    public String setSpeed(float speed) {
        if (!player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return getSpeedText();
        player.setPlaybackParameters(player.getPlaybackParameters().withSpeed(speed));
        return getSpeedText();
    }

    public String addSpeed() {
        float speed = getSpeed();
        float step = speed >= 2 ? 1f : 0.25f;
        return setSpeed(speed >= 5 ? 0.25f : Math.min(speed + step, 5.0f));
    }

    public String addSpeed(float value) {
        return setSpeed(Math.clamp(getSpeed() + value, 0.25f, 5.0f));
    }

    public String subSpeed(float value) {
        return setSpeed(Math.clamp(getSpeed() - value, 0.25f, 5.0f));
    }

    public String toggleSpeed() {
        return setSpeed(getSpeed() == 1 ? PlayerSetting.getSpeed() : 1);
    }

    public void setTrack(List<Track> tracks) {
        if (!tracks.isEmpty()) TrackUtil.setTrackSelection(player, tracks);
    }

    public void setSubtitleStyle() {
        if (engine != null) engine.setSubtitleStyle();
    }

    public void play() {
        player.play();
    }

    public void pause() {
        player.pause();
    }

    public void stop() {
        engine.stop();
        stopParse();
    }

    public void clearMediaItems() {
        player.clearMediaItems();
    }

    public boolean isRepeatOne() {
        return player.getRepeatMode() == Player.REPEAT_MODE_ONE;
    }

    public void setRepeatOne(boolean repeat) {
        player.setRepeatMode(repeat ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
    }

    public void replay(long positionMs) {
        if (positionMs == C.TIME_UNSET) player.seekToDefaultPosition();
        else player.seekTo(positionMs);
        player.play();
    }

    public void seekTo(long time) {
        player.seekTo(time);
    }

    public long getTextOffsetMs() {
        return player.isCommandAvailable(Player.COMMAND_GET_TEXT_OFFSET) ? player.getTextOffsetMs() : 0;
    }

    public void setTextOffsetMs(long offsetMs) {
        if (player.isCommandAvailable(Player.COMMAND_SET_TEXT_OFFSET)) player.setTextOffsetMs(offsetMs);
    }

    public long getAudioOffsetMs() {
        return player.isCommandAvailable(Player.COMMAND_GET_AUDIO_OFFSET) ? player.getAudioOffsetMs() : 0;
    }

    public void setAudioOffsetMs(long offsetMs) {
        if (player.isCommandAvailable(Player.COMMAND_SET_AUDIO_OFFSET)) player.setAudioOffsetMs(offsetMs);
    }

    public void reset() {
        App.removeCallbacks(runnable, firstFrameRunnable);
        retry = 0;
        sourceRetry = 0;
        mpvFallbackUsed = false;
        openReported = false;
    }

    public void clear() {
        spec = null;
    }

    public void resetTrack() {
        TrackUtil.reset(player);
    }

    public void toggleDecode() {
        decode = nextDecode(decode, engine.getType() == PlayerEngine.Type.MPV);
        PlayerSetting.putDecode(liveMode, getEngine(), decode);
        boolean rebuild = engine.setDecode(decode);
        callback.onDecodeChanged();
        if (!rebuild) return;
        setPlayer(engine.rebuild());
        startCurrent(getPosition());
    }

    /**
     * Hardware-first fallback: performance → compatible → soft (last resort).
     * Manual cycling uses the same order so UI and error recovery stay consistent.
     */
    private static int nextDecode(int current, boolean mpv) {
        if (!mpv) return current == PlayerEngine.HARD ? PlayerEngine.SOFT : PlayerEngine.HARD;
        return switch (current) {
            case PlayerEngine.HARD_PERFORMANCE -> PlayerEngine.HARD;
            case PlayerEngine.HARD -> PlayerEngine.SOFT;
            default -> PlayerEngine.HARD_PERFORMANCE;
        };
    }

    private void handleDecodeError(PlaybackException e) {
        if (++retry > 2) {
            callback.onError(engine.getErrorMessage(e));
        } else {
            Notify.show(R.string.error_decode_fallback);
            toggleDecode();
        }
    }

    private void handleSourceRetry(PlaybackException e) {
        if (++sourceRetry > 2) {
            handleFatalError(e);
            return;
        }
        long delayMs = sourceRetry * 800L;
        if (sourceRetry == 1) Notify.show(R.string.error_play_retry);
        App.post(() -> {
            if (spec == null || isReleased()) return;
            startCurrent(Math.max(0, getPosition()));
        }, delayMs);
    }

    private void handleFatalError(PlaybackException e) {
        if (spec != null) LineQualityStore.recordFailure(spec.getUrl());
        if (!fallbackMpvToExo()) callback.onError(engine.getErrorMessage(e));
    }

    /**
     * External/soft subtitles need gpu(-next) path; zero-copy embed cannot render them.
     */
    private void ensureDecodeForSubs(PlaySpec playSpec) {
        if (engine.getType() != PlayerEngine.Type.MPV) return;
        if (decode != PlayerEngine.HARD_PERFORMANCE) return;
        if (!hasExternalSubs(playSpec)) return;
        decode = PlayerEngine.HARD;
        if (engine.setDecode(decode)) setPlayer(engine.rebuild());
        if (!subtitleDecodeHintShown) {
            subtitleDecodeHintShown = true;
            Notify.show(R.string.player_sub_decode_hint);
        }
        callback.onDecodeChanged();
    }

    private static boolean hasExternalSubs(PlaySpec playSpec) {
        return playSpec != null && playSpec.getSubs() != null && !playSpec.getSubs().isEmpty();
    }

    private boolean isHard() {
        return decode != PlayerEngine.SOFT;
    }

    private void onPlayTimeout() {
        stop();
        if (spec != null) LineQualityStore.recordFailure(spec.getUrl());
        callback.onError(ResUtil.getString(R.string.error_play_timeout));
    }

    private void onFirstFrameTimeout() {
        if (openReported || spec == null || isReleased()) return;
        if (engine.getType() == PlayerEngine.Type.MPV && fallbackMpvToExo()) return;
        onPlayTimeout();
    }

    private void ensureEngine(PlaySpec spec) {
        if (PlayerEngineFactory.matches(engine, preferredEngine, spec)) return;
        PlayerEngine old = engine;
        player.removeListener(listener);
        engine = PlayerEngineFactory.create(decode, preferredEngine, liveMode, spec, listener);
        // Release MPV while PlayerView still owns its valid Surface. Publishing the replacement
        // first detaches that Surface and makes MPV's asynchronous shutdown rebuild a surface-less
        // VO, which can leave the next channel black.
        old.release();
        setPlayer(engine.getPlayer());
    }

    private boolean fallbackMpvToExo() {
        if (mpvFallbackUsed || engine.getType() != PlayerEngine.Type.MPV || spec == null) {
            return false;
        }
        mpvFallbackUsed = true;
        long position = Math.max(0, getPosition());
        PlayerEngine old = engine;
        player.removeListener(listener);
        engine = PlayerEngineFactory.createExo(decode, liveMode, listener);
        // Keep the old render target attached until native MPV shutdown is complete.
        old.release();
        setPlayer(engine.getPlayer());
        engine.start(spec, position);
        setDanmakus(spec.getDanmakus());
        App.post(runnable, Constant.TIMEOUT_PLAY);
        scheduleFirstFrameTimeout();
        callback.onPrepare();
        initTrack = false;
        return true;
    }

    private void setPlayer(Player player) {
        this.player = player;
        callback.onPlayerRebuild(player);
    }

    public void browse(PlaySpec spec, long startPositionMs) {
        reset();
        clear();
        stopParse();
        start(spec, Constant.TIMEOUT_PLAY, startPositionMs);
    }

    public void start(PlaySpec spec, long timeout) {
        start(spec, timeout, C.TIME_UNSET);
    }

    public void start(PlaySpec spec, long timeout, long startPositionMs) {
        this.spec = spec;
        this.subtitleDecodeHintShown = false;
        setMediaItem(timeout, startPositionMs);
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata) {
        parse(key, result, useParse, metadata, C.TIME_UNSET);
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata, long startPositionMs) {
        stopParse();
        pendingStartPositionMs = startPositionMs;
        spec = PlaySpec.fromParse(result, key, metadata);
        parseJob = ParseJob.create(this).start(result, useParse);
    }

    private void stopParse() {
        if (parseJob != null) parseJob.stop();
        parseJob = null;
        pendingStartPositionMs = C.TIME_UNSET;
    }

    private void setMediaItem(long timeout, long startPositionMs) {
        if (spec == null || spec.getUrl() == null) return;
        sourceRetry = 0;
        openReported = false;
        playStartRealtimeMs = SystemClock.elapsedRealtime();
        ensureEngine(spec.checkUa());
        ensureDecodeForSubs(spec);
        engine.start(spec, startPositionMs);
        setDanmakus(spec.getDanmakus());
        App.post(runnable, timeout);
        scheduleFirstFrameTimeout();
        callback.onPrepare();
        initTrack = false;
    }

    private void scheduleFirstFrameTimeout() {
        long timeout = liveMode ? Constant.TIMEOUT_FIRST_FRAME_LIVE : Constant.TIMEOUT_FIRST_FRAME_VOD;
        App.post(firstFrameRunnable, timeout);
    }

    private void startCurrent() {
        startCurrent(getPosition());
    }

    private void startCurrent(long startPositionMs) {
        setMediaItem(Constant.TIMEOUT_PLAY, startPositionMs);
    }

    private Danmaku getSelectedDanmaku(List<Danmaku> items) {
        if (items == null || items.isEmpty()) return Danmaku.empty();
        return items.stream().filter(Danmaku::isSelected).findFirst().orElse(items.get(0));
    }

    public Danmaku getSelectedDanmaku() {
        return getSelectedDanmaku(getDanmakus());
    }

    public Uri getSelectedDanmakuUri() {
        return getSelectedDanmaku().getUri();
    }

    public void setDanmaku(Danmaku item) {
        if (spec == null) return;
        spec.setDanmaku(item);
        notifyDanmakuSourceChanged();
    }

    public void addDanmaku(Danmaku item) {
        if (spec != null) spec.addDanmaku(item);
    }

    @Override
    public void onParseSuccess(Map<String, String> headers, String url, String from) {
        if (!TextUtils.isEmpty(from)) Notify.show(ResUtil.getString(R.string.parse_from, from));
        if (headers != null) headers.remove(HttpHeaders.RANGE);
        if (spec != null) spec.setHeaders(headers);
        if (spec != null) spec.setUrl(url);
        startCurrent(pendingStartPositionMs);
        pendingStartPositionMs = C.TIME_UNSET;
    }

    @Override
    public void onParseError() {
        pendingStartPositionMs = C.TIME_UNSET;
        callback.onError(ResUtil.getString(R.string.error_play_parse));
    }

    public interface Callback {

        void onPrepare();

        void onTracksChanged();

        void onDecodeChanged();

        void onMediaOptionsChanged();

        void onError(String msg);

        void onPlayerRebuild(Player newPlayer);

        void onDanmakuSourceChanged(Uri uri);

        void onDanmakuConfigChanged(DanmakuConfig config);

        void onDanmakuEnabledChanged(boolean enabled);

        void onDanmakuSent(String text);
    }

    private final Player.Listener listener = new Player.Listener() {

        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_READY) {
                App.removeCallbacks(runnable, firstFrameRunnable);
                if (!openReported && spec != null) {
                    openReported = true;
                    long openMs = Math.max(0, SystemClock.elapsedRealtime() - playStartRealtimeMs);
                    LineQualityStore.recordSuccess(spec.getUrl(), openMs);
                }
            } else if (state == Player.STATE_ENDED) {
                App.removeCallbacks(runnable, firstFrameRunnable);
            }
        }

        @Override
        public void onVideoSizeChanged(@NonNull VideoSize size) {
            videoSize = size;
        }

        @Override
        public void onTracksChanged(@NonNull Tracks tracks) {
            if (tracks.isEmpty() || initTrack) return;
            setTrack(Track.find(getKey()));
            callback.onTracksChanged();
            initTrack = true;
        }

        @Override
        public void onMediaChaptersChanged(@NonNull List<MediaChapter> chapters) {
            callback.onMediaOptionsChanged();
        }

        @Override
        public void onMediaEditionsChanged(@NonNull List<MediaEdition> editions) {
            callback.onMediaOptionsChanged();
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException e) {
            App.removeCallbacks(runnable, firstFrameRunnable);
            if (spec == null) return;
            switch (engine.handleError(e)) {
                case RETRY -> handleSourceRetry(e);
                case DECODE -> handleDecodeError(e);
                case RECOVERED -> setDanmakus(spec.getDanmakus());
                case FATAL -> handleFatalError(e);
            }
        }
    };

}
