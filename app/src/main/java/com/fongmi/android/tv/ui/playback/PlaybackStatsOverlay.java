package com.fongmi.android.tv.ui.playback;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

import com.fongmi.android.tv.player.PlaybackStats;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.setting.PlayerSetting;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Optional top-right stats block, refreshed once a second while playback is visible. */
public final class PlaybackStatsOverlay extends TextView {

    private static final long INTERVAL_MS = 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Supplier<PlayerManager> manager;
    private final BooleanSupplier windowVisible;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            if (started) handler.postDelayed(this, INTERVAL_MS);
        }
    };
    private boolean started;

    public PlaybackStatsOverlay(Context context, Supplier<PlayerManager> manager,
                                BooleanSupplier windowVisible) {
        super(context);
        this.manager = manager;
        this.windowVisible = windowVisible;
        setTypeface(android.graphics.Typeface.MONOSPACE);
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        setTextColor(0xE6FFFFFF);
        setBackgroundResource(com.fongmi.android.tv.R.drawable.bg_stats_overlay);
        setGravity(Gravity.START);
        int pad = dp(10);
        setPadding(pad, pad, pad, pad);
        setMinWidth(dp(190));
        setLineSpacing(dp(2), 1.0f);
        setVisibility(GONE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean isVisible() {
        return PlayerSetting.isPlaybackStatsVisible() && windowVisible.getAsBoolean();
    }

    public void start() {
        started = true;
        refresh();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, INTERVAL_MS);
    }

    public void stop() {
        started = false;
        handler.removeCallbacks(tick);
    }

    public void refresh() {
        boolean show = isVisible();
        setVisibility(show && started ? VISIBLE : GONE);
        if (!show) return;
        PlayerManager player = manager.get();
        setText(PlaybackStats.format(getContext(), player));
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }
}
