package com.fongmi.android.tv.player.mpv;

import android.util.Log;

import androidx.media3.common.Format;
import androidx.media3.mpvplayer.MpvPlayer;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.player.effect.PlayerEffect;
import com.fongmi.android.tv.player.effect.audio.AudioEffectBands;
import com.fongmi.android.tv.player.effect.audio.AudioEffectConfig;
import com.fongmi.android.tv.player.effect.audio.MpvAudioEffectFilter;
import com.fongmi.android.tv.player.effect.video.MpvVideoEffectController;
import com.fongmi.android.tv.setting.AudioSetting;
import com.fongmi.android.tv.setting.VideoSetting;

/** Public libmpv property/filter implementation. Video EQ automatically uses GPU output. */
public final class MpvPlayerEffect implements PlayerEffect {

    private static final String TAG = "MpvPlayerEffect";
    private final MpvVideoEffectController videoController = new MpvVideoEffectController();
    private final MpvPlayer player;
    private boolean audioFailed;
    private boolean videoFailed;

    public MpvPlayerEffect(MpvPlayer player) {
        this.player = player;
    }

    @Override
    public boolean supportsVideoEffect() {
        return !videoFailed;
    }

    @Override
    public boolean supportsVideoSharpness() {
        return true;
    }

    @Override
    public int getVideoEffectError() {
        return videoFailed ? R.string.error_video_effect_apply : 0;
    }

    @Override
    public void applyVideoEffect() {
        // The owner rebuilds into GPU output when a live direct session enables an effect.
        // Do not write a shader into mediacodec_embed during that transition.
        if (player.getDecode() == 2 && VideoSetting.isEnabled()) return;
        videoFailed = VideoSetting.isEnabled()
                ? !videoController.apply(player, VideoSetting.getAppliedProfile())
                : !videoController.clear(player);
    }

    @Override
    public void previewVideoEffect(boolean original) {
        if (original) videoController.clear(player);
        else applyVideoEffect();
    }

    @Override
    public boolean supportsAudioEffect() {
        return !audioFailed;
    }

    @Override
    public AudioEffectBands getAudioEffectBands() {
        return AudioEffectBands.STANDARD;
    }

    @Override
    public int getAudioEffectError() {
        return audioFailed ? R.string.error_audio_effect_apply : 0;
    }

    @Override
    public void applyAudioEffect() {
        int channels = player.getAudioChannelCount();
        if (channels == Format.NO_VALUE) {
            // The track layout is not known yet. Drop any chain left over from the previous item
            // rather than returning silently: a stale graph would otherwise keep running against a
            // different layout. PlayerManager re-applies this once the tracks are known.
            Log.d(TAG, "applyAudioEffect: channels unknown, clearing af");
            player.setAudioFilter("");
            return;
        }
        AudioEffectConfig config = AudioEffectConfig.from(AudioEffectBands.STANDARD, channels);
        String filter = MpvAudioEffectFilter.create(config, channels);
        Log.d(TAG, "applyAudioEffect: channels=" + channels + " enabled=" + AudioSetting.isEnabled()
                + " hasEffect=" + config.hasEffect() + " filter=" + (filter.isEmpty() ? "<empty>" : filter));
        audioFailed = !player.setAudioFilter(filter);
    }

    @Override
    public void previewAudioEffect(boolean original) {
        if (original) player.setAudioFilter("");
        else applyAudioEffect();
    }
}
