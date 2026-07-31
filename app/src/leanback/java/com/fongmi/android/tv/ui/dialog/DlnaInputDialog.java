package com.fongmi.android.tv.ui.dialog;

import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.DialogDlnaInputBinding;
import com.fongmi.android.tv.ui.activity.SettingDlnaActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

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
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        type = requireArguments().getInt("type");
        SettingDlnaActivity activity = (SettingDlnaActivity) requireActivity();
        if (type == TYPE_PORT) {
            binding.text.setHint(activity.getPortHint());
            binding.text.setInputType(InputType.TYPE_CLASS_NUMBER);
            String value = activity.getPortValue();
            binding.text.setText(value);
            if (!TextUtils.isEmpty(value)) binding.text.setSelection(value.length());
        } else {
            binding.text.setHint(activity.getNameHint());
            binding.text.setInputType(InputType.TYPE_CLASS_TEXT);
            String value = activity.getNameValue();
            binding.text.setText(value);
            if (!TextUtils.isEmpty(value)) binding.text.setSelection(value.length());
        }
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
        ((SettingDlnaActivity) requireActivity()).setDlnaInput(type, binding.text.getText().toString().trim());
        dismiss();
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.45f);
    }
}
