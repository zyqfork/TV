package com.fongmi.android.tv.ui.dialog;

import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.inputmethod.EditorInfo;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogDlnaInputBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class NetworkInputDialog extends BaseAlertDialog {

    public static final int TYPE_TEXT = 0;
    public static final int TYPE_NUMBER = 1;
    public static final int TYPE_PASSWORD = 2;

    private DialogDlnaInputBinding binding;
    private Callback callback;
    private int field;

    public interface Callback {
        void onNetworkInput(int field, String value);
    }

    public static void show(FragmentActivity activity, int field, String hint, String value, int inputType, Callback callback) {
        NetworkInputDialog dialog = new NetworkInputDialog();
        dialog.callback = callback;
        Bundle args = new Bundle();
        args.putInt("field", field);
        args.putString("hint", hint);
        args.putString("value", value == null ? "" : value);
        args.putInt("inputType", inputType);
        dialog.setArguments(args);
        dialog.show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogDlnaInputBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        // The builder's own buttons are enough on a phone; the TV version had to draw its own
        // because it is driven by a remote rather than a touch screen.
        return builder()
                .setTitle(requireArguments().getString("hint", ""))
                .setView(getBinding().getRoot())
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> submit())
                .setNegativeButton(R.string.dialog_negative, null);
    }

    @Override
    protected void initView() {
        Bundle args = requireArguments();
        field = args.getInt("field");
        String value = args.getString("value", "");
        int inputType = args.getInt("inputType", TYPE_TEXT);
        if (inputType == TYPE_NUMBER) {
            binding.text.setInputType(InputType.TYPE_CLASS_NUMBER);
        } else if (inputType == TYPE_PASSWORD) {
            binding.text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        } else {
            binding.text.setInputType(InputType.TYPE_CLASS_TEXT);
        }
        binding.text.setText(value);
        if (!TextUtils.isEmpty(value)) binding.text.setSelection(value.length());
    }

    @Override
    protected void initEvent() {
        binding.text.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit();
                dismiss();
            }
            return true;
        });
    }

    private void submit() {
        if (callback != null) callback.onNetworkInput(field, binding.text.getText().toString().trim());
    }

    @Override
    public void onDestroy() {
        callback = null;
        super.onDestroy();
    }
}
