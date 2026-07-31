package com.fongmi.android.tv.ui.activity;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.SeekBar;

import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityAirplayCastBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Util;

import kotlin.jvm.functions.Function1;

import io.github.jqssun.airplay.audio.TrackInfo;
import io.github.jqssun.airplay.service.AirPlayService;
import io.github.jqssun.airplay.service.VideoPlaybackInfo;

public class AirPlayCastActivity extends BaseActivity {

    private static final long SEEK_STEP_MS = 10_000L;
    private static final long POLL_MS = 200L;
    /** After a session ends, leave the idle waiting screen briefly then exit. */
    private static final long IDLE_FINISH_MS = 8_000L;

    private ActivityAirplayCastBinding mBinding;
    private AirPlayService mService;
    private Clock mClock;
    private Handler mHandler;
    private Runnable mPoll;
    private Runnable mHideControl;
    private Runnable mIdleFinish;
    private final Function1<? super String, kotlin.Unit> mPinCallback = pin -> {
        App.post(() -> showPin(pin));
        return kotlin.Unit.INSTANCE;
    };
    private boolean bound;
    private boolean scrubbing;
    private boolean hadSession;
    private boolean shownControlForSession;
    private boolean idleFinishPending;
    private long seekHoldMs;
    private String lastPin = "";
    private int lastMode = -1;

    public static void start(Context context) {
        context.startActivity(new Intent(context, AirPlayCastActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    @Override
    protected boolean customWall() {
        return false;
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityAirplayCastBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mHandler = new Handler(Looper.getMainLooper());
        mClock = Clock.create(mBinding.widget.clock);
        mHideControl = this::hideControl;
        mIdleFinish = this::finishIfIdle;
        mPoll = this::poll;
        bound = bindService(new Intent(this, AirPlayService.class), mConnection, Context.BIND_AUTO_CREATE);
        setupSurfaces();
        mBinding.root.requestFocus();
        showWaiting();
    }

    @Override
    protected void initEvent() {
        mBinding.root.setOnClickListener(v -> onToggle());
        mBinding.control.play.setOnClickListener(v -> onPlayPause());
        mBinding.control.prev.setOnClickListener(v -> onPrev());
        mBinding.control.next.setOnClickListener(v -> onNext());
        mBinding.control.stop.setOnClickListener(v -> onStopPlayback());
        mBinding.control.close.setOnClickListener(v -> finish());
        mBinding.control.seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || mService == null) return;
                long duration = currentDuration();
                if (duration <= 0) return;
                long position = progress * duration / seekBar.getMax();
                mBinding.widget.position.setText(Util.timeMs(position));
                mBinding.widget.duration.setText(Util.timeMs(duration));
                mBinding.widget.center.setVisibility(View.VISIBLE);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                scrubbing = true;
                if (mService != null && isVideoMode()) mService.setVideoScrubbing(true);
                App.removeCallbacks(mHideControl);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                scrubbing = false;
                if (mService == null) return;
                long duration = currentDuration();
                if (duration > 0 && isVideoMode()) {
                    long position = seekBar.getProgress() * duration / seekBar.getMax();
                    mService.seekVideoTo(position);
                    mService.setVideoScrubbing(false);
                }
                App.post(this::hideCenterDelayed, 400);
                setHideCallback();
            }

            private void hideCenterDelayed() {
                if (!scrubbing) hideCenter();
            }
        });
    }

    private void setupSurfaces() {
        mBinding.mirrorSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                if (mService != null) mService.setVideoSurface(holder.getSurface());
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                if (mService != null) mService.setVideoSurface(holder.getSurface());
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                if (mService != null) mService.clearVideoSurface(holder.getSurface());
            }
        });
        mBinding.videoSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                if (mService != null) mService.setVideoPlaybackSurface(holder.getSurface());
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                if (mService != null) mService.setVideoPlaybackSurface(holder.getSurface());
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                if (mService != null) mService.clearVideoPlaybackSurface(holder.getSurface());
            }
        });
    }

    private void attachSurfaces() {
        if (mService == null) return;
        if (mBinding.mirrorSurface.getHolder().getSurface().isValid()) {
            mService.setVideoSurface(mBinding.mirrorSurface.getHolder().getSurface());
        }
        if (mBinding.videoSurface.getVisibility() == View.VISIBLE
                && mBinding.videoSurface.getHolder().getSurface().isValid()) {
            mService.setVideoPlaybackSurface(mBinding.videoSurface.getHolder().getSurface());
        }
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!bound) return;
            mService = ((AirPlayService.LocalBinder) binder).getService();
            mService.setPinCallback(mPinCallback);
            attachSurfaces();
            poll();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
        }
    };

    private void poll() {
        mHandler.removeCallbacks(mPoll);
        refreshUi();
        mHandler.postDelayed(mPoll, POLL_MS);
    }

    private void showWaiting() {
        mBinding.widget.status.setVisibility(View.VISIBLE);
        mBinding.widget.status.setText(R.string.airplay_cast_waiting);
        mBinding.widget.title.setText(R.string.setting_airplay);
        mBinding.widget.subtitle.setText("");
        mBinding.widget.top.setVisibility(View.GONE);
    }

    private void refreshUi() {
        if (mService == null) return;
        boolean video = Boolean.TRUE.equals(mService.getVideoPlaybackActive().getValue());
        boolean mirroring = Boolean.TRUE.equals(mService.getMirroringActive().getValue());
        boolean audio = Boolean.TRUE.equals(mService.getAudioOnly().getValue());
        boolean pending = mService.videoSessionPending();
        Integer connectionsObj = mService.getConnectionCount().getValue();
        int connections = connectionsObj == null ? 0 : connectionsObj;
        VideoPlaybackInfo info = mService.getVideoPlaybackInfo().getValue();
        TrackInfo track = mService.getTrackInfo().getValue();
        String videoTitle = mService.getVideoTitle().getValue();
        String resolution = mService.getVideoResolution().getValue();
        if (videoTitle == null) videoTitle = "";
        if (resolution == null) resolution = "";
        boolean buffering = info != null && info.getBuffering();
        boolean infoPlaying = info != null && info.getPlaying();
        String trackTitle = track != null ? track.getTitle() : "";
        String trackArtist = track != null ? track.getArtist() : "";
        String trackAlbum = track != null ? track.getAlbum() : "";

        boolean active = video || mirroring || audio || pending || connections > 0 || !lastPin.isEmpty();
        if (active) {
            hadSession = true;
            idleFinishPending = false;
            App.removeCallbacks(mIdleFinish);
        } else if (hadSession && !idleFinishPending) {
            idleFinishPending = true;
            App.post(mIdleFinish, IDLE_FINISH_MS);
        }

        boolean showVideoSurface = video || pending;
        boolean wasVideoVisible = mBinding.videoSurface.getVisibility() == View.VISIBLE;
        mBinding.videoSurface.setVisibility(showVideoSurface ? View.VISIBLE : View.GONE);
        mBinding.mirrorSurface.setVisibility(showVideoSurface ? View.INVISIBLE : View.VISIBLE);
        if (showVideoSurface && !wasVideoVisible) {
            mBinding.videoSurface.post(this::attachSurfaces);
        }

        boolean showCover = audio && !video && !mirroring;
        mBinding.cover.setVisibility(showCover ? View.VISIBLE : View.GONE);
        mBinding.buffering.setVisibility(video && buffering ? View.VISIBLE : View.GONE);

        if (showCover && track != null && track.getCoverArt() != null) {
            mBinding.cover.setImageBitmap(track.getCoverArt());
        } else if (!showCover) {
            mBinding.cover.setImageDrawable(null);
        }

        String title;
        String subtitle;
        if (video) {
            title = !videoTitle.isEmpty() ? videoTitle : getString(R.string.airplay_cast_video);
            subtitle = resolution;
        } else if (mirroring) {
            title = getString(R.string.airplay_cast_mirroring);
            subtitle = resolution;
        } else if (audio) {
            title = trackTitle != null && !trackTitle.isEmpty() ? trackTitle : getString(R.string.airplay_cast_audio);
            subtitle = joinMeta(trackArtist, trackAlbum);
        } else if (connections > 0 || pending) {
            title = getString(R.string.setting_airplay);
            subtitle = getString(R.string.airplay_cast_connecting);
        } else {
            title = getString(R.string.setting_airplay);
            subtitle = getString(R.string.airplay_cast_waiting);
        }
        mBinding.widget.title.setText(title);
        mBinding.widget.title.setSelected(true);
        mBinding.widget.subtitle.setText(subtitle);
        mBinding.widget.subtitle.setSelected(true);

        boolean showPin = !lastPin.isEmpty();
        boolean showStatus = !video && !mirroring && !audio && !showPin;
        mBinding.widget.status.setVisibility(showStatus ? View.VISIBLE : View.GONE);
        if (showStatus) {
            mBinding.widget.status.setText(connections > 0 || pending
                    ? R.string.airplay_cast_connecting
                    : R.string.airplay_cast_waiting);
        }

        // Seek only for URL video; audio position is sender-driven.
        boolean canSeek = video && currentDuration() > 0;
        mBinding.control.seek.setVisibility(canSeek ? View.VISIBLE : View.GONE);
        mBinding.control.prev.setVisibility(audio && !video ? View.VISIBLE : View.GONE);
        mBinding.control.next.setVisibility(audio && !video ? View.VISIBLE : View.GONE);
        mBinding.control.stop.setVisibility(video ? View.VISIBLE : View.GONE);
        mBinding.control.play.setVisibility(video || audio ? View.VISIBLE : View.GONE);
        updateControlFocusChain();

        boolean playing = video ? infoPlaying : Boolean.TRUE.equals(mService.getPlaying().getValue());
        mBinding.control.play.setText(playing ? R.string.airplay_cast_pause : R.string.airplay_cast_play);

        if (!scrubbing && canSeek) {
            long duration = currentDuration();
            long position = currentPosition();
            int max = mBinding.control.seek.getMax();
            mBinding.control.seek.setProgress((int) (position * max / Math.max(1, duration)));
        }

        int mode = video ? 1 : mirroring ? 2 : audio ? 3 : 0;
        if (mode != lastMode) {
            lastMode = mode;
            onModeChanged(video, mirroring, audio, showPin);
        }

        if (isAudioMode() && !showPin && isGone(mBinding.control.getRoot())) {
            mBinding.widget.top.setVisibility(View.VISIBLE);
        }
    }

    private void onModeChanged(boolean video, boolean mirroring, boolean audio, boolean showPin) {
        if (showPin) return;
        if ((video || audio) && !shownControlForSession) {
            shownControlForSession = true;
            showControl();
        } else if (mirroring) {
            // Brief title overlay for mirroring, then hide.
            mBinding.widget.top.setVisibility(View.VISIBLE);
            App.post(mHideControl, Constant.INTERVAL_HIDE);
        } else if (!video && !mirroring && !audio) {
            shownControlForSession = false;
            hideControl();
        }
    }

    private void updateControlFocusChain() {
        View first = firstVisibleControl();
        View last = mBinding.control.close;
        if (first == null) return;
        first.setNextFocusLeftId(last.getId());
        last.setNextFocusRightId(first.getId());
    }

    @Nullable
    private View firstVisibleControl() {
        if (isVisible(mBinding.control.prev)) return mBinding.control.prev;
        if (isVisible(mBinding.control.play)) return mBinding.control.play;
        if (isVisible(mBinding.control.next)) return mBinding.control.next;
        if (isVisible(mBinding.control.stop)) return mBinding.control.stop;
        return mBinding.control.close;
    }

    private void finishIfIdle() {
        idleFinishPending = false;
        if (isFinishing() || mService == null) return;
        boolean video = Boolean.TRUE.equals(mService.getVideoPlaybackActive().getValue());
        boolean mirroring = Boolean.TRUE.equals(mService.getMirroringActive().getValue());
        boolean audio = Boolean.TRUE.equals(mService.getAudioOnly().getValue());
        Integer connectionsObj = mService.getConnectionCount().getValue();
        int connections = connectionsObj == null ? 0 : connectionsObj;
        boolean pending = mService.videoSessionPending();
        if (video || mirroring || audio || pending || connections > 0 || !lastPin.isEmpty()) return;
        finish();
    }

    private String joinMeta(String artist, String album) {
        if (artist == null) artist = "";
        if (album == null) album = "";
        if (!artist.isEmpty() && !album.isEmpty()) return artist + " · " + album;
        if (!artist.isEmpty()) return artist;
        return album;
    }

    private void showPin(@Nullable String pin) {
        if (pin == null || pin.isEmpty()) {
            lastPin = "";
            mBinding.widget.pinBox.setVisibility(View.GONE);
            return;
        }
        lastPin = pin;
        hadSession = true;
        App.removeCallbacks(mIdleFinish);
        mBinding.widget.pin.setText(pin);
        mBinding.widget.pinBox.setVisibility(View.VISIBLE);
        mBinding.widget.status.setVisibility(View.GONE);
        hideControl();
        hideCenter();
    }

    private boolean isVideoMode() {
        return mService != null && Boolean.TRUE.equals(mService.getVideoPlaybackActive().getValue());
    }

    private boolean isAudioMode() {
        return mService != null && Boolean.TRUE.equals(mService.getAudioOnly().getValue()) && !isVideoMode();
    }

    private boolean isMirrorMode() {
        return mService != null && Boolean.TRUE.equals(mService.getMirroringActive().getValue()) && !isVideoMode();
    }

    private boolean canControlPlayback() {
        return isVideoMode() || isAudioMode();
    }

    private long currentDuration() {
        if (mService == null) return 0;
        if (isVideoMode()) {
            VideoPlaybackInfo info = mService.getVideoPlaybackInfo().getValue();
            return info == null ? 0 : info.getDurationMs();
        }
        Long value = mService.getDurationMs().getValue();
        return value == null ? 0 : value;
    }

    private long currentPosition() {
        if (mService == null) return 0;
        if (isVideoMode()) {
            VideoPlaybackInfo info = mService.getVideoPlaybackInfo().getValue();
            return info == null ? 0 : info.getPositionMs();
        }
        return mService.currentPositionMs();
    }

    private void onPlayPause() {
        if (mService == null || !canControlPlayback()) return;
        if (isVideoMode()) {
            VideoPlaybackInfo info = mService.getVideoPlaybackInfo().getValue();
            boolean playing = info != null && info.getPlaying();
            mService.setVideoPlaying(!playing);
        } else {
            mService.togglePlayPause();
        }
        setHideCallback();
    }

    private void onPrev() {
        if (mService == null || mService.getDacpController() == null) return;
        mService.getDacpController().prevItem();
        setHideCallback();
    }

    private void onNext() {
        if (mService == null || mService.getDacpController() == null) return;
        mService.getDacpController().nextItem();
        setHideCallback();
    }

    private void onStopPlayback() {
        if (mService == null) return;
        mService.stopLocalSession("ui");
        setHideCallback();
    }

    private void onToggle() {
        if (!lastPin.isEmpty()) return;
        if (isVisible(mBinding.control.getRoot())) hideControl();
        else showControl();
    }

    private void showControl() {
        mBinding.widget.top.setVisibility(View.VISIBLE);
        mBinding.control.getRoot().setVisibility(View.VISIBLE);
        updateControlFocusChain();
        View focus = firstVisibleControl();
        if (focus != null) focus.requestFocus();
        setHideCallback();
    }

    private void hideControl() {
        mBinding.control.getRoot().setVisibility(View.GONE);
        App.removeCallbacks(mHideControl);
        if (lastPin.isEmpty() && !isVisible(mBinding.widget.center)) {
            mBinding.widget.top.setVisibility(isAudioMode() ? View.VISIBLE : View.GONE);
        }
        if (isGone(mBinding.control.getRoot())) mBinding.root.requestFocus();
    }

    private void showCenter(long deltaMs) {
        long duration = currentDuration();
        long position = Math.max(0, Math.min(duration > 0 ? duration : Long.MAX_VALUE, currentPosition() + deltaMs));
        mBinding.widget.center.setVisibility(View.VISIBLE);
        mBinding.widget.position.setText(Util.timeMs(position));
        mBinding.widget.duration.setText(Util.timeMs(Math.max(0, duration)));
        mBinding.widget.action.setImageResource(deltaMs >= 0 ? R.drawable.ic_widget_forward : R.drawable.ic_widget_rewind);
        hideControl();
    }

    private void hideCenter() {
        mBinding.widget.center.setVisibility(View.GONE);
        mBinding.widget.action.setImageResource(R.drawable.ic_widget_play);
    }

    private void setHideCallback() {
        App.post(mHideControl, Constant.INTERVAL_HIDE);
    }

    private void seekBy(long deltaMs) {
        if (mService == null || !isVideoMode()) return;
        seekHoldMs += deltaMs;
        showCenter(seekHoldMs);
        mService.seekVideoBy(deltaMs);
    }

    private void endSeek() {
        seekHoldMs = 0;
        App.post(this::hideCenter, 400);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (KeyUtil.isMenuKey(event)) {
            onToggle();
            return true;
        }
        if (isVisible(mBinding.control.getRoot())) {
            setHideCallback();
            return super.dispatchKeyEvent(event);
        }
        if (isGone(mBinding.control.getRoot()) && handlePlaybackKeys(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    private boolean handlePlaybackKeys(KeyEvent event) {
        if (KeyUtil.isEnterKey(event) && KeyUtil.isActionUp(event)) {
            if (canControlPlayback()) onPlayPause();
            else if (isMirrorMode() || !lastPin.isEmpty()) onToggle();
            else onToggle();
            return true;
        }
        if (KeyUtil.isUpKey(event) && KeyUtil.isActionUp(event)) {
            showControl();
            return true;
        }
        if (KeyUtil.isDownKey(event) && KeyUtil.isActionUp(event)) {
            showControl();
            return true;
        }
        if (!isVideoMode()) return false;
        if (KeyUtil.isLeftKey(event) && KeyUtil.isActionDown(event)) {
            seekBy(-SEEK_STEP_MS);
            return true;
        }
        if (KeyUtil.isRightKey(event) && KeyUtil.isActionDown(event)) {
            seekBy(SEEK_STEP_MS);
            return true;
        }
        if ((KeyUtil.isLeftKey(event) || KeyUtil.isRightKey(event)) && KeyUtil.isActionUp(event)) {
            endSeek();
            return true;
        }
        return false;
    }

    @Override
    protected void onStart() {
        super.onStart();
        mClock.stop().start();
        mHandler.post(mPoll);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mClock.stop();
        mHandler.removeCallbacks(mPoll);
        App.removeCallbacks(mIdleFinish);
    }

    @Override
    protected void onBackInvoked() {
        if (isVisible(mBinding.control.getRoot())) {
            hideControl();
        } else if (isVisible(mBinding.widget.center)) {
            hideCenter();
        } else {
            super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacks(mPoll);
        App.removeCallbacks(mHideControl);
        App.removeCallbacks(mIdleFinish);
        if (mService != null && mService.getPinCallback() == mPinCallback) {
            mService.setPinCallback(null);
        }
        if (bound) {
            unbindService(mConnection);
            bound = false;
        }
        mService = null;
        mClock.release();
        super.onDestroy();
    }
}
