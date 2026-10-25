package com.fongmi.android.tv.api.config;

import java.io.InterruptedIOException;

/** Distinguishes a cancelled worker from network timeouts which must finish the loading UI. */
public final class ConfigLoadCancellation {
    private ConfigLoadCancellation() {}

    public static boolean isCanceled(Throwable error) {
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 32; depth++, cause = cause.getCause()) {
            if ("Canceled".equals(cause.getMessage()) || cause instanceof InterruptedException) return true;
            if (cause instanceof InterruptedIOException && Thread.currentThread().isInterrupted()) return true;
            if (cause.getCause() == cause) break;
        }
        return false;
    }
}
