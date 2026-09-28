package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.databinding.ActivityDlnaServerBinding;
import com.fongmi.android.tv.dlna.DlnaMediaManager;
import com.fongmi.android.tv.dlna.DlnaPin;
import com.fongmi.android.tv.dlna.DlnaPinStore;
import com.fongmi.android.tv.ui.adapter.DlnaServerAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class DlnaServerActivity extends BaseActivity implements DlnaServerAdapter.OnClickListener, DlnaMediaManager.DeviceListener {

    private ActivityDlnaServerBinding mBinding;
    private DlnaServerAdapter mAdapter;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, DlnaServerActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityDlnaServerBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setSupportActionBar(mBinding.toolbar);
        setTitle(R.string.setting_dlna_library);
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setAdapter(mAdapter = new DlnaServerAdapter(this));
        mBinding.progressLayout.showProgress();
        DlnaMediaManager.get().addDeviceListener(this);
        DlnaMediaManager.get().init(this);
        // List refresh happens in onResume (covers first show + return from browse for pins).
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Kick SSDP again on entry so a late IPTV box (IPNP-iptv) is re-queried
        // without waiting for the user to press Refresh.
        DlnaMediaManager.get().searchWithRescan();
        refreshList();
    }

    @Override
    protected void initEvent() {
        mBinding.refresh.setOnClickListener(v -> {
            mBinding.progressLayout.showProgress();
            DlnaMediaManager.get().searchWithRescan();
            refreshList();
            // Late M-SEARCH replies land at ~3.5s; only then report a truly empty LAN.
            App.post(this::showEmptyIfStillEmpty, 4_000L);
        });
    }

    private void showEmptyIfStillEmpty() {
        if (isFinishing() || isDestroyed()) return;
        if (mAdapter.getServerCount() == 0) Notify.show(R.string.dlna_library_empty);
    }

    private void refreshList() {
        mAdapter.setItems(DlnaPinStore.getAll(), DlnaMediaManager.get().getRegistered());
        mBinding.progressLayout.showContent(true, mAdapter.getItemCount());
    }

    @Override
    public void onDeviceAdded(Device device) {
        if (isFinishing()) return;
        mAdapter.addServer(device);
        mBinding.progressLayout.showContent(true, mAdapter.getItemCount());
    }

    @Override
    public void onDeviceRemoved(Device device) {
        if (isFinishing()) return;
        mAdapter.removeServer(device);
        mBinding.progressLayout.showContent(true, mAdapter.getItemCount());
    }

    @Override
    public void onItemClick(Device item) {
        DlnaBrowseActivity.start(this, item.getUuid(), item.getName());
    }

    @Override
    public void onPinClick(DlnaPin pin) {
        if (pin.isContainer()) {
            DlnaBrowseActivity.start(this, pin.getUuid(), pin.getServerName(), pin.getObjectId(), pin.getTitle());
            return;
        }
        if (!pin.hasUrl()) {
            Notify.show(R.string.dlna_library_play_fail);
            return;
        }
        VideoActivity.start(this, SiteApi.PUSH, pin.getUrl(), pin.getTitle(), null, pin.getServerName());
    }

    @Override
    public void onPinLongClick(DlnaPin pin) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(pin.getTitle())
                .setItems(new String[]{getString(R.string.dlna_library_unpin)}, (d, w) -> {
                    DlnaPinStore.remove(pin);
                    Notify.show(R.string.dlna_library_pin_removed);
                    refreshList();
                })
                .show();
    }

    @Override
    protected void onDestroy() {
        DlnaMediaManager.get().removeDeviceListener(this);
        DlnaMediaManager.get().release(this);
        super.onDestroy();
    }
}
