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
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.mpvplayer.MpvPlayer;
import androidx.media3.mpvplayer.MpvPlayerConfig;
import androidx.media3.ui.PlayerView;

/**
 * ADB-driven debug harness used by the Android Docker playback smoke test.
 */
public final class MpvSmokeActivity extends Activity implements Player.Listener {

    public static final String TAG = "MpvSmoke";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MpvPlayer player;
    private boolean controlsApplied;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        String url = getIntent().getStringExtra("url");
        String headerName = getIntent().getStringExtra("header_name");
        String headerValue = getIntent().getStringExtra("header_value");
        if (url == null) throw new IllegalArgumentException("Missing url extra");

        PlayerView view = new PlayerView(this);
        view.setUseController(false);
        setContentView(view);
        player = new MpvPlayer.Builder(this)
                .setDecode(0)
                .setConfig(new MpvPlayerConfig.Builder().build())
                .build();
        player.addListener(this);
        view.setPlayer(player);

        MediaItem.RequestMetadata.Builder request = new MediaItem.RequestMetadata.Builder();
        if (headerName != null && headerValue != null) {
            Bundle extras = new Bundle();
            extras.putString(headerName, headerValue);
            request.setExtras(extras);
        }
        player.setMediaItem(new MediaItem.Builder()
                .setUri(url)
                .setRequestMetadata(request.build())
                .build());
        player.prepare();
        player.play();
        handler.postDelayed(this::reportProgress, 8_000);
    }

    private void reportProgress() {
        if (player == null) return;
        Log.i(TAG, "RESULT state=" + player.getPlaybackState()
                + " positionMs=" + player.getCurrentPosition()
                + " durationMs=" + player.getDuration()
                + " tracks=" + player.getCurrentTracks().getGroups().size()
                + " audioOffsetMs=" + player.getAudioOffsetMs()
                + " textOffsetMs=" + player.getTextOffsetMs()
                + " video=" + player.getVideoSize().width + "x" + player.getVideoSize().height);
    }

    @Override
    public void onPlaybackStateChanged(int state) {
        Log.i(TAG, "STATE " + state);
        if (state != Player.STATE_READY || controlsApplied) return;
        controlsApplied = true;
        player.setAudioOffsetMs(120);
        player.setTextOffsetMs(-80);
        TrackSelectionParameters.Builder selection =
                player.getTrackSelectionParameters().buildUpon();
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.length > 0) {
                selection.setOverrideForType(
                        new TrackSelectionOverride(group.getMediaTrackGroup(), 0));
            }
        }
        player.setTrackSelectionParameters(selection.build());
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        Log.e(TAG, "ERROR code=" + error.errorCode + " message=" + error.getMessage(), error);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) player.release();
        player = null;
        super.onDestroy();
    }
}
