package com.fongmi.android.tv.dlna;

import android.os.SystemClock;
import android.util.Log;

/** Opt-in stage timing; disabled by default, enabled with log.tag.DlnaDiscovery=DEBUG. */
public final class DlnaDiscoveryTrace {
    private static final String TAG = "DlnaDiscovery";
    private DlnaDiscoveryTrace() {}
    public static void log(String message) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "t=" + SystemClock.elapsedRealtime() + " " + message);
    }
}
