package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivitySettingCastBinding;
import com.fongmi.android.tv.setting.AirPlaySetting;
import com.fongmi.android.tv.setting.DlnaSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.base.CastSettingPage;

public class SettingCastActivity extends BaseActivity {

    private ActivitySettingCastBinding mBinding;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingCastActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingCastBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        CastSettingPage.showToolbar(this, mBinding.toolbar, R.string.setting_cast_setting);
        CastSettingPage.focusFirst(mBinding.dlna);
        refresh();
    }

    @Override
    protected void initEvent() {
        mBinding.dlna.setOnClickListener(view -> SettingDlnaActivity.start(this));
        mBinding.airplay.setOnClickListener(view -> SettingAirPlayActivity.start(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    /** Each row shows the receiver's on/off state, matching the parent row's DLNA · AirPlay summary. */
    private void refresh() {
        mBinding.dlnaText.setText(Setting.getSwitch(DlnaSetting.isEnabled()));
        mBinding.airplayText.setText(Setting.getSwitch(AirPlaySetting.isEnabled()));
    }
}
