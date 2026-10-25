package com.fongmi.android.tv.utils;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.databinding.ViewProgressBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

public class Notify {

    public static final String DEFAULT = "default";
    public static final int ID = 9527;
    private AlertDialog mDialog;
    private Toast mToast;
    private Snackbar mBar;

    private static class Loader {
        static volatile Notify INSTANCE = new Notify();
    }

    private static Notify get() {
        return Loader.INSTANCE;
    }

    public static void createChannel() {
        NotificationManagerCompat notifyMgr = NotificationManagerCompat.from(App.get());
        notifyMgr.createNotificationChannel(new NotificationChannelCompat.Builder(DEFAULT, NotificationManagerCompat.IMPORTANCE_LOW).setName("TV").build());
    }

    public static String getError(int resId, Throwable e) {
        if (TextUtils.isEmpty(e.getMessage())) return ResUtil.getString(resId);
        return ResUtil.getString(resId) + "\n" + e.getMessage();
    }

    public static void show(Notification notification) {
        if (ContextCompat.checkSelfPermission(App.get(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManagerCompat.from(App.get()).notify(ID, notification);
    }

    public static void show(int resId) {
        if (resId != 0) show(ResUtil.getString(resId));
    }

    public static void show(String text) {
        if (!TextUtils.isEmpty(text)) get().makeText(text);
    }

    public static void progress(Context context) {
        dismiss();
        get().create(context);
    }

    public static void dismiss() {
        try {
            if (get().mDialog != null) get().mDialog.dismiss();
        } catch (Exception ignored) {
        }
    }

    private void create(Context context) {
        ViewProgressBinding binding = ViewProgressBinding.inflate(LayoutInflater.from(context));
        mDialog = new MaterialAlertDialogBuilder(context).setView(binding.getRoot()).create();
        mDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        mDialog.show();
    }

    private void makeText(String text) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            App.post(() -> makeText(text));
            return;
        }
        if (showBar(text)) return;
        if (mToast != null) mToast.cancel();
        mToast = Toast.makeText(App.get(), text, Toast.LENGTH_LONG);
        mToast.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, ResUtil.dp2px(32));
        mToast.show();
    }

    /** Keep short messages near the top so they do not cover the playback bar. */
    private boolean showBar(String text) {
        try {
            Activity activity = App.activity();
            View root = activity == null || activity.isFinishing() ? null : activity.findViewById(android.R.id.content);
            if (root == null) return false;
            if (mBar != null) mBar.dismiss();
            if (mToast != null) mToast.cancel();
            Snackbar bar = Snackbar.make(root, text, Snackbar.LENGTH_LONG);
            View view = bar.getView();
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params instanceof FrameLayout.LayoutParams layout) {
                layout.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                layout.topMargin = ResUtil.dp2px(32);
                view.setLayoutParams(layout);
            }
            mBar = bar;
            bar.show();
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
