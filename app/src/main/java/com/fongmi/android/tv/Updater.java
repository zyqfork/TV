package com.fongmi.android.tv;

import android.view.View;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.impl.UpdateListener;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.dialog.UpdateDialog;
import com.fongmi.android.tv.utils.Download;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Github;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import java.io.File;

public class Updater implements Download.Callback, UpdateListener {

    /** Created once the release tag is known, so the download targets that exact release. */
    private Download download;
    private UpdateDialog dialog;

    private Updater() {
    }

    public static Updater create() {
        return new Updater();
    }

    private File getFile() {
        return Path.cache("update.apk");
    }

    private String getJson() {
        return Github.getJson();
    }

    private String getApk(String tag) {
        return Github.getApk(tag, BuildConfig.FLAVOR_mode + "-" + BuildConfig.FLAVOR_abi);
    }

    public Updater force() {
        Notify.show(R.string.update_check);
        Setting.putUpdate(true);
        return this;
    }

    public void start(FragmentActivity activity) {
        if (!Setting.getUpdate()) return;
        Task.execute(() -> doInBackground(activity));
    }

    private void doInBackground(FragmentActivity activity) {
        try {
            // VERSION_NAME carries the source revision for tag-built releases (see the CI stamping
            // step). Without it a "-source.N" tag would compare as newer forever and the dialog
            // would reappear after every install.
            int currentSource = Github.parseSourceRevision(BuildConfig.VERSION_NAME);
            Github.Release release = Github.findNewer(OkHttp.string(getJson()), BuildConfig.VERSION_CODE, currentSource);
            if (release == null) return;
            App.post(() -> show(activity, release));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void show(FragmentActivity activity, Github.Release release) {
        // The check runs on a worker thread; by the time the reply lands the host may already be
        // finishing, destroyed, or have had its state saved (e.g. the user pushed a URL, which
        // starts VideoActivity on top of HomeActivity). Showing a DialogFragment then throws
        // IllegalStateException("Can not perform this action after onSaveInstanceState") and the
        // whole process dies in CrashActivity. Drop the prompt instead - it is only a suggestion,
        // and the next launch will offer it again.
        if (!isActive(activity)) return;
        dismiss();
        download = Download.create(getApk(release.tag()), getFile());
        dialog = UpdateDialog.create().title(ResUtil.getString(R.string.update_version, release.tag())).desc(release.desc()).listener(this).show(activity);
    }

    private static boolean isActive(FragmentActivity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed() && !activity.getSupportFragmentManager().isStateSaved();
    }

    @Override
    public void onConfirm(View view) {
        view.setEnabled(false);
        if (download != null) download.start(this);
    }

    @Override
    public void onCancel(View view) {
        Setting.putUpdate(false);
        if (download != null) download.cancel();
        dismiss();
    }

    private void dismiss() {
        try {
            if (dialog != null) dialog.dismiss();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void progress(int progress) {
        if (dialog != null) dialog.setProgress(progress);
    }

    @Override
    public void error(String msg) {
        Notify.show(msg);
        dismiss();
    }

    @Override
    public void success(File file) {
        FileUtil.openFile(file);
        dismiss();
    }
}
