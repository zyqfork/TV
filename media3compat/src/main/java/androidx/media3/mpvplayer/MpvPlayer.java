package androidx.media3.mpvplayer;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaChapter;
import androidx.media3.common.MediaEdition;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import is.xyz.mpv.MPVLib;

/**
 * Media3 player backed by the real libmpv engine.
 *
 * <p>The class translates Media3's player and video-output contracts to mpv properties and
 * commands. Video, audio and libass subtitles are decoded and rendered by libmpv.
 */
public final class MpvPlayer extends SimpleBasePlayer
        implements MPVLib.EventObserver, MPVLib.LogObserver {

    private static final long DEFAULT_SEEK_INCREMENT_MS = 10_000;
    /**
     * Use MediaCodec copy-back by default on Android TV.
     *
     * <p>The zero-copy MediaCodec path keeps vendor AImageReader/GraphicBuffer state across
     * {@code loadfile replace}. A number of TV decoders then expose the old YUV buffer as a green
     * frame, or block while releasing it during rapid episode changes. Copy-back still uses the
     * hardware decoder, but gives mpv ownership of the output frames and makes file replacement
     * independent from the previous codec surface. An explicit hwdec value in mpv.conf continues
     * to override this compatibility default.
     */
    private static final String HWDEC_HARD = "mediacodec-copy";
    private static final String HWDEC_PERFORMANCE = "mediacodec";
    private static final String HWDEC_SOFT = "no";
    private static final String VO_DEFAULT = "gpu";
    private static final String[] OBSERVED_DOUBLE = {"time-pos", "duration", "cache-buffering-state"};
    private static final String[] OBSERVED_FLAG = {"pause", "paused-for-cache", "seekable"};
    private static final String[] OBSERVED_INT = {"video-params/w", "video-params/h"};

    private final Context context;
    private final Handler applicationHandler;
    private final MpvPlayerConfig config;
    private final Commands commands;
    private final AudioManager audioManager;
    private final AudioFocusRequest audioFocusRequest;
    private final List<MediaItem> playlist = new ArrayList<>();
    private final Map<TrackGroup, List<Integer>> mpvTrackIds = new HashMap<>();
    private State state;
    @Nullable private MediaItem mediaItem;
    @Nullable private Object videoOutput;
    @Nullable private Surface attachedSurface;
    @Nullable private String pendingLoadUri;
    private boolean ownsSurface;
    private boolean surfaceReady;
    /** True after surfaceDestroyed while a file was (or is being) played; needs video-reload. */
    private boolean surfaceNeedsVideoReload;
    /** Performance embed VO failed permanently for this instance; stay on gpu + copy. */
    private boolean embedVoDisabled;
    /** How many times we already retried embed after a surface race (before copy fallback). */
    private int embedSurfaceRetries;
    private static final int EMBED_SURFACE_RETRY_LIMIT = 2;
    /** Set when mediacodec_embed reports Surface unavailable; used by end-file retry. */
    private boolean recentSurfaceFailure;
    private volatile boolean fileLoaded;
    private boolean firstFrameReported;
    private volatile boolean renderFallbackUsed;
    private boolean surfaceRecovering;
    private volatile int decode;
    /** Debounced Surface for embed: phone rotation briefly exposes a portrait buffer. */
    @Nullable private Surface settlingSurface;
    private boolean settlingOwnsSurface;
    private int settlingWidth;
    private int settlingHeight;
    private final Runnable settleSurfaceRunnable = this::commitSettledSurface;
    /** Event-driven black-screen recovery (scheduled from time-pos, not a blind timer). */
    private final Runnable blackScreenWatchdog = this::checkBlackScreen;
    private long positionMs;
    private long pendingSeekMs = C.TIME_UNSET;
    private long durationMs = C.TIME_UNSET;
    private long bufferedPositionMs;
    private int videoWidth;
    private int videoHeight;
    private int repeatMode = REPEAT_MODE_OFF;
    private long audioOffsetMs;
    private long textOffsetMs;
    private float volume = 1f;
    private PlaybackParameters playbackParameters = PlaybackParameters.DEFAULT;
    private boolean seekable = true;
    private boolean released;
    private int currentMediaItemIndex;
    private Tracks currentTracks = Tracks.EMPTY;
    private TrackSelectionParameters trackSelectionParameters;
    private List<MediaChapter> chapters = List.of();
    private List<MediaEdition> editions = List.of();
    @Nullable private String lastNativeError;

    private final SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            attachSurfaceHolder(holder);
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            // SurfaceView often delivers surfaceCreated with a 0x0 frame; mediacodec_embed then
            // fails with "Android Surface unavailable". Wait for a real size before first attach.
            // Size changes also restart the settle timer so portrait→landscape churn does not bind
            // MediaCodec to a Surface that is about to be destroyed.
            if (width > 0 && height > 0) {
                attachSurfaceHolder(holder);
            }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            cancelSurfaceSettle();
            // Keep the pending/current URI so the next valid Surface can finish the first load
            // after orientation churn (common on phones when Smoke/Activity starts).
            if (pendingLoadUri == null && mediaItem != null
                    && mediaItem.localConfiguration != null && !fileLoaded) {
                pendingLoadUri = mediaItem.localConfiguration.uri.toString();
            }
            if (fileLoaded || pendingLoadUri != null) surfaceNeedsVideoReload = true;
            detachNativeSurface();
        }
    };

    private final TextureView.SurfaceTextureListener textureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                    if (width <= 0 || height <= 0) return;
                    offerSurface(new Surface(texture), true, width, height);
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
                    if (width <= 0 || height <= 0) return;
                    if (videoOutput instanceof TextureView view
                            && view.isAvailable() && view.getSurfaceTexture() != null) {
                        offerSurface(new Surface(view.getSurfaceTexture()), true, width, height);
                    }
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                    cancelSurfaceSettle();
                    if (fileLoaded || pendingLoadUri != null) surfaceNeedsVideoReload = true;
                    detachNativeSurface();
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture texture) {
                }
            };

    private MpvPlayer(Context context, int decode, MpvPlayerConfig config) {
        super(Looper.getMainLooper());
        this.context = context.getApplicationContext();
        this.applicationHandler = new Handler(getApplicationLooper());
        this.decode = decode;
        this.config = config;
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        this.audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build())
                .setOnAudioFocusChangeListener(this::onAudioFocusChange, applicationHandler)
                .build();
        this.trackSelectionParameters = TrackSelectionParameters.getDefaults(this.context);
        this.commands = new Commands.Builder()
                .addAll(
                        COMMAND_PLAY_PAUSE,
                        COMMAND_PREPARE,
                        COMMAND_STOP,
                        COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        COMMAND_SEEK_BACK,
                        COMMAND_SEEK_FORWARD,
                        COMMAND_SET_SPEED_AND_PITCH,
                        COMMAND_SET_REPEAT_MODE,
                        COMMAND_SEEK_TO_MEDIA_ITEM,
                        COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        COMMAND_GET_CURRENT_MEDIA_ITEM,
                        COMMAND_GET_TIMELINE,
                        COMMAND_GET_MEDIA_ITEMS_METADATA,
                        COMMAND_SET_MEDIA_ITEM,
                        COMMAND_CHANGE_MEDIA_ITEMS,
                        COMMAND_GET_VOLUME,
                        COMMAND_SET_VOLUME,
                        COMMAND_SET_VIDEO_SURFACE,
                        COMMAND_GET_TRACKS,
                        COMMAND_SET_TRACK_SELECTION_PARAMETERS,
                        COMMAND_GET_AUDIO_OFFSET,
                        COMMAND_SET_AUDIO_OFFSET,
                        COMMAND_GET_TEXT_OFFSET,
                        COMMAND_SET_TEXT_OFFSET,
                        COMMAND_RELEASE)
                .build();
        this.state = buildState(STATE_IDLE, false, null);
        initializeNative();
    }

    public static boolean isAvailable() {
        return MPVLib.load();
    }

    private void initializeNative() {
        if (!MPVLib.acquireInstance()) {
            throw new IllegalStateException("libmpv is unavailable or another MPV player is active");
        }
        try {
            MPVLib.create(context);
            applyAndroidDefaults();
            applyOptions(config.preInitOptions);
            applyDecodeOption();
            MPVLib.init();
        } catch (Throwable error) {
            MPVLib.releaseInstance();
            throw error;
        }
        // After init, options must be set as properties (setOptionString is a no-op/fragile).
        applyProperties(config.postInitOptions);
        // Same as mpv-android BaseMPVView: keep VO idle until a Surface is attached.
        MPVLib.setPropertyString("force-window", "no");
        MPVLib.setPropertyString("idle", "yes");
        MPVLib.addObserver(this);
        MPVLib.addLogObserver(this);
        for (String property : OBSERVED_DOUBLE) MPVLib.observeProperty(property, MPVLib.MpvFormat.DOUBLE);
        for (String property : OBSERVED_FLAG) MPVLib.observeProperty(property, MPVLib.MpvFormat.FLAG);
        for (String property : OBSERVED_INT) MPVLib.observeProperty(property, MPVLib.MpvFormat.INT64);
    }

    private void applyAndroidDefaults() {
        // Required for vo=gpu on Android. Without these, playback is often audio-only.
        if (!config.preInitOptions.containsKey("gpu-context")) {
            MPVLib.setOptionString("gpu-context", "android");
            MPVLib.setOptionString("opengl-es", "yes");
        }
        if (!config.preInitOptions.containsKey("vo")) {
            MPVLib.setOptionString("vo", VO_DEFAULT);
        }
        MPVLib.setOptionString("force-window", "no");
        MPVLib.setOptionString("keepaspect", "no");
        // Allow reading mpv.conf from config-dir (mpv-android does the same).
        if (config.preInitOptions.containsKey("config-dir")) {
            MPVLib.setOptionString("config", "yes");
        }
        if (!config.preInitOptions.containsKey("hwdec-codecs")) {
            MPVLib.setOptionString("hwdec-codecs",
                    "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1,vc1");
        }
        if (!config.preInitOptions.containsKey("ao")) {
            MPVLib.setOptionString("ao", "audiotrack,opensles");
        }
        if (!config.preInitOptions.containsKey("audio-set-media-role")) {
            MPVLib.setOptionString("audio-set-media-role", "yes");
        }
        if (!config.preInitOptions.containsKey("network-timeout")) {
            MPVLib.setOptionString("network-timeout", "60");
        }
        // Direct HTTP URLs must not fall into youtube-dl; missing yt-dlp would poison errors.
        if (!config.preInitOptions.containsKey("ytdl") && !config.postInitOptions.containsKey("ytdl")) {
            MPVLib.setOptionString("ytdl", "no");
        }
    }

    private void onAudioFocusChange(int change) {
        if (released) return;
        if (change == AudioManager.AUDIOFOCUS_LOSS
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            setPlayWhenReady(false);
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            MPVLib.setPropertyDouble("volume", state.volume * 20.0);
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            MPVLib.setPropertyDouble("volume", state.volume * 100.0);
        }
    }

    private void applyOptions(Map<String, String> options) {
        for (Map.Entry<String, String> option : options.entrySet()) {
            MPVLib.setOptionString(option.getKey(), option.getValue());
        }
    }

    private void applyProperties(Map<String, String> options) {
        for (Map.Entry<String, String> option : options.entrySet()) {
            MPVLib.setPropertyString(option.getKey(), option.getValue());
        }
    }

    private void applyDecodeOption() {
        MPVLib.setOptionString("hwdec", getDecodeOption());
    }

    private String getDecodeOption() {
        // mediacodec_embed requires a live Android Surface; after Surface-unavailable we stay on
        // copy-back so audio-only / black-screen sessions can recover without a full rebuild.
        if (decode == 2 && !embedVoDisabled) return HWDEC_PERFORMANCE;
        if (decode == 2) return HWDEC_HARD;
        if (decode == 1) return config.preInitOptions.getOrDefault("hwdec", HWDEC_HARD);
        return HWDEC_SOFT;
    }

    public void setDecode(int decode) {
        this.decode = decode;
        renderFallbackUsed = false;
        if (decode == 2) {
            embedVoDisabled = false;
            embedSurfaceRetries = 0;
        }
        if (!released) {
            MPVLib.setPropertyString("hwdec", getDecodeOption());
            if (surfaceReady) MPVLib.setPropertyString("vo", getVo());
        }
    }

    private String getVo() {
        if (decode == 2 && !embedVoDisabled) {
            return config.preInitOptions.getOrDefault("vo", "mediacodec_embed");
        }
        String configured = config.preInitOptions.get("vo");
        if (configured != null && !"mediacodec_embed".equals(configured)) return configured;
        return VO_DEFAULT;
    }

    public int getDecode() {
        return decode;
    }

    public void setSubtitleOptions(MpvPlayerConfig subtitleConfig) {
        if (!released) applyProperties(subtitleConfig.postInitOptions);
    }

    public void addSubtitle(MediaItem.SubtitleConfiguration subtitle) {
        if (released) return;
        Uri uri = subtitle.uri;
        MPVLib.command(new String[]{"sub-add", uri.toString(), "select"});
    }

    @Override
    public boolean selectChapter(MediaChapter chapter) {
        if (released || chapter == null) return false;
        MPVLib.setPropertyInt("chapter", chapter.index);
        return true;
    }

    @Override
    public boolean selectEdition(MediaEdition edition) {
        if (released || edition == null) return false;
        MPVLib.setPropertyInt("edition", edition.index);
        return true;
    }

    @Override
    protected State getState() {
        return state;
    }

    private State buildState(int playbackState, boolean playWhenReady,
                             @Nullable PlaybackException error) {
        State.Builder builder = new State.Builder()
                .setAvailableCommands(commands)
                .setPlayWhenReady(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackState(playbackState)
                .setPlayerError(error)
                .setRepeatMode(repeatMode)
                .setPlaybackParameters(playbackParameters)
                .setVolume(volume)
                .setAudioOffsetMs(audioOffsetMs)
                .setTextOffsetMs(textOffsetMs)
                .setSeekBackIncrementMs(DEFAULT_SEEK_INCREMENT_MS)
                .setSeekForwardIncrementMs(DEFAULT_SEEK_INCREMENT_MS)
                .setVideoSize(videoWidth > 0 && videoHeight > 0
                        ? new VideoSize(videoWidth, videoHeight) : VideoSize.UNKNOWN)
                .setTrackSelectionParameters(trackSelectionParameters)
                .setCurrentMediaChapters(chapters)
                .setCurrentMediaEditions(editions)
                .setContentPositionMs(() -> positionMs)
                .setContentBufferedPositionMs(() -> Math.max(positionMs, bufferedPositionMs))
                .setTotalBufferedDurationMs(() -> Math.max(0, bufferedPositionMs - positionMs));
        if (!playlist.isEmpty()) {
            ImmutableList.Builder<MediaItemData> items = ImmutableList.builder();
            for (int i = 0; i < playlist.size(); i++) {
                MediaItem item = playlist.get(i);
                boolean current = i == currentMediaItemIndex;
                long durationUs = current && durationMs != C.TIME_UNSET
                        ? durationMs * 1000 : C.TIME_UNSET;
                items.add(new MediaItemData.Builder(item.mediaId + ":" + i)
                        .setMediaItem(item)
                        .setTracks(current ? currentTracks : Tracks.EMPTY)
                        .setIsSeekable(!current || seekable)
                        .setIsDynamic(current && durationMs == C.TIME_UNSET)
                        .setDurationUs(durationUs)
                        .build());
            }
            builder.setPlaylist(items.build()).setCurrentMediaItemIndex(currentMediaItemIndex);
        }
        return builder.build();
    }

    private void updateState(int playbackState, boolean playWhenReady,
                             @Nullable PlaybackException error) {
        state = buildState(playbackState, playWhenReady, error);
        invalidateState();
    }

    private static ListenableFuture<Void> done() {
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetMediaItems(
            List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
        playlist.clear();
        playlist.addAll(mediaItems);
        int requestedIndex = startIndex == C.INDEX_UNSET ? 0 : Math.max(0, startIndex);
        currentMediaItemIndex = mediaItems.isEmpty()
                ? 0 : Math.min(requestedIndex, mediaItems.size() - 1);
        mediaItem = mediaItems.isEmpty() ? null : mediaItems.get(currentMediaItemIndex);
        // C.TIME_UNSET must not be treated as a resume offset.
        positionMs = startPositionMs == C.TIME_UNSET ? 0 : Math.max(0, startPositionMs);
        pendingSeekMs = positionMs > 0 ? positionMs : C.TIME_UNSET;
        durationMs = C.TIME_UNSET;
        videoWidth = 0;
        videoHeight = 0;
        currentTracks = Tracks.EMPTY;
        mpvTrackIds.clear();
        chapters = List.of();
        editions = List.of();
        lastNativeError = null;
        fileLoaded = false;
        embedSurfaceRetries = 0;
        embedVoDisabled = false;
        recentSurfaceFailure = false;
        bufferedPositionMs = positionMs;
        updateState(STATE_IDLE, false, null);
        return done();
    }

    @Override
    protected ListenableFuture<?> handlePrepare() {
        if (mediaItem == null || mediaItem.localConfiguration == null) return done();
        updateState(STATE_BUFFERING, state.playWhenReady, null);
        applyHttpHeaders(mediaItem);
        String uri = mediaItem.localConfiguration.uri.toString();
        // mpv-android waits for Surface before the first loadfile; loading earlier often
        // leaves video without an output (audio-only) especially with MediaCodec hwdec.
        if (surfaceReady) {
            loadFile(uri);
        } else {
            pendingLoadUri = uri;
        }
        return done();
    }

    private void loadFile(String uri) {
        fileLoaded = false;
        firstFrameReported = false;
        renderFallbackUsed = false;
        surfaceRecovering = false;
        applicationHandler.removeCallbacks(blackScreenWatchdog);
        // A previous stream may have activated the per-file copy-back fallback.
        MPVLib.setPropertyString("hwdec", getDecodeOption());
        // Avoid loadfile options entirely: mpv 0.38+ inserted an integer index argument, and
        // KEYVALUELIST parsing differs across builds. Resume via seek after FILE_LOADED instead.
        MPVLib.command(new String[]{"loadfile", uri, "replace"});
    }

    private void applyPendingSeekAndSubtitles() {
        if (pendingSeekMs != C.TIME_UNSET && pendingSeekMs > 0) {
            MPVLib.command(new String[]{
                    "seek", Double.toString(pendingSeekMs / 1000.0), "absolute+exact"
            });
            pendingSeekMs = C.TIME_UNSET;
        }
        if (mediaItem == null || mediaItem.localConfiguration == null) return;
        for (MediaItem.SubtitleConfiguration subtitle :
                mediaItem.localConfiguration.subtitleConfigurations) {
            MPVLib.command(new String[]{"sub-add", subtitle.uri.toString(), "auto"});
        }
    }

    private void applyHttpHeaders(MediaItem item) {
        Bundle extras = item.requestMetadata.extras;
        if (extras == null || extras.isEmpty()) {
            MPVLib.setPropertyString("http-header-fields", "");
            return;
        }
        List<String> fields = new ArrayList<>();
        for (String key : extras.keySet()) {
            Object value = extras.get(key);
            if (value != null) {
                String safeKey = key.replace("\r", "").replace("\n", "");
                String safeValue = value.toString().replace("\r", "").replace("\n", "")
                        .replace("\\", "\\\\").replace(",", "\\,");
                fields.add(safeKey + ": " + safeValue);
            }
        }
        MPVLib.setPropertyString("http-header-fields", String.join(",", fields));
    }

    @Override
    protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
        if (playWhenReady && audioManager.requestAudioFocus(audioFocusRequest)
                != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return done();
        }
        if (!playWhenReady) audioManager.abandonAudioFocusRequest(audioFocusRequest);
        MPVLib.setPropertyBoolean("pause", !playWhenReady);
        updateState(state.playbackState, playWhenReady, null);
        return done();
    }

    @Override
    protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs, int seekCommand) {
        if (mediaItemIndex != currentMediaItemIndex && mediaItemIndex >= 0
                && mediaItemIndex < playlist.size()) {
            currentMediaItemIndex = mediaItemIndex;
            mediaItem = playlist.get(mediaItemIndex);
            this.positionMs = Math.max(0, positionMs);
            handlePrepare();
            return done();
        }
        this.positionMs = Math.max(0, positionMs);
        MPVLib.command(new String[]{"seek", Double.toString(this.positionMs / 1000.0), "absolute+exact"});
        state = state.buildUpon()
                .setContentPositionMs(() -> this.positionMs)
                .setPositionDiscontinuity(DISCONTINUITY_REASON_SEEK, this.positionMs)
                .build();
        invalidateState();
        return done();
    }

    @Override
    protected ListenableFuture<?> handleAddMediaItems(int index, List<MediaItem> mediaItems) {
        playlist.addAll(Math.min(index, playlist.size()), mediaItems);
        mediaItem = playlist.isEmpty() ? null : playlist.get(currentMediaItemIndex);
        updateState(state.playbackState, state.playWhenReady, state.playerError);
        return done();
    }

    @Override
    protected ListenableFuture<?> handleRemoveMediaItems(int fromIndex, int toIndex) {
        playlist.subList(fromIndex, Math.min(toIndex, playlist.size())).clear();
        currentMediaItemIndex = Math.min(currentMediaItemIndex, Math.max(0, playlist.size() - 1));
        mediaItem = playlist.isEmpty() ? null : playlist.get(currentMediaItemIndex);
        updateState(playlist.isEmpty() ? STATE_IDLE : state.playbackState,
                !playlist.isEmpty() && state.playWhenReady, state.playerError);
        return done();
    }

    @Override
    protected ListenableFuture<?> handleMoveMediaItems(int fromIndex, int toIndex, int newIndex) {
        List<MediaItem> moved = new ArrayList<>(playlist.subList(fromIndex, toIndex));
        playlist.subList(fromIndex, toIndex).clear();
        playlist.addAll(Math.min(newIndex, playlist.size()), moved);
        currentMediaItemIndex = Math.min(currentMediaItemIndex, playlist.size() - 1);
        mediaItem = playlist.get(currentMediaItemIndex);
        updateState(state.playbackState, state.playWhenReady, state.playerError);
        return done();
    }

    @Override
    protected ListenableFuture<?> handleReplaceMediaItems(
            int fromIndex, int toIndex, List<MediaItem> mediaItems) {
        playlist.subList(fromIndex, Math.min(toIndex, playlist.size())).clear();
        playlist.addAll(Math.min(fromIndex, playlist.size()), mediaItems);
        currentMediaItemIndex = Math.min(currentMediaItemIndex, Math.max(0, playlist.size() - 1));
        mediaItem = playlist.isEmpty() ? null : playlist.get(currentMediaItemIndex);
        updateState(state.playbackState, state.playWhenReady, state.playerError);
        return done();
    }

    @Override
    protected ListenableFuture<?> handleSetPlaybackParameters(PlaybackParameters parameters) {
        MPVLib.setPropertyDouble("speed", parameters.speed);
        playbackParameters = parameters;
        state = state.buildUpon().setPlaybackParameters(parameters).build();
        invalidateState();
        return done();
    }

    @Override
    protected ListenableFuture<?> handleSetRepeatMode(int repeatMode) {
        MPVLib.setPropertyString("loop-file", repeatMode == REPEAT_MODE_OFF ? "no" : "inf");
        this.repeatMode = repeatMode;
        state = state.buildUpon().setRepeatMode(repeatMode).build();
        invalidateState();
        return done();
    }

    @Override
    protected ListenableFuture<?> handleSetVolume(float volume) {
        MPVLib.setPropertyDouble("volume", volume * 100.0);
        this.volume = volume;
        state = state.buildUpon().setVolume(volume).build();
        invalidateState();
        return done();
    }

    @Override
    protected ListenableFuture<?> handleSetAudioOffsetMs(long offsetMs) {
        MPVLib.setPropertyDouble("audio-delay", offsetMs / 1000.0);
        audioOffsetMs = offsetMs;
        state = state.buildUpon().setAudioOffsetMs(offsetMs).build();
        invalidateState();
        return done();
    }

    @Override
    protected ListenableFuture<?> handleSetTextOffsetMs(long offsetMs) {
        MPVLib.setPropertyDouble("sub-delay", offsetMs / 1000.0);
        textOffsetMs = offsetMs;
        state = state.buildUpon().setTextOffsetMs(offsetMs).build();
        invalidateState();
        return done();
    }

    @Override
    protected ListenableFuture<?> handleStop() {
        pendingLoadUri = null;
        fileLoaded = false;
        applicationHandler.removeCallbacks(blackScreenWatchdog);
        cancelSurfaceSettle();
        MPVLib.setPropertyBoolean("pause", true);
        MPVLib.command(new String[]{"stop"});
        // stop alone keeps MediaCodec hwdec allocated on many SoCs (Rockchip/Amlogic). Tear
        // down VO/hwdec while the Android surface is still valid; loadFile/attach restore them.
        if (surfaceReady) MPVLib.setPropertyString("vo", "null");
        MPVLib.setPropertyString("hwdec", "no");
        positionMs = 0;
        updateState(STATE_IDLE, false, null);
        audioManager.abandonAudioFocusRequest(audioFocusRequest);
        return done();
    }

    @Override
    protected ListenableFuture<?> handleSetTrackSelectionParameters(
            TrackSelectionParameters parameters) {
        trackSelectionParameters = parameters;
        applyTrackSelection(C.TRACK_TYPE_VIDEO, "vid", parameters);
        applyTrackSelection(C.TRACK_TYPE_AUDIO, "aid", parameters);
        applyTrackSelection(C.TRACK_TYPE_TEXT, "sid", parameters);
        state = state.buildUpon().setTrackSelectionParameters(parameters).build();
        invalidateState();
        return done();
    }

    private void applyTrackSelection(int type, String property,
                                     TrackSelectionParameters parameters) {
        if (parameters.disabledTrackTypes.contains(type)) {
            MPVLib.setPropertyString(property, "no");
            return;
        }
        for (TrackSelectionOverride override : parameters.overrides.values()) {
            if (override.getType() != type || override.trackIndices.isEmpty()) continue;
            List<Integer> ids = mpvTrackIds.get(override.mediaTrackGroup);
            int index = override.trackIndices.get(0);
            if (ids != null && index >= 0 && index < ids.size()) {
                MPVLib.setPropertyInt(property, ids.get(index));
                return;
            }
        }
        MPVLib.setPropertyString(property, "auto");
    }

    @Override
    protected ListenableFuture<?> handleSetVideoOutput(Object output) {
        // Keep the current native surface alive until the replacement is ready. PlayerView may
        // replace SurfaceView/SurfaceHolder objects while playback is running.
        unregisterVideoOutputCallbacks();
        videoOutput = output;
        if (output instanceof Surface surface) {
            offerSurface(surface, false, 0, 0);
        } else if (output instanceof SurfaceHolder holder) {
            holder.addCallback(surfaceCallback);
            if (holder.getSurface().isValid()) attachSurfaceHolder(holder);
        } else if (output instanceof SurfaceView view) {
            SurfaceHolder holder = view.getHolder();
            holder.addCallback(surfaceCallback);
            if (holder.getSurface().isValid()) attachSurfaceHolder(holder);
        } else if (output instanceof TextureView view) {
            view.setSurfaceTextureListener(textureListener);
            if (view.isAvailable() && view.getSurfaceTexture() != null
                    && view.getWidth() > 0 && view.getHeight() > 0) {
                offerSurface(new Surface(view.getSurfaceTexture()), true,
                        view.getWidth(), view.getHeight());
            }
        } else {
            cancelSurfaceSettle();
            detachNativeSurface();
        }
        return done();
    }

    @Override
    protected ListenableFuture<?> handleClearVideoOutput(@Nullable Object output) {
        if (output == null || output == videoOutput) clearVideoOutputInternal();
        return done();
    }

    private void attachNativeSurface(Surface surface) {
        attachNativeSurface(surface, false, 0, 0);
    }

    private void attachSurfaceHolder(SurfaceHolder holder) {
        // PlayerView can dynamically create its SurfaceView before assigning the player. In that
        // case SurfaceHolder.Callback is registered after surfaceChanged() already ran. libmpv's
        // Android GPU context still needs the existing buffer size or it can present one frame and
        // then leave that frame frozen while audio continues.
        if (!holder.getSurface().isValid()) return;
        int width = holder.getSurfaceFrame().width();
        int height = holder.getSurfaceFrame().height();
        if (width <= 0 || height <= 0) return;
        offerSurface(holder.getSurface(), false, width, height);
    }

    /**
     * mediacodec_embed binds MediaCodec directly to the ANativeWindow. A Surface that is about to
     * be destroyed by orientation change (common: portrait buffer then landscape) yields
     * "Android Surface unavailable". Debounce embed attaches and reject portrait-sized buffers
     * so we keep zero-copy instead of falling back to CPU-costly mediacodec-copy.
     */
    private boolean needsSurfaceSettle() {
        return decode == 2 && !embedVoDisabled;
    }

    /**
     * Reject only a Surface that disagrees with the current UI orientation. That filters the phone
     * race where a landscape Activity briefly gets a portrait buffer (or vice versa) without
     * blocking real portrait VOD playback.
     */
    private boolean isEmbedSurfaceAcceptable(int width, int height) {
        if (width <= 0 || height <= 0) return false;
        int orientation = context.getResources().getConfiguration().orientation;
        if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            return width >= height;
        }
        if (orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            return height >= width;
        }
        return true;
    }

    private void offerSurface(Surface surface, boolean ownsSurface, int width, int height) {
        if (needsSurfaceSettle()) {
            // Skip a Surface whose aspect disagrees with the current UI orientation (startup race).
            if (!isEmbedSurfaceAcceptable(width, height)) {
                if (ownsSurface && surface != attachedSurface && surface != settlingSurface) {
                    surface.release();
                }
                return;
            }
            queueSurfaceSettle(surface, ownsSurface, width, height);
            return;
        }
        cancelSurfaceSettle();
        attachNativeSurface(surface, ownsSurface, width, height);
    }

    private void queueSurfaceSettle(Surface surface, boolean ownsSurface, int width, int height) {
        if (settlingSurface != null && settlingSurface != surface && settlingOwnsSurface) {
            settlingSurface.release();
        }
        settlingSurface = surface;
        settlingOwnsSurface = ownsSurface;
        settlingWidth = width;
        settlingHeight = height;
        applicationHandler.removeCallbacks(settleSurfaceRunnable);
        // Extra settle after landscape appears: rotation can still replace the buffer once more.
        applicationHandler.postDelayed(settleSurfaceRunnable, 120);
    }

    private void cancelSurfaceSettle() {
        applicationHandler.removeCallbacks(settleSurfaceRunnable);
        if (settlingSurface != null && settlingOwnsSurface
                && settlingSurface != attachedSurface) {
            settlingSurface.release();
        }
        settlingSurface = null;
        settlingOwnsSurface = false;
        settlingWidth = 0;
        settlingHeight = 0;
    }

    private void commitSettledSurface() {
        if (released || settlingSurface == null) return;
        Surface surface = settlingSurface;
        boolean owns = settlingOwnsSurface;
        int width = settlingWidth;
        int height = settlingHeight;
        settlingSurface = null;
        settlingOwnsSurface = false;
        if (!surface.isValid()) {
            if (owns) surface.release();
            return;
        }
        // Size may have changed again during the delay; refuse portrait / 0x0.
        if (needsSurfaceSettle() && !isEmbedSurfaceAcceptable(width, height)) {
            if (owns) surface.release();
            return;
        }
        attachNativeSurface(surface, owns, width, height);
    }

    private void attachNativeSurface(Surface surface, boolean ownsSurface) {
        attachNativeSurface(surface, ownsSurface, 0, 0);
    }

    private void attachNativeSurface(Surface surface, boolean ownsSurface, int width, int height) {
        if (attachedSurface == surface && surfaceReady) {
            MPVLib.setPropertyString("force-window", "yes");
            MPVLib.setPropertyString("vo", getVo());
            if (width > 0 && height > 0) {
                MPVLib.setPropertyString("android-surface-size", width + "x" + height);
            }
            if (surfaceNeedsVideoReload && fileLoaded) reloadVideoAfterSurface();
            return;
        }
        Surface oldSurface = attachedSurface;
        boolean releaseOldSurface = this.ownsSurface;
        attachedSurface = surface;
        this.ownsSurface = ownsSurface;
        if (oldSurface == null) {
            MPVLib.attachSurface(surface);
        } else {
            try {
                // FongMi's bridge changes the render target without destroying the active VO.
                MPVLib.replaceSurface(surface);
            } catch (UnsatisfiedLinkError unsupportedByOldBridge) {
                // Keep x86/debug builds based on upstream mpv-android compatible.
                MPVLib.detachSurface();
                MPVLib.attachSurface(surface);
            }
            if (releaseOldSurface) oldSurface.release();
        }
        // Match mpv-android BaseMPVView surfaceCreated().
        MPVLib.setPropertyString("force-window", "yes");
        MPVLib.setPropertyString("vo", getVo());
        if (width > 0 && height > 0) {
            MPVLib.setPropertyString("android-surface-size", width + "x" + height);
        }
        surfaceReady = true;
        if (pendingLoadUri != null) {
            String uri = pendingLoadUri;
            pendingLoadUri = null;
            loadFile(uri);
        } else if (fileLoaded) {
            // Surface came back after a transient detach: restore VO and reload video decoder.
            // mediacodec_embed is poisoned once its window disappears mid-stream (black + audio).
            reloadVideoAfterSurface();
        }
    }

    private void reloadVideoAfterSurface() {
        surfaceNeedsVideoReload = false;
        firstFrameReported = false;
        MPVLib.setPropertyString("hwdec", getDecodeOption());
        MPVLib.setPropertyString("vo", getVo());
        MPVLib.command(new String[]{"video-reload"});
    }

    private void detachNativeSurface() {
        if (attachedSurface == null) return;
        surfaceReady = false;
        // Match the reference player: detach only the Android window. Changing vo to null tears
        // down the GPU pipeline and some live decoders never reconnect it when the Surface returns.
        MPVLib.detachSurface();
        if (ownsSurface) attachedSurface.release();
        attachedSurface = null;
        ownsSurface = false;
    }

    private void clearVideoOutputInternal() {
        cancelSurfaceSettle();
        unregisterVideoOutputCallbacks();
        detachNativeSurface();
        videoOutput = null;
    }

    private void unregisterVideoOutputCallbacks() {
        if (videoOutput instanceof SurfaceHolder holder) {
            holder.removeCallback(surfaceCallback);
        } else if (videoOutput instanceof SurfaceView view) {
            view.getHolder().removeCallback(surfaceCallback);
        } else if (videoOutput instanceof TextureView view
                && view.getSurfaceTextureListener() == textureListener) {
            view.setSurfaceTextureListener(null);
        }
    }

    @Override
    protected ListenableFuture<?> handleRelease() {
        if (released) return done();
        released = true;
        applicationHandler.removeCallbacks(blackScreenWatchdog);
        cancelSurfaceSettle();
        MPVLib.removeObserver(this);
        MPVLib.removeLogObserver(this);
        audioManager.abandonAudioFocusRequest(audioFocusRequest);
        pendingLoadUri = null;
        fileLoaded = false;
        try {
            // Do not rely on Service/Activity ordering: silence native audio synchronously before
            // destroying the handle, including task-removal and application shutdown paths.
            MPVLib.setPropertyBoolean("pause", true);
            MPVLib.command(new String[]{"stop"});
            // stop is asynchronous. Disable the VO while its Android window is still valid so a
            // late idle/video-reconfig event cannot recreate gpu-next after detachSurface().
            if (surfaceReady) MPVLib.setPropertyString("vo", "null");
            clearVideoOutputInternal();
            MPVLib.destroy();
        } finally {
            MPVLib.releaseInstance();
        }
        return done();
    }

    private void onApplicationThread(Runnable runnable) {
        if (Looper.myLooper() == getApplicationLooper()) runnable.run();
        else applicationHandler.post(() -> {
            if (!released) runnable.run();
        });
    }

    @Override
    public void eventProperty(String property) {
    }

    @Override
    public void eventProperty(String property, long value) {
        onApplicationThread(() -> {
            if ("video-params/w".equals(property)) videoWidth = (int) value;
            if ("video-params/h".equals(property)) videoHeight = (int) value;
            updateState(state.playbackState, state.playWhenReady, state.playerError);
        });
    }

    @Override
    public void eventProperty(String property, boolean value) {
        onApplicationThread(() -> {
            switch (property) {
                case "pause" -> updateState(state.playbackState, !value, state.playerError);
                case "paused-for-cache" -> updateState(value ? STATE_BUFFERING : STATE_READY,
                        state.playWhenReady, state.playerError);
                case "seekable" -> {
                    seekable = value;
                    updateState(state.playbackState, state.playWhenReady, state.playerError);
                }
            }
        });
    }

    @Override
    public void eventProperty(String property, String value) {
    }

    @Override
    public void eventProperty(String property, double value) {
        onApplicationThread(() -> {
            switch (property) {
                case "time-pos" -> {
                    positionMs = Math.max(0, (long) (value * 1000));
                    // Event-driven: only arm recovery after demux/audio actually advances without a
                    // first frame. Avoids a blind 2.5s timer on every healthy open.
                    if (!firstFrameReported && fileLoaded && surfaceReady && positionMs >= 300) {
                        applicationHandler.removeCallbacks(blackScreenWatchdog);
                        applicationHandler.postDelayed(blackScreenWatchdog, 500);
                    }
                }
                case "duration" -> durationMs = value > 0 ? (long) (value * 1000) : C.TIME_UNSET;
                case "cache-buffering-state" -> {
                    if (durationMs != C.TIME_UNSET) {
                        bufferedPositionMs = Math.min(durationMs,
                                positionMs + (long) ((durationMs - positionMs) * value / 100.0));
                    }
                }
            }
            updateState(state.playbackState, state.playWhenReady, state.playerError);
        });
    }

    @Override
    public void event(int eventId) {
        onApplicationThread(() -> {
            switch (eventId) {
                case MPVLib.MpvEvent.START_FILE ->
                        updateState(STATE_BUFFERING, state.playWhenReady, null);
                case MPVLib.MpvEvent.FILE_LOADED -> {
                    fileLoaded = true;
                    applyPendingSeekAndSubtitles();
                    refreshTracks();
                    refreshChaptersAndEditions();
                    lastNativeError = null;
                    updateState(STATE_READY, state.playWhenReady, null);
                }
                // VIDEO_RECONFIG is also emitted by force-window before loadfile and therefore
                // does not prove that a decoded frame reached the Android Surface.
                case MPVLib.MpvEvent.PLAYBACK_RESTART ->
                        reportFirstFrame();
                // Current native builds deliver END_FILE through eventEndFile(), including its
                // reason and error. Retain this only for compatibility with an older bridge.
                case MPVLib.MpvEvent.END_FILE -> handleEndFile(
                        lastNativeError == null
                                ? MPVLib.MpvEndFileReason.EOF
                                : MPVLib.MpvEndFileReason.ERROR,
                        0, lastNativeError);
                case MPVLib.MpvEvent.SHUTDOWN ->
                        updateState(STATE_IDLE, false,
                                new PlaybackException("libmpv shut down unexpectedly", null,
                                        PlaybackException.ERROR_CODE_UNSPECIFIED));
            }
        });
    }

    @Override
    public void eventEndFile(int reason, int error, String fileError) {
        onApplicationThread(() -> handleEndFile(reason, error, fileError));
    }

    private void handleEndFile(int reason, int error, @Nullable String fileError) {
        if (reason == MPVLib.MpvEndFileReason.STOP
                || reason == MPVLib.MpvEndFileReason.QUIT
                || reason == MPVLib.MpvEndFileReason.REDIRECT) {
            return;
        }
        if (reason != MPVLib.MpvEndFileReason.EOF || error < 0) {
            String detail = fileError;
            if (detail == null || detail.isBlank()) detail = lastNativeError;
            if (detail == null || detail.isBlank()) {
                detail = error < 0 ? "mpv failed to play media (" + error + ")"
                        : "mpv ended media without reaching EOF";
            }
            // First-load surface races (common on phones) must not stick on a black Activity:
            // disable embed VO and reload once with copy-back instead of surfacing a fatal error.
            if (maybeRetryAfterSurfaceFailure(detail)) return;
            updateState(STATE_IDLE, false, new PlaybackException(detail, null,
                    mapMpvIoErrorCode(detail)));
            return;
        }
        if (currentMediaItemIndex + 1 < playlist.size()
                && state.repeatMode != REPEAT_MODE_ONE) {
            currentMediaItemIndex++;
            mediaItem = playlist.get(currentMediaItemIndex);
            positionMs = 0;
            handlePrepare();
        } else if (state.playbackState != STATE_ENDED) {
            updateState(STATE_ENDED, false, null);
        }
    }

    private boolean maybeRetryAfterSurfaceFailure(String detail) {
        if (released || mediaItem == null || mediaItem.localConfiguration == null) return false;
        if (decode != 2) return false;
        String d = detail == null ? "" : detail.toLowerCase(Locale.US);
        boolean worthRetry = recentSurfaceFailure
                || d.contains("surface") || d.contains("no audio or video")
                || d.contains("mediacodec_embed") || d.contains("hwdevice")
                || d.contains("loading failed") || d.isBlank();
        if (!worthRetry) return false;
        recentSurfaceFailure = false;
        surfaceNeedsVideoReload = true;
        firstFrameReported = false;
        fileLoaded = false;
        String uri = mediaItem.localConfiguration.uri.toString();
        // Prefer staying on zero-copy embed: drop the poisoned window and wait for a landscape
        // Surface. Only after repeated failures fall back to mediacodec-copy (higher CPU).
        if (!embedVoDisabled && embedSurfaceRetries < EMBED_SURFACE_RETRY_LIMIT) {
            embedSurfaceRetries++;
            if (surfaceReady) detachNativeSurface();
            pendingLoadUri = uri;
            updateState(STATE_BUFFERING, state.playWhenReady, null);
            return true;
        }
        if (embedVoDisabled) return false;
        embedVoDisabled = true;
        if (surfaceReady) {
            MPVLib.setPropertyString("vo", "null");
            MPVLib.setPropertyString("hwdec", getDecodeOption());
            MPVLib.setPropertyString("vo", getVo());
            loadFile(uri);
            updateState(STATE_BUFFERING, state.playWhenReady, null);
            return true;
        }
        pendingLoadUri = uri;
        updateState(STATE_BUFFERING, state.playWhenReady, null);
        return true;
    }

    private static int mapMpvIoErrorCode(@Nullable String detail) {
        if (detail == null || detail.isBlank()) return PlaybackException.ERROR_CODE_IO_UNSPECIFIED;
        String d = detail.toLowerCase(Locale.US);
        if (d.contains("surface unavailable") || d.contains("missing surface")
                || d.contains("no audio or video") || d.contains("mediacodec_embed")) {
            return PlaybackException.ERROR_CODE_DECODING_FAILED;
        }
        if (d.contains("http error") || d.contains("403") || d.contains("404") || d.contains("503")
                || d.contains("502") || d.contains("500")) {
            return PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS;
        }
        if (d.contains("timeout") || d.contains("timed out")) {
            return PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT;
        }
        if (d.contains("unsupported") || d.contains("no demuxer") || d.contains("unrecognized")) {
            return PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED;
        }
        if (d.contains("failed to open") || d.contains("connection") || d.contains("network")
                || d.contains("loading failed")) {
            return PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED;
        }
        return PlaybackException.ERROR_CODE_IO_UNSPECIFIED;
    }

    private void scheduleProgressBlackScreenCheck() {
        applicationHandler.removeCallbacks(blackScreenWatchdog);
        applicationHandler.postDelayed(blackScreenWatchdog, 500);
    }

    private void checkBlackScreen() {
        if (released || firstFrameReported || !fileLoaded || !surfaceReady) return;
        // Demuxer/audio advanced but no frame reached the Android window → classic black screen.
        if (positionMs < 300) return;
        if (decode == 2 && !embedVoDisabled) {
            recoverVideoOutput("progress without first frame");
            return;
        }
        // Copy-back / soft path still black: escalate so PlayerManager can toggle decode / Exo.
        updateState(STATE_IDLE, false, new PlaybackException(
                "video output stuck without first frame", null,
                PlaybackException.ERROR_CODE_DECODING_FAILED));
    }

    private void recoverVideoOutput(String reason) {
        if (released || surfaceRecovering) return;
        surfaceRecovering = true;
        firstFrameReported = false;
        lastNativeError = reason;
        boolean wasEmbed = decode == 2 && !embedVoDisabled;
        if (wasEmbed && embedSurfaceRetries < EMBED_SURFACE_RETRY_LIMIT) {
            // Keep zero-copy: drop poisoned Surface binding and wait for a landscape window.
            embedSurfaceRetries++;
            if (surfaceReady) detachNativeSurface();
            surfaceNeedsVideoReload = true;
            if (pendingLoadUri == null && mediaItem != null
                    && mediaItem.localConfiguration != null) {
                pendingLoadUri = mediaItem.localConfiguration.uri.toString();
            }
            fileLoaded = false;
            surfaceRecovering = false;
            updateState(STATE_BUFFERING, state.playWhenReady, null);
            return;
        }
        // Exhausted embed retries — copy-back is the last resort (higher CPU).
        if (wasEmbed) embedVoDisabled = true;
        if (!surfaceReady) {
            surfaceNeedsVideoReload = true;
            if (pendingLoadUri == null && mediaItem != null
                    && mediaItem.localConfiguration != null) {
                pendingLoadUri = mediaItem.localConfiguration.uri.toString();
            }
            surfaceRecovering = false;
            updateState(STATE_BUFFERING, state.playWhenReady, null);
            applicationHandler.postDelayed(() -> {
                if (released || firstFrameReported || surfaceReady) return;
                updateState(STATE_IDLE, false, new PlaybackException(reason, null,
                        PlaybackException.ERROR_CODE_DECODING_FAILED));
            }, 3_000);
            return;
        }
        MPVLib.setPropertyString("vo", "null");
        MPVLib.setPropertyString("hwdec", getDecodeOption());
        MPVLib.setPropertyString("vo", getVo());
        if (fileLoaded) {
            MPVLib.command(new String[]{"video-reload"});
            scheduleProgressBlackScreenCheck();
            surfaceRecovering = false;
            return;
        }
        if (pendingLoadUri == null && mediaItem != null && mediaItem.localConfiguration != null) {
            pendingLoadUri = mediaItem.localConfiguration.uri.toString();
        }
        if (pendingLoadUri != null) {
            String uri = pendingLoadUri;
            pendingLoadUri = null;
            loadFile(uri);
            updateState(STATE_BUFFERING, state.playWhenReady, null);
        }
        surfaceRecovering = false;
    }

    private void reportFirstFrame() {
        if (firstFrameReported || !surfaceReady || !fileLoaded
                || videoWidth <= 0 || videoHeight <= 0) return;
        firstFrameReported = true;
        applicationHandler.removeCallbacks(blackScreenWatchdog);
        state = buildState(STATE_READY, state.playWhenReady, null).buildUpon()
                .setNewlyRenderedFirstFrame(true)
                .build();
        invalidateState();
        // newlyRenderedFirstFrame is an edge event. Do not leave it in the backing state or
        // later unrelated invalidations will notify PlayerView/listeners repeatedly.
        state = state.buildUpon().setNewlyRenderedFirstFrame(false).build();
    }

    private void refreshTracks() {
        Integer count = MPVLib.getPropertyInt("track-list/count");
        if (count == null || count <= 0) {
            currentTracks = Tracks.EMPTY;
            mpvTrackIds.clear();
            return;
        }
        Map<Integer, List<Format>> formats = new LinkedHashMap<>();
        Map<Integer, List<Integer>> ids = new LinkedHashMap<>();
        Map<Integer, List<Boolean>> selected = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String prefix = "track-list/" + i + "/";
            String typeName = MPVLib.getPropertyString(prefix + "type");
            int type = switch (typeName == null ? "" : typeName) {
                case "video" -> C.TRACK_TYPE_VIDEO;
                case "audio" -> C.TRACK_TYPE_AUDIO;
                case "sub" -> C.TRACK_TYPE_TEXT;
                default -> C.TRACK_TYPE_UNKNOWN;
            };
            if (type == C.TRACK_TYPE_UNKNOWN) continue;
            Integer id = MPVLib.getPropertyInt(prefix + "id");
            if (id == null) continue;
            Format.Builder format = new Format.Builder()
                    .setId(Integer.toString(id))
                    .setLabel(MPVLib.getPropertyString(prefix + "title"))
                    .setLanguage(MPVLib.getPropertyString(prefix + "lang"))
                    .setCodecs(MPVLib.getPropertyString(prefix + "codec"));
            Integer width = MPVLib.getPropertyInt(prefix + "demux-w");
            Integer height = MPVLib.getPropertyInt(prefix + "demux-h");
            Integer channels = MPVLib.getPropertyInt(prefix + "demux-channel-count");
            Integer sampleRate = MPVLib.getPropertyInt(prefix + "demux-samplerate");
            if (width != null) format.setWidth(width);
            if (height != null) format.setHeight(height);
            if (channels != null) format.setChannelCount(channels);
            if (sampleRate != null) format.setSampleRate(sampleRate);
            formats.computeIfAbsent(type, ignored -> new ArrayList<>()).add(format.build());
            ids.computeIfAbsent(type, ignored -> new ArrayList<>()).add(id);
            selected.computeIfAbsent(type, ignored -> new ArrayList<>())
                    .add(Boolean.TRUE.equals(MPVLib.getPropertyBoolean(prefix + "selected")));
        }
        List<Tracks.Group> groups = new ArrayList<>();
        mpvTrackIds.clear();
        for (Map.Entry<Integer, List<Format>> entry : formats.entrySet()) {
            int type = entry.getKey();
            TrackGroup group = new TrackGroup("mpv-" + type,
                    entry.getValue().toArray(Format[]::new));
            int[] support = new int[group.length];
            boolean[] selection = new boolean[group.length];
            for (int i = 0; i < group.length; i++) {
                support[i] = C.FORMAT_HANDLED;
                selection[i] = selected.get(type).get(i);
            }
            groups.add(new Tracks.Group(group, false, support, selection));
            mpvTrackIds.put(group, ids.get(type));
        }
        currentTracks = new Tracks(groups);
    }

    private void refreshChaptersAndEditions() {
        Integer chapterCount = MPVLib.getPropertyInt("chapter-list/count");
        List<MediaChapter> refreshedChapters = new ArrayList<>();
        int selectedChapter = valueOr(MPVLib.getPropertyInt("chapter"), -1);
        for (int i = 0; i < valueOr(chapterCount, 0); i++) {
            Double time = MPVLib.getPropertyDouble("chapter-list/" + i + "/time");
            String title = MPVLib.getPropertyString("chapter-list/" + i + "/title");
            refreshedChapters.add(MediaChapter.chapter(i,
                    time == null ? 0 : (long) (time * 1_000_000),
                    title == null ? Integer.toString(i + 1) : title, i == selectedChapter));
        }
        chapters = List.copyOf(refreshedChapters);

        Integer editionCount = MPVLib.getPropertyInt("edition-list/count");
        List<MediaEdition> refreshedEditions = new ArrayList<>();
        int selectedEdition = valueOr(MPVLib.getPropertyInt("edition"), -1);
        for (int i = 0; i < valueOr(editionCount, 0); i++) {
            Integer id = MPVLib.getPropertyInt("edition-list/" + i + "/id");
            String title = MPVLib.getPropertyString("edition-list/" + i + "/title");
            int editionId = id == null ? i : id;
            refreshedEditions.add(MediaEdition.edition(editionId, C.TIME_UNSET,
                    title == null ? Integer.toString(i + 1) : title,
                    editionId == selectedEdition));
        }
        editions = List.copyOf(refreshedEditions);
    }

    private static int valueOr(@Nullable Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    @Override
    public void logMessage(String prefix, int level, String text) {
        // libmpv levels 10/20/30 are fatal/error/warn. This matches the reference player's
        // diagnostic capture and lets legacy bridges distinguish load failure from natural EOF.
        if (level > 30 || text == null || text.isBlank()) return;
        if ("ytdl_hook".equals(prefix)) return;
        String trimmed = text.trim();
        boolean surfaceFail = trimmed.contains("Android Surface unavailable")
                || trimmed.contains("Missing surface")
                || trimmed.contains("Could not create EGL surface")
                || (prefix != null && prefix.contains("mediacodec_embed")
                && (trimmed.contains("Surface") || trimmed.contains("surface")));
        if (surfaceFail) {
            onApplicationThread(() -> {
                recentSurfaceFailure = true;
                recoverVideoOutput(trimmed);
            });
            return;
        }
        if (!renderFallbackUsed && fileLoaded && surfaceReady
                && prefix != null && prefix.startsWith("vo/gpu")
                && text.contains("OpenGL error")) {
            onApplicationThread(this::fallbackRendering);
            return;
        }
        onApplicationThread(() -> lastNativeError = "mpv[" + prefix + "]: " + trimmed);
    }

    private void fallbackRendering() {
        // Player replacement and Activity teardown detach the Android window before every
        // asynchronous native log has drained. Rebuilding gpu-next without a live window turns a
        // recoverable stream/HTTP error into "Missing surface pointer" and can poison the next
        // MPV instance.
        if (released || renderFallbackUsed || !fileLoaded || !surfaceReady) return;
        renderFallbackUsed = true;
        firstFrameReported = false;
        lastNativeError = null;
        MPVLib.command(new String[]{"apply-profile", "fast"});
        // A direct MediaCodec presentation error can leave the existing EGL/VO state poisoned.
        // Recreate it before reloading the decoder; changing hwdec alone still produces black.
        if (decode == 2) embedVoDisabled = true;
        String activeHwdec = MPVLib.getPropertyString("hwdec");
        MPVLib.setPropertyString("vo", "null");
        if ((decode == 1 || embedVoDisabled) && activeHwdec != null && !"no".equals(activeHwdec)) {
            MPVLib.setPropertyString("hwdec", HWDEC_HARD);
        }
        MPVLib.setPropertyString("vo", getVo());
        // Reload only the video decoder. Demuxing, audio and the current live position continue.
        MPVLib.command(new String[]{"video-reload"});
        scheduleProgressBlackScreenCheck();
    }

    public static final class Builder {

        private final Context context;
        private int decode;
        @Nullable private MpvPlayerConfig config;

        public Builder(Context context) {
            this.context = context.getApplicationContext();
        }

        public Builder setDecode(int decode) {
            this.decode = decode;
            return this;
        }

        public Builder setConfig(MpvPlayerConfig config) {
            this.config = config;
            return this;
        }

        public MpvPlayer build() {
            MpvPlayerConfig resolved =
                    config == null ? new MpvPlayerConfig.Builder().build() : config;
            return new MpvPlayer(context, decode, resolved);
        }
    }
}
