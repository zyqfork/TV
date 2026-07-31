package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivitySettingAirplayBinding;
import com.fongmi.android.tv.dlna.DlnaNetwork;
import com.fongmi.android.tv.service.AirPlayServer;
import com.fongmi.android.tv.setting.AirPlaySetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.AirPlayInputDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

import io.github.jqssun.airplay.Prefs;

public class SettingAirPlayActivity extends BaseActivity {

    private ActivitySettingAirplayBinding mBinding;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingAirPlayActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingAirplayBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mBinding.enabled.requestFocus();
        refresh();
    }

    @Override
    protected void initEvent() {
        mBinding.enabled.setOnClickListener(this::setEnabled);
        mBinding.name.setOnClickListener(view -> AirPlayInputDialog.show(this, AirPlayInputDialog.TYPE_NAME));
        mBinding.port.setOnClickListener(view -> AirPlayInputDialog.show(this, AirPlayInputDialog.TYPE_PORT));
        mBinding.iface.setOnClickListener(this::setIface);
        mBinding.requirePin.setOnClickListener(v -> toggle(AirPlaySetting.isRequirePin(), AirPlaySetting::putRequirePin));
        mBinding.allowNewConn.setOnClickListener(v -> toggle(AirPlaySetting.isAllowNewConn(), AirPlaySetting::putAllowNewConn));
        mBinding.advertiseVideo.setOnClickListener(v -> toggle(AirPlaySetting.isAdvertiseVideo(), AirPlaySetting::putAdvertiseVideo));
        mBinding.advertiseAudio.setOnClickListener(v -> toggle(AirPlaySetting.isAdvertiseAudio(), AirPlaySetting::putAdvertiseAudio));
        mBinding.advanced.setOnClickListener(view -> SettingAirPlayAdvancedActivity.start(this));
    }

    private interface BoolSetter {
        void put(boolean value);
    }

    private void toggle(boolean current, BoolSetter setter) {
        setter.put(!current);
        AirPlayServer.apply(this);
        refresh();
    }

    private void refresh() {
        boolean enabled = AirPlaySetting.isEnabled();
        mBinding.enabledText.setText(Setting.getSwitch(enabled));
        mBinding.nameText.setText(AirPlaySetting.getDisplayName());
        mBinding.portText.setText(String.valueOf(AirPlaySetting.getPort()));
        mBinding.ifaceText.setText(getIfaceText());
        mBinding.requirePinText.setText(Setting.getSwitch(AirPlaySetting.isRequirePin()));
        mBinding.allowNewConnText.setText(Setting.getSwitch(AirPlaySetting.isAllowNewConn()));
        mBinding.advertiseVideoText.setText(Setting.getSwitch(AirPlaySetting.isAdvertiseVideo()));
        mBinding.advertiseAudioText.setText(Setting.getSwitch(AirPlaySetting.isAdvertiseAudio()));

        int visibility = enabled ? View.VISIBLE : View.GONE;
        mBinding.name.setVisibility(visibility);
        mBinding.port.setVisibility(visibility);
        mBinding.iface.setVisibility(visibility);
        mBinding.requirePin.setVisibility(visibility);
        mBinding.allowNewConn.setVisibility(visibility);
        mBinding.advertiseVideo.setVisibility(visibility);
        mBinding.advertiseAudio.setVisibility(visibility);
        mBinding.advanced.setVisibility(visibility);
    }

    private void setEnabled(View view) {
        AirPlaySetting.putEnabled(!AirPlaySetting.isEnabled());
        AirPlayServer.apply(this);
        refresh();
    }

    private void setIface(View view) {
        List<DlnaNetwork.Iface> ifaces = DlnaNetwork.listUsable();
        if (ifaces.isEmpty()) {
            mBinding.ifaceText.setText(R.string.setting_airplay_iface_none);
            return;
        }
        if (ifaces.size() == 1) {
            AirPlaySetting.putInterface(ifaces.get(0).name());
            refresh();
            return;
        }
        String current = AirPlaySetting.resolveInterfaceName();
        List<String> labels = new ArrayList<>();
        int selected = 0;
        for (int i = 0; i < ifaces.size(); i++) {
            DlnaNetwork.Iface item = ifaces.get(i);
            labels.add(item.label());
            if (item.name().equals(current)) selected = i;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setting_airplay_iface)
                .setSingleChoiceItems(labels.toArray(new String[0]), selected, (dialog, which) -> {
                    String name = ifaces.get(which).name();
                    dialog.dismiss();
                    if (name.equals(current)) return;
                    AirPlaySetting.putInterface(name);
                    AirPlayServer.apply(this);
                    refresh();
                })
                .show();
    }

    public void setAirPlayInput(int type, String value) {
        if (type == AirPlayInputDialog.TYPE_PORT) {
            int port = Prefs.DEF_SERVER_PORT;
            if (!TextUtils.isEmpty(value)) {
                try {
                    port = Integer.parseInt(value);
                } catch (Exception ignored) {
                    port = AirPlaySetting.getPort();
                }
            }
            AirPlaySetting.putPort(port);
        } else {
            AirPlaySetting.putName(value);
        }
        AirPlayServer.apply(this);
        refresh();
    }

    public String getNameHint() {
        return Prefs.DEF_SERVER_NAME;
    }

    public String getNameValue() {
        return AirPlaySetting.getName();
    }

    public String getPortHint() {
        return String.valueOf(Prefs.DEF_SERVER_PORT);
    }

    public String getPortValue() {
        return String.valueOf(AirPlaySetting.getPort());
    }

    private String getIfaceText() {
        DlnaNetwork.Iface item = AirPlaySetting.resolveIface();
        return item == null ? getString(R.string.setting_airplay_iface_none) : item.label();
    }
}
