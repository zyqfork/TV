package com.fongmi.android.tv.player.exo;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.AudioOutputProvider;
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
        return buildPlayer(decode, listener, null);
    }

    public static ExoPlayer buildPlayer(int decode, Player.Listener listener, AudioProcessor audioProcessor) {
        return buildPlayer(decode, listener, audioProcessor, false, null);
    }

    public static ExoPlayer buildPlayer(int decode, Player.Listener listener, AudioProcessor audioProcessor, boolean live) {
        return buildPlayer(decode, listener, audioProcessor, live, null);
    }

    /**
     * @param onVideoDecoderInitialized receives the video decoder Media3 actually opened, so the
     *     caller can notice a software decoder where hardware was requested. Null to ignore.
     */
    public static ExoPlayer buildPlayer(int decode, Player.Listener listener, AudioProcessor audioProcessor,
                                        boolean live, @Nullable java.util.function.Consumer<String> onVideoDecoderInitialized) {
        decode = decode == PlayerEngine.SOFT ? PlayerEngine.SOFT : PlayerEngine.HARD;
        ExoPlayer player = new ExoPlayer.Builder(App.get())
                .setTrackSelector(buildTrackSelector(decode))
                .setLoadControl(buildLoadControl(live))
                .setRenderersFactory(buildPlaybackRenderersFactory(decode, audioProcessor, onVideoDecoderInitialized))
                .setMediaSourceFactory(buildMediaSourceFactory())
                .build();
        if (BuildConfig.DEBUG) player.addAnalyticsListener(new EventLogger());
        player.setAudioAttributes(AudioAttributes.DEFAULT, true);
        player.setHandleAudioBecomingNoisy(true);
        player.setPlayWhenReady(true);
        player.addListener(listener);
        return player;
    }

    /** Map Setting buffer seconds (1–15) onto Media3 LoadControl durations. */
    public static LoadControl buildLoadControl() {
        return buildLoadControl(false);
    }

    /**
     * The setting is the target reservoir in seconds. 1s and 15s must not collapse to the same
     * floor. Playback still starts once half of that reservoir is ready, capped so a long buffer
     * does not hold the first frame. Live low-latency ignores the reservoir and stays small.
     * VOD keeps a short back buffer so a small rewind does not refetch.
     */
    public static LoadControl buildLoadControl(boolean live) {
        int targetMs = PlayerSetting.getBuffer() * 1000;
        int playbackMs = Math.clamp(targetMs / 2, 500, live ? 3000 : 5000);
        int rebufferMs = Math.clamp(targetMs, playbackMs, live ? 8000 : 15000);
        int minBufferMs = Math.max(targetMs, rebufferMs);
        int maxBufferMs = Math.min(Math.max(minBufferMs * 2, minBufferMs), live ? 20000 : 60000);
        int targetBufferBytes = getTargetBufferBytes(live);
        int backBufferMs = live ? 0 : Math.min(10000, targetMs);
        if (live && PlayerSetting.isLiveLowLatency()) {
            minBufferMs = Math.max(1000, Math.min(targetMs, 2000));
            maxBufferMs = Math.max(minBufferMs * 2, 4000);
            playbackMs = Math.min(1000, minBufferMs);
            rebufferMs = Math.min(minBufferMs, Math.max(playbackMs, 1500));
            targetBufferBytes = 8 * 1024 * 1024;
            backBufferMs = 0;
        }
        return new DefaultLoadControl.Builder()
                .setBufferDurationsMs(minBufferMs, maxBufferMs, playbackMs, rebufferMs)
                .setTargetBufferBytes(targetBufferBytes)
                .setBackBuffer(backBufferMs, backBufferMs > 0)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
    }

    /** Settings that are read only when an ExoPlayer is constructed. */
    public static int playbackConfig(int decode, boolean live) {
        int value = decode == PlayerEngine.SOFT ? 1 : 2;
        value = value * 31 + (live ? 1 : 0);
        value = value * 31 + PlayerSetting.getBuffer();
        value = value * 31 + PlayerSetting.getLiveLatency();
        value = value * 31 + (PlayerSetting.isTunnelingEnabled() ? 1 : 0);
        value = value * 31 + (PlayerSetting.isAudioPassThrough() ? 1 : 0);
        value = value * 31 + (PlayerSetting.isAudioPrefer() ? 1 : 0);
        value = value * 31 + (PlayerSetting.isVideoPrefer() ? 1 : 0);
        value = value * 31 + (PlayerSetting.isPreferAAC() ? 1 : 0);
        return value;
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

    public static Map<String, String> extractHeaders(MediaItem item) {
        Bundle extras = item.requestMetadata.extras;
        if (extras == null) return new HashMap<>();
        return extras.keySet().stream().filter(key -> extras.getString(key) != null).collect(Collectors.toMap(key -> key, extras::getString));
    }

    private static TrackSelector buildTrackSelector(int decode) {
        DefaultTrackSelector trackSelector = new DefaultTrackSelector(App.get());
        DefaultTrackSelector.Parameters.Builder builder = trackSelector.buildUponParameters();
        if (PlayerSetting.isPreferAAC()) builder.setPreferredAudioMimeType(MimeTypes.AUDIO_AAC);
        builder.setPreferredTextLanguages(LangUtil.getPreferredTextLanguages());
        // Tunneling needs a hardware video and audio decoder. Soft video, 「视频软解」 or
        // 「音频软解」 makes Media3 fail the track instead of playing.
        boolean software = decode == PlayerEngine.SOFT || PlayerSetting.isVideoPrefer() || PlayerSetting.isAudioPrefer();
        builder.setTunnelingEnabled(PlayerSetting.isTunnelingEnabled() && !software);
        trackSelector.setParameters(builder.build());
        return trackSelector;
    }

    private static RenderersFactory buildPlaybackRenderersFactory(int decode, AudioProcessor audioProcessor) {
        return buildRenderersFactory(PlayerSetting.isAudioPrefer(), PlayerSetting.isVideoPrefer(), decode, audioProcessor, null);
    }

    private static RenderersFactory buildPlaybackRenderersFactory(int decode, AudioProcessor audioProcessor,
                                                                 @Nullable java.util.function.Consumer<String> onVideoDecoderInitialized) {
        return buildRenderersFactory(PlayerSetting.isAudioPrefer(), PlayerSetting.isVideoPrefer(), decode, audioProcessor, onVideoDecoderInitialized);
    }

    static RenderersFactory buildRenderersFactory() {
        return buildRenderersFactory(PlayerSetting.isAudioPrefer(), PlayerSetting.isVideoPrefer(), PlayerEngine.HARD, null, null);
    }

    private static RenderersFactory buildRenderersFactory(boolean audioPrefer, boolean videoPrefer, int decode, AudioProcessor audioProcessor) {
        return buildRenderersFactory(audioPrefer, videoPrefer, decode, audioProcessor, null);
    }

    private static RenderersFactory buildRenderersFactory(boolean audioPrefer, boolean videoPrefer, int decode,
                                                          AudioProcessor audioProcessor,
                                                          @Nullable java.util.function.Consumer<String> onVideoDecoderInitialized) {
        boolean softwareDecode = decode == PlayerEngine.SOFT;
        DefaultRenderersFactory factory = new DefaultRenderersFactory(App.get()) {
            @Override
            protected AudioSink buildAudioSink(@NonNull Context context, boolean enableFloatOutput, boolean enableAudioOutputPlaybackParams) {
                return ExoUtil.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams, audioProcessor);
            }

            @Override
            protected void buildVideoRenderers(Context context, int extensionRendererMode,
                                               MediaCodecSelector mediaCodecSelector,
                                               boolean enableDecoderFallback, Handler eventHandler,
                                               VideoRendererEventListener eventListener,
                                               long allowedVideoJoiningTimeMs,
                                               ArrayList<Renderer> out) {
                // FFmpeg in this project is audio-only, so extension mode never changes the video
                // codec. Scene soft decode and 「视频软解」 both have to select PREFER_SOFTWARE.
                boolean videoSoftware = softwareDecode || videoPrefer;
                VideoRendererEventListener observer = eventListener;
                if (onVideoDecoderInitialized != null) {
                    // Media3 reports the decoder it actually opened here, including the one chosen
                    // by its own renderer fallback. Nothing else exposes that choice.
                    observer = new VideoRendererEventListener() {
                        @Override
                        public void onVideoDecoderInitialized(@NonNull String decoderName, long initializedTimestampMs, long initializationDurationMs) {
                            onVideoDecoderInitialized.accept(decoderName);
                            eventListener.onVideoDecoderInitialized(decoderName, initializedTimestampMs, initializationDurationMs);
                        }
                    };
                }
                super.buildVideoRenderers(context,
                        videoSoftware ? EXTENSION_RENDERER_MODE_PREFER : EXTENSION_RENDERER_MODE_ON,
                        videoSoftware ? MediaCodecSelector.PREFER_SOFTWARE : mediaCodecSelector,
                        enableDecoderFallback, eventHandler, observer,
                        allowedVideoJoiningTimeMs, out);
            }

            @Override
            protected void buildAudioRenderers(Context context, int extensionRendererMode,
                                               MediaCodecSelector mediaCodecSelector,
                                               boolean enableDecoderFallback, AudioSink audioSink,
                                               Handler eventHandler,
                                               AudioRendererEventListener eventListener,
                                               ArrayList<Renderer> out) {
                boolean audioSoftware = softwareDecode || audioPrefer;
                MediaCodecSelector audioSelector = audioSoftware
                        ? MediaCodecSelector.PREFER_SOFTWARE
                        : mediaCodecSelector;
                super.buildAudioRenderers(context,
                        audioSoftware
                                ? EXTENSION_RENDERER_MODE_PREFER
                                : EXTENSION_RENDERER_MODE_ON,
                        audioSelector, enableDecoderFallback, audioSink, eventHandler,
                        eventListener, out);
            }
        };
        return factory.setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
    }

    private static AudioSink buildAudioSink(Context context, boolean enableFloatOutput, boolean enableAudioOutputPlaybackParams, AudioProcessor audioProcessor) {
        DefaultAudioSink.Builder builder = new DefaultAudioSink.Builder(context).setEnableFloatOutput(enableFloatOutput).setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams);
        if (!PlayerSetting.isAudioPassThrough()) {
            // The default provider already is AudioTrack and still accepts AC3/DTS/E-AC3 directly.
            // Rejecting non-PCM is what actually turns passthrough off.
            AudioOutputProvider output = new AudioTrackAudioOutputProvider.Builder(context).build();
            builder.setAudioOutputProvider(new PcmOnlyAudioOutputProvider(output));
        }
        if (audioProcessor != null) builder.setAudioProcessors(new AudioProcessor[]{audioProcessor});
        return builder.build();
    }

    private static MediaSource.Factory buildMediaSourceFactory() {
        return new MediaSourceFactory();
    }
}
