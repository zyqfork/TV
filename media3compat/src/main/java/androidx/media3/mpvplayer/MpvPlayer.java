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
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

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
import androidx.media3.common.text.CueGroup;

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
 * commands. Hard decode selects zero-copy only for eligible subtitle-free live sessions;
 * subtitles and video effects use GPU/libass output without changing the saved decode choice.
 */
public final class MpvPlayer extends SimpleBasePlayer
        implements MPVLib.EventObserver, MPVLib.LogObserver {

    private static final long DEFAULT_SEEK_INCREMENT_MS = 10_000;
    /**
     * Use MediaCodec's Surface/AHardwareBuffer path, not CPU copy-back. The pinned mpv GPU
     * mapper samples an external RGB0 texture (8-bit output even for 10-bit input); embed
     * bypasses that mapper. Copy-back is a separate, explicit hwdec and has produced green
     * frames on multiple Android TV SoCs, but no Rockchip-specific root cause is established.
     * An explicit hwdec in mpv.conf is respected in the GPU hard mode; a conflicting override
     * disqualifies automatic embed rather than being silently overwritten.
     */
    private static final String HWDEC_HARD = "mediacodec";
    private static final String HWDEC_SOFT = "no";
    private static final String VO_DEFAULT = "gpu";
    private static final String[] OBSERVED_DOUBLE = {"time-pos", "duration", "demuxer-cache-time", "video-frame-info/pts"};
    private static final String[] OBSERVED_FLAG = {"pause", "paused-for-cache", "seekable"};
    private static final String[] OBSERVED_INT = {"video-params/w", "video-params/h", "video-params/crop-w", "video-params/crop-h", "video-out-params/w", "video-out-params/h"};
    private static final long END_FILE_ERROR_DEBOUNCE_MS = 500;

    private final Context context;
    private final Handler applicationHandler;
    private final MpvPlayerConfig config;
    private final boolean live;
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
    /** Performance embed VO failed permanently for this instance; stay on direct gpu output. */
    private boolean embedVoDisabled;
    /** Per-file latch: subtitles need GPU composition, not CPU copy-back or a decode-mode change. */
    private boolean subtitleGpuRequired;
    /** How many times we already retried embed after a surface race (before gpu fallback). */
    private int embedSurfaceRetries;
    private static final int EMBED_SURFACE_RETRY_LIMIT = 2;
    /** Set when mediacodec_embed reports Surface unavailable; used by end-file retry. */
    private boolean recentSurfaceFailure;
    private volatile boolean fileLoaded;
    private boolean firstFrameReported;
    private boolean playbackRestartSeen;
    private final Runnable metadataRefresh = () -> {
        if (this.released || !fileLoaded) return;
        refreshTracks();
        refreshChaptersAndEditions();
        checkSelectedSubtitles();
        updateState(state.playbackState, state.playWhenReady, state.playerError);
        if (playbackRestartSeen) reportFirstFrame();
        scheduleProgressBlackScreenCheck();
    };
    private volatile boolean renderFallbackUsed;
    private boolean surfaceRecovering;
    private volatile int decode;
    /** Debounced Surface for embed: phone rotation briefly exposes a portrait buffer. */
    @Nullable private Surface settlingSurface;
    private boolean settlingOwnsSurface;
    private int settlingWidth;
    private int settlingHeight;
    private final Runnable settleSurfaceRunnable = this::commitSettledSurface;
    /** Embed-only: progress without a frame retries the Surface bind. GPU waits for the play timeout. */
    private final Runnable blackScreenWatchdog = this::checkBlackScreen;
    private final MpvFirstFrameWatchdog firstFrameWatchdog = new MpvFirstFrameWatchdog();
    private long positionMs;
    private long firstFrameStartPositionMs;
    private long pendingSeekMs = C.TIME_UNSET;
    private final MpvSubtitleRequests subtitleRequests;
    private long durationMs = C.TIME_UNSET;
    private long bufferedPositionMs;
    private int videoWidth;
    private int videoHeight;
    private int videoCropWidth;
    private int videoCropHeight;
    private int videoOutputWidth;
    private int videoOutputHeight;
    private int repeatMode = REPEAT_MODE_OFF;
    private long audioOffsetMs;
    private long textOffsetMs;
    private float volume = 1f;
    /** Amplification applied on top of {@link #volume}; kept separate because Media3 caps volume at 1. */
    private float volumeGain = 1f;
    private final String baseAudioFilter;
    private final String baseVideoFilter;
    private final String baseUserAgent;
    private final String baseReferrer;
    private final String baseHttpHeaderFields;
    private final String baseCacheOnDisk;
    private final boolean baseSensitiveHeaders;
    private PlaybackParameters playbackParameters = PlaybackParameters.DEFAULT;
    private boolean seekable = true;
    private boolean released;
    private int currentMediaItemIndex;
    private Tracks currentTracks = Tracks.EMPTY;
    private TrackSelectionParameters trackSelectionParameters;
    private List<MediaChapter> chapters = List.of();
    private List<MediaEdition> editions = List.of();
    @Nullable private String lastNativeError;
    // Observed read-only mpv current-vo, not the requested/pre-init vo option.
    private volatile String activeVideoOutput = "";
    /**
     * Set once when a hardware decoder was requested but libmpv had to fall back to software for
     * the current item. Drives the one-shot "switched to software" notice, mirroring EXO's
     * built-in renderer fallback. Cleared for every new item so the notice can appear again.
     */
    private boolean softwareFallbackReported;
    private boolean softwareFallbackSeen;
    @Nullable private Runnable pendingEndFileError;

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

    private MpvPlayer(Context context, int decode, boolean live, MpvPlayerConfig config) {
        super(Looper.getMainLooper());
        this.context = context.getApplicationContext();
        this.applicationHandler = new Handler(getApplicationLooper());
        this.subtitleRequests = new MpvSubtitleRequests(new MpvSubtitleRequests.Transport() {
            @Override public int send(long id, String[] args) {
                return MPVLib.commandAsync(id, args);
            }
            @Override public void abort(long id) {
                MPVLib.abortAsyncCommand(id);
            }
        }, this::onSubtitleAdded);
        this.decode = decode;
        this.live = live;
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
        this.baseAudioFilter = config.preInitOptions.getOrDefault("af", "");
        this.baseVideoFilter = config.preInitOptions.getOrDefault("vf", "");
        this.baseUserAgent = config.preInitOptions.getOrDefault("user-agent", "");
        this.baseReferrer = config.preInitOptions.getOrDefault("referrer", "");
        this.baseHttpHeaderFields = config.preInitOptions.getOrDefault("http-header-fields", "");
        this.baseCacheOnDisk = config.preInitOptions.getOrDefault("cache-on-disk", "no");
        String baseHeaders = baseHttpHeaderFields.toLowerCase(Locale.US);
        this.baseSensitiveHeaders = baseHeaders.contains("authorization:")
                || baseHeaders.contains("proxy-authorization:")
                || baseHeaders.contains("cookie:") || baseHeaders.contains("set-cookie:");
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
                        COMMAND_GET_TEXT,
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
        boolean created = false;
        try {
            MPVLib.create(context);
            created = true;
            applyAndroidDefaults();
            applyOptions(config.preInitOptions);
            applyDecodeOption();
            MPVLib.init();
        } catch (Throwable error) {
            try {
                if (created) MPVLib.destroy();
            } catch (Throwable ignored) {
            } finally {
                MPVLib.releaseInstance();
            }
            throw error;
        }
        // After init, options must be set as properties (setOptionString is a no-op/fragile).
        applyProperties(config.postInitOptions);
        // Same as mpv-android BaseMPVView: keep VO idle until a Surface is attached.
        MPVLib.setPropertyAsync("force-window", "no");
        MPVLib.setPropertyAsync("idle", "yes");
        MPVLib.addObserver(this);
        MPVLib.addLogObserver(this);
        for (String property : OBSERVED_DOUBLE) MPVLib.observeProperty(property, MPVLib.MpvFormat.DOUBLE);
        for (String property : OBSERVED_FLAG) MPVLib.observeProperty(property, MPVLib.MpvFormat.FLAG);
        for (String property : OBSERVED_INT) MPVLib.observeProperty(property, MPVLib.MpvFormat.INT64);
        MPVLib.observeProperty("sid", MPVLib.MpvFormat.STRING);
        MPVLib.observeProperty("secondary-sid", MPVLib.MpvFormat.STRING);
        MPVLib.observeProperty("current-vo", MPVLib.MpvFormat.STRING);
        MPVLib.observeProperty("hwdec-current", MPVLib.MpvFormat.STRING);
        MPVLib.observeProperty("sub-visibility", MPVLib.MpvFormat.FLAG);
        MPVLib.observeProperty("track-list/count", MPVLib.MpvFormat.INT64);
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
        Log.d("MpvPlayer", "Audio focus change=" + change + " intent=" + state.playWhenReady);
        if (change == AudioManager.AUDIOFOCUS_LOSS
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            setPlayWhenReady(false);
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            MPVLib.setPropertyAsync("volume", state.volume * volumeGain * 20.0);
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            MPVLib.setPropertyAsync("volume", state.volume * volumeGain * 100.0);
        }
    }

    private void applyOptions(Map<String, String> options) {
        for (Map.Entry<String, String> option : options.entrySet()) {
            MPVLib.setOptionString(option.getKey(), option.getValue());
        }
    }

    private void applyProperties(Map<String, String> options) {
        for (Map.Entry<String, String> option : options.entrySet()) {
            MPVLib.setPropertyAsync(option.getKey(), option.getValue());
        }
    }

    private void applyDecodeOption() {
        MPVLib.setOptionString("hwdec", getDecodeOption());
    }

    /**
     * Records the decoder libmpv actually selected for the current item.
     *
     * A hardware request that ends in "no" means libmpv dropped to software for this stream, the
     * same outcome EXO reaches through its own renderer fallback. The requested option cannot be
     * used for this: it stays at the requested value even after the fallback happens.
     */
    private void noteActiveHwdec(@Nullable String value) {
        if (value == null || value.isBlank()) return;
        boolean software = "no".equals(value);
        if (software) {
            // Only a request for hardware that ended in software is a fallback. A user mpv.conf
            // that explicitly asks for hwdec=no is a choice, not a fallback.
            if (wantsHardware()) softwareFallbackSeen = true;
        } else {
            softwareFallbackSeen = false;
        }
    }

    private boolean wantsHardware() {
        // decode 0 is the explicit soft mode; 1 and the internal 2 both asked for hardware.
        // An explicit hwdec=no in mpv.conf also means the user asked for software.
        if (decode == 0) return false;
        return !HWDEC_SOFT.equals(getDecodeOption());
    }

    /**
     * Consumes the "hardware decoder was unavailable, software is in use" notice for this item.
     * Returns true at most once per item so the UI does not repeat itself on every reconfig.
     */
    public boolean consumeSoftwareFallbackNotice() {
        if (!softwareFallbackSeen || softwareFallbackReported) return false;
        softwareFallbackReported = true;
        return true;
    }

    private String getDecodeOption() {
        // mediacodec_embed requires a live Android Surface; after Surface-unavailable we stay on
        // direct gpu output so audio-only / black-screen sessions can recover without a rebuild.
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
            MPVLib.setPropertyAsync("hwdec", getDecodeOption());
            if (surfaceReady) MPVLib.setPropertyAsync("vo", getVo());
            checkSelectedSubtitles();
        }
    }

    private String getVo() {
        // Internal auto-direct candidate only: never bind embed to TextureView or an item
        // carrying external subtitles. Before the first item/SurfaceView is known, retain the
        // candidate so initial Surface settling still protects Rockchip's MediaCodec window.
        boolean external = mediaItem != null && mediaItem.localConfiguration != null
                && !mediaItem.localConfiguration.subtitleConfigurations.isEmpty();
        boolean surfaceView = videoOutput == null || videoOutput instanceof SurfaceView;
        if (!embedVoDisabled && MpvAutomaticOutputPolicy.direct(live, decode == 2, false,
                external, subtitleGpuRequired, surfaceView)) {
            return config.preInitOptions.getOrDefault("vo", "mediacodec_embed");
        }
        String configured = config.preInitOptions.get("vo");
        if (configured != null && !"mediacodec_embed".equals(configured)) return configured;
        return VO_DEFAULT;
    }

    private boolean isEmbedVo() { return "mediacodec_embed".equals(getVo()); }

    /** Once needed, retain GPU output for this file to avoid repeated decoder teardown on sid changes. */
    private void requireSubtitleGpu() {
        if (released || decode != 2 || subtitleGpuRequired) return;
        boolean wasEmbed = isEmbedVo();
        subtitleGpuRequired = true;
        Log.i("MpvPlayer", "Selected subtitle needs GPU/libass; leaving automatic direct output");
        if (wasEmbed && surfaceReady) {
            activeVideoOutput = "";
            MPVLib.setPropertyAsync("vo", "null");
            MPVLib.setPropertyAsync("hwdec", HWDEC_HARD);
            MPVLib.setPropertyAsync("vo", getVo());
            if (fileLoaded) {
                firstFrameReported = false;
                playbackRestartSeen = false;
                videoFrameSeen = false;
                reopenVideoDecoder();
                scheduleProgressBlackScreenCheck();
            }
        }
    }

    private void checkSelectedSubtitles() {
        if (released || !fileLoaded || decode != 2) return;
        int sid = parseInt(MPVLib.getCachedString("sid"), -1);
        int secondary = parseInt(MPVLib.getCachedString("secondary-sid"), -1);
        boolean visible = !trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
                && !Boolean.FALSE.equals(MPVLib.getCachedBoolean("sub-visibility"));
        if (visible && (sid > 0 || secondary > 0)) requireSubtitleGpu();
    }

    public int getDecode() {
        return decode;
    }

    /** Observed native current-vo for this session (empty means no confirmed output). */
    public String getActiveVideoOutput() {
        return activeVideoOutput;
    }

    public void setSubtitleOptions(MpvPlayerConfig subtitleConfig) {
        if (!released) applyProperties(subtitleConfig.postInitOptions);
    }

    public void addSubtitle(MediaItem.SubtitleConfiguration subtitle) {
        if (released) return;
        // Manual URL imports must not block UI either. Codec remains native-authoritative.
        subtitleRequests.enqueue(subtitle.uri.toString(), true);
        if (fileLoaded) subtitleRequests.start();
    }

    @Override
    public boolean selectChapter(MediaChapter chapter) {
        if (released || chapter == null) return false;
        MPVLib.setPropertyAsync("chapter", chapter.index);
        return true;
    }

    @Override
    public boolean selectEdition(MediaEdition edition) {
        if (released || edition == null) return false;
        MPVLib.setPropertyAsync("edition", edition.index);
        return true;
    }

    @Override
    protected State getState() {
        return state;
    }

    private State buildState(int playbackState, boolean playWhenReady,
                             @Nullable PlaybackException error) {
        // Media3 forbids an empty timeline in transient states. Native events may still arrive
        // after stop()/clearMediaItems(), so coerce those stale callbacks back to IDLE.
        if (playlist.isEmpty() && playbackState != STATE_IDLE && playbackState != STATE_ENDED) {
            playbackState = STATE_IDLE;
            // A late native event cannot overwrite intent established for a pending parse/probe.
        }
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
                .setCurrentCues(CueGroup.EMPTY_TIME_ZERO)
                .setSeekBackIncrementMs(DEFAULT_SEEK_INCREMENT_MS)
                .setSeekForwardIncrementMs(DEFAULT_SEEK_INCREMENT_MS)
                .setVideoSize(videoWidth > 0 && videoHeight > 0
                        ? new VideoSize(videoCropWidth > 0 ? videoCropWidth : videoWidth,
                                videoCropHeight > 0 ? videoCropHeight : videoHeight) : VideoSize.UNKNOWN)
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
        subtitleRequests.reset();
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
        videoCropWidth = 0;
        videoCropHeight = 0;
        videoOutputWidth = videoOutputHeight = 0;
        videoHeight = 0;
        currentTracks = Tracks.EMPTY;
        mpvTrackIds.clear();
        chapters = List.of();
        editions = List.of();
        lastNativeError = null;
        activeVideoOutput = "";
        softwareFallbackReported = false;
        softwareFallbackSeen = false;
        fileLoaded = false;
        embedSurfaceRetries = 0;
        embedVoDisabled = false;
        recentSurfaceFailure = false;
        bufferedPositionMs = positionMs;
        // Replacing the timeline is not a pause request. Publishing false here causes
        // controllers/focused play-pause widgets to observe a spurious pause during prepare.
        updateState(STATE_IDLE, state.playWhenReady, null);
        return done();
    }

    @Override
    protected ListenableFuture<?> handlePrepare() {
        if (mediaItem == null || mediaItem.localConfiguration == null) return done();
        updateState(STATE_BUFFERING, state.playWhenReady, null);
        applyHttpHeaders(mediaItem);
        String uri = mediaItem.localConfiguration.uri.toString();
        applyDemuxerOptions(uri);
        // The new item re-evaluates direct eligibility; GPU fallback never persists.
        subtitleGpuRequired = false;
        // An automatic live hard candidate may select embed after the item and view are known.
        // Wait for its Surface even when this particular item needs GPU (e.g. external subs),
        // so the pre-init embed VO cannot race the final routing decision or open audio-only.
        if (decode != 2 || surfaceReady) {
            loadFile(uri);
        } else {
            pendingLoadUri = uri;
            // SurfaceView callbacks can race prepare() or be delivered before the player output
            // is registered. Re-offer the current valid output instead of waiting indefinitely.
            applicationHandler.postDelayed(this::reofferCurrentVideoOutput, 200);
        }
        return done();
    }

    private void applyDemuxerOptions(String uri) {
        String configured = config.preInitOptions.getOrDefault("demuxer-lavf-o", "");
        String lower = uri == null ? "" : uri.toLowerCase(Locale.US);
        boolean hls = lower.contains(".m3u8") || lower.contains("=m3u8")
                || lower.endsWith(".php") || lower.contains(".php?");
        String options = configured;
        if (hls && !configured.toLowerCase(Locale.US).contains("http_persistent=")) {
            options = configured.isBlank()
                    ? "http_persistent=0"
                    : configured + ",http_persistent=0";
        }
        MPVLib.setPropertyAsync("demuxer-lavf-o", options);
    }

    private void loadFile(String uri) {
        subtitleRequests.reset();
        cancelPendingEndFileError();
        activeVideoOutput = "";
        fileLoaded = false;
        firstFrameReported = false;
        playbackRestartSeen = false;
        videoFrameSeen = false;
        firstFrameStartPositionMs = positionMs;
        firstFrameWatchdog.reset();
        renderFallbackUsed = false;
        surfaceRecovering = false;
        surfaceNeedsVideoReload = false;
        applicationHandler.removeCallbacks(blackScreenWatchdog);
        // loadfile replace owns teardown of the previous decoder. An async stop reply only
        // acknowledges the command, NOT END_FILE/decoder destruction; dropping its live VO
        // here can destroy the ImageReader while MediaCodec still has outstanding buffers.
        if (surfaceReady) {
            MPVLib.setPropertyAsync("force-window", "yes");
            MPVLib.setPropertyAsync("vo", getVo());
        }
        // Restore per-file output policy without manufacturing an intermediate null VO.
        MPVLib.setPropertyAsync("hwdec", getDecodeOption());
        // Avoid loadfile options entirely: mpv 0.38+ inserted an integer index argument, and
        // KEYVALUELIST parsing differs across builds. Resume via seek after FILE_LOADED instead.
        MPVLib.commandAsync(new String[]{"loadfile", uri, "replace"});
    }

    private void applyPendingSeekAndSubtitles() {
        if (pendingSeekMs != C.TIME_UNSET && pendingSeekMs > 0) {
            seekAbsolute(pendingSeekMs);
            pendingSeekMs = C.TIME_UNSET;
        }
        enqueueConfiguredSubtitles();
    }

    private void enqueueConfiguredSubtitles() {
        if (!fileLoaded || mediaItem == null || mediaItem.localConfiguration == null
                || trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) return;
        // A completed import may precede cancellation but its Java reply may follow it.
        // Consult native inventory when re-enabling TEXT instead of adding it a second time.
        List<String> imported = new ArrayList<>();
        int count = valueOr(MPVLib.getCachedInt("track-list/count"), 0);
        for (int i = 0; i < count; i++) {
            String file = MPVLib.getCachedString("track-list/" + i + "/external-filename");
            if (file != null) imported.add(file);
        }
        for (MediaItem.SubtitleConfiguration subtitle :
                mediaItem.localConfiguration.subtitleConfigurations) {
            if (!imported.contains(subtitle.uri.toString()))
                subtitleRequests.enqueue(subtitle.uri.toString(), false);
        }
        subtitleRequests.start();
    }

    private void onSubtitleAdded(int error) {
        if (released || !fileLoaded) return;
        if (error < 0) {
            // Do not turn an optional subtitle HTTP error into END_FILE/playback failure.
            Log.w("MpvSubtitle", "External subtitle import failed: " + error);
        } else {
            refreshTracks();
            checkSelectedSubtitles();
            updateState(state.playbackState, state.playWhenReady, state.playerError);
        }
    }

    @Override
    public void eventCommandReply(long id, int error) {
        onApplicationThread(() -> subtitleRequests.complete(id, error));
    }

    private void applyHttpHeaders(MediaItem item) {
        Bundle extras = item.requestMetadata.extras;
        if (extras == null || extras.isEmpty()) {
            MPVLib.setPropertyAsync("http-header-fields", baseHttpHeaderFields);
            MPVLib.setPropertyAsync("user-agent", baseUserAgent);
            MPVLib.setPropertyAsync("referrer", baseReferrer);
            MPVLib.setPropertyAsync("cache-on-disk", baseSensitiveHeaders ? "no" : baseCacheOnDisk);
            return;
        }
        List<String> fields = new ArrayList<>();
        if (!baseHttpHeaderFields.isBlank()) fields.add(baseHttpHeaderFields);
        String userAgent = null;
        String referrer = null;
        boolean sensitiveHeaders = baseSensitiveHeaders;
        for (String key : extras.keySet()) {
            Object value = extras.get(key);
            if (value == null) continue;
            String safeKey = key.replace("\r", "").replace("\n", "");
            if (!safeKey.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) continue;
            String plainValue = value.toString().replace("\r", "").replace("\n", "");
            if ("Authorization".equalsIgnoreCase(safeKey)
                    || "Proxy-Authorization".equalsIgnoreCase(safeKey)
                    || "Cookie".equalsIgnoreCase(safeKey)
                    || "Set-Cookie".equalsIgnoreCase(safeKey)) sensitiveHeaders = true;
            if ("User-Agent".equalsIgnoreCase(safeKey)) {
                userAgent = plainValue;
                continue;
            }
            if ("Referer".equalsIgnoreCase(safeKey)) {
                referrer = plainValue;
                continue;
            }
            String escapedValue = plainValue.replace("\\", "\\\\").replace(",", "\\,");
            fields.add(safeKey + ": " + escapedValue);
        }
        // Upstream FongMi sets UA/Referer as dedicated mpv props (ffmpeg reads these).
        MPVLib.setPropertyAsync("user-agent", userAgent == null ? baseUserAgent : userAgent);
        MPVLib.setPropertyAsync("referrer", referrer == null ? baseReferrer : referrer);
        MPVLib.setPropertyAsync("cache-on-disk", sensitiveHeaders ? "no" : baseCacheOnDisk);
        MPVLib.setPropertyAsync("http-header-fields", String.join(",", fields));
    }

    @Override
    protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
        Log.d("MpvPlayer", "Playback intent=" + playWhenReady + " state=" + state.playbackState);
        if (playWhenReady && audioManager.requestAudioFocus(audioFocusRequest)
                != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return done();
        }
        if (!playWhenReady) audioManager.abandonAudioFocusRequest(audioFocusRequest);
        MPVLib.setPropertyAsync("pause", !playWhenReady);
        updateState(state.playbackState, playWhenReady, null);
        if (playWhenReady) scheduleProgressBlackScreenCheck();
        else {
            firstFrameWatchdog.reset();
            applicationHandler.removeCallbacks(blackScreenWatchdog);
        }
        return done();
    }

    @Override
    protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs, int seekCommand) {
        if (mediaItemIndex != currentMediaItemIndex && mediaItemIndex >= 0
                && mediaItemIndex < playlist.size()) {
            currentMediaItemIndex = mediaItemIndex;
            mediaItem = playlist.get(mediaItemIndex);
            this.positionMs = Math.max(0, positionMs);
            pendingSeekMs = this.positionMs > 0 ? this.positionMs : C.TIME_UNSET;
            handlePrepare();
            return done();
        }
        if (fileLoaded && !seekable) return done();
        this.positionMs = Math.max(0, positionMs);
        if (!fileLoaded) {
            pendingSeekMs = this.positionMs > 0 ? this.positionMs : C.TIME_UNSET;
        } else {
            seekAbsolute(this.positionMs);
        }
        state = state.buildUpon()
                .setCurrentCues(CueGroup.EMPTY_TIME_ZERO)
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
        MPVLib.setPropertyAsync("speed", parameters.speed);
        playbackParameters = parameters;
        state = state.buildUpon().setPlaybackParameters(parameters).build();
        invalidateState();
        return done();
    }

    @Override
    protected ListenableFuture<?> handleSetRepeatMode(int repeatMode) {
        MPVLib.setPropertyAsync("loop-file", repeatMode == REPEAT_MODE_OFF ? "no" : "inf");
        this.repeatMode = repeatMode;
        state = state.buildUpon().setRepeatMode(repeatMode).build();
        invalidateState();
        return done();
    }

    @Override
    protected ListenableFuture<?> handleSetVolume(float volume) {
        MPVLib.setPropertyAsync("volume", volume * volumeGain * 100.0);
        this.volume = volume;
        state = state.buildUpon().setVolume(volume).build();
        invalidateState();
        return done();
    }

    /**
     * Applies playback amplification above the Media3 volume range.
     *
     * <p>{@link #setVolume(float)} cannot express this: Media3's contract requires volume in
     * {@code 0..1}, while listening gain can legitimately exceed 1.0.
     */
    public void setVolumeGain(float gain) {
        volumeGain = Math.max(0f, gain);
        // mpv truncates `volume` at `volume-max`, which defaults to 130 and would silently swallow
        // anything above 1.3x, so raise the ceiling before applying the gain.
        MPVLib.setPropertyAsync("volume-max", Math.max(130.0, volumeGain * 100.0));
        MPVLib.setPropertyAsync("volume", volume * volumeGain * 100.0);
    }

    /** Applies a libavfilter graph through mpv's public {@code af} property. */
    public boolean setAudioFilter(String filter) {
        if (released) return false;
        try {
            MPVLib.setPropertyAsync("af", mergeFilters(baseAudioFilter, filter));
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Applies a public libavfilter graph through mpv's {@code vf} property. */
    public boolean setVideoFilter(String filter) {
        if (released || decode == 2) return false;
        try {
            MPVLib.setPropertyAsync("vf", mergeFilters(baseVideoFilter, filter));
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String mergeFilters(String base, @Nullable String effect) {
        String extra = effect == null ? "" : effect.trim();
        if (base == null || base.isBlank()) return extra;
        if (extra.isEmpty()) return base;
        return base + "," + extra;
    }

    /** Applies mpv's built-in GPU equalizer properties. Values use mpv's -100..100 range. */
    public boolean setVideoEqualizer(float brightness, float contrast, float saturation, float gamma, float hue) {
        if (released || decode == 2) return false;
        try {
            MPVLib.setPropertyAsync("brightness", combineEqualizer("brightness", brightness));
            MPVLib.setPropertyAsync("contrast", combineEqualizer("contrast", contrast));
            MPVLib.setPropertyAsync("saturation", combineEqualizer("saturation", saturation));
            MPVLib.setPropertyAsync("gamma", combineEqualizer("gamma", gamma));
            MPVLib.setPropertyAsync("hue", combineEqualizer("hue", hue));
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private double combineEqualizer(String key, float effect) {
        double base = 0.0;
        try {
            base = Double.parseDouble(config.preInitOptions.getOrDefault(key, "0"));
        } catch (NumberFormatException ignored) {
        }
        return Math.clamp(base + effect, -100.0, 100.0);
    }

    /**
     * Channel count of the audio being played, or {@link Format#NO_VALUE} when no audio track is known.
     *
     * <p>Falls back to the first known audio track when none is flagged selected: libmpv owns track
     * selection, so Media3's selection flags can stay empty even while audio plays, and callers that
     * build an audio graph still need the real layout.
     */
    public int getAudioChannelCount() {
        int fallback = Format.NO_VALUE;
        for (Tracks.Group group : currentTracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < group.length; i++) {
                int channels = group.getTrackFormat(i).channelCount;
                if (group.isTrackSelected(i)) return channels;
                if (fallback == Format.NO_VALUE) fallback = channels;
            }
        }
        if (fallback != Format.NO_VALUE) return fallback;
        // The demuxed track list may omit the channel count; ask mpv for the decoded layout instead.
        Integer decoded = MPVLib.getCachedInt("audio-params/channel-count");
        return decoded == null || decoded <= 0 ? Format.NO_VALUE : decoded;
    }

    /** Selects mpv's independent secondary subtitle track from a public Media3 override. */
    public boolean setSecondaryTextTrackSelectionOverride(@Nullable TrackSelectionOverride override) {
        if (released) return false;
        if (override == null || override.trackIndices.isEmpty()) {
            MPVLib.setPropertyAsync("secondary-sid", "no");
            checkSelectedSubtitles();
            return true;
        }
        List<Integer> ids = mpvTrackIds.get(override.mediaTrackGroup);
        int index = override.trackIndices.get(0);
        if (ids == null || index < 0 || index >= ids.size()) return false;
        MPVLib.setPropertyAsync("secondary-sid", ids.get(index));
        checkSelectedSubtitles();
        return true;
    }

    public void setSecondarySubtitleDelayMs(long offsetMs) {
        if (!released) MPVLib.setPropertyAsync("secondary-sub-delay", offsetMs / 1000.0);
    }

    @Override
    protected ListenableFuture<?> handleSetAudioOffsetMs(long offsetMs) {
        MPVLib.setPropertyAsync("audio-delay", offsetMs / 1000.0);
        audioOffsetMs = offsetMs;
        state = state.buildUpon().setAudioOffsetMs(offsetMs).build();
        invalidateState();
        return done();
    }

    @Override
    protected ListenableFuture<?> handleSetTextOffsetMs(long offsetMs) {
        MPVLib.setPropertyAsync("sub-delay", offsetMs / 1000.0);
        textOffsetMs = offsetMs;
        state = state.buildUpon().setTextOffsetMs(offsetMs).build();
        invalidateState();
        return done();
    }

    @Override
    protected ListenableFuture<?> handleStop() {
        subtitleRequests.reset();
        cancelPendingEndFileError();
        pendingLoadUri = null;
        fileLoaded = false;
        firstFrameWatchdog.reset();
        applicationHandler.removeCallbacks(blackScreenWatchdog);
        cancelSurfaceSettle();
        MPVLib.setPropertyAsync("pause", true);
        MPVLib.commandAsync(new String[]{"stop"});
        // Let mpv's stop/END_FILE lifecycle close its decoder. The async command reply does
        // not mean that teardown finished; racing it with vo=null/hwdec=no poisons Surface
        // consumers and the next hardware load. Native termination still closes all resources.
        positionMs = 0;
        activeVideoOutput = "";
        updateState(STATE_IDLE, false, null);
        audioManager.abandonAudioFocusRequest(audioFocusRequest);
        return done();
    }

    @Override
    protected ListenableFuture<?> handleSetTrackSelectionParameters(
            TrackSelectionParameters parameters) {
        boolean wasTextDisabled = trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT);
        trackSelectionParameters = parameters;
        if (parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) subtitleRequests.cancel();
        applyTrackSelection(C.TRACK_TYPE_VIDEO, "vid", parameters);
        applyTrackSelection(C.TRACK_TYPE_AUDIO, "aid", parameters);
        applyTrackSelection(C.TRACK_TYPE_TEXT, "sid", parameters);
        if (parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
            MPVLib.setPropertyAsync("secondary-sid", "no");
        checkSelectedSubtitles();
        if (wasTextDisabled && !parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
            enqueueConfiguredSubtitles();
        state = state.buildUpon().setTrackSelectionParameters(parameters).build();
        invalidateState();
        return done();
    }

    private void applyTrackSelection(int type, String property,
                                     TrackSelectionParameters parameters) {
        if (parameters.disabledTrackTypes.contains(type)) {
            MPVLib.setPropertyAsync(property, "no");
            return;
        }
        for (TrackSelectionOverride override : parameters.overrides.values()) {
            if (override.getType() != type) continue;
            if (override.trackIndices.isEmpty()) {
                MPVLib.setPropertyAsync(property, "no");
                return;
            }
            List<Integer> ids = mpvTrackIds.get(override.mediaTrackGroup);
            int index = override.trackIndices.get(0);
            if (ids != null && index >= 0 && index < ids.size()) {
                MPVLib.setPropertyAsync(property, ids.get(index));
                return;
            }
        }
        MPVLib.setPropertyAsync(property, "auto");
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
     * mediacodec_embed binds MediaCodec directly to the ANativeWindow. Debounce rapid
     * Surface churn (PlayerView recreate / rotation) so we do not bind to a window that is
     * about to be destroyed. Upstream FongMi attaches immediately with no orientation filter;
     * a previous aspect check against ApplicationContext orientation permanently rejected valid
     * Surfaces and blocked loadfile.
     */
    private boolean needsSurfaceSettle() {
        return isEmbedVo();
    }

    private void offerSurface(Surface surface, boolean ownsSurface, int width, int height) {
        // Debounce only the first window. Once mediacodec_embed is running, delaying a
        // replacement leaves the decoder bound to the old window after Android invalidates it.
        if (needsSurfaceSettle() && !surfaceReady && width > 0 && height > 0) {
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
        // Short debounce only — do not reject by orientation/aspect.
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
        attachNativeSurface(surface, owns, width, height);
    }

    private void attachNativeSurface(Surface surface, boolean ownsSurface) {
        attachNativeSurface(surface, ownsSurface, 0, 0);
    }

    private void attachNativeSurface(Surface surface, boolean ownsSurface, int width, int height) {
        if (attachedSurface == surface && surfaceReady) {
            // surfaceChanged() is also emitted for layout/inset changes. Re-setting vo while
            // mediacodec_embed is active destroys its current hwdevice and leaves MediaCodec
            // bound to an unavailable ANativeWindow. The window itself has not changed here;
            // only publish its new dimensions.
            if (width > 0 && height > 0) {
                MPVLib.setPropertyAsync("android-surface-size", width + "x" + height);
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
        MPVLib.setPropertyAsync("force-window", "yes");
        MPVLib.setPropertyAsync("vo", getVo());
        if (width > 0 && height > 0) {
            MPVLib.setPropertyAsync("android-surface-size", width + "x" + height);
        }
        surfaceReady = true;
        if (pendingLoadUri != null) {
            String uri = pendingLoadUri;
            pendingLoadUri = null;
            // Prefer reload when demux already started (prepare no longer waits for Surface).
            if (fileLoaded) {
                reloadVideoAfterSurface();
            } else {
                loadFile(uri);
            }
        } else if (fileLoaded) {
            // Surface came back after a transient detach: restore VO and reload video decoder.
            // mediacodec_embed is poisoned once its window disappears mid-stream (black + audio).
            reloadVideoAfterSurface();
        }
    }

    private void reofferCurrentVideoOutput() {
        if (released || surfaceReady || videoOutput == null) return;
        Object output = videoOutput;
        if (output instanceof Surface surface) {
            if (surface.isValid()) offerSurface(surface, false, 0, 0);
        } else if (output instanceof SurfaceHolder holder) {
            if (holder.getSurface().isValid()) attachSurfaceHolder(holder);
        } else if (output instanceof SurfaceView view) {
            SurfaceHolder holder = view.getHolder();
            if (holder.getSurface().isValid()) attachSurfaceHolder(holder);
        } else if (output instanceof TextureView view) {
            if (view.isAvailable() && view.getSurfaceTexture() != null
                    && view.getWidth() > 0 && view.getHeight() > 0) {
                offerSurface(new Surface(view.getSurfaceTexture()), true,
                        view.getWidth(), view.getHeight());
            }
        }
    }

    private void reloadVideoAfterSurface() {
        surfaceNeedsVideoReload = false;
        firstFrameReported = false;
        playbackRestartSeen = false;
        videoFrameSeen = false;
        MPVLib.setPropertyAsync("hwdec", getDecodeOption());
        MPVLib.setPropertyAsync("vo", getVo());
        reopenVideoDecoder();
    }

    private void detachNativeSurface() {
        if (attachedSurface == null) return;
        surfaceReady = false;
        activeVideoOutput = "";
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
        subtitleRequests.reset();
        released = true;
        activeVideoOutput = "";
        firstFrameWatchdog.reset();
        applicationHandler.removeCallbacks(blackScreenWatchdog);
        cancelPendingEndFileError();
        cancelSurfaceSettle();
        applicationHandler.removeCallbacks(metadataRefresh);
        MPVLib.removeObserver(this);
        MPVLib.removeLogObserver(this);
        audioManager.abandonAudioFocusRequest(audioFocusRequest);
        pendingLoadUri = null;
        fileLoaded = false;
        try {
            // Do not rely on Service/Activity ordering: silence native audio synchronously before
            // destroying the handle, including task-removal and application shutdown paths.
            MPVLib.setPropertyAsync("pause", true);
            MPVLib.commandAsync(new String[]{"stop"});
            // stop is asynchronous. Disable the VO while its Android window is still valid so a
            // late idle/video-reconfig event cannot recreate gpu-next after detachSurface().
            if (surfaceReady) MPVLib.setPropertyAsync("vo", "null");
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

    private void metadataChanged(String property) {
        if (!property.startsWith("track-list/") && !property.startsWith("chapter-list/")
                && !property.startsWith("edition-list/") && !property.equals("chapter")
                && !property.equals("edition")) return;
        onApplicationThread(() -> {
            applicationHandler.removeCallbacks(metadataRefresh);
            applicationHandler.postDelayed(metadataRefresh, 50);
        });
    }

    @Override
    public void eventProperty(String property) {
        metadataChanged(property);
        if ("current-vo".equals(property)) onApplicationThread(() -> activeVideoOutput = "");
        if ("video-out-params/w".equals(property)) onApplicationThread(() -> videoOutputWidth = 0);
        if ("video-out-params/h".equals(property)) onApplicationThread(() -> videoOutputHeight = 0);
        if ("video-params/crop-w".equals(property) || "video-params/crop-h".equals(property)) {
            onApplicationThread(() -> {
                if ("video-params/crop-w".equals(property)) videoCropWidth = 0;
                else videoCropHeight = 0;
                updateState(state.playbackState, state.playWhenReady, state.playerError);
            });
        }
    }

    @Override
    public void eventProperty(String property, long value) {
        metadataChanged(property);
        onApplicationThread(() -> {
            if ("video-params/w".equals(property)) videoWidth = (int) value;
            if ("video-params/h".equals(property)) videoHeight = (int) value;
            if ("video-params/crop-w".equals(property)) videoCropWidth = value > 0 && value <= Integer.MAX_VALUE ? (int) value : 0;
            if ("video-params/crop-h".equals(property)) videoCropHeight = value > 0 && value <= Integer.MAX_VALUE ? (int) value : 0;
            if ("video-out-params/w".equals(property)) videoOutputWidth = (int) value;
            if ("video-out-params/h".equals(property)) videoOutputHeight = (int) value;
            if (playbackRestartSeen) reportFirstFrame();
            if ("track-list/count".equals(property) && fileLoaded) {
                refreshTracks();
                checkSelectedSubtitles();
            }
            updateState(state.playbackState, state.playWhenReady, state.playerError);
        });
    }

    @Override
    public void eventProperty(String property, boolean value) {
        metadataChanged(property);
        onApplicationThread(() -> {
            switch (property) {
                case "pause" -> {
                    // The initial "not paused" value arrives before any file. Applying it would
                    // mark the idle player as ready to play.
                    if (!fileLoaded) break;
                    // Native observations may describe an earlier queued pause request.
                    // UI/MediaSession owns intent; a late receipt must not undo a newer play/pause.
                    // Buffering is reported separately by paused-for-cache.
                }
                case "paused-for-cache" -> {
                    if (!fileLoaded) break;
                    if (value) updateState(STATE_BUFFERING, state.playWhenReady, state.playerError);
                    else if (state.playbackState == STATE_BUFFERING)
                        updateState(STATE_READY, state.playWhenReady, state.playerError);
                }
                case "sub-visibility" -> checkSelectedSubtitles();
                case "seekable" -> {
                    seekable = value;
                    updateState(state.playbackState, state.playWhenReady, state.playerError);
                }
            }
        });
    }

    @Override
    public void eventProperty(String property, String value) {
        metadataChanged(property);
        if ("current-vo".equals(property)) {
            onApplicationThread(() -> {
                activeVideoOutput = value == null || "null".equals(value) ? "" : value;
                if (!activeVideoOutput.isEmpty())
                    Log.i("MpvPlayer", "Active video output: " + activeVideoOutput);
            });
        } else if ("hwdec-current".equals(property)) {
            // The decoder libmpv actually selected. Also read from the cache at first frame, since
            // mpv publishes this on video-reconfig and that may already have passed.
            onApplicationThread(() -> noteActiveHwdec(value));
        } else if ("sid".equals(property) || "secondary-sid".equals(property)) {
            onApplicationThread(() -> {
                if (!fileLoaded) return;
                refreshTracks();
                checkSelectedSubtitles();
                updateState(state.playbackState, state.playWhenReady, state.playerError);
            });
        }
    }

    @Override
    public void eventProperty(String property, double value) {
        metadataChanged(property);
        onApplicationThread(() -> {
            boolean publish = false;
            switch (property) {
                case "video-frame-info/pts" -> {
                    if (fileLoaded && Double.isFinite(value)) {
                        videoFrameSeen = true;
                        if (playbackRestartSeen) reportFirstFrame();
                    }
                }
                case "time-pos" -> {
                    long nextPositionMs = Math.max(0, (long) (value * 1000));
                    // Audio-led time-pos can advance while the hardware video decoder is dead.
                    // It must not erase the video decoder's error burst.
                    positionMs = nextPositionMs;
                    // Compare against this load's starting position. A resumed item must not look
                    // stuck merely because its absolute position is already greater than 300 ms.
                    if (!firstFrameReported && hasVideoTrack()) scheduleProgressBlackScreenCheck();
                }
                case "duration" -> {
                    durationMs = value > 0 ? (long) (value * 1000) : C.TIME_UNSET;
                    publish = true;
                }
                case "demuxer-cache-time" -> {
                    // Absolute end of the demuxer cache, in seconds. cache-buffering-state is only
                    // how full that cache is (0-100) and must not be scaled across the whole title.
                    long end = value > 0 ? (long) (value * 1000) : positionMs;
                    if (durationMs != C.TIME_UNSET) end = Math.min(durationMs, end);
                    bufferedPositionMs = Math.max(positionMs, end);
                }
            }
            if (publish) updateState(state.playbackState, state.playWhenReady, state.playerError);
        });
    }

    /** Live windows rarely survive an exact decode-to-timestamp seek. */
    private void seekAbsolute(long positionMs) {
        MPVLib.commandAsync(new String[]{"seek", Double.toString(positionMs / 1000.0), live ? "absolute" : "absolute+exact"});
    }

    @Override
    public void event(int eventId) {
        onApplicationThread(() -> {
            switch (eventId) {
                case MPVLib.MpvEvent.START_FILE -> {
                    // An HLS master can expose several renditions as native playlist entries.
                    // Starting the next one means the preceding END_FILE error was not terminal.
                    cancelPendingEndFileError();
                    subtitleRequests.reset();
                    fileLoaded = false;
                    videoOutputWidth = videoOutputHeight = 0;
                    firstFrameReported = false;
                    playbackRestartSeen = false;
                    videoFrameSeen = false;
                    firstFrameStartPositionMs = positionMs;
                    firstFrameWatchdog.reset();
                    applicationHandler.removeCallbacks(blackScreenWatchdog);
                    updateState(STATE_BUFFERING, state.playWhenReady, null);
                }
                case MPVLib.MpvEvent.FILE_LOADED -> {
                    cancelPendingEndFileError();
                    fileLoaded = true;
                    applyPendingSeekAndSubtitles();
                    if (trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) {
                        MPVLib.setPropertyAsync("sid", "no");
                        MPVLib.setPropertyAsync("secondary-sid", "no");
                    }
                    refreshTracks();
                    checkSelectedSubtitles();
                    refreshChaptersAndEditions();
                    lastNativeError = null;
                    updateState(STATE_READY, state.playWhenReady, null);
                    scheduleProgressBlackScreenCheck();
                }
                // VIDEO_RECONFIG is also emitted by force-window before loadfile and therefore
                // does not prove that a decoded frame reached the Android Surface.
                case MPVLib.MpvEvent.PLAYBACK_RESTART -> {
                    playbackRestartSeen = true;
                    reportFirstFrame();
                }
                // Current native builds deliver END_FILE through eventEndFile(), including its
                // reason and error. Retain this only for compatibility with an older bridge.
                case MPVLib.MpvEvent.END_FILE -> handleEndFile(
                        lastNativeError == null
                                ? MPVLib.MpvEndFileReason.EOF
                                : MPVLib.MpvEndFileReason.ERROR,
                        0, lastNativeError);
                case MPVLib.MpvEvent.SHUTDOWN ->
                        updateState(STATE_IDLE, state.playWhenReady,
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
        subtitleRequests.reset();
        if (reason == MPVLib.MpvEndFileReason.STOP
                || reason == MPVLib.MpvEndFileReason.QUIT
                || reason == MPVLib.MpvEndFileReason.REDIRECT) {
            cancelPendingEndFileError();
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
            // disable embed VO and reload once with gpu/android instead of surfacing a fatal error.
            if (maybeRetryAfterSurfaceFailure(detail)) return;
            scheduleEndFileError(detail);
            return;
        }
        cancelPendingEndFileError();
        if (currentMediaItemIndex + 1 < playlist.size()
                && state.repeatMode != REPEAT_MODE_ONE) {
            currentMediaItemIndex++;
            mediaItem = playlist.get(currentMediaItemIndex);
            positionMs = 0;
            handlePrepare();
        } else if (state.playbackState != STATE_ENDED) {
            updateState(STATE_ENDED, state.playWhenReady, null);
        }
    }

    private void scheduleEndFileError(String detail) {
        cancelPendingEndFileError();
        pendingEndFileError = () -> {
            pendingEndFileError = null;
            if (released) return;
            updateState(STATE_IDLE, state.playWhenReady, new PlaybackException(detail, null,
                    mapMpvIoErrorCode(detail)));
        };
        applicationHandler.postDelayed(pendingEndFileError, END_FILE_ERROR_DEBOUNCE_MS);
    }

    private void cancelPendingEndFileError() {
        if (pendingEndFileError == null) return;
        applicationHandler.removeCallbacks(pendingEndFileError);
        pendingEndFileError = null;
    }

    private boolean maybeRetryAfterSurfaceFailure(String detail) {
        if (released || mediaItem == null || mediaItem.localConfiguration == null) return false;
        if (decode != 2) return false;
        String d = detail == null ? "" : detail.toLowerCase(Locale.US);
        // HTTP/auth failures also end as "loading failed" — do not treat them as Surface races.
        if (d.contains("http error") || d.contains("403") || d.contains("404") || d.contains("402")
                || d.contains("401") || d.contains("503") || d.contains("502") || d.contains("500")
                || d.contains("failed to open https") || d.contains("failed to open http")
                || d.contains("png") || d.contains("no demuxer")) {
            return false;
        }
        boolean worthRetry = recentSurfaceFailure
                || d.contains("surface") || d.contains("mediacodec_embed") || d.contains("hwdevice")
                || ((d.contains("no audio or video") || d.contains("loading failed") || d.isBlank())
                && (recentSurfaceFailure || d.contains("surface") || d.contains("mediacodec")));
        if (!worthRetry) return false;
        recentSurfaceFailure = false;
        surfaceNeedsVideoReload = true;
        firstFrameReported = false;
        playbackRestartSeen = false;
        videoFrameSeen = false;
        fileLoaded = false;
        String uri = mediaItem.localConfiguration.uri.toString();
        // Prefer staying on zero-copy embed: drop the poisoned window and re-bind. Only after
        // repeated failures fall back to mediacodec with gpu/android.
        if (isEmbedVo() && embedSurfaceRetries < EMBED_SURFACE_RETRY_LIMIT) {
            embedSurfaceRetries++;
            if (surfaceReady) detachNativeSurface();
            pendingLoadUri = uri;
            updateState(STATE_BUFFERING, state.playWhenReady, null);
            // Do not wait forever for a new Surface callback — re-offer the current view.
            applicationHandler.postDelayed(this::reofferCurrentVideoOutput, 200);
            return true;
        }
        if (embedVoDisabled) return false;
        embedVoDisabled = true;
        if (surfaceReady) {
            activeVideoOutput = "";
            MPVLib.setPropertyAsync("vo", "null");
            MPVLib.setPropertyAsync("hwdec", getDecodeOption());
            MPVLib.setPropertyAsync("vo", getVo());
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
                || d.contains("mediacodec_embed")) {
            return PlaybackException.ERROR_CODE_DECODING_FAILED;
        }
        // "no audio or video data played" is often a demux/format issue (e.g. PNG-wrapped TS),
        // not a MediaCodec failure — only treat as decode when Surface/hwdec is implicated.
        if (d.contains("no audio or video")
                && (d.contains("surface") || d.contains("mediacodec") || d.contains("hwdec"))) {
            return PlaybackException.ERROR_CODE_DECODING_FAILED;
        }
        if (d.contains("http error") || d.contains("server returned") || d.contains("status code")) {
            return PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS;
        }
        // A codec the device cannot open in hardware (AV1 on many phones, MPEG-2/VC-1 on others)
        // must switch to soft decode. "failed to open" alone is a network failure and stays there.
        if (d.contains("could not open codec") || d.contains("cannot open codec")
                || d.contains("failed to create mediacodec") || d.contains("mediacodec decoder")
                || d.contains("no decoder") || d.contains("hwdec")) {
            return PlaybackException.ERROR_CODE_DECODER_INIT_FAILED;
        }
        if (d.contains("timeout") || d.contains("timed out")) {
            return PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT;
        }
        if (d.contains("png") || d.contains("no demuxer") || d.contains("unrecognized")
                || d.contains("no audio or video")) {
            return PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED;
        }
        if (d.contains("unsupported")) {
            return PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED;
        }
        if (d.contains("failed to open") || d.contains("connection") || d.contains("network")
                || d.contains("loading failed")) {
            return PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED;
        }
        return PlaybackException.ERROR_CODE_IO_UNSPECIFIED;
    }

    private void scheduleProgressBlackScreenCheck() {
        if (released || firstFrameReported || !fileLoaded || !surfaceReady || !state.playWhenReady) return;
        // Frequent time-pos notifications must not push the deadline into the future.
        if (firstFrameWatchdog.arm(android.os.SystemClock.elapsedRealtime())) {
            applicationHandler.postDelayed(blackScreenWatchdog, 500);
        }
    }

    private void checkBlackScreen() {
        if (released || firstFrameReported || !fileLoaded || !surfaceReady
                || !state.playWhenReady || !hasVideoTrack()) {
            firstFrameWatchdog.reset();
            return;
        }
        long nowMs = android.os.SystemClock.elapsedRealtime();
        if (isEmbedVo() && firstFrameWatchdog.allowEmbedRecovery(nowMs)
                && Math.abs(positionMs - firstFrameStartPositionMs) >= 300) {
            firstFrameWatchdog.reset();
            recoverVideoOutput("progress without first frame");
        } else if (firstFrameWatchdog.expired(nowMs)) {
            firstFrameWatchdog.reset();
            // GPU receives a real bounded error, not a forced soft/hard or quality change.
            // Preserve intent for PlayerManager's one-shot recovery; a user pause cancels this check.
            updateState(STATE_IDLE, state.playWhenReady, new PlaybackException(
                    "MPV video did not render a first frame", null, PlaybackException.ERROR_CODE_TIMEOUT));
        } else {
            applicationHandler.postDelayed(blackScreenWatchdog, 500);
        }
    }

    private void recoverVideoOutput(String reason) {
        if (released || surfaceRecovering) return;
        surfaceRecovering = true;
        firstFrameReported = false;
        playbackRestartSeen = false;
        videoFrameSeen = false;
        lastNativeError = reason;
        boolean wasEmbed = isEmbedVo();
        if (wasEmbed && embedSurfaceRetries < EMBED_SURFACE_RETRY_LIMIT) {
            // Keep zero-copy: drop poisoned Surface binding and re-offer the current window.
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
            applicationHandler.postDelayed(this::reofferCurrentVideoOutput, 200);
            return;
        }
        // Exhausted embed retries — use mediacodec with gpu/android, never copy-back.
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
                updateState(STATE_IDLE, state.playWhenReady, new PlaybackException(reason, null,
                        PlaybackException.ERROR_CODE_DECODING_FAILED));
            }, 3_000);
            return;
        }
        MPVLib.setPropertyAsync("vo", "null");
        MPVLib.setPropertyAsync("hwdec", getDecodeOption());
        MPVLib.setPropertyAsync("vo", getVo());
        if (fileLoaded) {
            reopenVideoDecoder();
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

    private boolean videoFrameSeen;

    private void reportFirstFrame() {
        // An unchanged video-out-params size may not emit a new property event after a decoder
        // reopen or same-sized file. Wait for the VO's current non-placeholder video frame,
        // NOT audio progress or width/height; this also handles a paused first frame.
        if (firstFrameReported || !surfaceReady || !fileLoaded || !hasVideoTrack()
                || !videoFrameSeen || !playbackRestartSeen) return;
        firstFrameReported = true;
        surfaceRecovering = false;
        firstFrameWatchdog.reset();
        applicationHandler.removeCallbacks(blackScreenWatchdog);
        // The decoder is settled once a real frame exists, so this is the point to find out which
        // one libmpv actually used. Read it from the event-fed cache, never a blocking query: mpv
        // publishes hwdec-current on video-reconfig, which precedes the first frame.
        noteActiveHwdec(MPVLib.getCachedString("hwdec-current"));
        state = buildState(STATE_READY, state.playWhenReady, null).buildUpon()
                .setNewlyRenderedFirstFrame(true)
                .build();
        invalidateState();
        // newlyRenderedFirstFrame is an edge event. Do not leave it in the backing state or
        // later unrelated invalidations will notify PlayerView/listeners repeatedly.
        state = state.buildUpon().setNewlyRenderedFirstFrame(false).build();
    }

    private boolean hasVideoTrack() {
        // A preceding video's dimensions can survive an audio-only load. vid=no leaves video
        // groups unselected; don't treat metadata/stale dimensions as active video output.
        for (Tracks.Group group : currentTracks.getGroups()) {
            if (group.getType() == C.TRACK_TYPE_VIDEO && group.isSelected()) return true;
        }
        return false;
    }

    private void refreshTracks() {
        Integer count = MPVLib.getCachedInt("track-list/count");
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
            String typeName = MPVLib.getCachedString(prefix + "type");
            int type = switch (typeName == null ? "" : typeName) {
                case "video" -> C.TRACK_TYPE_VIDEO;
                case "audio" -> C.TRACK_TYPE_AUDIO;
                case "sub" -> C.TRACK_TYPE_TEXT;
                default -> C.TRACK_TYPE_UNKNOWN;
            };
            if (type == C.TRACK_TYPE_UNKNOWN) continue;
            Integer id = MPVLib.getCachedInt(prefix + "id");
            if (id == null) continue;
            Format.Builder format = new Format.Builder()
                    .setId(Integer.toString(id))
                    .setLabel(MPVLib.getCachedString(prefix + "title"))
                    .setLanguage(MPVLib.getCachedString(prefix + "lang"))
                    .setCodecs(MPVLib.getCachedString(prefix + "codec"));
            // Media3 infers group type from MIME, not native group ids/codec names.
            // Unclassified video makes PlayerView close its audio-only shutter when subtitle
            // tracks change, hiding the otherwise valid MediaCodec Surface output.
            if (type == C.TRACK_TYPE_VIDEO) format.setSampleMimeType("video/x-unknown");
            if (type == C.TRACK_TYPE_AUDIO) format.setSampleMimeType("audio/x-unknown");
            if (type == C.TRACK_TYPE_TEXT) format.setSampleMimeType(
                    MpvSubtitleFormats.mimeType(MPVLib.getCachedString(prefix + "codec")));
            Integer width = MPVLib.getCachedInt(prefix + "demux-w");
            Integer height = MPVLib.getCachedInt(prefix + "demux-h");
            Integer channels = MPVLib.getCachedInt(prefix + "demux-channel-count");
            Integer sampleRate = MPVLib.getCachedInt(prefix + "demux-samplerate");
            if (width != null) format.setWidth(width);
            if (height != null) format.setHeight(height);
            if (channels != null) format.setChannelCount(channels);
            if (sampleRate != null) format.setSampleRate(sampleRate);
            formats.computeIfAbsent(type, ignored -> new ArrayList<>()).add(format.build());
            ids.computeIfAbsent(type, ignored -> new ArrayList<>()).add(id);
            selected.computeIfAbsent(type, ignored -> new ArrayList<>())
                    .add(Boolean.TRUE.equals(MPVLib.getCachedBoolean(prefix + "selected")));
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
        Integer chapterCount = MPVLib.getCachedInt("chapter-list/count");
        List<MediaChapter> refreshedChapters = new ArrayList<>();
        int selectedChapter = valueOr(MPVLib.getCachedInt("chapter"), -1);
        for (int i = 0; i < valueOr(chapterCount, 0); i++) {
            Double time = MPVLib.getCachedDouble("chapter-list/" + i + "/time");
            String title = MPVLib.getCachedString("chapter-list/" + i + "/title");
            refreshedChapters.add(MediaChapter.chapter(i,
                    time == null ? 0 : (long) (time * 1_000_000),
                    title == null ? Integer.toString(i + 1) : title, i == selectedChapter));
        }
        chapters = List.copyOf(refreshedChapters);

        Integer editionCount = MPVLib.getCachedInt("edition-list/count");
        List<MediaEdition> refreshedEditions = new ArrayList<>();
        // mpv returns the literal "auto" until a concrete edition is selected. Requesting that
        // property as MPV_FORMAT_INT64 logs "unsupported format" for ordinary HLS/MP4 files.
        int selectedEdition = parseInt(MPVLib.getCachedString("edition"), -1);
        for (int i = 0; i < valueOr(editionCount, 0); i++) {
            Integer id = MPVLib.getCachedInt("edition-list/" + i + "/id");
            String title = MPVLib.getCachedString("edition-list/" + i + "/title");
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

    private static int parseInt(@Nullable String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void reopenVideoDecoder() {
        String selected = config.preInitOptions.getOrDefault("vid", "auto");
        for (Tracks.Group group : currentTracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_VIDEO) continue;
            for (int i = 0; i < group.length; i++) {
                if (group.isTrackSelected(i) && group.getTrackFormat(i).id != null) {
                    selected = group.getTrackFormat(i).id;
                    break;
                }
            }
        }
        // mpv's video-reload is ONLY for external tracks, not the internal MKV/MP4 decoder.
        // Async reply serialization guarantees the old decoder closes before this track reopens.
        MPVLib.setPropertyAsync("vid", "no");
        MPVLib.setPropertyAsync("vid", selected);
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
        // Warnings such as a refused seek must not become the reason for a later empty END_FILE.
        if (level <= 20) onApplicationThread(() -> lastNativeError = "mpv[" + prefix + "]: " + trimmed);
    }

    private void fallbackRendering() {
        // Player replacement and Activity teardown detach the Android window before every
        // asynchronous native log has drained. Rebuilding gpu-next without a live window turns a
        // recoverable stream/HTTP error into "Missing surface pointer" and can poison the next
        // MPV instance.
        if (released || renderFallbackUsed || !fileLoaded || !surfaceReady) return;
        renderFallbackUsed = true;
        firstFrameReported = false;
        playbackRestartSeen = false;
        videoFrameSeen = false;
        lastNativeError = null;
        // Rebind the render target without changing the user's quality/profile options.
        // A direct MediaCodec presentation error can leave the existing EGL/VO state poisoned.
        // Recreate it before reloading the decoder; changing hwdec alone still produces black.
        if (decode == 2) embedVoDisabled = true;
        String activeHwdec = MPVLib.getCachedString("hwdec");
        MPVLib.setPropertyAsync("vo", "null");
        if ((decode == 1 || embedVoDisabled) && activeHwdec != null && !"no".equals(activeHwdec)) {
            MPVLib.setPropertyAsync("hwdec", HWDEC_HARD);
        }
        MPVLib.setPropertyAsync("vo", getVo());
        // Reload only the video decoder. Demuxing, audio and the current live position continue.
        reopenVideoDecoder();
        scheduleProgressBlackScreenCheck();
    }

    public static final class Builder {

        private final Context context;
        private int decode;
        private boolean live;
        @Nullable private MpvPlayerConfig config;

        public Builder(Context context) {
            this.context = context.getApplicationContext();
        }

        public Builder setDecode(int decode) {
            this.decode = decode;
            return this;
        }

        public Builder setLive(boolean live) {
            this.live = live;
            return this;
        }

        public Builder setConfig(MpvPlayerConfig config) {
            this.config = config;
            return this;
        }

        public MpvPlayer build() {
            MpvPlayerConfig resolved =
                    config == null ? new MpvPlayerConfig.Builder().build() : config;
            return new MpvPlayer(context, decode, live, resolved);
        }
    }
}
