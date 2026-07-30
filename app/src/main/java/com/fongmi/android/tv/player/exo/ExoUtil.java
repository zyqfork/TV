package com.fongmi.android.tv.player.exo;

import android.content.Context;
import android.app.ActivityManager;
import android.os.Bundle;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.TrackSelector;
import androidx.media3.exoplayer.util.EventLogger;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.track.LangUtil;
import com.fongmi.android.tv.setting.PlayerSetting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class ExoUtil {

    public static ExoPlayer buildPlayer(int decode, Player.Listener listener) {
        return buildPlayer(decode, false, PlayerSetting.isAudioPassThrough(), listener);
    }

    public static ExoPlayer buildPlayer(int decode, boolean live, boolean audioPassThrough, Player.Listener listener) {
        ExoPlayer player = new ExoPlayer.Builder(App.get())
                .setTrackSelector(buildTrackSelector(live))
                .setLoadControl(buildLoadControl(live))
                .setRenderersFactory(buildPlaybackRenderersFactory(decode, audioPassThrough))
                .setMediaSourceFactory(buildMediaSourceFactory(live))
                .build();
        if (BuildConfig.DEBUG) player.addAnalyticsListener(new EventLogger());
        player.setAudioAttributes(AudioAttributes.DEFAULT, true);
        player.setHandleAudioBecomingNoisy(true);
        player.setPlayWhenReady(true);
        player.addListener(listener);
        return player;
    }

    /**
     * Map Setting buffer seconds (1–15) onto Media3 LoadControl durations.
     * Mirrors fork/dev exo_buffer behavior for weak-network / IPTV resilience.
     */
    public static LoadControl buildLoadControl() {
        return buildLoadControl(false);
    }

    public static LoadControl buildLoadControl(boolean live) {
        int bufferMs = PlayerSetting.getBuffer() * 1000;
        int minBufferMs = Math.max(bufferMs, live ? 3000 : 5000);
        int maxBufferMs = Math.clamp(minBufferMs * 3, live ? 8000 : 15000,
                live ? 15000 : 30000);
        int playbackMs = Math.min(2500, Math.max(1000, bufferMs / 2));
        int rebufferMs = Math.min(5000, Math.max(playbackMs, bufferMs));
        int targetBufferBytes = getTargetBufferBytes(live);
        if (live && PlayerSetting.isLiveLowLatency()) {
            minBufferMs = Math.max(1000, bufferMs / 2);
            maxBufferMs = Math.max(minBufferMs * 2, 6000);
            playbackMs = Math.min(1200, minBufferMs);
            rebufferMs = Math.min(2500, Math.max(playbackMs, minBufferMs));
            targetBufferBytes = 8 * 1024 * 1024;
        }
        return new DefaultLoadControl.Builder()
                .setBufferDurationsMs(minBufferMs, maxBufferMs, playbackMs, rebufferMs)
                .setTargetBufferBytes(targetBufferBytes)
                .setBackBuffer(0, false)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
    }

    /** Scale memory buffering to the actual device class rather than reserving 32 MiB everywhere. */
    private static int getTargetBufferBytes(boolean live) {
        ActivityManager manager = (ActivityManager) App.get().getSystemService(Context.ACTIVITY_SERVICE);
        int memoryClass = manager == null ? 256 : manager.getMemoryClass();
        if (live) return memoryClass <= 256 ? 8 * 1024 * 1024 : 16 * 1024 * 1024;
        if (memoryClass <= 256) return 16 * 1024 * 1024;
        if (memoryClass <= 512) return 24 * 1024 * 1024;
        return 32 * 1024 * 1024;
    }

    public static String getMimeType(int errorCode) {
        if (errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED || errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED || errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED) return MimeTypes.APPLICATION_M3U8;
        if (errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED || errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED) return MimeTypes.APPLICATION_OCTET_STREAM;
        return null;
    }

    public static Map<String, String> extractHeaders(MediaItem item) {
        Bundle extras = item.requestMetadata.extras;
        if (extras == null) return new HashMap<>();
        return extras.keySet().stream().filter(key -> extras.getString(key) != null).collect(Collectors.toMap(key -> key, extras::getString));
    }

    private static int getRenderMode(int decode) {
        return decode == PlayerEngine.HARD ? DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON : DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER;
    }

    private static TrackSelector buildTrackSelector(boolean live) {
        DefaultTrackSelector trackSelector = new DefaultTrackSelector(App.get());
        DefaultTrackSelector.Parameters.Builder builder = trackSelector.buildUponParameters();
        if (PlayerSetting.isPreferAAC()) builder.setPreferredAudioMimeType(MimeTypes.AUDIO_AAC);
        builder.setPreferredTextLanguages(LangUtil.getPreferredTextLanguages());
        builder.setTunnelingEnabled(PlayerSetting.isTunnelingEnabled(live));
        trackSelector.setParameters(builder.build());
        return trackSelector;
    }

    private static RenderersFactory buildPlaybackRenderersFactory(int decode, boolean audioPassThrough) {
        // Decode mode is the explicit user choice. Advanced extension preferences apply only in
        // compatibility mode and must never silently override “系统硬解优先”.
        boolean compatibility = decode != PlayerEngine.HARD;
        return buildRenderersFactory(getRenderMode(decode), compatibility && PlayerSetting.isAudioPrefer(), compatibility && PlayerSetting.isVideoPrefer(), audioPassThrough);
    }

    static RenderersFactory buildRenderersFactory() {
        return buildRenderersFactory(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER, PlayerSetting.isAudioPrefer(), PlayerSetting.isVideoPrefer(), PlayerSetting.isAudioPassThrough());
    }

    private static RenderersFactory buildRenderersFactory(int renderMode, boolean audioPrefer, boolean videoPrefer, boolean audioPassThrough) {
        boolean preferByDecode = renderMode == DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER;
        DefaultRenderersFactory factory = new DefaultRenderersFactory(App.get()) {
            @Override
            protected AudioSink buildAudioSink(@NonNull Context context, boolean enableFloatOutput, boolean enableAudioOutputPlaybackParams) {
                return ExoUtil.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams, audioPassThrough);
            }

            @Override
            protected void buildVideoRenderers(Context context, int extensionRendererMode,
                                               MediaCodecSelector mediaCodecSelector,
                                               boolean enableDecoderFallback, Handler eventHandler,
                                               VideoRendererEventListener eventListener,
                                               long allowedVideoJoiningTimeMs,
                                               ArrayList<Renderer> out) {
                super.buildVideoRenderers(context,
                        preferByDecode || videoPrefer
                                ? EXTENSION_RENDERER_MODE_PREFER
                                : EXTENSION_RENDERER_MODE_ON,
                        mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener,
                        allowedVideoJoiningTimeMs, out);
            }

            @Override
            protected void buildAudioRenderers(Context context, int extensionRendererMode,
                                               MediaCodecSelector mediaCodecSelector,
                                               boolean enableDecoderFallback, AudioSink audioSink,
                                               Handler eventHandler,
                                               AudioRendererEventListener eventListener,
                                               ArrayList<Renderer> out) {
                super.buildAudioRenderers(context,
                        preferByDecode || audioPrefer
                                ? EXTENSION_RENDERER_MODE_PREFER
                                : EXTENSION_RENDERER_MODE_ON,
                        mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler,
                        eventListener, out);
            }
        };
        return factory.setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
    }

    private static AudioSink buildAudioSink(Context context, boolean enableFloatOutput, boolean enableAudioOutputPlaybackParams, boolean audioPassThrough) {
        DefaultAudioSink.Builder builder = new DefaultAudioSink.Builder(context).setEnableFloatOutput(enableFloatOutput).setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams);
        if (!audioPassThrough) builder.setAudioOutputProvider(new AudioTrackAudioOutputProvider.Builder(null).build());
        return builder.build();
    }

    private static MediaSource.Factory buildMediaSourceFactory(boolean live) {
        // VOD benefits from a bounded read/write cache. Live bypasses it entirely.
        return new MediaSourceFactory(!live, !live);
    }
}
