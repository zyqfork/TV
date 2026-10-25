package com.fongmi.android.tv.player.exo;

import java.util.Arrays;

/** Source cadence from presentation timestamps, not wall-clock playback speed or display Hz. */
public final class FrameRateEstimator {
    private final long[] intervals = new long[24];
    private long previousUs = Long.MIN_VALUE;
    private int count;
    private int next;

    public synchronized void reset() {
        previousUs = Long.MIN_VALUE;
        count = next = 0;
    }

    public synchronized void addFrame(long presentationTimeUs) {
        if (previousUs != Long.MIN_VALUE) {
            long delta = presentationTimeUs - previousUs;
            if (delta > 0 && delta <= 1_000_000) {
                intervals[next] = delta;
                next = (next + 1) % intervals.length;
                count = Math.min(count + 1, intervals.length);
            } else if (delta != 0) {
                count = next = 0;
            }
        }
        previousUs = presentationTimeUs;
    }

    public synchronized float getFrameRate() {
        if (count < 6) return 0;
        long[] sorted = Arrays.copyOf(intervals, count);
        Arrays.sort(sorted);
        double median = (sorted[(count - 1) / 2] + sorted[count / 2]) / 2.0;
        return (float) (1_000_000.0 / median);
    }
}
