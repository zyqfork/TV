package androidx.media3.mpvplayer;

/** A first-frame deadline, independent of READY and never extended by advancing audio. */
public final class MpvFirstFrameWatchdog {
    public static final long TIMEOUT_MS = 30_000;
    private long startedMs = -1;

    public boolean arm(long nowMs) {
        if (startedMs >= 0) return false;
        startedMs = nowMs;
        return true;
    }

    public boolean allowEmbedRecovery(long nowMs) {
        return startedMs >= 0 && nowMs - startedMs >= 1_000;
    }

    public boolean expired(long nowMs) {
        return startedMs >= 0 && nowMs - startedMs >= TIMEOUT_MS;
    }

    public void reset() { startedMs = -1; }
}
