package com.fongmi.android.tv.dlna;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.service.AirPlayServer;
import com.fongmi.android.tv.setting.AirPlaySetting;
import com.fongmi.android.tv.ui.activity.AirPlayCastActivity;
import com.fongmi.android.tv.ui.activity.CastActivity;

/**
 * The TV half of the cast-receiver subsystem.
 *
 * `main` owns the DLNA renderer and the AirPlay/DLNA mutual exclusion, but showing a cast session —
 * and the whole AirPlay side — is flavour-specific. The phone flavour has its own copy of this
 * class (same FQN in app/src/mobile), so each flavour compiles exactly one.
 *
 * This is a pure forward of the behaviour that used to live inline in CastConflict and
 * DLNAAvTransportImpl: nothing here changed when it moved.
 */
public final class CastPlatform {

    private CastPlatform() {
    }

    /** A DLNA controller pushed media: show it on the cast page. */
    public static void showDlnaSession(Context context, CastAction action) {
        Intent intent = new Intent(context, CastActivity.class);
        intent.putExtra(CastAction.KEY_EXTRA, action);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(intent);
    }

    /** Close the DLNA cast page if it is the one showing. */
    public static void hideDlnaSession() {
        finishIf(CastActivity.class);
    }

    /** DLNA is about to play: AirPlay has to give up the box. */
    public static void yieldToDlna(Context context) {
        finishIf(AirPlayCastActivity.class);
        // Only yield the local A/V session to DLNA. ACTION_STOP_SERVER would unregister
        // AirPlay/NSD and the TV vanishes from the Apple picker after one DLNA cast.
        AirPlayServer.stopLocalSession(context, "dlna");
    }

    /** Re-bind AirPlay discovery after a network switch. */
    public static void applyAirPlay(Context context) {
        AirPlayServer.apply(context);
    }

    /** Whether the AirPlay receiver is switched on (a TV-only setting). */
    public static boolean isAirPlayEnabled() {
        return AirPlaySetting.isEnabled();
    }

    private static void finishIf(Class<? extends Activity> type) {
        Activity current = App.activity();
        if (current != null && type.isInstance(current) && !current.isFinishing()) {
            current.finish();
        }
    }
}
