package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.DlnaEntry;
import com.fongmi.android.tv.databinding.ActivityNetworkBrowseBinding;
import com.fongmi.android.tv.dlna.DlnaMediaManager;
import com.fongmi.android.tv.dlna.DlnaPin;
import com.fongmi.android.tv.dlna.DlnaPinStore;
import com.fongmi.android.tv.ui.adapter.DlnaEntryAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayDeque;
import java.util.Deque;

public class DlnaBrowseActivity extends BaseActivity implements DlnaEntryAdapter.OnClickListener {

    private static final String EXTRA_UUID = "uuid";
    private static final String EXTRA_NAME = "name";
    private static final String EXTRA_OBJECT_ID = "objectId";
    private static final String EXTRA_TITLE = "title";

    private ActivityNetworkBrowseBinding mBinding;
    private DlnaEntryAdapter mAdapter;
    private final Deque<String> mIds = new ArrayDeque<>();
    private final Deque<String> mTitles = new ArrayDeque<>();
    private String mUuid;
    private String mRootName;
    private boolean mManagerBound;
    /** Incremented per browse request; stale callbacks compare against it and drop their result. */
    private int mLoadToken;

    public static void start(Activity activity, String uuid, String name) {
        start(activity, uuid, name, "0", name);
    }

    public static void start(Activity activity, String uuid, String name, String objectId, String title) {
        Intent intent = new Intent(activity, DlnaBrowseActivity.class);
        intent.putExtra(EXTRA_UUID, uuid);
        intent.putExtra(EXTRA_NAME, name);
        intent.putExtra(EXTRA_OBJECT_ID, objectId);
        intent.putExtra(EXTRA_TITLE, title);
        activity.startActivity(intent);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityNetworkBrowseBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mUuid = getIntent().getStringExtra(EXTRA_UUID);
        mRootName = getIntent().getStringExtra(EXTRA_NAME);
        String objectId = getIntent().getStringExtra(EXTRA_OBJECT_ID);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (TextUtils.isEmpty(mUuid)) {
            finish();
            return;
        }
        if (TextUtils.isEmpty(mRootName)) mRootName = getString(R.string.home_media_library);
        if (TextUtils.isEmpty(objectId)) objectId = "0";
        if (TextUtils.isEmpty(title)) title = mRootName;
        setTitle(title);
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
        mBinding.recycler.setAdapter(mAdapter = new DlnaEntryAdapter(this));
        DlnaMediaManager.get().init(this);
        mManagerBound = true;
        open(objectId, title);
    }

    private void open(String objectId, String title) {
        mIds.addLast(objectId);
        mTitles.addLast(title);
        setTitle(title);
        load(objectId);
    }

    private void load(String objectId) {
        // Browse pages arrive asynchronously and the adapter is shared, so a browse that is left
        // behind (back press, or opening another folder) would otherwise append its own entries to
        // the new folder's list — or wipe it with its own error. Only the newest request may write.
        final int token = ++mLoadToken;
        mBinding.progressLayout.showProgress();
        DlnaMediaManager.get().browse(mUuid, objectId, new DlnaMediaManager.BrowseCallback() {
            @Override
            public void onPage(java.util.List<DlnaEntry> entries, boolean firstPage, boolean done) {
                if (isFinishing() || token != mLoadToken) return;
                if (firstPage) {
                    mAdapter.setItems(entries);
                    mBinding.recycler.setSelectedPosition(0);
                    // Show first page immediately; keep list visible while later pages append.
                    mBinding.progressLayout.showContent(true, mAdapter.getItemCount());
                } else {
                    mAdapter.addItems(entries);
                }
            }

            @Override
            public void onSuccess(java.util.List<DlnaEntry> entries) {
                if (isFinishing() || token != mLoadToken) return;
                mAdapter.setItems(entries);
                mBinding.recycler.setSelectedPosition(0);
                mBinding.progressLayout.showContent(true, mAdapter.getItemCount());
            }

            @Override
            public void onError(String msg) {
                if (isFinishing() || token != mLoadToken) return;
                mAdapter.setItems(null);
                mBinding.progressLayout.showContent(true, 0);
                Notify.show(TextUtils.isEmpty(msg) ? getString(R.string.dlna_library_browse_fail) : msg);
            }
        });
    }

    private DlnaPin toPin(DlnaEntry item) {
        if (item.isContainer()) {
            return DlnaPin.folder(mUuid, mRootName, item.getId(), item.getTitle());
        }
        return DlnaPin.item(mUuid, mRootName, item.getId(), item.getTitle(), item.getUrl(), item.getMime());
    }

    @Override
    public void onItemClick(DlnaEntry item) {
        if (item.isContainer()) {
            open(item.getId(), item.getTitle());
            return;
        }
        if (!item.hasUrl()) {
            Notify.show(R.string.dlna_library_play_fail);
            return;
        }
        VideoActivity.start(this, SiteApi.PUSH, item.getUrl(), item.getTitle(), null, mRootName);
    }

    @Override
    public void onItemLongClick(DlnaEntry item) {
        DlnaPin pin = toPin(item);
        boolean pinned = DlnaPinStore.contains(pin);
        String action = getString(pinned ? R.string.dlna_library_unpin : R.string.dlna_library_pin);
        new MaterialAlertDialogBuilder(this)
                .setTitle(item.getTitle())
                .setItems(new String[]{action}, (d, w) -> {
                    boolean added = DlnaPinStore.toggle(pin);
                    Notify.show(added ? R.string.dlna_library_pin_added : R.string.dlna_library_pin_removed);
                })
                .show();
    }

    @Override
    protected void onDestroy() {
        if (mManagerBound) DlnaMediaManager.get().release(this);
        super.onDestroy();
    }

    @Override
    protected void onBackInvoked() {
        if (mIds.size() <= 1) {
            super.onBackInvoked();
            return;
        }
        mIds.removeLast();
        mTitles.removeLast();
        setTitle(mTitles.peekLast());
        load(mIds.peekLast());
    }
}
