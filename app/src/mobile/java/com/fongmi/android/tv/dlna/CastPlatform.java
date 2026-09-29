package com.fongmi.android.tv.dlna;

import android.app.Activity;
import android.content.Context;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.service.AirPlayServer;
import com.fongmi.android.tv.setting.AirPlaySetting;
import com.fongmi.android.tv.ui.activity.AirPlayCastActivity;
import com.fongmi.android.tv.ui.activity.CastActivity;

/**
 * The phone half of the cast-receiver subsystem.
 *
 * Both receivers use dedicated session pages. The phone's DLNA page uses the shared renderer,
 * transport state and playback service (including DIDL metadata, request headers and SOAP seek),
 * while providing touch controls instead of the TV's remote-control layout. AirPlay shares its
 * session page with the TV flavour.
 *
 * `main` is written against this class and the TV flavour provides its own copy with the same FQN.
 */
public final class CastPlatform {

    private CastPlatform() {
    }

    /** A DLNA controller pushed media: show it on the touch cast page. */
    public static void showDlnaSession(Context context, CastAction action) {
        CastActivity.start(context, action);
    }

    public static void hideDlnaSession() {
        finishIf(CastActivity.class);
    }

    /**
     * DLNA is about to play: AirPlay has to give up the phone. Without this the AirPlay audio keeps
     * playing underneath the DLNA video — on the TV the two never overlap.
     *
     * Only the local A/V session is stopped. Tearing the server down would unregister NSD and the
     * phone would disappear from the Apple picker after a single DLNA cast.
     */
    public static void yieldToDlna(Context context) {
        finishIf(AirPlayCastActivity.class);
        AirPlayServer.stopLocalSession(context, "dlna");
    }

    private static void finishIf(Class<? extends Activity> type) {
        Activity current = App.activity();
        if (current != null && type.isInstance(current) && !current.isFinishing()) {
            current.finish();
        }
    }

    /** Re-bind AirPlay discovery after a network switch. */
    public static void applyAirPlay(Context context) {
        AirPlayServer.apply(context);
    }

    /** Whether the AirPlay receiver is switched on. */
    public static boolean isAirPlayEnabled() {
        return AirPlaySetting.isEnabled();
    }
}
