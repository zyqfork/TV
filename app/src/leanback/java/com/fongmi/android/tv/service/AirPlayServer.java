package com.fongmi.android.tv.service;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.AirPlaySetting;

import io.github.jqssun.airplay.service.AirPlayService;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

public class AirPlayServer {

    /** Wait until user stops clicking before touching the service. */
    private static final long DEBOUNCE_MS = 1200;
    /** Gap after stopService so the previous FGS startForeground promise can settle. */
    private static final long RESTART_GAP_MS = 500;

    private static Runnable pendingApply;
    private static Runnable pendingStart;
    private static boolean bridgeBound;
    private static AirPlayService bridged;

    private static final Function1<Boolean, Unit> SESSION_CALLBACK = active -> {
        if (Boolean.TRUE.equals(active)) {
            CastConflict.yieldToAirPlay();
        }
        return Unit.INSTANCE;
    };

    private static final ServiceConnection BRIDGE = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            bridged = ((AirPlayService.LocalBinder) binder).getService();
            bridged.setSessionActiveCallback(SESSION_CALLBACK);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bridged = null;
            // Service died while still "bound" from our side; allow bindBridge() to reconnect.
            bridgeBound = false;
        }
    };

    public static void start(Context context) {
        if (!AirPlaySetting.isEnabled()) return;
        Context app = context.getApplicationContext();
        ContextCompat.startForegroundService(app, new Intent(app, AirPlayService.class).setAction(AirPlayService.ACTION_START_SERVER));
        bindBridge(app);
    }

    public static void stop(Context context) {
        Context app = context.getApplicationContext();
        unbindBridge(app);
        app.stopService(new Intent(app, AirPlayService.class));
    }

    public static void apply(Context context) {
        Context app = context.getApplicationContext();
        cancelPending();
        pendingApply = () -> {
            pendingApply = null;
            stop(app);
            pendingStart = () -> {
                pendingStart = null;
                start(app);
            };
            App.post(pendingStart, RESTART_GAP_MS);
        };
        App.post(pendingApply, DEBOUNCE_MS);
    }

    private static void bindBridge(Context app) {
        if (bridgeBound) return;
        bridgeBound = app.bindService(new Intent(app, AirPlayService.class), BRIDGE, Context.BIND_AUTO_CREATE);
    }

    private static void unbindBridge(Context app) {
        if (bridged != null) {
            bridged.setSessionActiveCallback(null);
            bridged = null;
        }
        if (!bridgeBound) return;
        try {
            app.unbindService(BRIDGE);
        } catch (Exception ignored) {
        }
        bridgeBound = false;
    }

    private static void cancelPending() {
        if (pendingApply != null) {
            App.removeCallbacks(pendingApply);
            pendingApply = null;
        }
        if (pendingStart != null) {
            App.removeCallbacks(pendingStart);
            pendingStart = null;
        }
    }
}
