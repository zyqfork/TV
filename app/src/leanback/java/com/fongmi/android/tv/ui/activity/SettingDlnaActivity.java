package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivitySettingDlnaBinding;
import com.fongmi.android.tv.dlna.DlnaNetwork;
import com.fongmi.android.tv.service.DLNARendererService;
import com.fongmi.android.tv.setting.DlnaSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.DlnaInputDialog;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class SettingDlnaActivity extends BaseActivity {

    private ActivitySettingDlnaBinding mBinding;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingDlnaActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingDlnaBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mBinding.enabled.requestFocus();
        refresh();
    }

    @Override
    protected void initEvent() {
        mBinding.enabled.setOnClickListener(this::setEnabled);
        mBinding.name.setOnClickListener(view -> DlnaInputDialog.show(this, DlnaInputDialog.TYPE_NAME));
        mBinding.iface.setOnClickListener(this::setIface);
        mBinding.httpPort.setOnClickListener(view -> DlnaInputDialog.show(this, DlnaInputDialog.TYPE_PORT));
    }

    private void refresh() {
        boolean enabled = DlnaSetting.isEnabled();
        mBinding.enabledText.setText(Setting.getSwitch(enabled));
        mBinding.nameText.setText(DlnaSetting.getDisplayName());
        mBinding.ifaceText.setText(getIfaceText());
        mBinding.httpPortText.setText(getPortText());
        int visibility = enabled ? View.VISIBLE : View.GONE;
        mBinding.name.setVisibility(visibility);
        mBinding.iface.setVisibility(visibility);
        mBinding.httpPort.setVisibility(visibility);
    }

    private void setEnabled(View view) {
        DlnaSetting.putEnabled(!DlnaSetting.isEnabled());
        DLNARendererService.apply(this);
        refresh();
    }

    private void setIface(View view) {
        List<DlnaNetwork.Iface> ifaces = DlnaNetwork.listUsable();
        if (ifaces.isEmpty()) {
            mBinding.ifaceText.setText(R.string.setting_dlna_iface_none);
            return;
        }
        if (ifaces.size() == 1) {
            DlnaSetting.putInterface(ifaces.get(0).name());
            refresh();
            return;
        }
        String current = DlnaSetting.resolveInterfaceName();
        List<String> labels = new ArrayList<>();
        int selected = 0;
        for (int i = 0; i < ifaces.size(); i++) {
            DlnaNetwork.Iface item = ifaces.get(i);
            labels.add(item.label());
            if (item.name().equals(current)) selected = i;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setting_dlna_iface)
                .setSingleChoiceItems(labels.toArray(new String[0]), selected, (dialog, which) -> {
                    String name = ifaces.get(which).name();
                    dialog.dismiss();
                    if (name.equals(current)) return;
                    DlnaSetting.putInterface(name);
                    DLNARendererService.apply(this);
                    refresh();
                })
                .show();
    }

    public void setDlnaInput(int type, String value) {
        if (type == DlnaInputDialog.TYPE_PORT) {
            int port = 0;
            if (!TextUtils.isEmpty(value)) {
                try {
                    port = Integer.parseInt(value);
                } catch (Exception ignored) {
                    port = DlnaSetting.getHttpPort();
                }
            }
            DlnaSetting.putHttpPort(port);
        } else {
            DlnaSetting.putName(value);
        }
        DLNARendererService.apply(this);
        refresh();
    }

    public String getNameHint() {
        return Util.getDeviceName();
    }

    public String getNameValue() {
        return DlnaSetting.getName();
    }

    public String getPortHint() {
        return getString(R.string.setting_auto);
    }

    public String getPortValue() {
        int port = DlnaSetting.getHttpPort();
        return port == 0 ? "" : String.valueOf(port);
    }

    private String getPortText() {
        int port = DlnaSetting.getHttpPort();
        return port == 0 ? getString(R.string.setting_auto) : String.valueOf(port);
    }

    private String getIfaceText() {
        DlnaNetwork.Iface item = DlnaSetting.resolveIface();
        return item == null ? getString(R.string.setting_dlna_iface_none) : item.label();
    }
}
