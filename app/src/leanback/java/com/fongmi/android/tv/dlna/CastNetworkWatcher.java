package com.fongmi.android.tv.dlna;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.AirPlaySetting;
import com.fongmi.android.tv.setting.DlnaSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.service.AirPlayServer;
import com.fongmi.android.tv.service.DLNARendererService;

/**
 * Re-bind DLNA / AirPlay discovery after network switches (Wi-Fi ↔ Ethernet, reconnect).
 */
public final class CastNetworkWatcher {

    private static final long DEBOUNCE_MS = 1500;
    private static ConnectivityManager.NetworkCallback callback;
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final Runnable APPLY = () -> {
        Context app = App.get();
        if (DlnaSetting.isEnabled()) DLNARendererService.apply(app);
        if (AirPlaySetting.isEnabled()) AirPlayServer.apply(app);
        if (Setting.isDlnaLibrary()) DlnaMediaManager.get().search();
    };

    private CastNetworkWatcher() {
    }

    public static synchronized void register(Context context) {
        if (callback != null) return;
        Context app = context.getApplicationContext();
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                HANDLER.removeCallbacks(APPLY);
                HANDLER.postDelayed(APPLY, DEBOUNCE_MS);
            }
        };
        try {
            cm.registerDefaultNetworkCallback(callback);
        } catch (Exception e) {
            callback = null;
        }
    }
}
