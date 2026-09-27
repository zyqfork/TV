package com.fongmi.android.tv.player;

import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaChapter;
import androidx.media3.common.MediaEdition;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
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
import com.fongmi.android.tv.player.effect.PlayerEffectManager;
import com.fongmi.android.tv.player.effect.audio.AudioEffectBands;
import com.fongmi.android.tv.player.engine.PlaybackRecoveryPolicy;
import com.fongmi.android.tv.player.engine.PlaybackCapabilities;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.engine.PlayerEngineFactory;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.player.parse.ParseJob;
import com.fongmi.android.tv.player.subtitle.SecondarySubtitleStore;
import com.fongmi.android.tv.player.track.TrackUtil;
import com.fongmi.android.tv.setting.AudioSetting;
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
import java.util.concurrent.TimeUnit;

public class PlayerManager implements ParseCallback {

    /** Extra first-frame rounds MPV may request while a slow HLS master is still opening. */
    private static final int MAX_FIRST_FRAME_EXTENSIONS = 3;
    /**
     * Source-level restarts allowed for one item before the failure is treated as fatal. Kept in
     * step with PlaybackRecoveryPolicy.MAX_ATTEMPTS: the engine only decides RETRY while its own
     * budget lasts, and this is how many restarts PlayerManager is willing to perform. Exo no
     * longer retries internally, so lowering this silently halves the recovery budget.
     */
    private static final int MAX_SOURCE_RETRY = 2;

    private final Runnable runnable;
    private final Runnable firstFrameRunnable;
    private final Runnable sourceRetryRunnable;
    private final Callback callback;
    private final PlayerEffectManager effects;
    private PlayerEngine engine;
    private VideoSize videoSize;
    private ParseJob parseJob;
    private PendingPreload pendingPreload;
    private PlaySpec spec;
    private Player player;
    private Sub secondarySub;
    private TrackSelectionOverride embeddedSecondarySelection;
    private String secondarySubtitleMemoryKey = "";

    private DanmakuConfig danmakuConfig;
    private long pendingStartPositionMs;
    private boolean danmakuEnabled;
    private boolean initTrack;
    private boolean mpvFallbackUsed;
    private boolean subtitleDecodeHintShown;
    private int retry;
    private int sourceRetry;
    private int firstFrameExtendCount;
    private long firstFrameDeadlineMs;
    private int decode;
    /** Bitmask of decode modes already tried for the current item (avoids HARD↔SOFT oscillation). */
    private int decodeTriedMask;
    private int preferredEngine;
    private long secondarySubtitleOffsetMs;
    private long playStartRealtimeMs;
    private boolean openReported;
    private boolean liveMode;

    public PlayerManager(Callback callback) {
        this.callback = callback;
        this.runnable = this::onPlayTimeout;
        this.firstFrameRunnable = this::onFirstFrameTimeout;
        this.sourceRetryRunnable = this::onSourceRetry;
        this.preferredEngine = PlayerSetting.getVodEngine();
        // PlaybackService may be created only for MediaSession browsing. Do not reserve libmpv's
        // process-global instance until an actual VOD/live source selects it.
        this.decode = PlayerSetting.getDecode(false, PlayerSetting.ENGINE_EXO);
        this.engine = PlayerEngineFactory.createExo(decode, false, listener);
        this.effects = new PlayerEffectManager(() -> engine);
        this.player = engine.getPlayer();
        applyPersistedVolumeGain();
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

    public boolean canPreloadNext() {
        return PlayerSetting.getVodEngine() == PlayerSetting.ENGINE_EXO
                && engine != null
                && engine.getType() == PlayerEngine.Type.EXO;
    }

    public boolean preload(PlaySpec spec, long startPositionMs) {
        if (!canPreloadNext() || spec == null) return false;
        pendingPreload = new PendingPreload(spec.checkUa(), Math.max(0, startPositionMs));
        startPreloadIfReady();
        return true;
    }

    public void clearPreload() {
        pendingPreload = null;
        if (engine != null) engine.clearPreload();
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
        // EXO only toggles software vs hardware MediaCodec. "兼容硬解/性能硬解" are MPV
        // (mediacodec-copy / mediacodec_embed) labels and must not be shown on EXO.
        if (getEngine() == PlayerSetting.ENGINE_EXO) {
            return ResUtil.getString(decode == PlayerEngine.SOFT ? R.string.decode_soft : R.string.decode_hard);
        }
        return ResUtil.getStringArray(R.array.select_decode)[decode];
    }

    public int getEngine() {
        return engine.getType() == PlayerEngine.Type.MPV ? PlayerSetting.ENGINE_MPV : PlayerSetting.ENGINE_EXO;
    }

    public PlaybackCapabilities getCapabilities() {
        return PlaybackCapabilities.forEngine(engine.getType(), effects.canSetAudioSetting(), effects.canSetVideoSetting(), effects.supportsVideoSharpness());
    }

    public boolean canSetAudioSetting() {
        return effects.canSetAudioSetting();
    }

    public boolean canSetVideoSetting() {
        return effects.canSetVideoSetting();
    }

    public AudioEffectBands getAudioSettingBands() {
        return effects.getAudioSettingBands();
    }

    public int getAudioSettingError() {
        return effects.getAudioSettingError();
    }

    public int getVideoSettingError() {
        return effects.getVideoSettingError();
    }

    public void refreshAudioSetting() {
        if (engine != null && engine.requiresAudioEffectRebuild() && AudioSetting.hasEffect(8)) {
            long position = Math.max(0, getPosition());
            setPlayer(engine.rebuild());
            startCurrent(position);
            return;
        }
        effects.refreshAudioSetting();
    }

    public void refreshVideoSetting() {
        effects.refreshVideoSetting();
    }

    public void setEngine(int targetEngine) {
        setEngine(targetEngine, true);
    }

    /** Apply a config-provided engine without overwriting the user's persistent preference. */
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
            // The service starts with a lightweight Exo instance and may have no PlaySpec yet.
            // Apply an explicit engine choice immediately instead of leaving UI and engine apart.
            if (getEngine() != targetEngine) replaceIdleEngine(targetEngine);
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

    /** Select an engine for the source that is about to be started, without restarting the old item. */
    public void setEngineForNextPlayback(int targetEngine) {
        targetEngine = Math.clamp(targetEngine, PlayerSetting.ENGINE_EXO, PlayerSetting.ENGINE_MPV);
        preferredEngine = targetEngine;
        decode = PlayerSetting.getDecode(liveMode, targetEngine);
        callback.onDecodeChanged();
        if (isEmpty() && getEngine() != targetEngine) replaceIdleEngine(targetEngine);
    }

    private void replaceIdleEngine(int targetEngine) {
        PlayerEngine old = engine;
        if (player != null) player.removeListener(listener);
        engine = PlayerEngineFactory.create(decode, targetEngine, liveMode, listener);
        if (old != null) old.release();
        setPlayer(engine.getPlayer());
    }

    public void setLiveMode(boolean liveMode) {
        boolean modeChanged = this.liveMode != liveMode;
        this.liveMode = liveMode;
        int target = liveMode ? PlayerSetting.getLiveEngine() : PlayerSetting.getVodEngine();
        int targetDecode = PlayerSetting.getDecode(liveMode, target);
        preferredEngine = target;
        if (engine == null) {
            decode = targetDecode;
            return;
        }
        engine.setLiveMode(liveMode);
        boolean decodeChanged = decode != targetDecode;
        decode = targetDecode;
        // A scene switch changes Exo LoadControl and MPV demux/cache policy even when the engine
        // type and decode mode are unchanged, so the old instance cannot simply be reused.
        if (getEngine() == target && (modeChanged || decodeChanged)) {
            engine.setDecode(decode);
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
        if (sub == null || sub.isEmpty()) return;
        if (spec != null) spec.setSub(sub);
        if (engine.addSubtitle(sub)) play();
        else startCurrent();
    }

    @Nullable
    public Sub getSecondarySub() {
        return secondarySub;
    }

    public void setSecondarySub(@Nullable Sub sub) {
        secondarySub = sub == null || sub.isEmpty() ? null : sub;
        secondarySubtitleOffsetMs = 0;
        if (secondarySub != null) setEmbeddedSecondarySubtitle(null);
        rememberSecondarySubtitle();
        callback.onSecondarySubtitleChanged(secondarySub);
    }

    public boolean hasSecondarySubtitle() {
        return secondarySub != null || embeddedSecondarySelection != null;
    }

    public boolean supportsEmbeddedSecondarySubtitle() {
        return engine != null && engine.supportsEmbeddedSecondarySubtitle();
    }

    public List<SecondaryTrackOption> getEmbeddedSecondarySubtitleOptions() {
        if (!supportsEmbeddedSecondarySubtitle()) return List.of();
        java.util.ArrayList<SecondaryTrackOption> result = new java.util.ArrayList<>();
        for (Tracks.Group group : getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < group.length; i++) {
                if (group.isTrackSelected(i)) continue;
                TrackSelectionOverride selection = new TrackSelectionOverride(group.getMediaTrackGroup(), List.of(i));
                result.add(new SecondaryTrackOption(group.getTrackFormat(i), selection, selection.equals(embeddedSecondarySelection)));
            }
        }
        return result;
    }

    public void setEmbeddedSecondarySubtitle(@Nullable TrackSelectionOverride selection) {
        embeddedSecondarySelection = selection;
        if (selection != null && secondarySub != null) {
            secondarySub = null;
            secondarySubtitleOffsetMs = 0;
            SecondarySubtitleStore.put(secondarySubtitleMemoryKey, null, 0);
            callback.onSecondarySubtitleChanged(null);
        }
        if (engine != null) {
            engine.setEmbeddedSecondarySubtitle(selection);
            engine.setEmbeddedSecondarySubtitleOffset(selection == null ? 0 : secondarySubtitleOffsetMs);
        }
    }

    public void clearSecondarySub() {
        secondarySub = null;
        secondarySubtitleOffsetMs = 0;
        SecondarySubtitleStore.put(secondarySubtitleMemoryKey, null, 0);
        callback.onSecondarySubtitleChanged(null);
    }

    private void clearSecondarySubTransient() {
        secondarySub = null;
        secondarySubtitleOffsetMs = 0;
        secondarySubtitleMemoryKey = "";
        embeddedSecondarySelection = null;
        if (engine != null) engine.setEmbeddedSecondarySubtitle(null);
        callback.onSecondarySubtitleChanged(null);
    }

    private void restoreSecondarySubtitle(PlaySpec target) {
        secondarySubtitleMemoryKey = SecondarySubtitleStore.keyOf(target);
        SecondarySubtitleStore.Entry saved = SecondarySubtitleStore.get(secondarySubtitleMemoryKey);
        secondarySub = saved == null ? null : saved.sub();
        secondarySubtitleOffsetMs = saved == null ? 0 : saved.offsetMs();
        if (!isRestorableSecondarySubtitle(secondarySub)) {
            secondarySub = null;
            secondarySubtitleOffsetMs = 0;
            SecondarySubtitleStore.put(secondarySubtitleMemoryKey, null, 0);
        }
        callback.onSecondarySubtitleChanged(secondarySub);
    }

    private boolean isRestorableSecondarySubtitle(@Nullable Sub sub) {
        if (sub == null || sub.isEmpty()) return false;
        Uri uri = sub.getUri();
        if (uri == null) return false;
        if (!TextUtils.isEmpty(uri.getScheme()) && !"file".equalsIgnoreCase(uri.getScheme())) return true;
        String path = "file".equalsIgnoreCase(uri.getScheme()) ? uri.getPath() : uri.toString();
        return !TextUtils.isEmpty(path) && new java.io.File(path).isFile();
    }

    private void rememberSecondarySubtitle() {
        SecondarySubtitleStore.put(secondarySubtitleMemoryKey, secondarySub, secondarySubtitleOffsetMs);
    }

    public long getSecondarySubtitleOffsetMs() {
        return secondarySubtitleOffsetMs;
    }

    public void setSecondarySubtitleOffsetMs(long offsetMs) {
        secondarySubtitleOffsetMs = Math.clamp(offsetMs, -TimeUnit.MINUTES.toMillis(10), TimeUnit.MINUTES.toMillis(10));
        if (embeddedSecondarySelection != null && engine != null) engine.setEmbeddedSecondarySubtitleOffset(secondarySubtitleOffsetMs);
        rememberSecondarySubtitle();
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
        callback.onSubtitleStyleChanged();
        callback.onDanmakuConfigChanged(DanmakuSetting.getConfig());
    }

    /** 1.0 = normal; up to 2.0 for quiet sources. Applies to ExoPlayer and MPV. */
    public void setVolumeGain(float gain) {
        float value = Math.clamp(gain, 0f, 2f);
        PlayerSetting.putVolumeGain(value);
        if (engine != null) engine.setVolumeGain(value);
    }

    public float getVolumeGain() {
        return PlayerSetting.getVolumeGain();
    }

    private void applyPersistedVolumeGain() {
        if (engine != null) engine.setVolumeGain(PlayerSetting.getVolumeGain());
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
        App.removeCallbacks(runnable, firstFrameRunnable, sourceRetryRunnable);
        retry = 0;
        sourceRetry = 0;
        decodeTriedMask = 0;
        firstFrameExtendCount = 0;
        firstFrameDeadlineMs = 0;
        mpvFallbackUsed = false;
        openReported = false;
    }

    public void clear() {
        spec = null;
        clearSecondarySubTransient();
    }

    public void resetTrack() {
        TrackUtil.reset(player);
    }

    /** User-initiated decode switch: persists the choice so it survives restarts. */
    public void toggleDecode() {
        switchDecode(true, true);
    }

    /**
     * Automatic fallback after a decode error. Deliberately does NOT persist.
     * Only unused decode modes are tried for this item (decodeTriedMask), so HARD↔SOFT
     * cannot oscillate. Codec-level fallback is also handled inside DefaultRenderersFactory.
     */
    private void toggleDecodeTransient() {
        switchDecode(false, false);
    }

    private void switchDecode(boolean persist, boolean freshAttempt) {
        long position = Math.max(0, getPosition());
        boolean mpv = engine.getType() == PlayerEngine.Type.MPV;
        if (persist) {
            decode = nextDecode(decode, mpv);
        } else {
            decodeTriedMask |= 1 << decode;
            int next = nextUnusedDecode(decode, mpv);
            if (next < 0) {
                handleFatalError(null);
                return;
            }
            decode = next;
        }
        if (persist) PlayerSetting.putDecode(liveMode, getEngine(), decode);
        boolean rebuild = engine.setDecode(decode);
        callback.onDecodeChanged();
        if (rebuild) setPlayer(engine.rebuild());
        if (freshAttempt) beginFreshAttempt();
        // Changing hwdec does not replace an already-open decoder; always reload the item.
        startCurrent(position);
    }

    /** Hardware-first fallback: performance → compatible → soft (last resort). */
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
            handleFatalError(e);
        } else {
            Notify.show(R.string.error_decode_fallback);
            toggleDecodeTransient();
        }
    }

    private void handleSourceRetry(PlaybackException e) {
        if (++sourceRetry > MAX_SOURCE_RETRY) {
            App.removeCallbacks(sourceRetryRunnable);
            handleFatalError(e);
            return;
        }
        Notify.show(R.string.error_play_retry);
        App.removeCallbacks(sourceRetryRunnable);
        // Share the policy's backoff so delay and budget stay defined in one place.
        App.post(sourceRetryRunnable, PlaybackRecoveryPolicy.retryDelayMs(sourceRetry - 1));
    }

    private void onSourceRetry() {
        if (spec == null || isReleased()) return;
        // Keep sourceRetry across the restart so retries cannot run forever.
        startCurrent(Math.max(0, getPosition()));
    }

    private void handleFatalError(PlaybackException e) {
        if (spec != null) LineQualityStore.recordFailure(spec.getUrl());
        String msg = e == null ? ResUtil.getString(R.string.error_play_timeout) : engine.getErrorMessage(e);
        // Always surface a toast so a stuck error panel cannot hide the failure.
        Notify.show(msg);
        callback.onError(msg);
    }

    /** External/soft subtitles need a gpu path; zero-copy embed cannot render them. */
    private void ensureDecodeForSubs(PlaySpec playSpec) {
        if (engine.getType() != PlayerEngine.Type.MPV) return;
        if (decode != PlayerEngine.HARD_PERFORMANCE) return;
        if (playSpec == null || playSpec.getSubs() == null || playSpec.getSubs().isEmpty()) return;
        decode = PlayerEngine.HARD;
        if (engine.setDecode(decode)) setPlayer(engine.rebuild());
        if (!subtitleDecodeHintShown) {
            subtitleDecodeHintShown = true;
            Notify.show(R.string.player_sub_decode_hint);
        }
        callback.onDecodeChanged();
    }

    /** Clear IO/decode retry state for a user- or parse-initiated (re)start. */
    private void beginFreshAttempt() {
        App.removeCallbacks(sourceRetryRunnable);
        retry = 0;
        sourceRetry = 0;
        decodeTriedMask = 0;
        firstFrameExtendCount = 0;
        firstFrameDeadlineMs = 0;
    }

    private boolean isHard() {
        return decode != PlayerEngine.SOFT;
    }

    private void onPlayTimeout() {
        stop();
        handleFatalError(null);
    }

    private void onFirstFrameTimeout() {
        if (openReported || spec == null || isReleased()) return;
        // MPV only reports STATE_READY once its file is loaded, and an HLS master with many
        // renditions can take longer than a single timeout to get there. Position is not evidence
        // of progress here (a resumed item already starts at position > 0) and MPV publishes no
        // "opening" signal before FILE_LOADED, so MPV simply gets a bounded number of extra
        // rounds. Exo keeps failing fast, and the total wait stays capped at
        // (1 + MAX_FIRST_FRAME_EXTENSIONS) x firstFrameTimeoutMs().
        if (engine.getType() == PlayerEngine.Type.MPV && firstFrameExtendCount < MAX_FIRST_FRAME_EXTENSIONS) {
            firstFrameExtendCount++;
            firstFrameDeadlineMs = SystemClock.elapsedRealtime() + firstFrameTimeoutMs();
            scheduleFirstFrameTimeout();
            return;
        }
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
        decode = decode == PlayerEngine.SOFT ? PlayerEngine.SOFT : PlayerEngine.HARD;
        engine = PlayerEngineFactory.createExo(decode, liveMode, listener);
        // Keep the old render target attached until native MPV shutdown is complete.
        old.release();
        setPlayer(engine.getPlayer());
        engine.start(spec, position);
        setDanmakus(spec.getDanmakus());
        App.post(runnable, Constant.TIMEOUT_PLAY);
        callback.onPrepare();
        initTrack = false;
        return true;
    }

    private void setPlayer(Player player) {
        this.player = player;
        embeddedSecondarySelection = null;
        applyPersistedVolumeGain();
        effects.refreshVideoSetting();
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
        // New URL resets retry/watchdog state. Same-URL restarts must not, or a
        // dead endpoint loops forever through start() -> budgets cleared.
        resetBudgetsIfUrlChanged(this.spec == null ? null : this.spec.getUrl(), spec == null ? null : spec.getUrl());
        if (this.spec != spec) clearSecondarySubTransient();
        this.spec = spec;
        restoreSecondarySubtitle(spec);
        setMediaItem(timeout, startPositionMs);
    }

    /**
     * A different URL means a different source, so retry and watchdog budgets start over. The same
     * URL restarting (source retry, decode switch) deliberately keeps them, which is what stops a
     * dead endpoint from retrying forever.
     */
    private void resetBudgetsIfUrlChanged(String prevUrl, String nextUrl) {
        if (java.util.Objects.equals(prevUrl, nextUrl)) return;
        sourceRetry = 0;
        retry = 0;
        firstFrameDeadlineMs = 0;
        firstFrameExtendCount = 0;
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata) {
        parse(key, result, useParse, metadata, C.TIME_UNSET);
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata, long startPositionMs) {
        stopParse();
        clearSecondarySubTransient();
        pendingStartPositionMs = startPositionMs;
        spec = PlaySpec.fromParse(result, key, metadata);
        restoreSecondarySubtitle(spec);
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
        ensureEngine(spec.checkUa());
        pendingPreload = null;
        openReported = false;
        firstFrameExtendCount = 0;
        if (firstFrameDeadlineMs == 0) {
            firstFrameDeadlineMs = SystemClock.elapsedRealtime() + firstFrameTimeoutMs();
        }
        playStartRealtimeMs = SystemClock.elapsedRealtime();
        ensureDecodeForSubs(spec);
        engine.start(spec, startPositionMs);
        setDanmakus(spec.getDanmakus());
        App.post(runnable, timeout);
        scheduleFirstFrameTimeout();
        callback.onPrepare();
        initTrack = false;
    }

    private long firstFrameTimeoutMs() {
        // FFmpeg-backed MPV may inspect every rendition in an HLS master before the first frame.
        // Keep Exo's fast failure, but give MPV the full play timeout instead of aborting a healthy load.
        return liveMode
                ? (engine.getType() == PlayerEngine.Type.MPV ? Constant.TIMEOUT_PLAY : Constant.TIMEOUT_FIRST_FRAME_LIVE)
                : Constant.TIMEOUT_FIRST_FRAME_VOD;
    }

    private void scheduleFirstFrameTimeout() {
        // Hard deadline across retries so a dead endpoint cannot refresh the watchdog forever.
        long now = SystemClock.elapsedRealtime();
        long remaining = firstFrameDeadlineMs > 0 ? firstFrameDeadlineMs - now : firstFrameTimeoutMs();
        App.post(firstFrameRunnable, Math.max(100L, remaining));
    }

    private void startCurrent() {
        startCurrent(getPosition());
    }

    private void startCurrent(long startPositionMs) {
        setMediaItem(Constant.TIMEOUT_PLAY, startPositionMs);
    }

    private void startPreloadIfReady() {
        PendingPreload preload = pendingPreload;
        if (preload == null || player.getPlaybackState() != Player.STATE_READY) return;
        pendingPreload = null;
        engine.preload(preload.spec(), preload.startPositionMs());
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
        // parse() mutates the spec in place and restarts through startCurrent(), which bypasses the
        // URL-change check in start(). A parse result is a new source, so reset the budgets here;
        // otherwise the new URL inherits the previous attempt's watchdog deadline and retry count.
        resetBudgetsIfUrlChanged(spec == null ? null : spec.getUrl(), url);
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

        void onSecondarySubtitleChanged(@Nullable Sub sub);

        void onSubtitleStyleChanged();
    }

    private final Player.Listener listener = new Player.Listener() {

        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_READY || state == Player.STATE_ENDED) App.removeCallbacks(runnable, firstFrameRunnable);
            if (state == Player.STATE_READY) {
                engine.resetErrorBudget();
                if (!openReported && liveMode && spec != null) {
                    openReported = true;
                    LineQualityStore.recordSuccess(spec.getUrl(), Math.max(0, SystemClock.elapsedRealtime() - playStartRealtimeMs));
                }
                startPreloadIfReady();
            }
        }

        @Override
        public void onVideoSizeChanged(@NonNull VideoSize size) {
            videoSize = size;
        }

        @Override
        public void onTracksChanged(@NonNull Tracks tracks) {
            if (tracks.isEmpty()) return;
            effects.refreshAudioSetting();
            effects.refreshVideoSetting();
            if (initTrack) return;
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
            if (spec == null) return;
            PlayerEngine.ErrorAction action = engine.handleError(e);
            if (action != PlayerEngine.ErrorAction.RECOVERED) App.removeCallbacks(runnable, firstFrameRunnable);
            switch (action) {
                case DECODE -> handleDecodeError(e);
                case RETRY -> handleSourceRetry(e);
                case RECOVERED -> setDanmakus(spec.getDanmakus());
                case FATAL -> {
                    if (liveMode && e.errorCode >= 2000 && e.errorCode < 3000) LineQualityStore.recordFailure(spec.getUrl());
                    if (!fallbackMpvToExo()) handleFatalError(e);
                }
            }
        }
    };

    public record SecondaryTrackOption(androidx.media3.common.Format format, TrackSelectionOverride selection, boolean selected) {
    }

    private record PendingPreload(PlaySpec spec, long startPositionMs) {
    }

}
