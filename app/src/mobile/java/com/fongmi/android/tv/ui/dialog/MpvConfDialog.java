package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogMpvConfBinding;
import com.fongmi.android.tv.player.mpv.MpvConfigFiles;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

public class MpvConfDialog extends BaseAlertDialog {

    private static final String[] MPV_CONF_MIME_TYPES = new String[]{"text/*", "application/octet-stream", "*/*"};

    private DialogMpvConfBinding binding;

    public static void show(Fragment fragment) {
        new MpvConfDialog().show(fragment.getChildFragmentManager(), null);
    }

    /** Settings-page entry: no owning fragment for the dialog editor. */
    public static void show(FragmentActivity activity) {
        new MpvConfDialog().show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogMpvConfBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setTitle(R.string.player_mpv_conf).setView(getBinding().getRoot()).setPositiveButton(R.string.dialog_positive, this::onPositive).setNegativeButton(R.string.dialog_negative, null);
    }

    @Override
    protected void initView() {
        setText(MpvConfigFiles.read());
    }

    @Override
    protected void initEvent() {
        binding.input.setEndIconOnClickListener(this::onChoose);
        binding.text.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateHint(s); }
            @Override public void afterTextChanged(Editable s) { }
        });
        updateHint(binding.text.getText());
    }

    private void updateHint(CharSequence text) {
        List<String> conflicts = MpvConfigFiles.findInterfaceManagedOptions(text);
        binding.input.setHelperText(conflicts.isEmpty()
                ? getString(R.string.player_mpv_conf_precedence)
                : getString(R.string.player_mpv_conf_conflict, TextUtils.join(", ", conflicts)));
    }

    private void setText(String text) {
        binding.text.setText(text);
        binding.text.setSelection(TextUtils.isEmpty(text) ? 0 : text.length());
    }

    private void onPositive(DialogInterface dialog, int which) {
        if (!MpvConfigFiles.write(binding.text.getText().toString())) Notify.show(R.string.player_mpv_conf_save_failed);
    }

    private void onChoose(View view) {
        FileChooser.from(launcher).show("*/*", MPV_CONF_MIME_TYPES);
    }

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) return;
        boolean success = MpvConfigFiles.importFrom(requireContext(), result.getData().getData());
        Notify.show(success ? R.string.player_mpv_conf_import_success : R.string.player_mpv_conf_import_failed);
        if (success) setText(MpvConfigFiles.read());
    });
}
