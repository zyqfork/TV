package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.ArrayRes;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.effect.audio.AudioChannelMode;
import com.fongmi.android.tv.player.effect.audio.AudioEffectBands;
import com.fongmi.android.tv.player.effect.audio.AudioEffectPreset;
import com.fongmi.android.tv.player.effect.video.VideoEffectPreset;
import com.fongmi.android.tv.setting.AudioSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.VideoSetting;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Locale;
import java.util.function.IntConsumer;

/** Compact, dependency-free settings UI shared by touch and Leanback builds. */
public final class EffectSettingDialog {

    private EffectSettingDialog() {
    }

    public static void showAudio(FragmentActivity activity) {
        showAudio(activity, null);
    }

    public static void showAudio(FragmentActivity activity, PlayerManager player) {
        AudioUi ui = new AudioUi(activity, player);
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.player_audio_effect)
                .setView(ui.root)
                .setPositiveButton(R.string.dialog_positive, null)
                .setNeutralButton(R.string.dialog_reset, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            AudioSetting.reset();
            ui.rebuild();
            ui.apply();
        }));
        dialog.show();
    }

    public static void showVideo(FragmentActivity activity) {
        showVideo(activity, null);
    }

    public static void showVideo(FragmentActivity activity, PlayerManager player) {
        VideoUi ui = new VideoUi(activity, player);
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.player_video_effect)
                .setView(ui.root)
                .setPositiveButton(R.string.dialog_positive, null)
                .setNeutralButton(R.string.dialog_reset, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            VideoSetting.reset();
            ui.rebuild();
            ui.apply();
        }));
        dialog.show();
    }

    private abstract static class Ui {
        final FragmentActivity activity;
        final PlayerManager player;
        final ScrollView root;
        final LinearLayout content;
        boolean binding;

        Ui(FragmentActivity activity, PlayerManager player) {
            this.activity = activity;
            this.player = player;
            root = new ScrollView(activity);
            content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            int pad = ResUtil.dp2px(20);
            content.setPadding(pad, pad / 2, pad, pad);
            root.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        void clear() {
            binding = true;
            content.removeAllViews();
        }

        void done() {
            binding = false;
        }

        Spinner spinner(@ArrayRes int array, int selected, IntConsumer callback) {
            Spinner spinner = new Spinner(activity);
            ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(activity, array, android.R.layout.simple_spinner_dropdown_item);
            spinner.setAdapter(adapter);
            spinner.setSelection(selected, false);
            spinner.setOnItemSelectedListener(new SimpleItemSelectedListener(position -> {
                if (!binding && position != selected) callback.accept(position);
            }));
            content.addView(spinner, matchWrap());
            return spinner;
        }

        CheckBox check(int text, boolean checked, java.util.function.Consumer<Boolean> callback) {
            CheckBox check = new CheckBox(activity);
            check.setText(text);
            check.setChecked(checked);
            check.setOnCheckedChangeListener((button, value) -> {
                if (!binding) callback.accept(value);
            });
            content.addView(check, matchWrap());
            return check;
        }

        void section(int text) {
            TextView label = new TextView(activity);
            label.setText(text);
            label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            label.setTextSize(16);
            label.setPadding(0, ResUtil.dp2px(16), 0, ResUtil.dp2px(4));
            content.addView(label, matchWrap());
        }

        void slider(String label, int min, int max, int value, int step, IntConsumer callback) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(activity);
            title.setText(formatLabel(label, value));
            SeekBar seek = new SeekBar(activity);
            seek.setMax(Math.max(1, (max - min) / Math.max(1, step)));
            seek.setProgress((value - min) / Math.max(1, step));
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    int next = Math.clamp(min + progress * Math.max(1, step), min, max);
                    title.setText(formatLabel(label, next));
                    if (fromUser && !binding) callback.accept(next);
                }

                @Override public void onStartTrackingTouch(SeekBar bar) { }
                @Override public void onStopTrackingTouch(SeekBar bar) { }
            });
            row.addView(title, matchWrap());
            row.addView(seek, matchWrap());
            content.addView(row, matchWrap());
        }

        String formatLabel(String label, int value) {
            return String.format(Locale.getDefault(), "%s  %d", label, value);
        }

        LinearLayout.LayoutParams matchWrap() {
            return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        abstract void rebuild();
        abstract void apply();
    }

    private static final class AudioUi extends Ui {
        AudioEffectBands bands = AudioEffectBands.STANDARD;

        AudioUi(FragmentActivity activity, PlayerManager player) {
            super(activity, player);
            rebuild();
        }

        @Override
        void rebuild() {
            clear();
            section(R.string.effect_preset);
            spinner(R.array.audio_effect_presets, AudioSetting.isEnabled() ? AudioSetting.getPreset() : AudioEffectPreset.OFF, value -> {
                AudioSetting.putPreset(value);
                apply();
                rebuild();
            });
            section(R.string.effect_equalizer);
            short[] levels = AudioSetting.getCustomLevels(bands);
            for (int i = 0; i < bands.getCount(); i++) {
                final int index = i;
                String hz = bands.getCenterFrequency(i) >= 1_000_000
                        ? (bands.getCenterFrequency(i) / 1_000_000) + " kHz"
                        : (bands.getCenterFrequency(i) / 1000) + " Hz";
                slider(hz, -1200, 1200, levels[i], 50, value -> {
                    short[] changed = AudioSetting.getCustomLevels(bands);
                    changed[index] = (short) value;
                    AudioSetting.putCustomLevels(bands, changed);
                    AudioSetting.putPreset(AudioEffectPreset.CUSTOM);
                    apply();
                });
            }
            section(R.string.effect_dynamics);
            slider(activity.getString(R.string.effect_stability), 0, 100, AudioSetting.getStability(), 5, value -> { AudioSetting.putStability(value); apply(); });
            slider(activity.getString(R.string.effect_dialogue), 0, 100, AudioSetting.getDialogue(), 5, value -> { AudioSetting.putDialogue(value); apply(); });
            slider(activity.getString(R.string.effect_boost), 0, 1200, AudioSetting.getBoost(), 50, value -> { AudioSetting.putBoost(value); apply(); });
            slider(activity.getString(R.string.effect_preamp), -1200, 0, AudioSetting.getPreamp(), 50, value -> { AudioSetting.putPreamp(value); apply(); });
            check(R.string.effect_loudness, AudioSetting.isLoudnessEnabled(), value -> { AudioSetting.putLoudness(value); apply(); });
            section(R.string.effect_channels);
            spinner(R.array.audio_channel_modes, AudioSetting.getChannelMode(), value -> { AudioSetting.putChannelMode(value); apply(); });
            slider(activity.getString(R.string.effect_balance), -100, 100, AudioSetting.getBalance(), 5, value -> { AudioSetting.putBalance(value); apply(); });
            slider(activity.getString(R.string.effect_center), 0, 1200, AudioSetting.getCenterGain(), 50, value -> { AudioSetting.putCenterGain(value); apply(); });
            done();
        }

        @Override
        void apply() {
            if (player != null) player.refreshAudioSetting();
        }
    }

    private static final class VideoUi extends Ui {
        VideoUi(FragmentActivity activity, PlayerManager player) {
            super(activity, player);
            rebuild();
        }

        @Override
        void rebuild() {
            clear();
            section(R.string.effect_preset);
            spinner(R.array.video_effect_presets, VideoSetting.isEnabled() ? VideoSetting.getPreset() : VideoEffectPreset.OFF, value -> {
                VideoSetting.putPreset(value);
                apply();
                rebuild();
            });
            section(R.string.effect_video_adjust);
            slider(activity.getString(R.string.effect_saturation), 50, 200, Math.round(VideoSetting.getSaturation() * 100), 5, value -> custom(() -> VideoSetting.putSaturation(value / 100f)));
            slider(activity.getString(R.string.effect_contrast), 50, 180, Math.round(VideoSetting.getContrast() * 100), 5, value -> custom(() -> VideoSetting.putContrast(value / 100f)));
            slider(activity.getString(R.string.effect_brightness), -20, 20, Math.round(VideoSetting.getBrightness() * 100), 1, value -> custom(() -> VideoSetting.putBrightness(value / 100f)));
            boolean sharpnessSupported = player != null ? player.getCapabilities().videoDetailEnhancement() : true;
            if (sharpnessSupported) slider(activity.getString(R.string.effect_sharpness), 0, 80, Math.round(VideoSetting.getSharpness() * 100), 5, value -> custom(() -> VideoSetting.putSharpness(value / 100f)));
            slider(activity.getString(R.string.effect_shadow), 0, 60, Math.round(VideoSetting.getShadow() * 100), 5, value -> custom(() -> VideoSetting.putShadow(value / 100f)));
            slider(activity.getString(R.string.effect_gamma), 50, 200, Math.round(VideoSetting.getGamma() * 100), 5, value -> custom(() -> VideoSetting.putGamma(value / 100f)));
            slider(activity.getString(R.string.effect_hue), -180, 180, Math.round(VideoSetting.getHue()), 5, value -> custom(() -> VideoSetting.putHue(value)));
            slider(activity.getString(R.string.effect_temperature), -100, 100, Math.round(VideoSetting.getTemperature()), 5, value -> custom(() -> VideoSetting.putTemperature(value)));
            done();
        }

        void custom(Runnable change) {
            change.run();
            VideoSetting.putPreset(VideoEffectPreset.CUSTOM);
            apply();
        }

        @Override
        void apply() {
            if (player != null) player.refreshVideoSetting();
        }
    }

    private static final class SimpleItemSelectedListener implements android.widget.AdapterView.OnItemSelectedListener {
        private final IntConsumer callback;

        SimpleItemSelectedListener(IntConsumer callback) {
            this.callback = callback;
        }

        @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { callback.accept(position); }
        @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
    }
}
