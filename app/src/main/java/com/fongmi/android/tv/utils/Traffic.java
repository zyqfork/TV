package com.fongmi.android.tv.utils;

import android.net.TrafficStats;
import android.view.View;
import android.widget.TextView;

import com.fongmi.android.tv.App;

import java.text.DecimalFormat;

/**
 * Per-playback UI bitrate sampler. TrafficStats is UID-scoped, but each Activity keeps its own
 * baseline so interleaved screens do not poison each other's first samples.
 */
public class Traffic {

    private static final DecimalFormat format = new DecimalFormat("#.0");
    private static final int UID = App.get().getApplicationInfo().uid;
    private static final String UNIT_KB = " KB/s";
    private static final String UNIT_MB = " MB/s";

    private long lastTotalRxBytes;
    private long lastTimeStamp;

    public synchronized void setSpeed(TextView view) {
        if (TrafficStats.getUidRxBytes(UID) == TrafficStats.UNSUPPORTED) return;
        view.setVisibility(View.VISIBLE);
        view.setText(getSpeed());
    }

    private String getSpeed() {
        long nowTimeStamp = System.currentTimeMillis();
        long nowTotalRxBytes = TrafficStats.getUidRxBytes(UID) / 1024;
        // The first sample after reset/player switch establishes a baseline.
        if (lastTimeStamp == 0) {
            lastTimeStamp = nowTimeStamp;
            lastTotalRxBytes = nowTotalRxBytes;
            return "0" + UNIT_KB;
        }
        long speed = (nowTotalRxBytes - lastTotalRxBytes) * 1000 / Math.max(nowTimeStamp - lastTimeStamp, 1);
        lastTimeStamp = nowTimeStamp;
        lastTotalRxBytes = nowTotalRxBytes;
        return speed < 1000 ? speed + UNIT_KB : format.format(speed / 1024f) + UNIT_MB;
    }

    public synchronized void reset() {
        long total = TrafficStats.getUidRxBytes(UID);
        lastTotalRxBytes = total == TrafficStats.UNSUPPORTED ? 0 : total / 1024;
        lastTimeStamp = total == TrafficStats.UNSUPPORTED ? 0 : System.currentTimeMillis();
    }
}
