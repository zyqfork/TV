package com.fongmi.android.tv.service;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.ui.activity.AirPlayCastActivity;
import com.fongmi.android.tv.ui.activity.CastActivity;

import io.github.jqssun.airplay.service.AirPlayService;

/** Mutual exclusion between AirPlay UI and DLNA CastActivity. */
public final class CastConflict {

    private CastConflict() {
    }

    /** AirPlay session became active: stop DLNA cast UI / shared player. */
    public static void yieldToAirPlay() {
        App.post(() -> {
            PlaybackService.requestSuspend(App.get());
            finishIf(CastActivity.class);
        });
    }

    /** DLNA cast is about to play: stop AirPlay local A/V and close its UI. */
    public static void yieldToDlna(Context context) {
        App.post(() -> {
            finishIf(AirPlayCastActivity.class);
            stopAirPlaySession(context.getApplicationContext());
        });
    }

    private static void finishIf(Class<? extends Activity> type) {
        Activity current = App.activity();
        if (current != null && type.isInstance(current) && !current.isFinishing()) {
            current.finish();
        }
    }

    private static void stopAirPlaySession(Context app) {
        Intent intent = new Intent(app, AirPlayService.class);
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                try {
                    ((AirPlayService.LocalBinder) binder).getService().stopLocalSession("dlna");
                } catch (Exception ignored) {
                }
                try {
                    app.unbindService(this);
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
            }
        };
        try {
            app.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Exception ignored) {
        }
    }
}
