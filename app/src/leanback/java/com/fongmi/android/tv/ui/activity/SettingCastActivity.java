package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.ActivitySettingCastBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;

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
        mBinding.dlna.requestFocus();
    }

    @Override
    protected void initEvent() {
        mBinding.dlna.setOnClickListener(view -> SettingDlnaActivity.start(this));
        mBinding.airplay.setOnClickListener(view -> SettingAirPlayActivity.start(this));
    }
}
