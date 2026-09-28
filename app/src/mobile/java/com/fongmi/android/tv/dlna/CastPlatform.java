package com.fongmi.android.tv.dlna;

import android.content.Context;
import android.text.TextUtils;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.service.AirPlayServer;
import com.fongmi.android.tv.setting.AirPlaySetting;
import com.fongmi.android.tv.ui.activity.VideoActivity;

/**
 * The phone half of the cast-receiver subsystem.
 *
 * The phone has no dedicated cast playback page: DLNA pushes are simply played by the normal
 * player. AirPlay is handled by the `airplay` module itself — it posts its own notification and
 * Compose activity — so all the phone has to do here is run and re-bind that server.
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

    /** No AirPlay local session to yield on the phone (the module owns its own playback). */
    public static void yieldToDlna(Context context) {
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
