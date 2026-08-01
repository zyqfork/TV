package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityNetworkStorageEditBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.storage.NetworkStorage;
import com.fongmi.android.tv.storage.NetworkStorageStore;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.NetworkInputDialog;
import com.fongmi.android.tv.utils.Notify;

public class NetworkStorageEditActivity extends BaseActivity implements NetworkInputDialog.Callback {

    private static final String EXTRA_TYPE = "type";
    private static final String EXTRA_ID = "id";
    private static final String EXTRA_HOST = "host";
    private static final String EXTRA_NAME = "name";

    private static final int FIELD_NAME = 0;
    private static final int FIELD_HOST = 1;
    private static final int FIELD_PORT = 2;
    private static final int FIELD_SHARE = 3;
    private static final int FIELD_PATH = 4;
    private static final int FIELD_USERNAME = 5;
    private static final int FIELD_PASSWORD = 6;

    private ActivityNetworkStorageEditBinding mBinding;
    private NetworkStorage mStorage;

    public static void start(Activity activity, String type, String id) {
        start(activity, type, id, null, null);
    }

    public static void start(Activity activity, String type, String id, String host, String name) {
        Intent intent = new Intent(activity, NetworkStorageEditActivity.class);
        intent.putExtra(EXTRA_TYPE, type);
        if (!TextUtils.isEmpty(id)) intent.putExtra(EXTRA_ID, id);
        if (!TextUtils.isEmpty(host)) intent.putExtra(EXTRA_HOST, host);
        if (!TextUtils.isEmpty(name)) intent.putExtra(EXTRA_NAME, name);
        activity.startActivity(intent);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityNetworkStorageEditBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        String id = getIntent().getStringExtra(EXTRA_ID);
        String type = getIntent().getStringExtra(EXTRA_TYPE);
        if (!TextUtils.isEmpty(id)) mStorage = NetworkStorageStore.find(id);
        if (mStorage == null) mStorage = NetworkStorage.create(TextUtils.isEmpty(type) ? NetworkStorage.TYPE_SMB : type);
        String host = getIntent().getStringExtra(EXTRA_HOST);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        if (!TextUtils.isEmpty(host) && TextUtils.isEmpty(mStorage.getHost())) mStorage.setHost(host);
        if (!TextUtils.isEmpty(name) && TextUtils.isEmpty(mStorage.getName())) mStorage.setName(name);
        applyTypeVisibility();
        refresh();
        if (mStorage.isSmb() && TextUtils.isEmpty(mStorage.getShare())) mBinding.share.requestFocus();
        else mBinding.name.requestFocus();
    }

    @Override
    protected void initEvent() {
        mBinding.name.setOnClickListener(v -> edit(FIELD_NAME, getString(R.string.network_storage_name), mStorage.getName(), NetworkInputDialog.TYPE_TEXT));
        mBinding.host.setOnClickListener(v -> edit(FIELD_HOST, getString(R.string.network_storage_host), mStorage.getHost(), NetworkInputDialog.TYPE_TEXT));
        mBinding.port.setOnClickListener(v -> edit(FIELD_PORT, getString(R.string.network_storage_port), mStorage.getPort() > 0 ? String.valueOf(mStorage.getPort()) : "", NetworkInputDialog.TYPE_NUMBER));
        mBinding.share.setOnClickListener(v -> edit(FIELD_SHARE, getString(R.string.network_storage_share_optional), mStorage.getShare(), NetworkInputDialog.TYPE_TEXT));
        mBinding.path.setOnClickListener(v -> edit(FIELD_PATH, getString(R.string.network_storage_path), mStorage.getPath(), NetworkInputDialog.TYPE_TEXT));
        mBinding.username.setOnClickListener(v -> edit(FIELD_USERNAME, getString(R.string.network_storage_username), mStorage.getUsername(), NetworkInputDialog.TYPE_TEXT));
        mBinding.password.setOnClickListener(v -> edit(FIELD_PASSWORD, getString(R.string.network_storage_password), mStorage.getPassword(), NetworkInputDialog.TYPE_PASSWORD));
        mBinding.https.setOnClickListener(v -> {
            mStorage.setHttps(!mStorage.isHttps());
            refresh();
        });
        mBinding.save.setOnClickListener(this::onSave);
    }

    private void applyTypeVisibility() {
        boolean smb = mStorage.isSmb();
        mBinding.share.setVisibility(smb ? View.VISIBLE : View.GONE);
        mBinding.path.setVisibility(smb ? View.GONE : View.VISIBLE);
        mBinding.https.setVisibility(smb ? View.GONE : View.VISIBLE);
    }

    private void edit(int field, String hint, String value, int inputType) {
        NetworkInputDialog.show(this, field, hint, value, inputType, this);
    }

    private void refresh() {
        mBinding.nameText.setText(mStorage.getName());
        mBinding.hostText.setText(mStorage.getHost());
        mBinding.portText.setText(mStorage.getPort() > 0 ? String.valueOf(mStorage.getPort()) : "");
        mBinding.shareText.setText(mStorage.getShare());
        mBinding.pathText.setText(mStorage.getPath());
        mBinding.usernameText.setText(mStorage.getUsername());
        mBinding.passwordText.setText(TextUtils.isEmpty(mStorage.getPassword()) ? "" : "••••••");
        mBinding.httpsText.setText(Setting.getSwitch(mStorage.isHttps()));
    }

    private void onSave(View view) {
        if (!mStorage.isValid()) {
            Notify.show(R.string.network_storage_invalid);
            return;
        }
        if (TextUtils.isEmpty(mStorage.getName())) mStorage.setName(mStorage.getHost());
        NetworkStorageStore.save(mStorage);
        finish();
    }

    @Override
    public void onNetworkInput(int field, String value) {
        switch (field) {
            case FIELD_NAME -> mStorage.setName(value);
            case FIELD_HOST -> mStorage.setHost(value);
            case FIELD_PORT -> {
                try {
                    mStorage.setPort(TextUtils.isEmpty(value) ? 0 : Integer.parseInt(value));
                } catch (Exception e) {
                    mStorage.setPort(0);
                }
            }
            case FIELD_SHARE -> mStorage.setShare(value);
            case FIELD_PATH -> mStorage.setPath(TextUtils.isEmpty(value) ? "/" : value);
            case FIELD_USERNAME -> mStorage.setUsername(value);
            case FIELD_PASSWORD -> mStorage.setPassword(value);
            default -> {
            }
        }
        refresh();
    }
}
