package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityNetworkStorageBinding;
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
        mBinding.addSmb.setOnClickListener(v -> NetworkStorageEditActivity.start(this, NetworkStorage.TYPE_SMB, null));
        mBinding.discoverSmb.setOnClickListener(v -> startDiscover());
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

    private void startDiscover() {
        if (mScanning) return;
        mScanning = true;
        mBinding.discoverSmb.setEnabled(false);
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
        mBinding.discoverSmb.setEnabled(true);
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
            return;
        }
        Notify.show(getString(R.string.network_storage_discover_done, hosts.size()));
        String[] labels = new String[hosts.size()];
        for (int i = 0; i < hosts.size(); i++) labels[i] = hosts.get(i).display();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.network_storage_discover_pick)
                .setItems(labels, (dialog, which) -> {
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
        String[] actions = {
                getString(R.string.network_storage_browse),
                getString(R.string.network_storage_edit),
                getString(R.string.network_storage_delete)
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(item.displayTitle())
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) NetworkBrowseActivity.start(this, item.getId());
                    else if (which == 1) NetworkStorageEditActivity.start(this, item.getType(), item.getId());
                    else confirmDelete(item);
                })
                .show();
    }

    private void confirmDelete(NetworkStorage item) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.network_storage_delete)
                .setMessage(item.displayTitle())
                .setPositiveButton(R.string.dialog_positive, (d, w) -> {
                    NetworkStorageStore.delete(item.getId());
                    refresh();
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }
}
