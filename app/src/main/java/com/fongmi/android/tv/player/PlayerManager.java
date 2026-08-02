package com.fongmi.android.tv.player;

import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.Format;
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
    private boolean subtitleDecodeHintShown;
    private boolean openReported;
    private long playStartRealtimeMs;
    private int firstFrameExtendCount;
    private int retry;
    private int sourceRetry;
    private int decode;
    /** Bitmask of decode modes already tried for the current item (avoids HARD↔SOFT oscillation). */
    private int decodeTriedMask;
    private int preferredEngine;
    private boolean liveMode;
    private final Runnable sourceRetryRunnable;

    public PlayerManager(Callback callback) {
        this.callback = callback;
        this.runnable = this::onPlayTimeout;
        this.firstFrameRunnable = this::onFirstFrameTimeout;
        this.sourceRetryRunnable = this::onSourceRetry;
        this.preferredEngine = PlayerSetting.getVodEngine();
        // MediaBrowser clients (launcher/system UI) may create PlaybackService without starting
        // playback. Do not reserve the process-wide native MPV instance for that idle service.
        // The playback Activity applies the configured live/VOD engine before loading a source.
        this.decode = PlayerSetting.getDecode(false, PlayerSetting.ENGINE_EXO);
        this.engine = PlayerEngineFactory.createExo(decode, false, listener);
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
        App.removeCallbacks(runnable, firstFrameRunnable, sourceRetryRunnable);
        stopParse();
        spec = null;
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
        String[] labels = ResUtil.getStringArray(getEngine() == PlayerSetting.ENGINE_EXO ? R.array.select_decode_exo : R.array.select_decode);
        return labels[Math.min(decode, labels.length - 1)];
    }

    /** A lightweight runtime snapshot for the user-facing player diagnostic dialog. */
    public String getDiagnosticText() {
        StringBuilder text = new StringBuilder();
        text.append("播放器: ").append(getEngine() == PlayerSetting.ENGINE_MPV ? "MPV" : "ExoPlayer");
        text.append("\n解码模式: ").append(getDecodeText());
        text.append("\n视频尺寸: ").append(getSizeText().isEmpty() ? "正在探测" : getSizeText());
        text.append("\n状态: ").append(getPlaybackStateText());
        text.append("\n播放位置: ").append(getPosition() / 1000).append("s");
        text.append("\n内存缓冲: ").append(Math.max(0, player.getBufferedPosition() - getPosition()) / 1000).append("s");
        Format format = getSelectedVideoFormat();
        if (format != null) {
            text.append("\n编码: ").append(format.sampleMimeType == null ? "未知" : format.sampleMimeType);
            if (format.bitrate > 0) text.append("  ").append(format.bitrate / 1000).append(" kbps");
            if (format.frameRate > 0) text.append("  ").append(String.format(Locale.getDefault(), "%.0f fps", format.frameRate));
        }
        text.append("\n缓存策略: ").append(liveMode ? "直播：仅内存，不读写磁盘" : "点播：Exo 可缓存；MPV 仅直链文件");
        return text.toString();
    }

    private String getPlaybackStateText() {
        return switch (player.getPlaybackState()) {
            case Player.STATE_BUFFERING -> "缓冲中";
            case Player.STATE_READY -> player.isPlaying() ? "播放中" : "已暂停";
            case Player.STATE_ENDED -> "已结束";
            default -> "空闲";
        };
    }

    private Format getSelectedVideoFormat() {
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_VIDEO) continue;
            for (int i = 0; i < group.length; i++) if (group.isTrackSelected(i)) return group.getTrackFormat(i);
        }
        return null;
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
        boolean samePreference = preferredEngine == targetEngine;
        preferredEngine = targetEngine;
        if (persist) {
            if (liveMode) PlayerSetting.putLiveEngine(targetEngine);
            else PlayerSetting.putVodEngine(targetEngine);
        }
        decode = PlayerSetting.getDecode(liveMode, targetEngine);
        decodeTriedMask = 0;
        callback.onDecodeChanged();
        if (isEmpty()) {
            // A background suspend or a failed/unfinished parse clears the current PlaySpec.
            // Persisting the preference alone leaves the old engine alive, so the playback page
            // continues to report MPV even after the user selected Exo (and vice versa).
            // Replace the idle engine now; the next resolved URL will then use the visible choice.
            if (getEngine() != targetEngine) {
                replaceIdleEngine(targetEngine);
                // The first notification ran while the old engine was still active.
                callback.onDecodeChanged();
            }
            return;
        }
        if (samePreference && getEngine() == targetEngine) return;
        if (targetEngine == PlayerSetting.ENGINE_MPV && spec != null
                && PlayerEngineFactory.requiresExo(spec)) {
            Notify.show(R.string.player_engine_requires_exo);
        }
        beginFreshAttempt();
        startCurrent();
    }

    private void replaceIdleEngine(int targetEngine) {
        PlayerEngine old = engine;
        player.removeListener(listener);
        boolean oldMpv = old.getType() == PlayerEngine.Type.MPV;
        boolean newMpv = PlayerEngineFactory.isMpvPreferred(targetEngine);
        // MPV holds a process-wide native lock: release the old MPV before creating another.
        if (oldMpv && newMpv) {
            old.release();
            engine = PlayerEngineFactory.create(decode, targetEngine, liveMode, listener);
        } else {
            // When leaving MPV for Exo, create Exo first so PlayerView keeps a valid Surface
            // while MPV tears down asynchronously.
            engine = PlayerEngineFactory.create(decode, targetEngine, liveMode, listener);
            old.release();
        }
        setPlayer(engine.getPlayer());
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

    /**
     * Stop playback and release decoder / native player resources while keeping PlayerManager
     * usable. Used when Live leaves the foreground or the app exits with background play off.
     */
    public void suspend() {
        App.removeCallbacks(runnable, firstFrameRunnable);
        stopParse();
        if (engine != null && !isReleased()) {
            engine.stop();
            // Rebuild fully frees MediaCodec / MPV hwdec that stop alone may retain.
            setPlayer(engine.rebuild());
        }
        clear();
        reset();
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
        App.removeCallbacks(runnable, firstFrameRunnable, sourceRetryRunnable);
        retry = 0;
        sourceRetry = 0;
        decodeTriedMask = 0;
        openReported = false;
    }

    public void clear() {
        spec = null;
    }

    public void resetTrack() {
        TrackUtil.reset(player);
    }

    public void toggleDecode() {
        switchDecode(true, true);
    }

    private void switchDecode(boolean persist, boolean freshAttempt) {
        long position = getPosition();
        boolean mpv = engine.getType() == PlayerEngine.Type.MPV;
        if (persist) {
            decode = nextDecode(decode, mpv);
        } else {
            decodeTriedMask |= 1 << decode;
            int next = nextUnusedDecode(decode, mpv);
            if (next < 0) {
                callback.onError(ResUtil.getString(R.string.error_play_url));
                return;
            }
            decode = next;
        }
        if (persist) PlayerSetting.putDecode(liveMode, getEngine(), decode);
        boolean rebuild = engine.setDecode(decode);
        callback.onDecodeChanged();
        if (rebuild) setPlayer(engine.rebuild());
        if (freshAttempt) beginFreshAttempt();
        // Changing hwdec does not replace a decoder that is already open. Reload the current item
        // even when the engine itself can be reused (compatible-hard <-> software).
        startCurrent(position);
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

    /** Next decode mode that has not failed for this item; -1 if all candidates exhausted. */
    private int nextUnusedDecode(int current, boolean mpv) {
        int candidate = nextDecode(current, mpv);
        for (int i = 0; i < 3; i++) {
            if ((decodeTriedMask & (1 << candidate)) == 0) return candidate;
            candidate = nextDecode(candidate, mpv);
        }
        return -1;
    }

    private void handleDecodeError(PlaybackException e) {
        decodeTriedMask |= 1 << decode;
        if (++retry > 2 || nextUnusedDecode(decode, engine.getType() == PlayerEngine.Type.MPV) < 0) {
            callback.onError(engine.getErrorMessage(e));
        } else {
            Notify.show(R.string.error_decode_fallback);
            // Automatic recovery is temporary for this item. Only an explicit user action writes
            // PlayerSetting, so the next item starts with the user's preferred decode mode.
            switchDecode(false, false);
        }
    }

    private void handleSourceRetry(PlaybackException e) {
        if (++sourceRetry > 2) {
            App.removeCallbacks(sourceRetryRunnable);
            handleFatalError(e);
            return;
        }
        long delayMs = sourceRetry * 800L;
        if (sourceRetry == 1) Notify.show(R.string.error_play_retry);
        App.removeCallbacks(sourceRetryRunnable);
        App.post(sourceRetryRunnable, delayMs);
    }

    private void onSourceRetry() {
        if (spec == null || isReleased()) return;
        // Keep sourceRetry across the restart; setMediaItem must not clear it or retries never end.
        startCurrent(Math.max(0, getPosition()));
    }

    private void handleFatalError(PlaybackException e) {
        if (spec != null) LineQualityStore.recordFailure(spec.getUrl());
        callback.onError(engine.getErrorMessage(e));
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
        // MPV may advance position before STATE_READY; extend a few times, then fail.
        if (engine.getType() == PlayerEngine.Type.MPV && getPosition() > 0 && firstFrameExtendCount < 3) {
            firstFrameExtendCount++;
            scheduleFirstFrameTimeout();
            return;
        }
        onPlayTimeout();
    }

    private void ensureEngine(PlaySpec spec) {
        if (PlayerEngineFactory.matches(engine, preferredEngine, spec)) return;
        PlayerEngine old = engine;
        player.removeListener(listener);
        boolean oldMpv = old.getType() == PlayerEngine.Type.MPV;
        boolean newMpv = PlayerEngineFactory.resolveType(preferredEngine, spec) == PlayerEngine.Type.MPV;
        if (oldMpv && newMpv) {
            old.release();
            engine = PlayerEngineFactory.create(decode, preferredEngine, liveMode, spec, listener);
        } else {
            // Release MPV while PlayerView still owns its valid Surface when switching to Exo.
            // Publishing Exo first keeps the Surface; releasing MPV first would risk a black frame.
            engine = PlayerEngineFactory.create(decode, preferredEngine, liveMode, spec, listener);
            old.release();
        }
        setPlayer(engine.getPlayer());
    }

    private void restorePreferredDecode() {
        int preferred = PlayerSetting.getDecode(liveMode, getEngine());
        if (decode == preferred) return;
        decode = preferred;
        if (engine.setDecode(decode)) setPlayer(engine.rebuild());
        callback.onDecodeChanged();
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
        beginFreshAttempt();
        ensureEngine(spec.checkUa());
        restorePreferredDecode();
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
        // Drop stale play/first-frame timeouts before engine.start; keep sourceRetry across retries.
        App.removeCallbacks(runnable, firstFrameRunnable);
        openReported = false;
        firstFrameExtendCount = 0;
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
        // FFmpeg-backed MPV may need to inspect every rendition in an HLS master playlist before
        // it can select and decode the first segment. On higher-latency mobile networks that can
        // legitimately exceed the normal 8-second live limit. Keep Exo's fast failure behavior,
        // but give MPV up to the existing overall play timeout instead of aborting a healthy load.
        long timeout = liveMode
                ? (engine.getType() == PlayerEngine.Type.MPV
                ? Constant.TIMEOUT_PLAY
                : Constant.TIMEOUT_FIRST_FRAME_LIVE)
                : Constant.TIMEOUT_FIRST_FRAME_VOD;
        App.post(firstFrameRunnable, timeout);
    }

    private void startCurrent() {
        startCurrent(getPosition());
    }

    private void startCurrent(long startPositionMs) {
        setMediaItem(Constant.TIMEOUT_PLAY, startPositionMs);
    }

    /** Clear IO/decode retry state for a user- or parse-initiated (re)start — not for sourceRetry loops. */
    private void beginFreshAttempt() {
        App.removeCallbacks(sourceRetryRunnable);
        retry = 0;
        sourceRetry = 0;
        decodeTriedMask = 0;
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
        beginFreshAttempt();
        if (spec != null) {
            ensureEngine(spec.checkUa());
            restorePreferredDecode();
        }
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
                // Playback succeeded; cancel pending timeouts and any queued source retry restart.
                App.removeCallbacks(runnable, firstFrameRunnable, sourceRetryRunnable);
                if (!openReported && spec != null) {
                    openReported = true;
                    long openMs = Math.max(0, SystemClock.elapsedRealtime() - playStartRealtimeMs);
                    LineQualityStore.recordSuccess(spec.getUrl(), openMs);
                }
            } else if (state == Player.STATE_ENDED) {
                App.removeCallbacks(runnable, firstFrameRunnable, sourceRetryRunnable);
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
            // Clear pending timeouts/retries; RETRY path will re-schedule sourceRetryRunnable.
            App.removeCallbacks(runnable, firstFrameRunnable, sourceRetryRunnable);
            if (spec == null) return;
            switch (engine.handleError(e)) {
                case RETRY -> handleSourceRetry(e);
                case DECODE -> handleDecodeError(e);
                case RECOVERED -> {
                    // Engine may have rebuilt its Player (e.g. audio pass-through fallback).
                    Player recovered = engine.getPlayer();
                    if (recovered != null && recovered != player) setPlayer(recovered);
                    setDanmakus(spec.getDanmakus());
                }
                case FATAL -> handleFatalError(e);
            }
        }
    };

}
