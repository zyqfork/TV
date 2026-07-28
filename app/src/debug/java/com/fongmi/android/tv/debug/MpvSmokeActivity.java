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
import androidx.media3.decoder.ffmpeg.FfmpegLibrary;
import androidx.media3.mpvplayer.MpvPlayer;
import androidx.media3.mpvplayer.MpvPlayerConfig;
import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.player.mpv.MpvUtil;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.ui.activity.VideoActivity;

import is.xyz.mpv.MPVLib;

/**
 * ADB-driven debug harness used by the Android Docker playback smoke test.
 *
 * <p>Extras:
 * <ul>
 *   <li>{@code url} (required)</li>
 *   <li>{@code decode} 1=hard/mediacodec, 0=soft, 2=performance (default 1)</li>
 *   <li>{@code live} apply live IJK-behavior options via MpvUtil (default false)</li>
 *   <li>{@code start_ms} resume position in milliseconds</li>
 *   <li>{@code header_name}/{@code header_value} optional HTTP header</li>
 * </ul>
 */
public final class MpvSmokeActivity extends Activity implements Player.Listener {

    public static final String TAG = "MpvSmoke";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MpvPlayer player;
    private boolean controlsApplied;
    private boolean renderedFirstFrame;
    private boolean stopOnBackground;
    private String secondUrl;
    private long startMs;
    private int decode;
    private boolean live;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        String url = getIntent().getStringExtra("url");
        secondUrl = getIntent().getStringExtra("second_url");
        String headerName = getIntent().getStringExtra("header_name");
        String headerValue = getIntent().getStringExtra("header_value");
        if (url == null) throw new IllegalArgumentException("Missing url extra");
        if (getIntent().getBooleanExtra("formal_activity", false)) {
            PlayerSetting.putEngine(PlayerSetting.ENGINE_MPV);
            VideoActivity.start(this, url);
            finish();
            return;
        }

        Log.i(TAG, "FFMPEG available=" + FfmpegLibrary.isAvailable()
                + " version=" + FfmpegLibrary.getVersion()
                + " aac=" + FfmpegLibrary.supportsFormat("audio/mp4a-latm")
                + " ac3=" + FfmpegLibrary.supportsFormat("audio/ac3")
                + " dts=" + FfmpegLibrary.supportsFormat("audio/vnd.dts"));
        stopOnBackground = getIntent().getBooleanExtra("stop_on_background", false);
        decode = getIntent().getIntExtra("decode", 1);
        live = getIntent().getBooleanExtra("live", false);
        startMs = Math.max(0, getIntent().getLongExtra("start_ms", 0L));

        // Ensure SurfaceView is created after landscape is applied (avoids a transient portrait
        // buffer that poisons mediacodec_embed on phones).
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        setContentView(R.layout.activity_mpv_smoke);
        PlayerView view = findViewById(R.id.player);
        // Exercise the same surface_type=none -> dynamic SurfaceView path as PlaybackActivity.
        view.setRender(0);
        view.setArtworkDisplayMode(PlayerView.ARTWORK_DISPLAY_MODE_OFF);
        view.setUseController(false);
        if (getIntent().getBooleanExtra("formal_config", false)) {
            player = MpvUtil.buildPlayer(decode, live, this);
        } else {
            player = new MpvPlayer.Builder(this)
                    .setDecode(decode)
                    .setConfig(new MpvPlayerConfig.Builder()
                            .addPreInitStringOption("gpu-context", "android")
                            .addPreInitStringOption("opengl-es", "yes")
                            .addPreInitStringOption("vo", "gpu")
                            .addPreInitStringOption("ytdl", "no")
                            .build())
                    .build();
        }
        player.addListener(this);
        view.setPlayer(player);

        MediaItem.RequestMetadata.Builder request = new MediaItem.RequestMetadata.Builder();
        if (headerName != null && headerValue != null) {
            Bundle extras = new Bundle();
            extras.putString(headerName, headerValue);
            request.setExtras(extras);
        }
        Log.i(TAG, "START url=" + url + " decode=" + decode + " startMs=" + startMs);
        final MediaItem item = new MediaItem.Builder()
                .setUri(url)
                .setRequestMetadata(request.build())
                .build();
        // Defer first load until PlayerView has laid out a non-zero Surface (phone rotation race).
        view.post(() -> {
            if (player == null) return;
            player.setMediaItem(item, startMs);
            player.prepare();
            player.play();
        });
        if (secondUrl != null) handler.postDelayed(this::switchMedia, 5_000);
        long reportAt = getIntent().getLongExtra("report_ms", secondUrl == null ? 18_000L : 22_000L);
        handler.postDelayed(this::reportProgress, Math.max(5_000L, reportAt));
    }

    private void switchMedia() {
        if (player == null || secondUrl == null) return;
        renderedFirstFrame = false;
        controlsApplied = false;
        Log.i(TAG, "SWITCH url=" + secondUrl);
        player.setMediaItem(MediaItem.fromUri(secondUrl));
        player.prepare();
        player.play();
    }

    private void reportProgress() {
        if (player == null) return;
        long position = player.getCurrentPosition();
        long duration = player.getDuration();
        int videoW = player.getVideoSize().width;
        int videoH = player.getVideoSize().height;
        boolean ok = player.getPlaybackState() == Player.STATE_READY
                && position > 0
                && videoW > 0
                && videoH > 0
                && renderedFirstFrame;
        boolean resumeOk = startMs <= 0 || position + 1500 >= startMs;
        Log.i(TAG, "RESULT ok=" + ok
                + " resumeOk=" + resumeOk
                + " state=" + player.getPlaybackState()
                + " positionMs=" + position
                + " durationMs=" + duration
                + " tracks=" + player.getCurrentTracks().getGroups().size()
                + " decode=" + decode
                + " live=" + live
                + " nativeHwdec=" + MPVLib.getPropertyString("hwdec")
                + " nativeVo=" + MPVLib.getPropertyString("current-vo")
                + " startMs=" + startMs
                + " renderedFirstFrame=" + renderedFirstFrame
                + " video=" + videoW + "x" + videoH);
        // Avoid leaving a black, controller-less Activity on screen after ADB smoke runs.
        if (getIntent().getBooleanExtra("finish_after_report", true)) {
            handler.postDelayed(this::finish, 500);
        }
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
        // Decode/surface recovery may emit a transient error then reload; wait for RESULT.
        if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED) return;
        if (getIntent().getBooleanExtra("finish_after_report", true) && secondUrl == null) {
            handler.postDelayed(this::finish, 1_500);
        }
    }

    @Override
    public void onRenderedFirstFrame() {
        renderedFirstFrame = true;
        Log.i(TAG, "RENDERED_FIRST_FRAME");
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (stopOnBackground && player != null) {
            Log.i(TAG, "BACKGROUND_STOP");
            player.stop();
            // Mirror production suspend: stop alone leaves MediaCodec on Rockchip.
            player.release();
            player = null;
            Log.i(TAG, "BACKGROUND_RELEASED");
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            Log.i(TAG, "DESTROY_RELEASED");
        }
        player = null;
        super.onDestroy();
    }
}
