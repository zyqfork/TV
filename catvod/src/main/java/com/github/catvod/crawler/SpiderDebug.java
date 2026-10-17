package com.github.catvod.crawler;

import android.os.SystemClock;
import android.text.TextUtils;

import com.orhanobut.logger.Logger;

public class SpiderDebug {

    private static final String TAG = SpiderDebug.class.getSimpleName();

    /**
     * A spider can log the same line on every poll of a playback session. Third-party jars do
     * exactly that, which floods logcat and hides real errors, so a repeated message is logged
     * once and then suppressed until the message changes.
     *
     * <p>The window must stay well above the caller's own repeat interval: an observed spider
     * polled every five to six seconds, so a five-second window let every line through.
     *
     * <p>Only this overload is throttled: the {@code log(tag, msg, args)} overload formats
     * caller-supplied arguments, so identical format strings can carry different data.
     */
    private static final long REPEAT_WINDOW_MS = 60_000L;
    private static String lastMessage;
    private static long lastLogAt;

    public static void log(Throwable th) {
        if (th != null) th.printStackTrace();
    }

    public static void log(String msg) {
        if (TextUtils.isEmpty(msg) || !accept(msg)) return;
        Logger.t(TAG).d(msg);
    }

    public static void log(String tag, String msg, Object... args) {
        if (!TextUtils.isEmpty(msg)) Logger.t(tag).d(msg, args);
    }

    private static boolean accept(String msg) {
        long now = SystemClock.elapsedRealtime();
        boolean repeat = msg.equals(lastMessage) && now - lastLogAt < REPEAT_WINDOW_MS;
        if (repeat) return false;
        lastMessage = msg;
        lastLogAt = now;
        return true;
    }
}
