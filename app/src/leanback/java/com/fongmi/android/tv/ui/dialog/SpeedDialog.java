package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogSpeedBinding;
import com.fongmi.android.tv.impl.SpeedListener;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class SpeedDialog extends BaseAlertDialog {

    private DialogSpeedBinding binding;

    public static void show(FragmentActivity activity) {
        new SpeedDialog().show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogSpeedBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setTitle(R.string.player_speed)
                .setSingleChoiceItems(labels(), selected(), (dialog, which) -> {
                    ((SpeedListener) requireActivity()).setSpeed(PlayerSetting.SPEED_PRESETS[which]);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.dialog_negative, null);
    }

    private int selected() {
        return PlayerSetting.nearestSpeedIndex(PlayerSetting.getSpeed());
    }

    private String[] labels() {
        float[] presets = PlayerSetting.SPEED_PRESETS;
        String[] labels = new String[presets.length];
        for (int i = 0; i < labels.length; i++) labels[i] = presets[i] + "×";
        return labels;
    }
}
