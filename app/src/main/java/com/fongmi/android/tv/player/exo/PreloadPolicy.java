package com.fongmi.android.tv.player.exo;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import com.fongmi.android.tv.setting.PreloadSetting;
import com.github.catvod.utils.Path;

import java.io.File;

/** Conservative policy that prevents background preload on costly or resource-starved devices. */
public final class PreloadPolicy {

    /** Keep headroom for the OS and other apps; preload is only a cache warm-up. */
    static final long MIN_FREE_BYTES = 256L * 1024 * 1024;

    private static volatile boolean testBypass;

    private PreloadPolicy() {
    }

    /**
     * Debug/automation escape hatch. Only {@code app/src/debug} harnesses should call this; it lets
     * an ADB driven test exercise preload on a lab network that would otherwise be metered or
     * unvalidated. Never enable from production code.
     */
    public static void setTestBypass(boolean bypass) {
        testBypass = bypass;
    }

    public static Decision evaluate(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        boolean connected = false;
        boolean validated = false;
        boolean metered = true;
        boolean backgroundRestricted = false;
        if (manager != null) {
            metered = manager.isActiveNetworkMetered();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                backgroundRestricted = manager.getRestrictBackgroundStatus() == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network network = manager.getActiveNetwork();
                NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
                connected = capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                validated = capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            } else {
                android.net.NetworkInfo info = manager.getActiveNetworkInfo();
                connected = validated = info != null && info.isConnected();
            }
        }
        File cacheDir = Path.exoCache();
        long freeBytes = Math.max(0, Path.available(cacheDir));
        return evaluate(connected, validated, metered, backgroundRestricted, freeBytes, false, testBypass);
    }

    static Decision evaluate(boolean connected, boolean validated, boolean metered, boolean backgroundRestricted, long freeBytes) {
        return evaluate(connected, validated, metered, backgroundRestricted, freeBytes, false, false);
    }

    static Decision evaluate(boolean connected, boolean validated, boolean metered, boolean backgroundRestricted, long freeBytes, boolean allowMetered, boolean bypass) {
        if (bypass) return new Decision(true, "bypass");
        // Storage is a hard stop: preload must never push the device into a low-space state.
        if (freeBytes < MIN_FREE_BYTES) return new Decision(false, "low_storage");
        // A restricted background data policy is an explicit user signal, so respect it as well.
        if (backgroundRestricted) return new Decision(false, "background_restricted");
        if (!connected) return new Decision(false, "network_disconnected");
        // "Allow metered" only controls cost. It must not bypass captive portals or networks that
        // Android has determined cannot reach the Internet; debug automation has its own bypass.
        if (!validated) return new Decision(false, "network_unvalidated");
        if (metered && !allowMetered) return new Decision(false, "network_metered");
        return new Decision(true, "allowed");
    }

    public record Decision(boolean allowed, String reason) {
    }
}
