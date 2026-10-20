package com.fongmi.android.tv.debug;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.player.exo.ExoUtil;
import com.fongmi.android.tv.player.exo.PreCache;
import com.fongmi.android.tv.player.exo.PreloadDiagnostics;
import com.fongmi.android.tv.player.exo.PreloadPolicy;
import com.fongmi.android.tv.setting.PreloadSetting;

/**
 * ADB driven end-to-end check for next-episode disk preload.
 *
 * <p>Plays {@code url1}, asks {@link PreCache} to warm {@code url2}, then reports how many bytes of
 * {@code url2} are actually resident in the shared playback cache. Because normal playback only
 * reads that cache, a positive byte count is a real "the next episode is on disk" signal rather
 * than a guess from timing.
 *
 * <p>Extras: url1 (required), url2 (required), decode 0=soft 1=hard (default 1),
 * durationMs (default from settings), switchToSecond true/false (default true),
 * waitMs (default 30000).
 */
@UnstableApi
public final class PreloadSmokeActivity extends Activity implements Player.Listener {

    public static final String TAG = "PreloadSmoke";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private PreCache preCache;
    private MediaItem first;
    private MediaItem second;
    private int decode;
    private long waitMs;
    private boolean switchOnDone;
    private long cancelAfterMs;
    private long preloadStartedAt;
    private long preloadDoneAt;
    private long switchStartedAt;
    private long switchReadyAt;
    private int ticks;
    // Captured so the harness can hand the device back exactly as it found it.
    private boolean originalPreload;
    private boolean restored;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        // Repair hatch: a previous run (before this harness learned to restore) can leave the device
        // with preload forced on. `--ez resetDefaults true` puts the shipped defaults back.
        if (getIntent().getBooleanExtra("resetDefaults", false)) {
            PreloadSetting.putPreload(false);
            Log.i(TAG, "RESET preload=" + PreloadSetting.isPreload());
            finish();
            return;
        }
        String url1 = getIntent().getStringExtra("url1");
        String url2 = getIntent().getStringExtra("url2");
        if (url1 == null || url2 == null) throw new IllegalArgumentException("Missing url1/url2 extra");
        decode = getIntent().getIntExtra("decode", 1);
        waitMs = getIntent().getLongExtra("waitMs", 30_000L);
        switchOnDone = getIntent().getBooleanExtra("switchToSecond", true);
        cancelAfterMs = getIntent().getLongExtra("cancelAfterMs", -1L);
        // Preload is off by default and the lab network is often metered, so opt in explicitly.
        // Snapshot first: this harness must not leave the device reconfigured.
        originalPreload = PreloadSetting.isPreload();
        PreloadSetting.putPreload(true);
        PreloadPolicy.setTestBypass(true);
        PreloadDiagnostics.reset();
        first = MediaItem.fromUri(url1);
        second = MediaItem.fromUri(url2);
        PlayerView view = new PlayerView(this);
        view.setUseController(false);
        setContentView(view);
        player = ExoUtil.buildPlayer(decode, this);
        player.addListener(this);
        view.setPlayer(player);
        preCache = new PreCache();
        Log.i(TAG, "START url1=" + url1 + " url2=" + url2 + " decode=" + decode
                + " durationMs=" + PreloadSetting.getPreloadDurationMs() + " switch=" + switchOnDone);
        player.setMediaItem(first);
        player.setPlayWhenReady(true);
        player.prepare();
        player.play();
        handler.postDelayed(this::startPreload, 2_000L);
    }

    private void startPreload() {
        if (player == null) return;
        // Registering the live player is what PreCache needs before it can preload a sibling item.
        preCache.start(player, first);
        preloadStartedAt = System.currentTimeMillis();
        preCache.preload(second, 0);
        if (cancelAfterMs >= 0) handler.postDelayed(preCache::clearPreload, cancelAfterMs);
        handler.postDelayed(this::poll, 500L);
    }

    private void poll() {
        if (player == null) return;
        PreloadDiagnostics.Snapshot latest = PreloadDiagnostics.latest();
        PreloadDiagnostics.Counters counters = PreloadDiagnostics.counters();
        boolean settled = counters.completed() > 0 || counters.failed() > 0
                || "completed".equals(latest.state()) || "failed".equals(latest.state()) || "cancelled".equals(latest.state());
        if (settled || ++ticks * 500L >= waitMs) {
            preloadDoneAt = System.currentTimeMillis();
            if (!settled) Log.w(TAG, "preload did not settle within " + waitMs + "ms");
            if (switchOnDone) switchToSecond();
            else report();
            return;
        }
        handler.postDelayed(this::poll, 500L);
    }

    private void switchToSecond() {
        switchStartedAt = System.currentTimeMillis();
        player.setMediaItem(second);
        player.prepare();
        player.play();
        handler.postDelayed(this::report, 8_000L);
    }

    private void report() {
        if (player == null) return;
        PreloadDiagnostics.Counters c = PreloadDiagnostics.counters();
        PreloadDiagnostics.Snapshot latest = PreloadDiagnostics.latest();
        long cachedBytes = PreloadDiagnostics.cachedBytes(second);
        boolean hit = cachedBytes > 0;
        Log.i(TAG, "RESULT preloadSettled=" + latest.state()
                + " completed=" + c.completed()
                + " failed=" + c.failed()
                + " cancelled=" + c.cancelled()
                + " skipped=" + c.skipped()
                + " cachedBytes=" + cachedBytes
                + " hit=" + hit
                + " preloadMs=" + Math.max(0, preloadDoneAt - preloadStartedAt)
                + " switchReadyMs=" + (switchReadyAt == 0 ? -1 : switchReadyAt - switchStartedAt)
                + " state=" + player.getPlaybackState()
                + " positionMs=" + player.getCurrentPosition());
        // The driver force-stops the process once it sees RESULT, so onDestroy never runs; undo the
        // opt-in here rather than relying on the activity lifecycle.
        restoreSettings();
    }

    /** Idempotent: the driver may exit via force-stop, back, or completion. */
    private void restoreSettings() {
        if (restored) return;
        restored = true;
        PreloadSetting.putPreload(originalPreload);
        Log.i(TAG, "RESTORED preload=" + originalPreload);
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        if (playbackState == Player.STATE_READY && switchStartedAt != 0 && switchReadyAt == 0) {
            switchReadyAt = System.currentTimeMillis();
        }
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        Log.e(TAG, "ERROR code=" + error.errorCode + " message=" + error.getMessage());
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (preCache != null) preCache.release();
        if (player != null) player.release();
        preCache = null;
        player = null;
        PreloadPolicy.setTestBypass(false);
        // Hand the device back with the settings it had before this run.
        restoreSettings();
        super.onDestroy();
    }
}
