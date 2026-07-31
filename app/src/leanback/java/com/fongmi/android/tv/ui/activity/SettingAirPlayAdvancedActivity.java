package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivitySettingAirplayAdvancedBinding;
import com.fongmi.android.tv.service.AirPlayServer;
import com.fongmi.android.tv.setting.AirPlaySetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.AirPlayInputDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import io.github.jqssun.airplay.Prefs;

public class SettingAirPlayAdvancedActivity extends BaseActivity {

    private static final String[] RESOLUTIONS = {"auto", "3840x2160", "2560x1440", "1920x1080", "1280x720"};
    private static final int[] FPS_PRESETS = {30, 60, 90, 120};

    private ActivitySettingAirplayAdvancedBinding mBinding;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingAirPlayAdvancedActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingAirplayAdvancedBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mBinding.h265.requestFocus();
        refresh();
    }

    @Override
    protected void initEvent() {
        mBinding.h265.setOnClickListener(v -> toggle(AirPlaySetting.isH265(), AirPlaySetting::putH265, true));
        mBinding.enforceSdr.setOnClickListener(v -> toggle(AirPlaySetting.isEnforceSdr(), AirPlaySetting::putEnforceSdr, true));
        mBinding.resolution.setOnClickListener(this::setResolution);
        mBinding.maxFps.setOnClickListener(this::setMaxFps);
        mBinding.overscanned.setOnClickListener(v -> toggle(AirPlaySetting.isOverscanned(), AirPlaySetting::putOverscanned, true));
        mBinding.lowLatency.setOnClickListener(v -> toggle(AirPlaySetting.isLowLatency(), AirPlaySetting::putLowLatency, false));
        mBinding.allowFrameDrop.setOnClickListener(v -> toggle(AirPlaySetting.isAllowFrameDrop(), AirPlaySetting::putAllowFrameDrop, true));
        mBinding.realtimePriority.setOnClickListener(v -> toggle(AirPlaySetting.isRealtimePriority(), AirPlaySetting::putRealtimePriority, true));
        mBinding.operatingRate.setOnClickListener(v -> toggle(AirPlaySetting.isOperatingRate(), AirPlaySetting::putOperatingRate, true));
        mBinding.alignedPacing.setOnClickListener(v -> toggle(AirPlaySetting.isAlignedPacing(), AirPlaySetting::putAlignedPacing, true));
        mBinding.aac.setOnClickListener(v -> toggle(AirPlaySetting.isAac(), AirPlaySetting::putAac, true));
        mBinding.alac.setOnClickListener(v -> toggle(AirPlaySetting.isAlac(), AirPlaySetting::putAlac, true));
        mBinding.forceSwAlac.setOnClickListener(v -> toggle(AirPlaySetting.isForceSwAlac(), AirPlaySetting::putForceSwAlac, false));
        mBinding.audioLatency.setOnClickListener(this::setAudioLatency);
        mBinding.audioAutoBuffer.setOnClickListener(v -> toggle(AirPlaySetting.isAudioAutoBuffer(), AirPlaySetting::putAudioAutoBuffer, false));
        mBinding.audioCushion.setOnClickListener(view -> AirPlayInputDialog.show(this, AirPlayInputDialog.TYPE_AUDIO_CUSHION));
        mBinding.audioAdaptive.setOnClickListener(this::setAudioAdaptive);
        mBinding.oboeBuffer.setOnClickListener(view -> AirPlayInputDialog.show(this, AirPlayInputDialog.TYPE_OBOE_BUFFER));
        mBinding.autoFullscreen.setOnClickListener(v -> toggle(AirPlaySetting.isAutoFullscreen(), AirPlaySetting::putAutoFullscreen, false));
        mBinding.idlePreview.setOnClickListener(v -> toggle(AirPlaySetting.isIdlePreview(), AirPlaySetting::putIdlePreview, false));
        mBinding.debugOverlay.setOnClickListener(v -> toggle(AirPlaySetting.isDebugOverlay(), AirPlaySetting::putDebugOverlay, false));
        mBinding.benchmarkLog.setOnClickListener(v -> toggle(AirPlaySetting.isBenchmarkLog(), AirPlaySetting::putBenchmarkLog, true));
    }

    private interface BoolSetter {
        void put(boolean value);
    }

    private void toggle(boolean current, BoolSetter setter, boolean restart) {
        setter.put(!current);
        if (restart) AirPlayServer.apply(this);
        refresh();
    }

    private void refresh() {
        mBinding.h265Text.setText(Setting.getSwitch(AirPlaySetting.isH265()));
        mBinding.enforceSdrText.setText(Setting.getSwitch(AirPlaySetting.isEnforceSdr()));
        mBinding.resolutionText.setText(getResolutionText());
        mBinding.maxFpsText.setText(String.valueOf(AirPlaySetting.getMaxFps()));
        mBinding.overscannedText.setText(Setting.getSwitch(AirPlaySetting.isOverscanned()));
        mBinding.lowLatencyText.setText(Setting.getSwitch(AirPlaySetting.isLowLatency()));
        mBinding.allowFrameDropText.setText(Setting.getSwitch(AirPlaySetting.isAllowFrameDrop()));
        mBinding.realtimePriorityText.setText(Setting.getSwitch(AirPlaySetting.isRealtimePriority()));
        mBinding.operatingRateText.setText(Setting.getSwitch(AirPlaySetting.isOperatingRate()));
        mBinding.alignedPacingText.setText(Setting.getSwitch(AirPlaySetting.isAlignedPacing()));
        mBinding.aacText.setText(Setting.getSwitch(AirPlaySetting.isAac()));
        mBinding.alacText.setText(Setting.getSwitch(AirPlaySetting.isAlac()));
        mBinding.forceSwAlacText.setText(Setting.getSwitch(AirPlaySetting.isForceSwAlac()));
        mBinding.audioLatencyText.setText(getAudioLatencyText());
        mBinding.audioAutoBufferText.setText(Setting.getSwitch(AirPlaySetting.isAudioAutoBuffer()));
        mBinding.audioCushionText.setText(String.valueOf(AirPlaySetting.getAudioCushionMs()));
        mBinding.audioAdaptiveText.setText(getAudioAdaptiveText());
        mBinding.oboeBufferText.setText(String.valueOf(AirPlaySetting.getOboeBufferFrames()));
        mBinding.autoFullscreenText.setText(Setting.getSwitch(AirPlaySetting.isAutoFullscreen()));
        mBinding.idlePreviewText.setText(Setting.getSwitch(AirPlaySetting.isIdlePreview()));
        mBinding.debugOverlayText.setText(Setting.getSwitch(AirPlaySetting.isDebugOverlay()));
        mBinding.benchmarkLogText.setText(Setting.getSwitch(AirPlaySetting.isBenchmarkLog()));

        boolean autoBuffer = AirPlaySetting.isAudioAutoBuffer();
        mBinding.audioCushion.setVisibility(autoBuffer ? View.GONE : View.VISIBLE);
        mBinding.audioAdaptive.setVisibility(autoBuffer ? View.VISIBLE : View.GONE);
        mBinding.forceSwAlac.setVisibility(AirPlaySetting.isAlac() ? View.VISIBLE : View.GONE);
    }

    private void setResolution(View view) {
        String current = AirPlaySetting.getResolution();
        String[] labels = new String[RESOLUTIONS.length + 1];
        int selected = RESOLUTIONS.length;
        for (int i = 0; i < RESOLUTIONS.length; i++) {
            labels[i] = "auto".equals(RESOLUTIONS[i])
                    ? getString(R.string.setting_airplay_resolution_auto)
                    : RESOLUTIONS[i];
            if (RESOLUTIONS[i].equalsIgnoreCase(current)) selected = i;
        }
        labels[RESOLUTIONS.length] = getString(R.string.setting_airplay_resolution_custom);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setting_airplay_resolution)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    dialog.dismiss();
                    if (which < RESOLUTIONS.length) {
                        AirPlaySetting.putResolution(RESOLUTIONS[which]);
                        AirPlayServer.apply(this);
                        refresh();
                    } else {
                        AirPlayInputDialog.show(this, AirPlayInputDialog.TYPE_RESOLUTION);
                    }
                })
                .show();
    }

    private void setMaxFps(View view) {
        int current = AirPlaySetting.getMaxFps();
        String[] labels = new String[FPS_PRESETS.length + 1];
        int selected = FPS_PRESETS.length;
        for (int i = 0; i < FPS_PRESETS.length; i++) {
            labels[i] = String.valueOf(FPS_PRESETS[i]);
            if (FPS_PRESETS[i] == current) selected = i;
        }
        labels[FPS_PRESETS.length] = getString(R.string.setting_airplay_max_fps_custom);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setting_airplay_max_fps)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    dialog.dismiss();
                    if (which < FPS_PRESETS.length) {
                        AirPlaySetting.putMaxFps(FPS_PRESETS[which]);
                        AirPlayServer.apply(this);
                        refresh();
                    } else {
                        AirPlayInputDialog.show(this, AirPlayInputDialog.TYPE_MAX_FPS);
                    }
                })
                .show();
    }

    private void setAudioLatency(View view) {
        int current = AirPlaySetting.getAudioLatencyMs();
        String[] labels = {
                getString(R.string.setting_airplay_audio_latency_default),
                "40 ms", "80 ms", "120 ms", "200 ms",
                getString(R.string.setting_airplay_resolution_custom)
        };
        int selected = labels.length - 1;
        int[] values = {-1, 40, 80, 120, 200};
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) selected = i;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setting_airplay_audio_latency)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    dialog.dismiss();
                    if (which < values.length) {
                        AirPlaySetting.putAudioLatencyMs(values[which]);
                        AirPlayServer.apply(this);
                        refresh();
                    } else {
                        AirPlayInputDialog.show(this, AirPlayInputDialog.TYPE_AUDIO_LATENCY);
                    }
                })
                .show();
    }

    private void setAudioAdaptive(View view) {
        String[] labels = getResources().getStringArray(R.array.setting_airplay_audio_adaptive_names);
        int selected = AirPlaySetting.getAudioAdaptiveStep();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setting_airplay_audio_adaptive)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    dialog.dismiss();
                    AirPlaySetting.putAudioAdaptiveStep(which);
                    refresh();
                })
                .show();
    }

    public void setAirPlayInput(int type, String value) {
        switch (type) {
            case AirPlayInputDialog.TYPE_RESOLUTION -> {
                String res = TextUtils.isEmpty(value) ? Prefs.DEF_RESOLUTION : value.trim().toLowerCase();
                if (!"auto".equals(res) && !res.matches("\\d{2,5}x\\d{2,5}")) res = Prefs.DEF_RESOLUTION;
                AirPlaySetting.putResolution(res);
                AirPlayServer.apply(this);
            }
            case AirPlayInputDialog.TYPE_MAX_FPS -> {
                int fps = AirPlaySetting.getMaxFps();
                try {
                    fps = Integer.parseInt(value);
                } catch (Exception ignored) {
                }
                AirPlaySetting.putMaxFps(fps);
                AirPlayServer.apply(this);
            }
            case AirPlayInputDialog.TYPE_AUDIO_LATENCY -> {
                int ms = Prefs.DEF_AUDIO_LATENCY_MS;
                if (!TextUtils.isEmpty(value)) {
                    try {
                        ms = Integer.parseInt(value);
                    } catch (Exception ignored) {
                        ms = AirPlaySetting.getAudioLatencyMs();
                    }
                }
                AirPlaySetting.putAudioLatencyMs(ms);
                AirPlayServer.apply(this);
            }
            case AirPlayInputDialog.TYPE_AUDIO_CUSHION -> {
                int ms = AirPlaySetting.getAudioCushionMs();
                try {
                    ms = Integer.parseInt(value);
                } catch (Exception ignored) {
                }
                AirPlaySetting.putAudioCushionMs(ms);
            }
            case AirPlayInputDialog.TYPE_OBOE_BUFFER -> {
                int frames = AirPlaySetting.getOboeBufferFrames();
                try {
                    frames = Integer.parseInt(value);
                } catch (Exception ignored) {
                }
                AirPlaySetting.putOboeBufferFrames(frames);
            }
            default -> {
            }
        }
        refresh();
    }

    public String getResolutionHint() {
        return "1920x1080";
    }

    public String getResolutionValue() {
        String value = AirPlaySetting.getResolution();
        return "auto".equalsIgnoreCase(value) ? "" : value;
    }

    public String getMaxFpsHint() {
        return String.valueOf(Prefs.DEF_MAX_FPS);
    }

    public String getMaxFpsValue() {
        return String.valueOf(AirPlaySetting.getMaxFps());
    }

    public String getAudioLatencyHint() {
        return "80";
    }

    public String getAudioLatencyValue() {
        int value = AirPlaySetting.getAudioLatencyMs();
        return value < 0 ? "" : String.valueOf(value);
    }

    public String getAudioCushionHint() {
        return String.valueOf(Prefs.DEF_AUDIO_CUSHION_MS);
    }

    public String getAudioCushionValue() {
        return String.valueOf(AirPlaySetting.getAudioCushionMs());
    }

    public String getOboeBufferHint() {
        return "0";
    }

    public String getOboeBufferValue() {
        return String.valueOf(AirPlaySetting.getOboeBufferFrames());
    }

    private String getResolutionText() {
        String value = AirPlaySetting.getResolution();
        return "auto".equalsIgnoreCase(value) ? getString(R.string.setting_airplay_resolution_auto) : value;
    }

    private String getAudioLatencyText() {
        int value = AirPlaySetting.getAudioLatencyMs();
        return value < 0
                ? getString(R.string.setting_airplay_audio_latency_default)
                : getString(R.string.setting_airplay_audio_latency_ms, value);
    }

    private String getAudioAdaptiveText() {
        String[] labels = getResources().getStringArray(R.array.setting_airplay_audio_adaptive_names);
        int step = AirPlaySetting.getAudioAdaptiveStep();
        return labels[Math.clamp(step, 0, labels.length - 1)];
    }
}
