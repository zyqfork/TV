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
import com.fongmi.android.tv.storage.NetworkStorage;
import com.fongmi.android.tv.storage.NetworkStorageStore;
import com.fongmi.android.tv.storage.SmbClientHelper;
import com.fongmi.android.tv.storage.WebDavClientHelper;
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
        Task.execute(() -> {
            try {
                List<NetworkEntry> entries;
                if (storage.isSmb()) {
                    entries = listSmb(storage, requestPath);
                } else {
                    entries = new WebDavClientHelper(storage).list(requestPath);
                }
                List<NetworkEntry> result = entries;
                App.post(() -> {
                    if (isFinishing()) return;
                    mAdapter.setItems(result);
                    mBinding.recycler.setSelectedPosition(0);
                    mBinding.progressLayout.showContent(true, mAdapter.getItemCount());
                });
            } catch (Exception e) {
                App.post(() -> {
                    if (isFinishing()) return;
                    mAdapter.setItems(null);
                    mBinding.progressLayout.showContent(true, 0);
                    Notify.show(Notify.getError(R.string.network_storage_list_fail, e));
                });
            }
        });
    }

    private List<NetworkEntry> listSmb(NetworkStorage storage, String requestPath) throws Exception {
        try (SmbClientHelper client = new SmbClientHelper(storage)) {
            return client.list(requestPath);
        } catch (Exception e) {
            boolean root = TextUtils.isEmpty(requestPath);
            boolean hasShare = !TextUtils.isEmpty(storage.getShare());
            if (!root || !hasShare || !isBadShareName(e)) throw e;
            NetworkStorage copy = NetworkStorage.create(NetworkStorage.TYPE_SMB);
            copy.setId(storage.getId());
            copy.setHost(storage.getHost());
            copy.setPort(storage.getPort());
            copy.setUsername(storage.getUsername());
            copy.setPassword(storage.getPassword());
            copy.setShare("");
            try (SmbClientHelper client = new SmbClientHelper(copy)) {
                List<NetworkEntry> shares = client.list("");
                storage.setShare("");
                NetworkStorageStore.save(storage);
                App.post(() -> Notify.show(R.string.network_storage_share_missing));
                return shares;
            }
        }
    }

    private static boolean isBadShareName(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null) {
                String upper = msg.toUpperCase();
                if (upper.contains("STATUS_BAD_NETWORK_NAME") || upper.contains("0xC00000CC") || msg.contains("共享不存在")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    @Override
    public void onItemClick(NetworkEntry item) {
        if (item.isDirectory()) {
            load(item.getPath());
        } else {
            String playUrl = mStorage.toPlayUrl(item.getPath());
            VideoActivity.start(this, SiteApi.PUSH, playUrl, item.getName());
        }
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
