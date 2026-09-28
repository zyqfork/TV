package com.fongmi.android.tv.ui.dialog;

import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.inputmethod.EditorInfo;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogDlnaInputBinding;
import com.fongmi.android.tv.ui.activity.SettingAirPlayActivity;
import com.fongmi.android.tv.ui.activity.SettingAirPlayAdvancedActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Phone half of the AirPlay value input (name / port / resolution / fps / audio tuning).
 *
 * Same split as {@link DlnaInputDialog}: the TV flavour draws its own buttons because it is
 * remote-driven, the phone uses the Material dialog's buttons. `main`'s SettingAirPlayActivity and
 * SettingAirPlayAdvancedActivity are written against this API, so both flavours must keep the same
 * signature.
 */
public class AirPlayInputDialog extends BaseAlertDialog {

    public static final int TYPE_NAME = 0;
    public static final int TYPE_PORT = 1;
    public static final int TYPE_RESOLUTION = 2;
    public static final int TYPE_MAX_FPS = 3;
    public static final int TYPE_AUDIO_LATENCY = 4;
    public static final int TYPE_AUDIO_CUSHION = 5;
    public static final int TYPE_OBOE_BUFFER = 6;

    private DialogDlnaInputBinding binding;
    private int type;

    public static void show(FragmentActivity activity, int type) {
        AirPlayInputDialog dialog = new AirPlayInputDialog();
        Bundle args = new Bundle();
        args.putInt("type", type);
        dialog.setArguments(args);
        dialog.show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogDlnaInputBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder()
                .setTitle(titleFor(type))
                .setView(getBinding().getRoot())
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> onPositive())
                .setNegativeButton(R.string.dialog_negative, null);
    }

    private int titleFor(int type) {
        return switch (type) {
            case TYPE_PORT -> R.string.setting_airplay_port;
            case TYPE_RESOLUTION -> R.string.setting_airplay_resolution;
            case TYPE_MAX_FPS -> R.string.setting_airplay_max_fps;
            case TYPE_AUDIO_LATENCY -> R.string.setting_airplay_audio_latency;
            case TYPE_AUDIO_CUSHION -> R.string.setting_airplay_audio_cushion;
            case TYPE_OBOE_BUFFER -> R.string.setting_airplay_oboe_buffer;
            default -> R.string.setting_airplay_name;
        };
    }

    @Override
    protected void initView() {
        type = requireArguments().getInt("type");
        FragmentActivity activity = requireActivity();
        String hint;
        String value;
        int inputType;
        if (activity instanceof SettingAirPlayAdvancedActivity advanced) {
            switch (type) {
                case TYPE_RESOLUTION -> {
                    hint = advanced.getResolutionHint();
                    value = advanced.getResolutionValue();
                    inputType = InputType.TYPE_CLASS_TEXT;
                }
                case TYPE_MAX_FPS -> {
                    hint = advanced.getMaxFpsHint();
                    value = advanced.getMaxFpsValue();
                    inputType = InputType.TYPE_CLASS_NUMBER;
                }
                case TYPE_AUDIO_LATENCY -> {
                    hint = advanced.getAudioLatencyHint();
                    value = advanced.getAudioLatencyValue();
                    inputType = InputType.TYPE_CLASS_NUMBER;
                }
                case TYPE_AUDIO_CUSHION -> {
                    hint = advanced.getAudioCushionHint();
                    value = advanced.getAudioCushionValue();
                    inputType = InputType.TYPE_CLASS_NUMBER;
                }
                case TYPE_OBOE_BUFFER -> {
                    hint = advanced.getOboeBufferHint();
                    value = advanced.getOboeBufferValue();
                    inputType = InputType.TYPE_CLASS_NUMBER;
                }
                default -> {
                    hint = "";
                    value = "";
                    inputType = InputType.TYPE_CLASS_TEXT;
                }
            }
        } else {
            SettingAirPlayActivity basic = (SettingAirPlayActivity) activity;
            if (type == TYPE_PORT) {
                hint = basic.getPortHint();
                value = basic.getPortValue();
                inputType = InputType.TYPE_CLASS_NUMBER;
            } else {
                hint = basic.getNameHint();
                value = basic.getNameValue();
                inputType = InputType.TYPE_CLASS_TEXT;
            }
        }
        binding.text.setHint(hint);
        binding.text.setInputType(inputType);
        binding.text.setText(value);
        if (!TextUtils.isEmpty(value)) binding.text.setSelection(value.length());
    }

    @Override
    protected void initEvent() {
        binding.text.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onPositive();
                dismiss();
            }
            return true;
        });
    }

    private void onPositive() {
        String value = binding.text.getText().toString().trim();
        FragmentActivity activity = requireActivity();
        if (activity instanceof SettingAirPlayAdvancedActivity advanced) {
            advanced.setAirPlayInput(type, value);
        } else {
            ((SettingAirPlayActivity) activity).setAirPlayInput(type, value);
        }
    }
}
