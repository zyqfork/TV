package com.fongmi.android.tv.service;

import android.content.Context;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.dlna.CastPlatform;

/**
 * Mutual exclusion between the AirPlay UI and the DLNA cast session.
 *
 * The shared half — suspending playback and closing whichever cast page is up — lives here. The
 * flavour-specific half lives behind {@link CastPlatform}: AirPlay only exists on the TV, and the
 * two flavours show a DLNA session differently.
 */
public final class CastConflict {

    private CastConflict() {
    }

    /** AirPlay session became active: stop DLNA cast UI / shared player. */
    public static void yieldToAirPlay() {
        App.post(() -> {
            PlaybackService.requestSuspend(App.get());
            CastPlatform.hideDlnaSession();
        });
    }

    /** DLNA cast is about to play: stop AirPlay local A/V and close its UI. */
    public static void yieldToDlna(Context context) {
        App.post(() -> CastPlatform.yieldToDlna(context.getApplicationContext()));
    }
}
