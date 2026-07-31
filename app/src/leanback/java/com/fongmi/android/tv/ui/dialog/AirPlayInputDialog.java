package com.fongmi.android.tv.ui.dialog;

import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.DialogDlnaInputBinding;
import com.fongmi.android.tv.ui.activity.SettingAirPlayActivity;
import com.fongmi.android.tv.ui.activity.SettingAirPlayAdvancedActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

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
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        type = requireArguments().getInt("type");
        String hint;
        String value;
        int inputType;
        FragmentActivity activity = requireActivity();
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
        binding.positive.setOnClickListener(this::onPositive);
        binding.negative.setOnClickListener(view -> dismiss());
        binding.text.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) binding.positive.performClick();
            return true;
        });
    }

    private void onPositive(View view) {
        String value = binding.text.getText().toString().trim();
        FragmentActivity activity = requireActivity();
        if (activity instanceof SettingAirPlayAdvancedActivity advanced) {
            advanced.setAirPlayInput(type, value);
        } else {
            ((SettingAirPlayActivity) activity).setAirPlayInput(type, value);
        }
        dismiss();
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.45f);
    }
}
