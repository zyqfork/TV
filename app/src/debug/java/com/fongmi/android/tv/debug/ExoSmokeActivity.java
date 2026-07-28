package com.fongmi.android.tv.debug;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.player.exo.ExoUtil;

/** ADB-driven ExoPlayer/FFmpeg audio smoke test. */
public final class ExoSmokeActivity extends Activity implements Player.Listener {

    public static final String TAG = "ExoSmoke";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private int decode;
    private boolean renderedFirstFrame;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        String url = getIntent().getStringExtra("url");
        if (url == null) throw new IllegalArgumentException("Missing url extra");
        decode = getIntent().getIntExtra("decode", 1);
        String mime = getIntent().getStringExtra("mime");
        PlayerView view = new PlayerView(this);
        view.setUseController(false);
        setContentView(view);
        player = ExoUtil.buildPlayer(decode, this);
        player.addListener(this);
        view.setPlayer(player);
        Log.i(TAG, "START url=" + url + " decode=" + decode + " mime=" + mime);
        MediaItem.Builder item = new MediaItem.Builder().setUri(url);
        if (mime != null && !mime.isEmpty()) {
            item.setMimeType(mime);
        } else if (url.contains(".mp4") || url.contains(".m4v")) {
            item.setMimeType(MimeTypes.VIDEO_MP4);
        }
        player.setMediaItem(item.build());
        player.prepare();
        player.play();
        long reportAt = getIntent().getLongExtra("report_ms", 12_000L);
        handler.postDelayed(this::reportProgress, Math.max(3_000L, reportAt));
    }

    private void reportProgress() {
        if (player == null) return;
        boolean ok = player.getPlaybackState() == Player.STATE_READY
                && player.getCurrentPosition() > 0
                && player.getVideoSize().width > 0
                && renderedFirstFrame;
        Log.i(TAG, "RESULT ok=" + ok
                + " state=" + player.getPlaybackState()
                + " positionMs=" + player.getCurrentPosition()
                + " decode=" + decode
                + " renderedFirstFrame=" + renderedFirstFrame
                + " video=" + player.getVideoSize().width + "x" + player.getVideoSize().height);
        if (getIntent().getBooleanExtra("finish_after_report", true)) {
            handler.postDelayed(this::finish, 500);
        }
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
    public void onRenderedFirstFrame() {
        renderedFirstFrame = true;
        Log.i(TAG, "RENDERED_FIRST_FRAME decode=" + decode
                + " video=" + player.getVideoSize().width + "x" + player.getVideoSize().height);
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        Log.e(TAG, "ERROR code=" + error.errorCode + " message=" + error.getMessage(), error);
        if (getIntent().getBooleanExtra("finish_after_report", true)) {
            handler.postDelayed(this::finish, 1_500);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) player.release();
        player = null;
        super.onDestroy();
    }
}
