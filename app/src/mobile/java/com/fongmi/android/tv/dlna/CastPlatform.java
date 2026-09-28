package com.fongmi.android.tv.dlna;

import android.content.Context;
import android.text.TextUtils;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.ui.activity.VideoActivity;

/**
 * The phone half of the cast-receiver subsystem.
 *
 * The phone can receive DLNA casts, but it has no AirPlay (that module is a leanback-only
 * dependency) and no dedicated cast playback page: the pushed media is simply played by the normal
 * player, so the yield hooks have nothing to do. `main` is written against this class and the TV
 * flavour provides its own copy with the same FQN.
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

    /** No AirPlay on the phone, so nothing has to give way. */
    public static void yieldToDlna(Context context) {
    }

    /** No AirPlay on the phone. */
    public static void applyAirPlay(Context context) {
    }
}
