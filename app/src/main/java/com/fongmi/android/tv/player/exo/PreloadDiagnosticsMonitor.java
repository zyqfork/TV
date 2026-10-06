package com.fongmi.android.tv.player.exo;

import android.util.Log;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.Task;

import java.util.function.Consumer;

/** UI-owned, bounded policy polling for network, data restrictions and free-storage changes. */
public final class PreloadDiagnosticsMonitor {

    private static final long INTERVAL_MS = 2000;
    private final Consumer<Boolean> listener;
    private final Runnable tick = this::refresh;
    private boolean active;
    private boolean checking;
    private boolean refreshPending;
    private int generation;

    public PreloadDiagnosticsMonitor(Consumer<Boolean> listener) {
        this.listener = listener;
    }

    /** All control methods and the listener run on the main thread. */
    public void start() {
        if (!active) {
            active = true;
            generation++;
        }
        refresh();
    }

    public void stop() {
        active = false;
        generation++;
        checking = false;
        refreshPending = false;
        App.removeCallbacks(tick);
    }

    public void refresh() {
        if (!active) return;
        App.removeCallbacks(tick);
        if (checking) {
            refreshPending = true;
            return;
        }
        checking = true;
        int token = generation;
        Task.execute(() -> {
            boolean allowed;
            try {
                allowed = PreloadPolicy.evaluate(App.get()).allowed();
            } catch (Exception e) {
                Log.w("PreloadDiagnostics", "Policy evaluation failed", e);
                allowed = false;
            }
            boolean result = allowed;
            App.post(() -> {
                if (!active || token != generation) return;
                checking = false;
                if (refreshPending) {
                    // A setting changed while this result was computed. Do not repaint the row
                    // with the earlier verdict; evaluate the new policy first.
                    refreshPending = false;
                    refresh();
                    return;
                }
                listener.accept(result);
                App.post(tick, INTERVAL_MS);
            });
        });
    }
}
