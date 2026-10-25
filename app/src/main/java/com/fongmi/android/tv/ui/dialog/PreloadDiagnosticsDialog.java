package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.app.UiModeManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.player.exo.PreloadDiagnostics;
import com.fongmi.android.tv.player.exo.PreloadPolicy;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Read-only view of the process-local preload counters and the policy verdict for the current
 * network. Replaces "guess from logcat" with something a user can screenshot while reporting an
 * issue; the raw lines are still mirrored to logcat under the {@code VodPreload} tag.
 */
public class PreloadDiagnosticsDialog extends DialogFragment {

    public static void show(Fragment fragment) {
        new PreloadDiagnosticsDialog().show(fragment.getChildFragmentManager(), null);
    }

    public static void show(FragmentActivity activity) {
        new PreloadDiagnosticsDialog().show(activity.getSupportFragmentManager(), null);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        boolean touch = isTouchDriven();
        TextView message = new TextView(requireContext());
        message.setTextSize(13f);
        // Selecting text makes the view focusable, which on a remote-only device swallows D-pad
        // focus and leaves the dialog buttons unreachable (verified on the rk3588 box).
        message.setTextIsSelectable(touch);
        message.setText(buildReport());
        int padding = Math.round(ResUtil.dp2px(16));
        message.setPadding(padding, padding / 2, padding, 0);
        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(message, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        if (!touch) {
            // Belt and braces: a ScrollView can take focus by itself, which would still hide the
            // button panel from the D-pad even with the TextView made non-focusable.
            scroll.setFocusable(false);
            scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.player_preload_diagnostics)
                .setView(scroll)
                .setNeutralButton(R.string.player_preload_diagnostics_reset, null)
                .setPositiveButton(R.string.dialog_positive, null)
                .create();
        // The reset button must not dismiss: watching the counters drop to zero is the whole point,
        // which used to be achieved by re-showing the fragment from the click callback - a new
        // transaction started while this dialog was still being dismissed, and against a different
        // fragment manager than the one that showed it. Overriding the click listener keeps the
        // dialog open and just refreshes the text.
        dialog.setOnShowListener(ignored -> {
            Button reset = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
            if (reset != null) reset.setOnClickListener(view -> {
                PreloadDiagnostics.reset();
                message.setText(buildReport());
            });
        });
        return dialog;
    }

    /**
     * Whether a finger (rather than a remote) can drive this dialog.
     *
     * <p>Cheap TV boxes routinely declare {@code android.hardware.touchscreen} while shipping no
     * touch panel at all, so the feature flag alone is not trustworthy here — the television UI mode
     * is the decisive signal.
     */
    private boolean isTouchDriven() {
        Context context = requireContext();
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) return false;
        UiModeManager uiMode = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
        return uiMode == null || uiMode.getCurrentModeType() != Configuration.UI_MODE_TYPE_TELEVISION;
    }

    private String buildReport() {
        PreloadPolicy.Decision decision = PreloadPolicy.evaluate(App.get());
        StringBuilder builder = new StringBuilder();
        builder.append("policy=").append(decision.allowed() ? "allowed" : "blocked");
        builder.append(" (").append(decision.reason()).append(")");
        builder.append("\nmeteredAllowed=").append(false);
        builder.append("\nduration=").append(PreloadSetting.getPreloadDurationMs() / 1000).append('s');
        builder.append("\nthreads=").append(1);
        builder.append("\n\n").append(PreloadDiagnostics.summary());
        return builder.toString();
    }
}
