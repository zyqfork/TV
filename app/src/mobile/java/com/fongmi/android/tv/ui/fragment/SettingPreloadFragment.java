package com.fongmi.android.tv.ui.fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.FragmentSettingPreloadBinding;
import com.fongmi.android.tv.player.exo.PreloadPolicy;
import com.fongmi.android.tv.player.exo.PreloadDiagnosticsMonitor;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.dialog.PreloadDialog;
import com.fongmi.android.tv.ui.dialog.PreloadDiagnosticsDialog;
import com.fongmi.android.tv.utils.FileUtil;

public class SettingPreloadFragment extends BaseFragment {

    private FragmentSettingPreloadBinding mBinding;
    private final PreloadDiagnosticsMonitor diagnostics = new PreloadDiagnosticsMonitor(this::applyDiagnostics);

    public static SettingPreloadFragment newInstance() {
        return new SettingPreloadFragment();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingPreloadBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        refresh();
    }

    @Override
    protected void initEvent() {
        mBinding.preload.setOnClickListener(this::setPreload);
        mBinding.preloadSize.setOnClickListener(view -> PreloadDialog.show(this, PreloadDialog.SIZE));
        mBinding.preloadTime.setOnClickListener(view -> PreloadDialog.show(this, PreloadDialog.TIME));
        mBinding.preloadThread.setOnClickListener(view -> PreloadDialog.show(this, PreloadDialog.THREADS));
        mBinding.preloadMetered.setOnClickListener(this::setMetered);
        mBinding.preloadDiagnostics.setOnClickListener(view -> PreloadDiagnosticsDialog.show(this));
    }

    private void refresh() {
        mBinding.preloadText.setText(Setting.getSwitch(PreloadSetting.isPreload()));
        mBinding.preloadMeteredText.setText(Setting.getSwitch(PreloadSetting.isPreloadOnMetered()));
        setPreloadThreadsText();
        setPreloadSizeText();
        setPreloadTimeText();
        setDiagnosticsText();
        setVisible();
    }

    private void setVisible() {
        boolean preload = PreloadSetting.isPreload();
        mBinding.preloadSize.setVisibility(preload ? View.VISIBLE : View.GONE);
        mBinding.preloadTime.setVisibility(preload ? View.VISIBLE : View.GONE);
        mBinding.preloadThread.setVisibility(preload && !PlayerSetting.isMpv() ? View.VISIBLE : View.GONE);
        mBinding.preloadMetered.setVisibility(preload ? View.VISIBLE : View.GONE);
        mBinding.preloadDiagnostics.setVisibility(preload ? View.VISIBLE : View.GONE);
    }

    private void setMetered(View view) {
        PreloadSetting.putPreloadOnMetered(!PreloadSetting.isPreloadOnMetered());
        mBinding.preloadMeteredText.setText(Setting.getSwitch(PreloadSetting.isPreloadOnMetered()));
        setDiagnosticsText();
        diagnostics.refresh();
    }

    private void setPreload(View view) {
        PreloadSetting.putPreload(!PreloadSetting.isPreload());
        mBinding.preloadText.setText(Setting.getSwitch(PreloadSetting.isPreload()));
        setDiagnosticsText();
        diagnostics.refresh();
        setVisible();
    }

    public void setPreload(int type, int value) {
        if (type == PreloadDialog.THREADS) {
            PreloadSetting.putPreloadThreads(value);
            setPreloadThreadsText();
        } else if (type == PreloadDialog.SIZE) {
            PreloadSetting.putPreloadSizeMb(value);
            setPreloadSizeText();
        } else if (type == PreloadDialog.TIME) {
            PreloadSetting.putPreloadTimeSeconds(value);
            setPreloadTimeText();
        }
    }

    private void setPreloadSizeText() {
        mBinding.preloadSizeText.setText(FileUtil.byteCountToDisplaySize(PreloadSetting.getPreloadSizeBytes()));
    }

    private void setPreloadTimeText() {
        mBinding.preloadTimeText.setText(getString(R.string.player_preload_time_value, PreloadSetting.getPreloadTimeSeconds()));
    }

    private void setPreloadThreadsText() {
        mBinding.preloadThreadText.setText(getString(R.string.player_preload_threads_value, PreloadSetting.getPreloadThreads()));
    }

    /**
     * The diagnostics row used to be an empty placeholder: tapping it opened a dialog, but the row
     * itself showed no value, which made it look broken next to every other row here. Surface the
     * same verdict the dialog computes; the reason detail stays in the dialog.
     */
    private void setDiagnosticsText() {
        applyDiagnostics(PreloadPolicy.evaluate(App.get()).allowed());
    }

    private void applyDiagnostics(boolean allowed) {
        if (mBinding != null) mBinding.preloadDiagnosticsText.setText(allowed ? R.string.player_preload_diagnostics_allowed : R.string.player_preload_diagnostics_blocked);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mBinding != null && !isHidden() && getUserVisibleHint()) diagnostics.start();
    }

    @Override
    public void onPause() {
        diagnostics.stop();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        diagnostics.stop();
        super.onDestroyView();
        mBinding = null;
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) diagnostics.stop();
        else if (mBinding != null) {
            refresh();
            if (isResumed() && getUserVisibleHint()) diagnostics.start();
        }
    }

    @Override
    public void setUserVisibleHint(boolean visible) {
        super.setUserVisibleHint(visible);
        if (!visible) diagnostics.stop();
        else if (mBinding != null && isResumed() && !isHidden()) diagnostics.start();
    }
}
