package com.fongmi.android.tv.player;

import android.content.Context;

import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.mpvplayer.MpvPlayer;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.PlayerSetting;

import is.xyz.mpv.MPVLib;

/** Short snapshot for the on-screen overlay; never includes URLs, headers or credentials. */
public final class PlaybackStats {

    private PlaybackStats() {
    }

    public static String format(Context context, PlayerManager manager) {
        if (manager == null || manager.isReleased()) return context.getString(R.string.player_info_empty);
        Player player = manager.getPlayer();
        if (player.getCurrentMediaItem() == null || player.getPlaybackState() == Player.STATE_IDLE) {
            return context.getString(R.string.player_info_empty);
        }
        String renderer = "Surface";
        String fps = "-";
        String dropped = "-";
        if (player instanceof ExoPlayer exo) {
            Format format = exo.getVideoFormat();
            if (format != null && format.frameRate > 0) fps = String.valueOf(format.frameRate);
            DecoderCounters counters = exo.getVideoDecoderCounters();
            if (counters != null) {
                counters.ensureUpdated();
                dropped = String.valueOf(counters.droppedBufferCount);
            }
        } else if (player instanceof MpvPlayer mpv) {
            renderer = mpv.getActiveVideoOutput();
            Double sourceFps = MPVLib.getPropertyDouble("container-fps");
            Double drops = MPVLib.getPropertyDouble("frame-drop-count");
            if (sourceFps != null) fps = sourceFps.toString();
            if (drops != null) dropped = String.valueOf(drops.longValue());
        }
        return (manager.getEngine() == PlayerSetting.ENGINE_MPV ? "MPV" : "Exo")
                + "\n" + manager.getVideoDecoderDescription()
                + "\n" + renderer + " · " + manager.getVideoWidth() + "×" + manager.getVideoHeight()
                + "\n" + fps + " fps · " + dropped + " " + context.getString(R.string.player_info_drops_short)
                + "\n" + player.getBufferedPercentage() + "%";
    }
}
