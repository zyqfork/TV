package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityNetworkStorageBinding;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.storage.NetworkStorage;
import com.fongmi.android.tv.storage.NetworkStorageStore;
import com.fongmi.android.tv.storage.SmbDiscover;
import com.fongmi.android.tv.ui.adapter.NetworkStorageAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

public class NetworkStorageActivity extends BaseActivity implements NetworkStorageAdapter.OnClickListener, SmbDiscover.Listener {

    private ActivityNetworkStorageBinding mBinding;
    private NetworkStorageAdapter mAdapter;
    private SmbDiscover mDiscover;
    private boolean mScanning;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, NetworkStorageActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityNetworkStorageBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
        mBinding.recycler.setAdapter(mAdapter = new NetworkStorageAdapter(this));
        mBinding.addSmb.requestFocus();
        refresh();
    }

    @Override
    protected void initEvent() {
        mBinding.addSmb.setOnClickListener(v -> startDiscover());
        mBinding.addWebdav.setOnClickListener(v -> NetworkStorageEditActivity.start(this, NetworkStorage.TYPE_WEBDAV, null));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        stopDiscover();
        super.onDestroy();
    }

    private void refresh() {
        mAdapter.setItems(NetworkStorageStore.getAll());
        mBinding.progressLayout.showContent(true, mAdapter.getItemCount());
    }

    private void openManualSmb() {
        NetworkStorageEditActivity.start(this, NetworkStorage.TYPE_SMB, null);
    }

    private void startDiscover() {
        if (mScanning) return;
        mScanning = true;
        mBinding.addSmb.setEnabled(false);
        Notify.show(R.string.network_storage_discovering);
        stopDiscover();
        mDiscover = new SmbDiscover(this);
        mDiscover.start();
    }

    private void stopDiscover() {
        if (mDiscover != null) {
            mDiscover.stop();
            mDiscover = null;
        }
    }

    private void finishDiscover() {
        mScanning = false;
        mBinding.addSmb.setEnabled(true);
        stopDiscover();
    }

    @Override
    public void onProgress(int done, int total) {
    }

    @Override
    public void onComplete(List<SmbDiscover.Host> hosts) {
        finishDiscover();
        if (isFinishing()) return;
        if (hosts == null || hosts.isEmpty()) {
            Notify.show(R.string.network_storage_discover_empty);
            openManualSmb();
            return;
        }
        Notify.show(getString(R.string.network_storage_discover_done, hosts.size()));
        String manual = getString(R.string.network_storage_manual);
        String[] labels = new String[hosts.size() + 1];
        for (int i = 0; i < hosts.size(); i++) labels[i] = hosts.get(i).display();
        labels[hosts.size()] = manual;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.network_storage_discover_pick)
                .setItems(labels, (dialog, which) -> {
                    if (which >= hosts.size()) {
                        openManualSmb();
                        return;
                    }
                    SmbDiscover.Host host = hosts.get(which);
                    String name = host.getName();
                    if (name == null || name.isEmpty() || name.equals(host.getIp())) name = host.getIp();
                    NetworkStorageEditActivity.start(this, NetworkStorage.TYPE_SMB, null, host.getIp(), name);
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    @Override
    public void onItemClick(NetworkStorage item) {
        NetworkBrowseActivity.start(this, item.getId());
    }

    @Override
    public void onItemLongClick(NetworkStorage item) {
        boolean pinned = NetworkStorageStore.isHome(item.getId());
        String[] actions = {
                getString(R.string.network_storage_browse),
                getString(R.string.network_storage_edit),
                getString(pinned ? R.string.network_storage_unpin_home : R.string.network_storage_pin_home),
                getString(R.string.network_storage_delete)
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(item.displayTitle())
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) NetworkBrowseActivity.start(this, item.getId());
                    else if (which == 1) NetworkStorageEditActivity.start(this, item.getType(), item.getId());
                    else if (which == 2) toggleHome(item);
                    else confirmDelete(item);
                })
                .show();
    }

    private void toggleHome(NetworkStorage item) {
        if (NetworkStorageStore.isHome(item.getId())) NetworkStorageStore.setHome(null);
        else NetworkStorageStore.setHome(item.getId());
        ConfigEvent.common();
        Notify.show(NetworkStorageStore.isHome(item.getId()) ? R.string.network_storage_pin_home : R.string.network_storage_unpin_home);
    }

    private void confirmDelete(NetworkStorage item) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.network_storage_delete)
                .setMessage(item.displayTitle())
                .setPositiveButton(R.string.dialog_positive, (d, w) -> {
                    NetworkStorageStore.delete(item.getId());
                    ConfigEvent.common();
                    refresh();
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }
}
