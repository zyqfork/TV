package com.fongmi.android.tv.ui.dialog;

import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.inputmethod.EditorInfo;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogDlnaInputBinding;
import com.fongmi.android.tv.ui.activity.SettingDlnaActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Phone half of the DLNA name/port input.
 *
 * The TV flavour has a class with the same FQN that draws its own OK/Cancel inside the layout,
 * because it is driven by a remote. A phone uses the Material dialog's own buttons instead — the
 * same split already used by {@link NetworkInputDialog}. `main`'s SettingDlnaActivity is written
 * against this API, so both flavours must keep the same signature.
 */
public class DlnaInputDialog extends BaseAlertDialog {

    public static final int TYPE_NAME = 0;
    public static final int TYPE_PORT = 1;

    private DialogDlnaInputBinding binding;
    private int type;

    public static void show(FragmentActivity activity, int type) {
        DlnaInputDialog dialog = new DlnaInputDialog();
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
                .setTitle(type == TYPE_PORT ? R.string.setting_dlna_http_port : R.string.setting_dlna_name)
                .setView(getBinding().getRoot())
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> onPositive())
                .setNegativeButton(R.string.dialog_negative, null);
    }

    @Override
    protected void initView() {
        type = requireArguments().getInt("type");
        SettingDlnaActivity activity = (SettingDlnaActivity) requireActivity();
        String value;
        if (type == TYPE_PORT) {
            binding.text.setHint(activity.getPortHint());
            binding.text.setInputType(InputType.TYPE_CLASS_NUMBER);
            value = activity.getPortValue();
        } else {
            binding.text.setHint(activity.getNameHint());
            binding.text.setInputType(InputType.TYPE_CLASS_TEXT);
            value = activity.getNameValue();
        }
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
        ((SettingDlnaActivity) requireActivity()).setDlnaInput(type, binding.text.getText().toString().trim());
    }
}
