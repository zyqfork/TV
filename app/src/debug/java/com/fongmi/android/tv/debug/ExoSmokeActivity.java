package com.fongmi.android.tv.debug;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.player.exo.ExoUtil;

/** ADB-driven ExoPlayer/FFmpeg audio smoke test. */
public final class ExoSmokeActivity extends Activity implements Player.Listener {

    public static final String TAG = "ExoSmoke";
    private ExoPlayer player;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        String url = getIntent().getStringExtra("url");
        if (url == null) throw new IllegalArgumentException("Missing url extra");
        PlayerView view = new PlayerView(this);
        view.setUseController(false);
        setContentView(view);
        player = ExoUtil.buildPlayer(0, this);
        view.setPlayer(player);
        Log.i(TAG, "START url=" + url);
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
    }

    @Override
    public void onPlaybackStateChanged(int state) {
        Log.i(TAG, "STATE " + state);
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        Log.i(TAG, "PLAYING " + isPlaying);
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        Log.e(TAG, "ERROR code=" + error.errorCode + " message=" + error.getMessage(), error);
    }

    @Override
    protected void onDestroy() {
        if (player != null) player.release();
        player = null;
        super.onDestroy();
    }
}
