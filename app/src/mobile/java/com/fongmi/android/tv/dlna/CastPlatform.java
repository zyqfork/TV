package com.fongmi.android.tv.dlna;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.service.AirPlayServer;
import com.fongmi.android.tv.setting.AirPlaySetting;
import com.fongmi.android.tv.ui.activity.AirPlayCastActivity;
import com.fongmi.android.tv.ui.activity.VideoActivity;

/**
 * The phone half of the cast-receiver subsystem.
 *
 * DLNA pushes are simply played by the normal player; there is no separate cast page. AirPlay shows
 * its session in {@link AirPlayCastActivity} — the same page the TV flavour uses, registered in the
 * phone manifest for the `${applicationId}.airplay` action the airplay module broadcasts when a
 * sender connects. (Without that registration the module falls back to its own Compose activity,
 * which is the overview/logs screen.)
 *
 * `main` is written against this class and the TV flavour provides its own copy with the same FQN.
 */
public final class CastPlatform {

    private CastPlatform() {
    }

    /** A DLNA controller pushed media: play it. */
    public static void showDlnaSession(Context context, CastAction action) {
        String url = action.getCurrentURI();
        if (TextUtils.isEmpty(url)) return;
        VideoActivity.start(context, SiteApi.PUSH, url, url);
    }

    /** Nothing to close — the phone has no separate cast page. */
    public static void hideDlnaSession() {
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
