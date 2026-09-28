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
import com.fongmi.android.tv.service.DLNARendererService;

/**
 * Re-bind DLNA / AirPlay discovery after network switches (Wi-Fi ↔ Ethernet, reconnect).
 */
public final class CastNetworkWatcher {

    private static final long DEBOUNCE_MS = 1500;
    private static ConnectivityManager.NetworkCallback callback;
    private static Network activeNetwork;
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final Runnable APPLY = () -> {
        Context app = App.get();
        if (DlnaSetting.isEnabled()) DLNARendererService.apply(app);
        if (AirPlaySetting.isEnabled()) CastPlatform.applyAirPlay(app);
        if (Setting.isDlnaLibrary()) DlnaMediaManager.get().search();
    };

    private CastNetworkWatcher() {
    }

    public static synchronized void register(Context context) {
        if (callback != null) return;
        Context app = context.getApplicationContext();
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        activeNetwork = cm.getActiveNetwork();
        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                if (network.equals(activeNetwork)) return;
                activeNetwork = network;
                scheduleApply();
            }

            @Override
            public void onLost(@NonNull Network network) {
                if (!network.equals(activeNetwork)) return;
                activeNetwork = null;
                scheduleApply();
            }
        };
        try {
            cm.registerDefaultNetworkCallback(callback);
        } catch (Exception e) {
            callback = null;
        }
    }

    /**
     * Keep the process-wide callback while any cast/discovery feature is enabled. The DLNA
     * renderer outlives HomeActivity, so tying this callback to that Activity leaves the
     * persistent service bound to a stale interface after Wi-Fi/Ethernet changes.
     */
    public static synchronized void unregisterIfUnused(Context context) {
        if (DlnaSetting.isEnabled() || AirPlaySetting.isEnabled() || Setting.isDlnaLibrary()) return;
        unregister(context);
    }

    public static synchronized void unregister(Context context) {
        if (callback == null) return;
        HANDLER.removeCallbacks(APPLY);
        Context app = context.getApplicationContext();
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            try {
                cm.unregisterNetworkCallback(callback);
            } catch (RuntimeException ignored) {
            }
        }
        callback = null;
        activeNetwork = null;
    }

    private static void scheduleApply() {
        HANDLER.removeCallbacks(APPLY);
        HANDLER.postDelayed(APPLY, DEBOUNCE_MS);
    }
}
