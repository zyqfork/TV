package com.fongmi.android.tv.ui.playback;

import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.setting.SubtitleSetting;
import com.github.catvod.net.OkHttp;

/**
 * Re-applies subtitle + danmaku overlays onto a PlayerView after engine rebuilds,
 * lifecycle restore, or settings changes so both EXO and MPV stay consistent.
 */
public final class PlaybackOverlayBinder {

    private static final String TAG = "TV-overlay";

    private PlaybackOverlayBinder() {
    }

    public static void applySubtitleStyle(@Nullable SubtitleView view) {
        SubtitleSetting.applyStyle(view);
    }

    public static void applyDanmaku(@Nullable PlayerView playerView, @Nullable PlayerManager player) {
        if (playerView == null) return;
        try {
            playerView.setDanmakuOkHttpClient(OkHttp.player());
            playerView.setDanmakuEnabled(DanmakuSetting.isShow());
            playerView.setDanmakuConfig(DanmakuSetting.getConfig());
            if (player != null && !player.isReleased()) {
                playerView.setDanmakuSource(player.getSelectedDanmakuUri());
            }
        } catch (Throwable e) {
            // Danmaku is optional, so a failure here must not break playback — but swallowing it
            // silently made "danmaku does not show" impossible to diagnose from a log.
            Log.w(TAG, "applyDanmaku failed", e);
        }
    }

    public static void sync(@Nullable PlayerView playerView, @Nullable PlayerManager player) {
        if (playerView == null) return;
        applySubtitleStyle(playerView.getSubtitleView());
        applyDanmaku(playerView, player);
    }
}
