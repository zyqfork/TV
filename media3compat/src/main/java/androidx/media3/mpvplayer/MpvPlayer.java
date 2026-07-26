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
    /** Prefer zero-copy MediaCodec, then copy-back; matches mpv-android HWDECS. */
    private static final String HWDEC_HARD = "mediacodec,mediacodec-copy";
    private static final String HWDEC_SOFT = "no";
    private static final String VO_DEFAULT = "gpu";
    private static final String[] OBSERVED_DOUBLE = {"time-pos", "duration", "cache-buffering-state"};
    private static final String[] OBSERVED_FLAG = {"pause", "paused-for-cache", "eof-reached", "seekable"};
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
    private boolean fileLoaded;
    private boolean firstFrameReported;
    private int decode;
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
            attachNativeSurface(holder.getSurface());
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            MPVLib.setPropertyString("android-surface-size", width + "x" + height);
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            detachNativeSurface();
        }
    };

    private final TextureView.SurfaceTextureListener textureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                    attachNativeSurface(new Surface(texture), true);
                    MPVLib.setPropertyString("android-surface-size", width + "x" + height);
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
                    MPVLib.setPropertyString("android-surface-size", width + "x" + height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
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
        // Allow reading mpv.conf from config-dir (mpv-android does the same).
        if (config.preInitOptions.containsKey("config-dir")) {
            MPVLib.setOptionString("config", "yes");
        }
        MPVLib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1");
        MPVLib.setOptionString("ao", "audiotrack,opensles");
        MPVLib.setOptionString("audio-set-media-role", "yes");
        MPVLib.setOptionString("network-timeout", "60");
        // Direct HTTP URLs must not fall into youtube-dl; missing yt-dlp would poison errors.
        if (!config.preInitOptions.containsKey("ytdl") && !config.postInitOptions.containsKey("ytdl")) {
            MPVLib.setOptionString("ytdl", "no");
        }
        if (!config.preInitOptions.containsKey("demuxer-max-bytes")
                && !config.postInitOptions.containsKey("demuxer-max-bytes")) {
            MPVLib.setOptionString("demuxer-max-bytes", "67108864");
            MPVLib.setOptionString("demuxer-max-back-bytes", "67108864");
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
        MPVLib.setOptionString("hwdec", decode == 1 ? HWDEC_HARD : HWDEC_SOFT);
    }

    public void setDecode(int decode) {
        this.decode = decode;
        if (!released) MPVLib.setPropertyString("hwdec", decode == 1 ? HWDEC_HARD : HWDEC_SOFT);
    }

    private String getVo() {
        return config.preInitOptions.getOrDefault("vo", VO_DEFAULT);
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
        lastNativeError = null;
        fileLoaded = false;
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
        MPVLib.setPropertyBoolean("pause", true);
        MPVLib.command(new String[]{"stop"});
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
            attachNativeSurface(surface);
        } else if (output instanceof SurfaceHolder holder) {
            holder.addCallback(surfaceCallback);
            if (holder.getSurface().isValid()) attachNativeSurface(holder.getSurface());
        } else if (output instanceof SurfaceView view) {
            SurfaceHolder holder = view.getHolder();
            holder.addCallback(surfaceCallback);
            if (holder.getSurface().isValid()) attachNativeSurface(holder.getSurface());
        } else if (output instanceof TextureView view) {
            view.setSurfaceTextureListener(textureListener);
            if (view.isAvailable() && view.getSurfaceTexture() != null) {
                attachNativeSurface(new Surface(view.getSurfaceTexture()), true);
            }
        } else {
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
        attachNativeSurface(surface, false);
    }

    private void attachNativeSurface(Surface surface, boolean ownsSurface) {
        if (attachedSurface == surface && surfaceReady) {
            MPVLib.setPropertyString("force-window", "yes");
            MPVLib.setPropertyString("vo", getVo());
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
        surfaceReady = true;
        if (pendingLoadUri != null) {
            String uri = pendingLoadUri;
            pendingLoadUri = null;
            loadFile(uri);
        } else if (fileLoaded) {
            // Surface came back after a transient detach: keep demuxer, restore VO only.
            MPVLib.setPropertyString("vo", getVo());
            firstFrameReported = false;
            reportFirstFrame();
        }
    }

    private void detachNativeSurface() {
        if (attachedSurface == null) return;
        surfaceReady = false;
        // Keep demux/decode alive; only tear down the video output while surface is gone.
        MPVLib.setPropertyString("vo", "null");
        MPVLib.setPropertyString("force-window", "no");
        MPVLib.detachSurface();
        if (ownsSurface) attachedSurface.release();
        attachedSurface = null;
        ownsSurface = false;
    }

    private void clearVideoOutputInternal() {
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
                case "eof-reached" -> {
                    if (value) updateState(STATE_ENDED, false, null);
                }
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
                case "time-pos" -> positionMs = Math.max(0, (long) (value * 1000));
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
                case MPVLib.MpvEvent.VIDEO_RECONFIG, MPVLib.MpvEvent.PLAYBACK_RESTART ->
                        reportFirstFrame();
                case MPVLib.MpvEvent.END_FILE -> {
                    if (currentMediaItemIndex + 1 < playlist.size()
                            && state.repeatMode != REPEAT_MODE_ONE) {
                        currentMediaItemIndex++;
                        mediaItem = playlist.get(currentMediaItemIndex);
                        positionMs = 0;
                        handlePrepare();
                    } else if (state.playbackState != STATE_ENDED) {
                        PlaybackException error = lastNativeError == null ? null
                                : new PlaybackException(lastNativeError, null,
                                PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
                        updateState(error == null ? STATE_ENDED : STATE_IDLE, false, error);
                    }
                }
                case MPVLib.MpvEvent.SHUTDOWN ->
                        updateState(STATE_IDLE, false,
                                new PlaybackException("libmpv shut down unexpectedly", null,
                                        PlaybackException.ERROR_CODE_UNSPECIFIED));
            }
        });
    }

    private void reportFirstFrame() {
        if (firstFrameReported || !surfaceReady) return;
        firstFrameReported = true;
        state = buildState(STATE_READY, state.playWhenReady, null).buildUpon()
                .setNewlyRenderedFirstFrame(true)
                .build();
        invalidateState();
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
        // mpv: 0=fatal 10=error 20=warn. Ignore hook/script noise that is not a stream failure.
        if (level > 10 || text == null || text.isBlank()) return;
        if ("ytdl_hook".equals(prefix)) return;
        onApplicationThread(() -> lastNativeError = "mpv[" + prefix + "]: " + text.trim());
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
