package com.fongmi.android.tv.ui.activity;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;

import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.ui.PlayerSeekView;
import androidx.media3.ui.PlayerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityCastBinding;
import com.fongmi.android.tv.dlna.CastAction;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.service.CastConflict;
import com.fongmi.android.tv.service.DLNARendererService;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.ui.dialog.PlayerEngineDialog;
import com.fongmi.android.tv.ui.dialog.SubtitleDialog;
import com.fongmi.android.tv.ui.dialog.TrackDialog;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.jupnp.support.contentdirectory.DIDLParser;

/** Phone UI for the same renderer/AVTransport session used by the TV cast page. */
public class CastActivity extends PlaybackActivity implements TrackDialog.Listener {

    private ActivityCastBinding binding;
    private DLNARendererService renderer;
    private CastAction action;
    private String playbackKey;
    private boolean rendererBound;
    private int scale;

    public static void start(Context context, CastAction action) {
        Intent intent = new Intent(context, CastActivity.class)
                .putExtra(CastAction.KEY_EXTRA, action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(intent);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = ActivityCastBinding.inflate(getLayoutInflater());
    }

    @Override
    protected PlayerView getPlayerView() {
        return binding.player;
    }

    @Override
    protected PlayerSeekView getSeekView() {
        return binding.seek;
    }

    @Override
    protected String getPlaybackKey() {
        return playbackKey;
    }

    @Override
    protected PlaybackService.NavigationCallback getNavigationCallback() {
        return navigationCallback;
    }

    private final PlaybackService.NavigationCallback navigationCallback = new PlaybackService.NavigationCallback() {
        @Override
        public void onStop() {
            finish();
        }
    };

    @Override
    protected void initView(Bundle savedInstanceState) {
        super.initView(savedInstanceState);
        Util.hideSystemUI(this);
        binding.player.setResizeMode(scale = PlayerSetting.getScale());
        rendererBound = bindService(new Intent(this, DLNARendererService.class), rendererConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void initEvent() {
        binding.playPause.setOnClickListener(v -> {
            if (controller() == null) return;
            if (controller().isPlaying()) controller().pause();
            else controller().play();
        });
        binding.more.setOnClickListener(v -> showOptions());
        binding.stop.setOnClickListener(v -> finish());
    }

    private void showOptions() {
        if (service() == null) return;
        int[] labels = {R.string.player_engine, R.string.cast_speed, R.string.player_scale,
                R.string.play_track_audio, R.string.play_track_video, R.string.play_track_text,
                R.string.danmaku_show_subtitle, R.string.cast_restart};
        String[] options = new String[labels.length];
        for (int i = 0; i < labels.length; i++) options[i] = getString(labels[i]);
        new MaterialAlertDialogBuilder(this).setTitle(R.string.cast_options)
                .setItems(options, (dialog, choice) -> {
                    switch (choice) {
                        case 0 -> PlayerEngineDialog.show(this, null, player(), binding.title.getText());
                        case 1 -> showSpeedOptions();
                        case 2 -> binding.player.setResizeMode(scale = (scale + 1) % getResources().getStringArray(R.array.select_scale).length);
                        case 3 -> TrackDialog.create().type(C.TRACK_TYPE_AUDIO).player(player()).show(this);
                        case 4 -> TrackDialog.create().type(C.TRACK_TYPE_VIDEO).player(player()).show(this);
                        case 5 -> TrackDialog.create().type(C.TRACK_TYPE_TEXT).player(player()).show(this);
                        case 6 -> SubtitleDialog.create().view(binding.player.getSubtitleView()).player(player()).show(this);
                        case 7 -> {
                            if (action != null) showAction(new Intent().putExtra(CastAction.KEY_EXTRA, action));
                        }
                        default -> { }
                    }
                }).show();
    }

    private void showSpeedOptions() {
        float[] values = {0.75f, 1f, 1.25f, 1.5f, 2f};
        String[] labels = {"0.75×", "1×", "1.25×", "1.5×", "2×"};
        new MaterialAlertDialogBuilder(this).setTitle(R.string.cast_speed)
                .setSingleChoiceItems(labels, -1, (dialog, selected) -> {
                    if (service() != null) player().setSpeed(values[selected]);
                    dialog.dismiss();
                }).show();
    }

    @Override
    protected void onServiceConnected() {
        showAction(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.hasExtra(CastAction.KEY_EXTRA)) showAction(intent);
        else finish();
    }

    private void showAction(Intent intent) {
        // A second push can arrive while the playback-service bind is still pending.
        // onServiceConnected() will read the latest intent once the player is available.
        if (service() == null) return;
        CastAction next = intent.getParcelableExtra(CastAction.KEY_EXTRA);
        if (next == null || next.getCurrentURI() == null || next.getCurrentURI().isEmpty()) {
            finish();
            return;
        }
        action = next;
        CastConflict.yieldToDlna(this);
        String title = next.getCurrentURI();
        try {
            String didlTitle = new DIDLParser().parse(next.getCurrentURIMetaData()).getItems().get(0).getTitle();
            if (didlTitle != null && !didlTitle.isEmpty()) title = didlTitle;
        } catch (Exception ignored) {
        }
        binding.title.setText(title);
        playbackKey = next.getCurrentURI();
        player().setSpeed(1f);
        player().setRepeatOne(false);
        if (renderer != null) renderer.setDlnaActive(true);
        // Unlike the old mobile VideoActivity.push path, CastAction carries DIDL metadata and
        // request headers, and the shared player/renderer can report state and honor SOAP seeks.
        MediaMetadata metadata = PlayerManager.buildMetadata(title, "", "");
        startPlayer(playbackKey, next.result(), false, Constant.TIMEOUT_PLAY, metadata);
        updateNavigationKey();
    }

    private void consumePendingSeek() {
        if (renderer == null || service() == null || player().isEmpty()) return;
        long seekMs = renderer.consumePendingSeekMs();
        if (seekMs >= 0) player().seekTo(seekMs);
    }

    private final ServiceConnection rendererConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!rendererBound) return;
            renderer = ((DLNARendererService.LocalBinder) binder).getService();
            renderer.setDlnaActive(true);
            consumePendingSeek();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            renderer = null;
        }
    };

    @Override
    protected void onPrepare() {
        consumePendingSeek();
    }

    @Override
    protected void onStateChanged(int state) {
        if (state != Player.STATE_ENDED || renderer == null) return;
        CastAction next = renderer.consumeNext();
        if (next != null) showAction(new Intent().putExtra(CastAction.KEY_EXTRA, next));
    }

    @Override
    protected void onPlayingChanged(boolean isPlaying) {
        binding.playPause.setText(isPlaying ? R.string.airplay_cast_pause : R.string.airplay_cast_play);
    }

    @Override
    public void onSubtitleClick() {
        if (service() != null) SubtitleDialog.create().view(binding.player.getSubtitleView()).player(player()).show(this);
    }

    @Override
    protected void onError(String message) {
        if (renderer != null) renderer.notifyError();
        player().resetTrack();
        player().reset();
        player().stop();
        binding.title.setText(message);
    }

    @Override
    protected void onDestroy() {
        if (renderer != null) renderer.setDlnaActive(false);
        if (rendererBound) unbindService(rendererConnection);
        rendererBound = false;
        renderer = null;
        super.onDestroy();
    }
}
