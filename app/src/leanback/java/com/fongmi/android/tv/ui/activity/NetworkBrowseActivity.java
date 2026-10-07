package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.databinding.ActivityNetworkBrowseBinding;
import com.fongmi.android.tv.storage.NetworkEntry;
import com.fongmi.android.tv.storage.NetworkMediaTypes;
import com.fongmi.android.tv.storage.NetworkStorage;
import com.fongmi.android.tv.storage.NetworkStorageStore;
import com.fongmi.android.tv.storage.SmbClientHelper;
import com.fongmi.android.tv.storage.NetworkStorageAccess;
import com.fongmi.android.tv.ui.adapter.NetworkEntryAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;

import java.util.List;

public class NetworkBrowseActivity extends BaseActivity implements NetworkEntryAdapter.OnClickListener {

    private static final String EXTRA_ID = "id";

    private ActivityNetworkBrowseBinding mBinding;
    private NetworkEntryAdapter mAdapter;
    private NetworkStorage mStorage;
    private String mPath = "";
    private int loadGeneration;

    public static void start(Activity activity, String storageId) {
        Intent intent = new Intent(activity, NetworkBrowseActivity.class);
        intent.putExtra(EXTRA_ID, storageId);
        activity.startActivity(intent);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityNetworkBrowseBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        String id = getIntent().getStringExtra(EXTRA_ID);
        mStorage = NetworkStorageStore.find(id);
        if (mStorage == null) {
            finish();
            return;
        }
        setTitle(mStorage.displayTitle());
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
        mBinding.recycler.setAdapter(mAdapter = new NetworkEntryAdapter(this));
        load(mPath);
    }

    private void load(String path) {
        mPath = path == null ? "" : path;
        mBinding.progressLayout.showProgress();
        NetworkStorage storage = mStorage;
        String requestPath = mPath;
        // Navigating into another folder while a listing is still in flight must not let the
        // slower response overwrite the newer directory.
        int generation = ++loadGeneration;
        Task.execute(() -> {
            try {
                NetworkStorageAccess.Listing listing = NetworkStorageAccess.list(storage, requestPath);
                List<NetworkEntry> result = listing.entries();
                App.post(() -> {
                    if (isFinishing() || generation != loadGeneration) return;
                    if (listing.shareRediscovered()) Notify.show(R.string.network_storage_share_missing);
                    mAdapter.setItems(result);
                    mBinding.recycler.setSelectedPosition(0);
                    mBinding.progressLayout.showContent(true, mAdapter.getItemCount());
                });
            } catch (Exception e) {
                App.post(() -> {
                    if (isFinishing() || generation != loadGeneration) return;
                    mAdapter.setItems(null);
                    mBinding.progressLayout.showContent(true, 0);
                    Notify.show(Notify.getError(R.string.network_storage_list_fail, e));
                });
            }
        });
    }

    @Override
    public void onItemClick(NetworkEntry item) {
        if (item.isDirectory()) {
            load(item.getPath());
            return;
        }
        // Never push arbitrary files (e.g. .txt) into VideoActivity — that crashes the player.
        if (!NetworkMediaTypes.isPlayable(item.getName())) {
            Notify.show(getString(R.string.network_storage_unsupported_file,
                    NetworkMediaTypes.supportedLabel()));
            return;
        }
        String playUrl = mStorage.toPlayUrl(item.getPath());
        VideoActivity.start(this, SiteApi.PUSH, playUrl, item.getName());
    }

    @Override
    protected void onBackInvoked() {
        if (TextUtils.isEmpty(mPath)) {
            super.onBackInvoked();
            return;
        }
        load(parentPath(mPath));
    }

    private static String parentPath(String path) {
        if (TextUtils.isEmpty(path)) return "";
        String value = path;
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        int index = value.lastIndexOf('/');
        if (index < 0) return "";
        return value.substring(0, index);
    }
}
