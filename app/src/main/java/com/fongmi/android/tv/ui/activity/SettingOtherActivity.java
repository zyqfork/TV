package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.databinding.ActivitySettingOtherBinding;
import com.fongmi.android.tv.db.BackupManager;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.UaListener;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.base.CastSettingPage;
import com.fongmi.android.tv.ui.dialog.RestoreDialog;
import com.fongmi.android.tv.ui.dialog.UaDialog;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.github.catvod.bean.Doh;
import com.github.catvod.net.OkHttp;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

/** Shared TV/phone miscellaneous settings; existing preference keys are preserved. */
public class SettingOtherActivity extends BaseActivity implements UaListener {

    private ActivitySettingOtherBinding binding;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingOtherActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = ActivitySettingOtherBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        CastSettingPage.showToolbar(this, binding.toolbar, R.string.setting_other);
        CastSettingPage.focusFirst(binding.autoRefresh);
        refresh();
        cacheSize();
    }

    @Override
    protected void initEvent() {
        binding.autoRefresh.setOnClickListener(v -> {
            Setting.putAutoSourceRefresh(!Setting.isAutoSourceRefresh());
            refresh();
        });
        binding.ssl.setOnClickListener(v -> toggleSsl());
        binding.doh.setOnClickListener(v -> chooseDoh());
        binding.ua.setOnClickListener(v -> UaDialog.show(this));
        binding.refreshSources.setOnClickListener(v -> refreshSources());
        binding.incognito.setOnClickListener(v -> {
            Setting.putIncognito(!Setting.isIncognito());
            refresh();
        });
        binding.update.setOnClickListener(v -> {
            Setting.putUpdate(!Setting.getUpdate());
            refresh();
        });
        binding.cache.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setting_cache).setMessage(R.string.setting_cache_confirm)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (d, w) -> FileUtil.clearCache(new Callback() {
                    @Override public void success() { cacheSize(); }
                })).show());
        binding.backup.setOnClickListener(v -> PermissionUtil.requestFile(this,
                allowed -> BackupManager.backup(result(R.string.backup_success, R.string.backup_fail))));
        binding.restore.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setting_restore).setMessage(R.string.setting_restore_confirm)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (d, w) -> PermissionUtil.requestFile(this,
                        allowed -> RestoreDialog.create().show(this, new Callback() {
                            @Override public void success() {
                                if (!alive()) return;
                                Notify.show(R.string.restore_success);
                                refresh();
                                refreshSources();
                                WallConfig.get().init().load();
                            }
                            @Override public void error() { if (alive()) Notify.show(R.string.restore_fail); }
                        }))).show());
    }

    private boolean alive() {
        return binding != null && !isFinishing() && !isDestroyed();
    }

    private Callback result(int success, int error) {
        return new Callback() {
            @Override public void success() { if (alive()) Notify.show(success); }
            @Override public void error() { if (alive()) Notify.show(error); }
        };
    }

    private void refresh() {
        if (!alive()) return;
        binding.autoRefreshText.setText(Setting.getSwitch(Setting.isAutoSourceRefresh()));
        binding.sslText.setText(Setting.getSwitch(Setting.isIgnoreParserSslErrors()));
        binding.incognitoText.setText(Setting.getSwitch(Setting.isIncognito()));
        binding.updateText.setText(Setting.getSwitch(Setting.getUpdate()));
        List<Doh> items = VodConfig.get().getDoh();
        int index = Math.max(0, items.indexOf(Doh.objectFrom(Setting.getDoh())));
        binding.dohText.setText(items.get(index).getName());
    }

    private void toggleSsl() {
        if (Setting.isIgnoreParserSslErrors()) {
            Setting.putIgnoreParserSslErrors(false);
            refresh();
        } else if (Setting.isParserSslWarningAccepted()) {
            Setting.putIgnoreParserSslErrors(true);
            refresh();
        } else {
            new MaterialAlertDialogBuilder(this).setTitle(R.string.setting_parser_ssl)
                    .setMessage(R.string.setting_parser_ssl_warning)
                    .setNegativeButton(R.string.dialog_negative, null)
                    .setPositiveButton(R.string.dialog_positive, (d, w) -> {
                        Setting.putParserSslWarningAccepted(true);
                        Setting.putIgnoreParserSslErrors(true);
                        refresh();
                    }).show();
        }
    }

    private void chooseDoh() {
        List<Doh> items = VodConfig.get().getDoh();
        String[] names = items.stream().map(Doh::getName).toArray(String[]::new);
        int index = Math.max(0, items.indexOf(Doh.objectFrom(Setting.getDoh())));
        new MaterialAlertDialogBuilder(this).setTitle(R.string.setting_doh)
                .setNegativeButton(R.string.dialog_negative, null)
                .setSingleChoiceItems(names, index, (d, which) -> {
                    Doh doh = items.get(which);
                    OkHttp.dns().setDoh(doh);
                    Setting.putDoh(doh.toString());
                    refresh();
                    d.dismiss();
                }).show();
    }

    @Override
    public void setUa(String ua) {
        Setting.putUa(ua);
    }

    private void cacheSize() {
        FileUtil.getCacheSize(new Callback() {
            @Override public void success(String size) { if (alive()) binding.cacheText.setText(size); }
        });
    }

    private void refreshSources() {
        if (!alive()) return;
        VodConfig.get().init();
        LiveConfig.get().init();
        if (TextUtils.isEmpty(VodConfig.getUrl()) && !LiveConfig.hasUrl()) {
            Notify.show(R.string.setting_sources_missing);
            return;
        }
        binding.refreshSources.setEnabled(false);
        if (TextUtils.isEmpty(VodConfig.getUrl())) {
            refreshLive(null);
        } else {
            VodConfig.get().load(new Callback() {
                @Override public void success() { refreshLive(null); }
                @Override public void error(String message) { refreshLive(message); }
            });
        }
    }

    private void refreshLive(String vodError) {
        // Embedded live entries were refreshed with VOD; independent live configs get
        // their own callback so a live failure is not reported as an all-source success.
        LiveConfig.get().init();
        if (!LiveConfig.hasUrl() || LiveConfig.getUrl().equals(VodConfig.getUrl())) {
            refreshDone(vodError);
        } else {
            LiveConfig.get().load(new Callback() {
                @Override public void success() { refreshDone(vodError); }
                @Override public void error(String message) {
                    refreshDone(vodError == null || vodError.isEmpty() ? message : vodError);
                }
            });
        }
    }

    private void refreshDone(String error) {
        if (!alive()) return;
        binding.refreshSources.setEnabled(true);
        if (error != null) Notify.show(error);
        else Notify.show(R.string.setting_sources_refreshed);
        refresh();
    }

    @Override protected void onResume() { super.onResume(); refresh(); }
    @Override protected void onDestroy() { binding = null; super.onDestroy(); }
}
