package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Path;

import java.io.File;
import java.util.List;

/** Private handoff activity: retains APK across unknown-source permission and never exits the app. */
public final class UpdateInstallActivity extends Activity {
    private static final int PERMISSION = 21;
    private static final int INSTALL = 22;
    private static final String MIME = "application/vnd.android.package-archive";
    private boolean launched;

    public static void start() {
        App.get().startActivity(new Intent(App.get(), UpdateInstallActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null && state.getBoolean("launched")) { launched = true; return; }
        validateAndInstall();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("launched", launched);
        super.onSaveInstanceState(state);
    }

    private void validateAndInstall() {
        File apk = Path.cache("update.apk");
        Task.execute(() -> {
            boolean valid = false;
            try {
                PackageInfo info = getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
                valid = apk.isFile() && apk.length() > 0 && info != null
                        && getPackageName().equals(info.packageName);
            } catch (RuntimeException ignored) {}
            boolean accepted = valid;
            App.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (!accepted) { fail(R.string.error_update_apk); return; }
                installOrRequestPermission(apk);
            });
        });
    }

    private void installOrRequestPermission(File apk) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                if (launched) { fail(R.string.error_update_permission); return; }
                launched = true;
                requestInstallPermission();
                return;
            }
            Uri uri = FileUtil.getShareUri(apk);
            Intent intent = installerIntent(uri);
            if (intent == null) { fail(R.string.error_update_installer); return; }
            intent.setClipData(ClipData.newRawUri("Update APK", uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
            launched = true;
            startActivityForResult(intent, INSTALL);
        } catch (RuntimeException error) {
            android.util.Log.e("UpdateInstaller", "Unable to launch package installer", error);
            fail(R.string.error_update_installer);
        }
    }

    private void requestInstallPermission() {
        // Some TV firmwares omit the standard per-app unknown-source Activity entirely.
        // Fall back to user-operated system settings, never grant AppOps or bypass permission.
        Intent[] settings = {
                new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())),
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())),
                new Intent(Settings.ACTION_SECURITY_SETTINGS),
                new Intent(Settings.ACTION_SETTINGS)
        };
        for (Intent intent : settings) {
            try {
                startActivityForResult(intent, PERMISSION);
                Notify.show(R.string.error_update_permission);
                return;
            } catch (android.content.ActivityNotFoundException ignored) {
                // Try the next standard system entry point without device/package hardcoding.
            }
        }
        fail(R.string.error_update_permission);
    }

    private Intent installerIntent(Uri uri) {
        // INSTALL_PACKAGE does not let unrelated APK file managers capture VIEW's default.
        for (String action : new String[]{Intent.ACTION_INSTALL_PACKAGE, Intent.ACTION_VIEW}) {
            Intent intent = new Intent(action).setDataAndType(uri, MIME);
            List<ResolveInfo> choices = getPackageManager().queryIntentActivities(intent,
                    PackageManager.MATCH_DEFAULT_ONLY);
            for (ResolveInfo choice : choices) {
                if (choice.activityInfo == null || !choice.activityInfo.enabled) continue;
                int flags = choice.activityInfo.applicationInfo.flags;
                if ((flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0) continue;
                intent.setComponent(new ComponentName(choice.activityInfo.packageName, choice.activityInfo.name));
                return intent;
            }
        }
        return null;
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PERMISSION) validateAndInstall();
        else if (request == INSTALL) {
            if (result != RESULT_OK && result != RESULT_CANCELED) Notify.show(R.string.error_update_installer);
            // Success replaces this process normally; cancellation/failure returns to the app.
            finish();
        }
    }

    private void fail(int message) { Notify.show(message); finish(); }
}
