package com.fongmi.android.tv.player.exo;

import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.ExoPlayer;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.player.effect.PlayerEffect;
import com.fongmi.android.tv.player.effect.audio.AudioEffectBands;
import com.fongmi.android.tv.player.effect.audio.AudioEffectConfig;
import com.fongmi.android.tv.player.effect.audio.ExoAudioEffectController;
import com.fongmi.android.tv.player.effect.video.ExoVideoEffectController;
import com.fongmi.android.tv.player.effect.video.VideoEffectProfile;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.VideoSetting;

/** Public Media3/Android implementation of the portable audio and video effect contracts. */
public final class ExoPlayerEffect implements PlayerEffect {

    private final ExoAudioEffectController audioController = new ExoAudioEffectController();
    private final ExoVideoEffectController videoController = new ExoVideoEffectController();
    private final boolean audioProcessorInstalled;
    private ExoPlayer player;
    private boolean audioFailed;
    private boolean videoFailed;

    public ExoPlayerEffect() {
        this(true);
    }

    public ExoPlayerEffect(boolean audioProcessorInstalled) {
        this.audioProcessorInstalled = audioProcessorInstalled;
    }

    public AudioProcessor getAudioProcessor() {
        return audioProcessorInstalled ? audioController.getProcessor() : null;
    }

    public boolean isAudioProcessorInstalled() {
        return audioProcessorInstalled;
    }

    public void setPlayer(ExoPlayer player) {
        this.player = player;
    }

    public void release() {
        audioController.release();
        player = null;
    }

    @Override
    public boolean supportsVideoEffect() {
        return player != null && !isVideoEffectUnsupported() && !videoFailed;
    }

    @Override
    public int getVideoEffectError() {
        if (isVideoEffectUnsupported()) return R.string.error_video_effect_unsupported;
        return videoFailed ? R.string.error_video_effect_apply : 0;
    }

    @Override
    public void applyVideoEffect() {
        if (player == null) return;
        try {
            if (VideoSetting.isEnabled() && player.getVideoFormat() == null) return;
            if (isVideoEffectUnsupported()) {
                videoController.clear(player);
                return;
            }
            if (VideoSetting.isEnabled()) videoController.apply(player, VideoSetting.getAppliedProfile());
            else videoController.clear(player);
            videoFailed = false;
        } catch (RuntimeException error) {
            videoController.clear(player);
            videoFailed = true;
        }
    }

    private boolean isVideoEffectUnsupported() {
        MediaItem item = player == null ? null : player.getCurrentMediaItem();
        return PlayerSetting.isTunnel() || (item != null && item.localConfiguration != null
                && item.localConfiguration.drmConfiguration != null) || isHdr();
    }

    private boolean isHdr() {
        Format format = player == null ? null : player.getVideoFormat();
        return format != null && ColorInfo.isTransferHdr(format.colorInfo);
    }

    @Override
    public void previewVideoEffect(boolean original) {
        if (player == null) return;
        try {
            if (original) videoController.clear(player);
            else applyVideoEffect();
        } catch (RuntimeException error) {
            videoFailed = true;
        }
    }

    @Override
    public boolean supportsAudioEffect() {
        return player != null && audioProcessorInstalled && !PlayerSetting.isAudioPassThrough() && !audioFailed;
    }

    @Override
    public AudioEffectBands getAudioEffectBands() {
        return AudioEffectBands.STANDARD;
    }

    @Override
    public int getAudioEffectError() {
        if (PlayerSetting.isAudioPassThrough() || !audioProcessorInstalled) return R.string.error_audio_effect_passthrough;
        return audioFailed ? R.string.error_audio_effect_apply : 0;
    }

    @Override
    public void applyAudioEffect() {
        if (player == null || !audioProcessorInstalled || PlayerSetting.isAudioPassThrough()) {
            audioController.release();
            return;
        }
        Format format = player.getAudioFormat();
        if (format == null || format.channelCount == Format.NO_VALUE) return;
        audioFailed = !audioController.apply(player, AudioEffectConfig.from(AudioEffectBands.STANDARD, format.channelCount));
    }

    @Override
    public void previewAudioEffect(boolean original) {
        if (original) audioController.resetToDisabled();
        else applyAudioEffect();
    }
}
