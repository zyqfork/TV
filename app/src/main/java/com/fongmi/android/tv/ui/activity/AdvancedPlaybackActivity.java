package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityAdvancedPlaybackBinding;
import com.fongmi.android.tv.player.mpv.MpvConfigFiles;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.fongmi.android.tv.ui.custom.CustomWallView;
import com.fongmi.android.tv.ui.dialog.MpvConfDialog;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Single-level advanced rows: tapping a row cycles its value like the other settings pages. */
public class AdvancedPlaybackActivity extends AppCompatActivity {

    private ActivityAdvancedPlaybackBinding binding;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, AdvancedPlaybackActivity.class));
    }

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        binding = ActivityAdvancedPlaybackBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        // Same wallpaper layer as the settings pages; without it this page falls back to black.
        ((ViewGroup) findViewById(android.R.id.content)).addView(new CustomWallView(this, null), 0,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Util.hideSystemUI(this);
        binding.decode.requestFocus();
        binding.decode.setOnClickListener(this::toggleDecode);
        binding.buffer.setOnClickListener(this::toggleBuffer);
        binding.audio.setOnClickListener(this::toggleAudio);
        binding.preload.setOnClickListener(this::togglePreload);
        binding.customConfig.setOnClickListener(this::toggleCustomConfig);
        binding.gpuNext.setOnClickListener(this::toggleGpuNext);
        binding.audioLang.setOnClickListener(this::cycleAudioLanguage);
        binding.quality.setOnClickListener(this::cycleQuality);
        binding.deband.setOnClickListener(this::toggleDeband);
        binding.editConfig.setOnClickListener(v -> MpvConfDialog.show(this));
        binding.info.setOnClickListener(v -> toggleStats());
        binding.restore.setOnClickListener(this::restore);
        refresh();
    }

    private void refresh() {
        boolean mpv = PlayerSetting.isMpv();
        int engine = mpv ? PlayerSetting.ENGINE_MPV : PlayerSetting.ENGINE_EXO;
        binding.decodeText.setText(decodeLabel(engine));
        binding.bufferText.setText(PlayerSetting.getBuffer() == 5
                ? R.string.player_buffer_standard : R.string.player_buffer_large);
        binding.audio.setVisibility(mpv ? View.GONE : View.VISIBLE);
        binding.preload.setVisibility(mpv ? View.GONE : View.VISIBLE);
        binding.customConfig.setVisibility(mpv ? View.VISIBLE : View.GONE);
        binding.gpuNext.setVisibility(mpv ? View.VISIBLE : View.GONE);
        binding.deband.setVisibility(mpv ? View.VISIBLE : View.GONE);
        binding.quality.setVisibility(mpv ? View.GONE : View.VISIBLE);
        binding.audioLangText.setText(switch (PlayerSetting.getAudioLanguage()) {
            case PlayerSetting.AUDIO_MANDARIN -> R.string.player_audio_lang_mandarin;
            case PlayerSetting.AUDIO_CANTONESE -> R.string.player_audio_lang_cantonese;
            case PlayerSetting.AUDIO_ENGLISH -> R.string.player_audio_lang_english;
            default -> R.string.player_audio_lang_follow;
        });
        binding.qualityText.setText(switch (PlayerSetting.getMaxVideoHeight()) {
            case PlayerSetting.QUALITY_1080 -> R.string.player_quality_1080;
            case PlayerSetting.QUALITY_4K -> R.string.player_quality_4k;
            default -> R.string.player_quality_auto;
        });
        binding.debandText.setText(PlayerSetting.isMpvDeband() ? R.string.setting_on : R.string.setting_off);
        binding.editConfig.setVisibility(mpv ? View.VISIBLE : View.GONE);
        binding.audioText.setText(PlayerSetting.isAudioPassThrough()
                ? R.string.player_audio_auto : R.string.player_audio_pcm);
        binding.preloadText.setText(PreloadSetting.isPreload() ? R.string.setting_on : R.string.setting_off);
        binding.customConfigText.setText(MpvConfigFiles.isEnabled() ? R.string.setting_on : R.string.setting_off);
        binding.gpuNextText.setText(PlayerSetting.isMpvGpuNext() ? R.string.setting_on : R.string.setting_off);
        binding.infoText.setText(PlayerSetting.isPlaybackStatsVisible() ? R.string.setting_on : R.string.setting_off);
    }

    private int decodeLabel(int engine) {
        boolean live = PlayerSetting.getDecode(true, engine) == 0;
        boolean vod = PlayerSetting.getDecode(false, engine) == 0;
        if (live != vod) return R.string.player_decode_mixed;
        return live ? R.string.player_decode_software : R.string.player_decode_auto;
    }

    private void toggleDecode(View view) {
        int engine = PlayerSetting.isMpv() ? PlayerSetting.ENGINE_MPV : PlayerSetting.ENGINE_EXO;
        int next = PlayerSetting.getDecode(false, engine) == 0 ? 1 : 0;
        PlayerSetting.putDecode(false, engine, next);
        PlayerSetting.putDecode(true, engine, next);
        refresh();
    }

    private void toggleBuffer(View view) {
        PlayerSetting.putBuffer(PlayerSetting.getBuffer() == 5 ? 10 : 5);
        refresh();
    }

    private void toggleAudio(View view) {
        PlayerSetting.putAudioPassThrough(!PlayerSetting.isAudioPassThrough());
        refresh();
    }

    private void togglePreload(View view) {
        PreloadSetting.putPreload(!PreloadSetting.isPreload());
        refresh();
    }

    private void cycleAudioLanguage(View view) {
        PlayerSetting.putAudioLanguage((PlayerSetting.getAudioLanguage() + 1) % 4);
        refresh();
    }

    private void cycleQuality(View view) {
        int next = switch (PlayerSetting.getMaxVideoHeight()) {
            case PlayerSetting.QUALITY_1080 -> PlayerSetting.QUALITY_4K;
            case PlayerSetting.QUALITY_4K -> PlayerSetting.QUALITY_AUTO;
            default -> PlayerSetting.QUALITY_1080;
        };
        PlayerSetting.putMaxVideoHeight(next);
        refresh();
    }

    private void toggleDeband(View view) {
        PlayerSetting.putMpvDeband(!PlayerSetting.isMpvDeband());
        refresh();
    }

    private void toggleGpuNext(View view) {
        PlayerSetting.putMpvGpuNext(!PlayerSetting.isMpvGpuNext());
        refresh();
    }

    private void toggleCustomConfig(View view) {
        MpvConfigFiles.setEnabled(!MpvConfigFiles.isEnabled());
        refresh();
    }

    private void toggleStats() {
        PlayerSetting.putPlaybackStatsVisible(!PlayerSetting.isPlaybackStatsVisible());
        refresh();
    }

    private void restore(View view) {
        new MaterialAlertDialogBuilder(this).setTitle(R.string.player_restore_defaults)
                .setMessage(R.string.player_restore_scope)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    PlayerSetting.restorePlaybackDefaults();
                    refresh();
                }).show();
    }
}
